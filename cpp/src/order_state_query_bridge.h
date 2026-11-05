#ifndef HG_ORDER_STATE_QUERY_BRIDGE_H
#define HG_ORDER_STATE_QUERY_BRIDGE_H
// =============================================================================
// 在途报单查询桥接件（T-212；ARCHITECTURE.md §4.5.3 ⑤⑥）
//
// 职责：把 `ctp_adapter` 的**异步 SPI 查询能力**（`CtAdapter::queryOrder`：发起
//   `ReqQryOrder` → 有界等待 `OnRspQryOrder` 回调 → 归一化结论）适配为 `flow_store`
//   重放控制器所需的**同步接缝** `hg::flow_store::IOrderStateQuery`。
//
// 为何单独成件（不塞进 ctp_adapter / trading_app）：
//   · ctp_adapter 的架构依赖仅「CTP SDK」（§3.1），**不得**反向依赖 flow_store 的类型
//     ⇒ CTP 查询能力留在 ctp_adapter、`IOrderStateQuery` 适配留在装配层（本件）；
//   · `trading_app.{h,cc}` 只做**装配接线**（`replay_.set_order_query(&order_query_)`），
//     不承载映射逻辑（工单要求「trading_app 仅装配点接线」）。
//
// 语义映射（`CtOrderQueryOutcome`/`CtOrderState` → `OrderQueryResult`）：
//   · kNotFound                         → kNotFound（未实际到达 ⇒ 释放冻结 + 补发 EXPIRED）
//   · kUnavailable / kTimeout           → kTimeout（状态未知 ⇒ 保守冻结不释放 + 告警）
//   · kFound + kLive                    → kLive（仍在途 ⇒ 冻结保留、继续跟踪）
//   · kFound + kFilled/kCancelled/kRejected → 同名终态（按 CTP 结果修正）
//   · kFound + kUnknown                 → kTimeout（不可判定 ⇒ 保守）
//   本件**不**产出 kConflict —— 冲突判定需流水终态与 CTP 结果对比，而重放接缝只传
//   《在途单（order_id/ref/ts）》、不含流水终态；冲突面仍由 `ReplayController` 的
//   kConflict 分支保留（当前无来源，属**已知覆盖残留**，随流水终态透传另立任务）。
//
// 线程：只在重放启动期由装配线程同步调用（逐单对账）；不属交易热路径。
// =============================================================================

#include <cstdint>
#include <string>

#include "hg/ctp_adapter/ct_adapter.h"
#include "hg/flow_store/replay.h"

namespace hg {
namespace app {

class CtOrderStateQueryBridge : public hg::flow_store::IOrderStateQuery {
 public:
  CtOrderStateQueryBridge(hg::ctp_adapter::CtAdapter* ct, std::int64_t timeout_ms)
      : ct_(ct), timeout_ms_(timeout_ms < 0 ? 0 : timeout_ms) {}

  virtual hg::flow_store::OrderQueryResult query(
      const hg::flow_store::InflightOrder& order, std::string* detail) {
    if (ct_ == 0) {
      if (detail != 0) *detail = "no ctp adapter wired";
      return hg::flow_store::OrderQueryResult::kTimeout;
    }

    hg::ctp_adapter::CtOrderQueryReq req;
    // 流水侧仅有 `order_id` / `client_order_ref`（= CTP OrderRef）⇒ 以 OrderRef 选行。
    // （`CThostFtdcQryOrderField` 无 OrderRef，无法服务端窄查，见 ct_types.h 头注。）
    req.client_order_ref = order.client_order_ref;

    const hg::ctp_adapter::CtOrderQueryReply reply = ct_->queryOrder(req, timeout_ms_);
    if (detail != 0) *detail = describe(reply);

    switch (reply.outcome) {
      case hg::ctp_adapter::CtOrderQueryOutcome::kNotFound:
        return hg::flow_store::OrderQueryResult::kNotFound;
      case hg::ctp_adapter::CtOrderQueryOutcome::kUnavailable:
      case hg::ctp_adapter::CtOrderQueryOutcome::kTimeout:
        return hg::flow_store::OrderQueryResult::kTimeout;
      case hg::ctp_adapter::CtOrderQueryOutcome::kFound:
        switch (reply.state) {
          case hg::ctp_adapter::CtOrderState::kLive:
            return hg::flow_store::OrderQueryResult::kLive;
          case hg::ctp_adapter::CtOrderState::kFilled:
            return hg::flow_store::OrderQueryResult::kFilled;
          case hg::ctp_adapter::CtOrderState::kCancelled:
            return hg::flow_store::OrderQueryResult::kCancelled;
          case hg::ctp_adapter::CtOrderState::kRejected:
            return hg::flow_store::OrderQueryResult::kRejected;
          case hg::ctp_adapter::CtOrderState::kUnknown:
          default:
            return hg::flow_store::OrderQueryResult::kTimeout;
        }
      default:
        return hg::flow_store::OrderQueryResult::kTimeout;
    }
  }

 private:
  static std::string describe(const hg::ctp_adapter::CtOrderQueryReply& r) {
    std::string s;
    s += "ctp_query outcome=";
    s += outcomeName(r.outcome);
    s += " state=";
    s += stateName(r.state);
    if (!r.order_sys_id.empty()) {
      s += " order_sys_id=";
      s += r.order_sys_id;
    }
    s += " vol(total/traded/left)=" + std::to_string(r.volume_total) + "/" +
         std::to_string(r.volume_traded) + "/" + std::to_string(r.volume_left);
    if (r.ctp_error_id != 0) {
      s += " ctp_error_id=" + std::to_string(r.ctp_error_id);
      if (!r.ctp_error_msg.empty()) s += " msg=" + r.ctp_error_msg;
    }
    return s;
  }

  static const char* outcomeName(hg::ctp_adapter::CtOrderQueryOutcome o) {
    switch (o) {
      case hg::ctp_adapter::CtOrderQueryOutcome::kFound: return "FOUND";
      case hg::ctp_adapter::CtOrderQueryOutcome::kNotFound: return "NOT_FOUND";
      case hg::ctp_adapter::CtOrderQueryOutcome::kUnavailable: return "UNAVAILABLE";
      case hg::ctp_adapter::CtOrderQueryOutcome::kTimeout: return "TIMEOUT";
      default: return "UNKNOWN";
    }
  }

  static const char* stateName(hg::ctp_adapter::CtOrderState s) {
    switch (s) {
      case hg::ctp_adapter::CtOrderState::kLive: return "LIVE";
      case hg::ctp_adapter::CtOrderState::kFilled: return "FILLED";
      case hg::ctp_adapter::CtOrderState::kCancelled: return "CANCELLED";
      case hg::ctp_adapter::CtOrderState::kRejected: return "REJECTED";
      case hg::ctp_adapter::CtOrderState::kUnknown: return "UNKNOWN";
      default: return "UNKNOWN";
    }
  }

  CtOrderStateQueryBridge(const CtOrderStateQueryBridge&);
  CtOrderStateQueryBridge& operator=(const CtOrderStateQueryBridge&);

  hg::ctp_adapter::CtAdapter* ct_;
  std::int64_t timeout_ms_;
};

}  // namespace app
}  // namespace hg

#endif  // HG_ORDER_STATE_QUERY_BRIDGE_H
