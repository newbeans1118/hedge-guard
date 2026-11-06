#ifndef HG_ORDER_STATE_QUERY_BRIDGE_H
#define HG_ORDER_STATE_QUERY_BRIDGE_H
// =============================================================================
// 在途报单查询桥接件（T-212；ARCHITECTURE.md §4.5.3 ⑤⑥）
//
// T-233 变更（根因收口·方案 B）：查询键由「只用 `client_order_ref`」改为「**`order_sys_id` 优先**」。
//   · 根因：流水侧 ref = `HG<yyyyMMdd><seq6>`（16 字符）> CTP `TThostFtdcOrderRefType[13]`
//     （12 可用字节）⇒ `mapping::setField` 静默截断 ⇒ CTP 回填的 `OrderRef` 只有前 12 字符
//     ⇒ 按整串比较恒不命中 ⇒ 误判 `kNotFound` ⇒ `replay.cc` 走 `kReleaseExpired` 释放冻结额度。
//   · T-212 只做**安全失败**（ref > 12 ⇒ `kUnavailable` ⇒ `kTimeout` 冻结），功能面仍退化：
//     生产态在途对账恒 `kTimeout`、CTP 终态不可采纳、只能人工释放。
//   · T-233 修法：流水 REPORTED 记录带回 CTP `OrderSysID`（`FlowRecord::order_sys_id`），
//     重放还原进 `InflightOrder::order_sys_id`，本件**优先**以它窄查（`CThostFtdcQryOrderField`
//     唯一可用于服务端过滤的键）；只有 sys_id 缺失/空白时才回退 ref，并保留 T-212 安全失败闸门。
//   · 配套（Java 侧）：`t_trade_order.order_sys_id` 由 `EVT_ORDER_REPORT` **按 `order_id`
//     解析父指令行**后回填（回报事件载荷**无** `clientOrderRef` 字段），供运维/审计按
//     交易所报单号核验；`client_order_ref` 格式与唯一性**零改动**。
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
// 线程 / 性能（**诚实披露**）：
//   · 只在重放启动期由装配线程同步调用（**逐单串行**；不属交易热路径）；
//   · 与 `ARCHITECTURE.md` §4.5.3「并发查询」的性能目标存在**已知缺口**：本实现未并发化，
//     单笔最坏 = `HG.REPLAY.QUERY_TIMEOUT_MS`（缺省 20 s），逐单累加可达数十秒；
//   · 兜底 = **总预算** `kReplayQueryTotalBudgetMs`（60000 ms，对齐 §9 `NFR-REL-1`）：
//     每单 `timeout = min(单笔上限, 剩余预算)`，预算耗尽后剩余单**不再发起查询**，
//     直接返回 `kTimeout`（状态未知 ⇒ 保守冻结不释放）。
// =============================================================================

#include <chrono>
#include <cstdint>
#include <string>

#include "hg/ctp_adapter/ct_adapter.h"
#include "hg/flow_store/flow_store.h"  // validOrderSysId（T-233 O-1 唯一合法性口径）
#include "hg/flow_store/replay.h"

namespace hg {
namespace app {

// 重放·在途对账的**总预算**（D-2 修复；来源 `ARCHITECTURE.md` §9 `NFR-REL-1` 60 s）。
//
// 缺口与取舍（诚实披露）：
//   · `ARCHITECTURE.md` §4.5.3「性能要求」的目标是「在途报单**并发查询**（按报单号）」，
//     本实现为**逐单串行**（未并发化）⇒ 单笔上限 `HG.REPLAY.QUERY_TIMEOUT_MS`（缺省
//     20 s）× N 线性累加：实测 3 笔全不应答 = **60001 ms**，已超 `NFR-REL-1` 的 60 s。
//   · 故在装配层加**总预算**兜底（不改变单笔语义）：每单
//     `timeout = min(单笔上限, 剩余总预算)`；**总预算耗尽后剩余单不再发起查询**，
//     直接按 `kTimeout`（状态未知 ⇒ 保守冻结，**不释放**）返回。
//   · 并发化（真正的 §4.5.3 目标）随「在途对账并发化」另立任务；本常量只封上界。
static const std::int64_t kReplayQueryTotalBudgetMs = 60000;

class CtOrderStateQueryBridge : public hg::flow_store::IOrderStateQuery {
 public:
  // `timeout_ms` = 单笔上限（= `HG.REPLAY.QUERY_TIMEOUT_MS`，缺省 20000）；
  // `total_budget_ms` = 本次对账总预算（缺省 60000，见上方常量与 `NFR-REL-1`）。
  CtOrderStateQueryBridge(hg::ctp_adapter::CtAdapter* ct, std::int64_t timeout_ms,
                          std::int64_t total_budget_ms = kReplayQueryTotalBudgetMs)
      : ct_(ct),
        timeout_ms_(timeout_ms < 0 ? 0 : timeout_ms),
        budget_ms_(total_budget_ms <= 0 ? 0 : total_budget_ms),
        budget_started_(false),
        budget_deadline_() {}

  virtual hg::flow_store::OrderQueryResult query(
      const hg::flow_store::InflightOrder& order, std::string* detail) {
    if (ct_ == 0) {
      if (detail != 0) *detail = "no ctp adapter wired";
      return hg::flow_store::OrderQueryResult::kTimeout;
    }

    // D-2：总预算耗尽 ⇒ 不再发起查询（保守：状态未知 ⇒ 冻结不释放）。
    std::int64_t budget_left_ms = 0;
    if (!reserveBudget(&budget_left_ms)) {
      if (detail != 0) {
        *detail = "replay query total budget (" + std::to_string(budget_ms_) +
                  " ms, NFR-REL-1) exhausted; no query issued; state unknown; frozen held";
      }
      return hg::flow_store::OrderQueryResult::kTimeout;
    }

    hg::ctp_adapter::CtOrderQueryReq req;
    // T-233：**sys_id 优先**。`OrderSysID` 是 `CThostFtdcQryOrderField` 唯一可用于服务端窄查的键；
    //   流水侧 `client_order_ref` = `HG<yyyyMMdd><seq6>`（16 字符）超出 CTP `OrderRef` 的 12 可用
    //   字节（`TThostFtdcOrderRefType[13]`），`setField` 静默截断 ⇒ 按整串比较恒不命中 ⇒ 会误判
    //   「未到达」并释放冻结（危险方向）。故：
    //     · sys_id **合法**（`validOrderSysId`：`[A-Za-z0-9_-]`、1..20 字节）⇒ 只按 sys_id 查，
    //       **不传 ref**（避免回退到不安全路径）；
    //     · sys_id 为空/非法 ⇒ 回退 ref，并由 `CtAdapter::queryOrder` 的 T-212 安全失败闸门兜底
    //       （ref > 12 字节 ⇒ kUnavailable ⇒ 本件映射 kTimeout ⇒ 保守冻结，**绝不** kNotFound）。
    //   ⚠️ 空白（全空格）/含 `;`/`|`/超长的 OrderSysID 一律视同缺失：CTP 未分配前可能回填空串或
    //      空格填充值，而 `;`/`|` 会破坏流水 `sys_id=<v>;` 编码的值边界 ⇒ 拿它窄查必然不命中
    //      ⇒ 若当成有效值会绕过安全失败闸门 ⇒ 误释放冻结。
    if (usableOrderSysId(order.order_sys_id)) {
      req.order_sys_id = order.order_sys_id;
    } else {
      req.client_order_ref = order.client_order_ref;
    }

    // 本笔实际等待上限 = min(单笔上限, 剩余总预算)。
    const std::int64_t per_order_ms = (timeout_ms_ < budget_left_ms) ? timeout_ms_ : budget_left_ms;
    const hg::ctp_adapter::CtOrderQueryReply reply = ct_->queryOrder(req, per_order_ms);
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

  // ---- 观测面（装配自检 / 单测；非交易路径）--------------------------------
  bool ctWired() const { return ct_ != 0; }        // CTP 适配器已接
  std::int64_t totalBudgetMs() const { return budget_ms_; }  // 总预算口径（NFR-REL-1）
  std::int64_t perOrderTimeoutMs() const { return timeout_ms_; }
  // 剩余总预算（未启动窗口 ⇒ 返回完整预算；已耗尽 ⇒ 0）。
  std::int64_t remainingBudgetMs() const {
    if (!budget_started_) return budget_ms_;
    const std::int64_t left = std::chrono::duration_cast<std::chrono::milliseconds>(
                                  budget_deadline_ - std::chrono::steady_clock::now())
                                  .count();
    return left <= 0 ? 0 : left;
  }

 private:
  // T-233：CTP `OrderSysID` 是否可用于窄查。
  //   · 合法字符集/长度由 `hg::flow_store::validOrderSysId` 统一裁定（`[A-Za-z0-9_-]`、
  //     1..20 字节），**与流水编码侧共用同一口径**，禁止本件另写字符判断；
  //   · 非法值（空白填充、含 `;`/`|`/空格、超长）**视同缺失**：既不窄查，也不编码进流水。
  //     若当成有效键 ⇒ 按一个错的键查询 ⇒ CTP 不命中 ⇒ 误判 `kNotFound` ⇒ 释放冻结
  //     （危险方向），同时绕过 T-212 安全失败闸门。
  static bool usableOrderSysId(const std::string& sys_id) {
    return hg::flow_store::validOrderSysId(sys_id);
  }

  // 预约本笔查询的等待额度：预算窗口在**首次查询**时启动（构造到重放之间可能间隔很久，
  //   故不取构造时刻）；窗口耗尽后返回 false ⇒ 调用方不再发起查询（保守 kTimeout）。
  //   同一进程内若发生第二次重放，窗口**沿用**（已耗尽 ⇒ 全部保守 kTimeout，安全方向）。
  bool reserveBudget(std::int64_t* remaining_ms) {
    const std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now();
    if (!budget_started_) {
      budget_started_ = true;
      budget_deadline_ = now + std::chrono::milliseconds(budget_ms_);
    }
    const std::int64_t left = std::chrono::duration_cast<std::chrono::milliseconds>(
                                  budget_deadline_ - now)
                                  .count();
    if (left <= 0) return false;
    if (remaining_ms != 0) *remaining_ms = left;
    return true;
  }

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
    }
    // 诊断留痕：错误文本不依赖 error_id（D-1 的「拒绝查询」无 CTP 错误码但有明确原因）。
    if (!r.ctp_error_msg.empty()) s += " msg=" + r.ctp_error_msg;
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
  std::int64_t timeout_ms_;   // 单笔等待上限（HG.REPLAY.QUERY_TIMEOUT_MS）
  std::int64_t budget_ms_;    // 本次对账总预算（NFR-REL-1 = 60 s；D-2）
  bool budget_started_;       // 预算窗口是否已启动（首次查询时启动）
  std::chrono::steady_clock::time_point budget_deadline_;
};

}  // namespace app
}  // namespace hg

#endif  // HG_ORDER_STATE_QUERY_BRIDGE_H
