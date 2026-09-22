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
    publishLastResult(r);
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
    publishLastResult(r);
    return r;
  }

  // ⑥ 原子替换（发布后不可变）。
  last_version_.store(batch.param_version(), std::memory_order_seq_cst);
  mgr_.publish(next);

  r.ok = true;
  r.code = kRebuildOk;
  r.record_count = next->record_count();
  publishLastResult(r);
  return r;
}

// 无锁发布（单写者）：写入前后各递增一次 seq_（偶数 = 稳定 / 奇数 = 写入中）。
// 各字段以 relaxed 存取 —— 生产者不依赖与读者之间的数据传递，仅依赖版本戳的
// 前后一致（acquire/release）来界定「读者看到的各字段来自同一次写入」。
void RiskState::publishLastResult(const RebuildResult& r) {
  last_.seq_.fetch_add(1, std::memory_order_release);  // → 奇数：标记写入中
  last_.ok_.store(r.ok, std::memory_order_relaxed);
  last_.code_.store(r.code, std::memory_order_relaxed);
  last_.version_.store(r.version, std::memory_order_relaxed);
  last_.record_count_.store(r.record_count, std::memory_order_relaxed);
  last_.seq_.fetch_add(1, std::memory_order_release);  // → 偶数：标记稳定
}

// 无锁读（任意线程）：重复直到取到「前后 seq_ 相等且为偶数」的自洽快照。
// 单写者下写入窗口极短，诊断访问器非热路径 ⇒ 至多重读若干次。
RebuildResult RiskState::last_result() const {
  for (;;) {
    const std::uint64_t s1 = last_.seq_.load(std::memory_order_acquire);
    if ((s1 & 1U) != 0U) continue;  // 写者写入中：等待其发布稳定版本
    RebuildResult r;
    r.ok = last_.ok_.load(std::memory_order_relaxed);
    r.code = last_.code_.load(std::memory_order_relaxed);
    r.version = last_.version_.load(std::memory_order_relaxed);
    r.record_count = last_.record_count_.load(std::memory_order_relaxed);
    const std::uint64_t s2 = last_.seq_.load(std::memory_order_acquire);
    if (s1 == s2) return r;
  }
}

}  // namespace risk_state
}  // namespace hg
