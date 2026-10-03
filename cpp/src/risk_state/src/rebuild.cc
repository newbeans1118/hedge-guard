// 控制指令参数生效会话实现（T-038，ARCHITECTURE.md §4.2.3B / §6.3）。
// 语义与边界见 rebuild.h 文件头。
#include "hg/risk_state/rebuild.h"

namespace hg {
namespace risk_state {

namespace {

// 门卫：会话版本是否可生效（严格单调，验收 2）。
bool versionStrictlyNewer(std::uint64_t v, const RiskState& s) {
  return v > s.param_version();
}

void resetSession(bool* in_progress, std::uint32_t* received, std::uint32_t* total) {
  *in_progress = false;
  *received = 0;
  *total = 0;
}

}  // namespace

ParamRebuildSession::ParamRebuildSession(RiskState* state)
    : state_(state),
      in_progress_(false),
      load_version_(0),
      total_(0),
      received_(0),
      next_index_(0) {}

const char* rebuildSessionCodeName(int code) {
  switch (code) {
    case kSessionOk: return "OK";
    case kSessionVersionStale:
      return "参数版本不新于当前版本（后发不得先至，0x0403）";
    case kSessionBatchInconsistent:
      return "分批不一致：批内版本漂移 / batch_index 越界";
    case kSessionBatchMissing: return "分批缺批：batch_index 不连续";
    case kSessionRebuildFailed: return "快照校验失败：本批未生效";
    default: return "未知会话错误码";
  }
}

SessionResult ParamRebuildSession::submitLoadBatch(LoadBatch batch,
                                                   std::uint32_t batch_index,
                                                   std::uint32_t batch_total) {
  SessionResult r;
  r.replace_all = true;

  // ① 分批参数合法性。
  if (batch_total == 0u || batch_index >= batch_total) {
    r.ok = false;
    r.code = kSessionBatchInconsistent;
    r.version = batch.param_version();
    return r;
  }

  // ② 新会话（首批）：锁定版本与 total；版本须严格新于当前快照（验收 2；
  //    快照为空时 current=0，任何非零版本恒过）。
  if (!in_progress_) {
    if (!versionStrictlyNewer(batch.param_version(), *state_)) {
      r.ok = false;
      r.code = kSessionVersionStale;
      r.version = batch.param_version();
      return r;
    }
    in_progress_ = true;
    load_version_ = batch.param_version();
    total_ = batch_total;
    received_ = 0;
    next_index_ = 0;
    session_batches_.clear();
  } else if (batch.param_version() != load_version_ || batch_total != total_) {
    // ③ 进行中会话：版本漂移 / total 变化 ⇒ 不一致（快照不变，会话保留待纠）。
    r.ok = false;
    r.code = kSessionBatchInconsistent;
    r.version = load_version_;
    return r;
  } else if (batch_index != next_index_) {
    // ④ 批序守卫：batch_index 必须等于期望值（防重复 / 跳跃 = 缺批）。
    r.ok = false;
    r.code = kSessionBatchMissing;
    r.version = load_version_;
    return r;
  }

  // ⑤ 受理本批：首批以全量空底承载（replace_all=true），后续批增量并入暂存。
  batch.set_replace_all(session_batches_.empty());
  session_batches_.push_back(batch);
  next_index_ = batch_index + 1u;
  ++received_;
  r.ok = true;
  r.version = load_version_;

  // ⑥ 收齐 ⇒ 一次性生效。LoadBatch 无公开合并接口 ⇒ 采用「影子预演 + 重放」：
  //    先在独立影子 RiskState 上按「首批 replace_all=true、其余增量」完整走一遍
  //    （发布 N-1 次不触达生产快照、不变量校验逐批执行）；全部成功后对生产
  //    state 重放同一序列（单写者 ⇒ 结果确定）。失败 ⇒ 会话复位、快照不变。
  if (received_ == total_) {
    RiskState shadow;
    for (std::size_t i = 0; i < session_batches_.size(); ++i) {
      const RebuildResult rr = shadow.rebuild(session_batches_[i]);
      if (!rr.ok) {
        r.ok = false;
        r.code = kSessionRebuildFailed;
        r.rebuild_code = rr.code;
        r.version = load_version_;
        resetSession(&in_progress_, &received_, &total_);
        session_batches_.clear();
        return r;
      }
    }
    for (std::size_t i = 0; i < session_batches_.size(); ++i) {
      const RebuildResult rr = state_->rebuild(session_batches_[i]);
      if (!rr.ok) {  // 单写者 + 影子已证 ⇒ 理论不可达；守卫保留（fail-closed）
        r.ok = false;
        r.code = kSessionRebuildFailed;
        r.rebuild_code = rr.code;
        r.version = load_version_;
        resetSession(&in_progress_, &received_, &total_);
        session_batches_.clear();
        return r;
      }
    }
    r.applied = true;
    r.version = state_->param_version();
    resetSession(&in_progress_, &received_, &total_);
    session_batches_.clear();
  }
  return r;
}

SessionResult ParamRebuildSession::submitIncremental(LoadBatch batch) {
  SessionResult r;
  // 版本门卫（验收 2）：仅接受 param_version > current；否则拒绝并丢弃。
  if (!versionStrictlyNewer(batch.param_version(), *state_)) {
    r.ok = false;
    r.code = kSessionVersionStale;
    r.version = batch.param_version();
    return r;
  }
  batch.set_replace_all(false);
  const RebuildResult rr = state_->rebuild(batch);
  if (!rr.ok) {
    r.ok = false;
    r.code = kSessionRebuildFailed;
    r.rebuild_code = rr.code;
    r.version = batch.param_version();
    return r;
  }
  r.ok = true;
  r.applied = true;
  r.version = rr.version;
  return r;
}

}  // namespace risk_state
}  // namespace hg
