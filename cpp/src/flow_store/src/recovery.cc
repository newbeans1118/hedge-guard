// 崩溃恢复集成（flow_store 侧台账，T-041）—— 决策消费 + 人工核验入口 + 门禁读面。
// 依据：ARCHITECTURE.md §4.5.3 ⑤「保守处理」/ TASKS §2 T-041 验收 2/3/4。
// 本目录不在 E4 扫描根（v1.5.12）；本类零 I/O，留痕经 FlowStore::append（T-036）。
#include "hg/flow_store/recovery.h"

#include <cstring>

namespace hg {
namespace flow_store {

const char* manualReleaseErrorName(ManualReleaseError e) {
  switch (e) {
    case ManualReleaseError::kNone: return "NONE";
    case ManualReleaseError::kNotFound: return "NOT_FOUND";
    case ManualReleaseError::kAlreadyReleased: return "ALREADY_RELEASED";
    case ManualReleaseError::kOperatorMissing: return "OPERATOR_MISSING";
    case ManualReleaseError::kSameOperator: return "SAME_OPERATOR";
    case ManualReleaseError::kRefMismatch: return "REF_MISMATCH";
    case ManualReleaseError::kReasonMissing: return "REASON_MISSING";
    default: return "UNKNOWN";
  }
}

RecoveryCoordinator::RecoveryCoordinator() {}

void RecoveryCoordinator::consume(const ReplayReport& report, std::int64_t now_ms) {
  // 只消费 kTimeout 的 kUnknownKeepFrozen（kLive 见 recovery.h 头注披露）。
  for (std::size_t i = 0; i < report.decisions.size(); ++i) {
    const ReplayDecision& d = report.decisions[i];
    if (d.kind != ReplayDecision::kUnknownKeepFrozen) continue;
    if (d.ctp_result != OrderQueryResult::kTimeout) continue;
    bool exists = false;
    for (std::size_t k = 0; k < entries_.size(); ++k) {
      if (entries_[k].order_id == d.order.order_id) {
        exists = true;  // 幂等：重复重放不重复登记（防阻断计数虚增）
        break;
      }
    }
    if (exists) continue;
    RecoveryEntry e;
    e.order_id = d.order.order_id;
    e.client_order_ref = d.order.client_order_ref;
    e.sent_ts_ms = d.order.sent_ts_ms;
    e.registered_ts_ms = now_ms;
    entries_.push_back(e);
    ++open_count_;
  }
}

bool RecoveryCoordinator::manualVerifyRelease(const ManualReleaseRequest& req,
                                              std::int64_t now_ms,
                                              ManualReleaseError* err) {
  ManualReleaseError local;
  ManualReleaseError* e = err != nullptr ? err : &local;

  // 五守卫（机械承载「双人复核」；顺序：先条目定位，后复核人守卫——
  // NotFound/Already 优先于操作人守卫，避免对不存在单泄漏守卫次序）。
  RecoveryEntry* target = nullptr;
  for (std::size_t k = 0; k < entries_.size(); ++k) {
    if (entries_[k].order_id == req.order_id) {
      target = &entries_[k];
      break;
    }
  }
  if (target == nullptr) {
    *e = ManualReleaseError::kNotFound;
    return false;
  }
  if (target->released) {
    *e = ManualReleaseError::kAlreadyReleased;
    return false;
  }
  if (req.operator_a_id == 0 || req.operator_b_id == 0) {
    *e = ManualReleaseError::kOperatorMissing;
    return false;
  }
  if (req.operator_a_id == req.operator_b_id) {
    *e = ManualReleaseError::kSameOperator;
    return false;
  }
  if (req.client_order_ref != target->client_order_ref) {
    *e = ManualReleaseError::kRefMismatch;
    return false;
  }
  if (req.reason.empty()) {
    *e = ManualReleaseError::kReasonMissing;
    return false;
  }

  // 台账闭环。
  target->released = true;
  target->operator_a_id = req.operator_a_id;
  target->operator_b_id = req.operator_b_id;
  target->released_ts_ms = now_ms;
  --open_count_;

  // 留痕（append-only；kExpired + recovery: 前缀，见 recovery.h 头注偏置化披露）。
  // 只增不改；append 失败（磁盘满/无权限）不得回滚台账——保守闭环优先（宁可
  // 少放行），留痕缺口以返回值之外的观测面披露（view().released_count）。
  if (flow_ != nullptr) {
    FlowRecord fr;
    fr.kind = FlowKind::kExpired;
    fr.order_id = target->order_id;
    fr.client_order_ref = target->client_order_ref;
    fr.ts_ms = now_ms;
    fr.detail = "recovery:manual release by A=" +
                std::to_string(req.operator_a_id) +
                ",B=" + std::to_string(req.operator_b_id) +
                ",reason=" + req.reason;
    (void)flow_->append(fr);  // 失败不阻断台账闭环（头注披露口径）
  }
  *e = ManualReleaseError::kNone;
  return true;
}

RecoveryLedgerView RecoveryCoordinator::view() const {
  RecoveryLedgerView v;
  v.total = entries_.size();
  v.open_count = open_count_;
  v.released_count = entries_.size() - open_count_;
  return v;
}

const RecoveryEntry* RecoveryCoordinator::find(std::uint64_t order_id) const {
  for (std::size_t k = 0; k < entries_.size(); ++k) {
    if (entries_[k].order_id == order_id) return &entries_[k];
  }
  return nullptr;
}

}  // namespace flow_store
}  // namespace hg
