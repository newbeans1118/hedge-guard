// 风控内存状态 —— 对外门面 RiskState 实现（ARCHITECTURE.md §3.4 / §4.4.3）
#include "hg/risk_state/risk_state.h"

namespace hg {
namespace risk_state {

const char* rebuildCodeName(int code) {
  switch (code) {
    case kRebuildOk: return "OK";
    case kRebuildVersionNotMonotonic:
      return "参数版本倒退：本次批次版本低于当前已发布版本（后发不得先至）";
    case kRebuildNegativeValue: return "存在负值：额度/持仓/敞口/规模基数不得为负";
    case kRebuildPositionInconsistent:
      return "持仓不自洽：可用量或冻结量大于持仓量";
    case kRebuildInvalidRatio: return "红线比例越界：须落在 [0, 1]";
    case kRebuildMissingConvertFactor:
      return "启用品种缺换算系数：换算关系缺失的品种不得启用";
    default: return "未知错误码";
  }
}

RiskState::RiskState() : mgr_(new RiskSnapshot()), last_version_(0) {}

RiskState::~RiskState() {}

RebuildResult RiskState::rebuild(const LoadBatch& batch) {
  RebuildResult r;
  r.version = batch.param_version();

  // ① 版本单调（FR-RISK-10 验收 3：连续下发以最后一次为准；后发不得先至）。
  const std::uint64_t cur_ver = last_version_.load(std::memory_order_seq_cst);
  if (batch.param_version() < cur_ver) {
    r.ok = false;
    r.code = kRebuildVersionNotMonotonic;
    r.record_count = mgr_.peek().record_count();
    last_ = r;
    return r;
  }

  // ② 构建新快照：全量 ⇒ 空底；增量 ⇒ 以当前快照深拷贝为基底（§4.4.3「完整或增量拷贝」）。
  RiskSnapshot* next = batch.replace_all() ? new RiskSnapshot() : mgr_.peek().clone();

  // ③ 应用本批数据到新快照。
  batch.applyTo(*next);

  // ④ 元信息（版本 / 构建时点 / 加载条数）。
  next->set_param_version(batch.param_version());
  next->set_build_time_ms(batch.build_time_ms());
  next->set_record_count(next->countRecords());

  // ⑤ 不变量校验：**未通过不得发布**（§4.4.3）。
  const int code = next->checkInvariants();
  if (code != kRebuildOk) {
    delete next;  // 丢弃半成品；当前快照不受影响
    r.ok = false;
    r.code = code;
    r.record_count = mgr_.peek().record_count();
    last_ = r;
    return r;
  }

  // ⑥ 原子替换（发布后不可变）。
  last_version_.store(batch.param_version(), std::memory_order_seq_cst);
  mgr_.publish(next);

  r.ok = true;
  r.code = kRebuildOk;
  r.record_count = next->record_count();
  last_ = r;
  return r;
}

}  // namespace risk_state
}  // namespace hg
