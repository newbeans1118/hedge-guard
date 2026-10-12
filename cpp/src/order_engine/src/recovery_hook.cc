// 崩溃恢复集成（order_engine 侧消费，T-041）—— 决策执行主体。
// 依据：ARCHITECTURE.md §4.5.3 ⑤⑥ / TASKS §2 T-041 验收 1/2/3/4；
// replay.h 头注「事件补发的执行主体 = T-041/T-039 装配」。
// 本目录属 E4 扫描根：零锁 / 零同步 I/O 形态 / 零 SQL；留痕走引擎流水轨（T-036）。
#include "hg/order_engine/recovery_hook.h"

namespace hg {
namespace order_engine {

RecoveryHook::RecoveryHook(OrderEngine* engine, flow_store::RecoveryCoordinator* ledger)
    : engine_(engine), ledger_(ledger) {}

bool RecoveryHook::has_unknown_open() const {
  return ledger_ != nullptr && ledger_->has_unknown_open();
}

RecoveryExecOutcome RecoveryHook::execute(const flow_store::ReplayReport& report,
                                          std::int64_t now_ms) {
  RecoveryExecOutcome out;

  // 幂等护栏：execute 全局单次。还原是一次性启动动作；重复执行会二次修正/二次
  // 释放/二次终态化——全部是验收 1「无重复计入」要阻断的形态。
  if (executed_) return out;
  executed_ = true;

  // ① OrderMap 还原（先于决策执行：决策修正需要档案在场）。
  if (engine_ != nullptr) {
    (void)engine_->restoreFromReplay(report.orders, now_ms);
  }

  // ② pending_unsent（PLACED 无 SENT）：冻结保留（§4.5.3「未发即崩」口径——
  //    已随 restoreFromReplay 还原为 kPendingConfirm 壳档案，冻结份额外源注入
  //    同 unknown 单 held 纪律；此处不再单独处置，只披露于回执 detail 语义）。

  // ③ 逐条执行对账决策（§4.5.3 ⑤⑥）。
  for (std::size_t i = 0; i < report.decisions.size(); ++i) {
    const flow_store::ReplayDecision& d = report.decisions[i];
    switch (d.kind) {
      case flow_store::ReplayDecision::kAdoptTerminal: {
        // 按 CTP 终态修正（kFilled/kCancelled/kRejected）。
        // 落地形态：held 纪律下该单冻结份额未入账 ⇒ finalizeRecovered 终态化
        // 的 releaseFor 为空操作（份额 0）⇒ 账本零增量、无二次释放；终态迁移
        // + 流水留痕（kFilled/kCancelled/kRejected）复用 markTerminal 现成轨。
        if (engine_ == nullptr) { ++out.skipped; break; }
        OrderStatus st;
        const char* why;
        if (d.ctp_result == flow_store::OrderQueryResult::kFilled) {
          st = OrderStatus::kFilled;
          why = "recovery: adopted CTP fill";
        } else if (d.ctp_result == flow_store::OrderQueryResult::kCancelled) {
          st = OrderStatus::kCancelled;
          why = "recovery: adopted CTP cancel";
        } else {
          st = OrderStatus::kRejected;
          why = "recovery: adopted CTP rejection";
        }
        if (engine_->finalizeRecovered(d.order.order_id, st, now_ms, why)) {
          ++out.adopted;
        } else {
          ++out.skipped;  // 档案缺失/已终态：保守跳过（计数披露）
        }
        break;
      }
      case flow_store::ReplayDecision::kReleaseExpired: {
        // CTP 无此单 ⇒ 判失效（终态迁移 + 流水 kExpired）+ 补发
        // EVT_ORDER_EXPIRED（事件载荷经调用方 emitter——与 expirePending 同一
        // 接缝形态，不新开第二发射通道）。
        if (engine_ == nullptr) { ++out.skipped; break; }
        if (engine_->finalizeRecovered(d.order.order_id, OrderStatus::kExpired, now_ms,
                                       "recovery: not found at CTP; released")) {
          EngineEvent ee;
          ee.kind = EngineEvent::Kind::kExpired;
          ee.expired.order_id = d.order.order_id;
          ee.expired.client_order_ref = d.order.client_order_ref;
          ee.expired.expired_ts_ms = now_ms;
          ee.expired.timeout_ms = 0;  // 恢复补发：非确认超时（timeout_ms 语义=CFG-28）
          out.events.push_back(ee);
          ++out.released;
        } else {
          ++out.skipped;
        }
        break;
      }
      case flow_store::ReplayDecision::kUnknownKeepFrozen: {
        // 状态未知 ⇒ 冻结保留 + 台账登记（kTimeout 才登记；kLive 继续跟踪，
        // 由 CTP 回报驱动闭环）。
        if (ledger_ != nullptr && d.ctp_result == flow_store::OrderQueryResult::kTimeout) {
          flow_store::ReplayReport one;
          one.decisions.push_back(d);
          ledger_->consume(one, now_ms);
          ++out.unknown_held;
        } else {
          ++out.skipped;
        }
        break;
      }
      default:
        ++out.skipped;
        break;
    }
  }

  return out;
}

bool RecoveryHook::manualRelease(const flow_store::ManualReleaseRequest& req,
                                 std::int64_t now_ms,
                                 flow_store::ManualReleaseError* err,
                                 EngineEvent* out_events) {
  // 先台账五守卫（双人复核机械承载）；任何拒绝 ⇒ 引擎零变更。
  if (ledger_ == nullptr) {
    if (err != nullptr) *err = flow_store::ManualReleaseError::kNotFound;
    return false;
  }
  if (!ledger_->manualVerifyRelease(req, now_ms, err)) return false;

  // 引擎终态化：held 纪律下 unknown 单份额未预入账（份额由装配方按外源档案
  // 推定后经 restoreFrozenHold 注入——注入时机在核验前或后皆幂等：未注入 ⇒
  // 份额 0、releaseFor 空操作，账本零增量；已注入 ⇒ releaseFor 正常清零档案
  // 份额并回吐账本）。此处只负责终态迁移 + 流水留痕（kExpired + recovery:
  // 前缀，复用 T-036 契约轨，无第二留痕通道）+ 事件补发。
  if (engine_ != nullptr) {
    (void)engine_->finalizeRecovered(req.order_id, OrderStatus::kExpired, now_ms,
                                     "recovery: manual release (dual-operator verified)");
    if (out_events != nullptr) {
      out_events->kind = EngineEvent::Kind::kExpired;
      out_events->expired.order_id = req.order_id;
      out_events->expired.client_order_ref = req.client_order_ref;
      out_events->expired.expired_ts_ms = now_ms;
      out_events->expired.timeout_ms = 0;
    }
  }
  return true;
}

}  // namespace order_engine
}  // namespace hg
