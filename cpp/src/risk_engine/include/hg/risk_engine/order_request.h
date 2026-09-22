#ifndef HG_RISK_ENGINE_ORDER_REQUEST_H
#define HG_RISK_ENGINE_ORDER_REQUEST_H
// =============================================================================
// 事前风控引擎 —— 判定入参 OrderRequest（ARCHITECTURE.md §3.3）
//
// `RiskEngine::check(const OrderRequest&)` 的入参：一笔待校验的交易指令要素。
// 字段取自 §4.2.4 `CMD_PLACE_ORDER`（协议字段级定义）与 §3.3 各规则「判定所需内存数据」。
//
// 定点纪律（§4.4.2）：金额 / 价格 / 数量**一律定点 int64**，禁止 double。
//   · 数量 `volume`：手数，scale = 1e4（`kQtyScale`）
//   · 价格 `limit_price` / `prev_settlement_price`：scale = 1e6（`kAmountScale`）
//
// ⚠️ 本目录属 `E4` 扫描根：本文件为**纯数据**（无 I/O、无锁、无堆分配）。
// =============================================================================

#include <cstdint>

#include "hg/risk_state/model.h"

namespace hg {
namespace risk_engine {

using hg::risk_state::Fixed;

// 买卖方向（对齐 §4.2.4 `direction`）
enum class OrderDirection : std::uint8_t { kBuy = 1, kSell = 2 };

// 开平标志（对齐 CTP `CombOffsetFlag` 的语义收敛）：本引擎只区分**开仓 / 平仓**。
//   · `kClose` = 平仓（含减仓 / 风险处置平仓）；
//   · 限额类规则 `R-RISK-2/3/4/5/6` 对其**豁免**（`R-RISK-5`「只限开仓、不限平仓」原则：
//     额度阻断 / 持仓上限 / 规模红线 / 时点净持仓 / 期限红线一律不得阻断平仓）；
//   · 资格 / 授权 / 报单合理性规则（`R-RISK-1` 白名单、`R-RISK-7` 权限与授权、
//     `R-RISK-8` 价格偏离）**不豁免**——非「限制类处置」，其豁免会放行非授权或数据不符的报单。
enum class OffsetFlag : std::uint8_t { kOpen = 1, kClose = 2 };

// 价格类型（对齐 §4.2.4 `price_type`）：市价单**不适用**价格偏离校验（`R-RISK-8`）。
enum class PriceType : std::uint8_t { kLimit = 1, kMarket = 2 };

struct OrderRequest {
  // ---- 溯源自 `CMD_PLACE_ORDER` ----
  std::uint64_t client_order_ref = 0;  // 客户端指令引用（唯一）
  std::uint64_t trade_plan_id = 0;     // 交易计划 ID（§6.1 协议级必填）
  std::uint64_t exposure_id = 0;       // 实货敞口 ID（§6.1 协议级必填）
  std::uint32_t sub_account_id = 0;    // 子账户（0 = 非法，拒绝）
  std::uint32_t product_id = 0;        // 品种（0 = 非法，拒绝）
  std::uint32_t instrument_id = 0;     // 合约（0 = 非法，拒绝）
  OrderDirection direction = OrderDirection::kBuy;
  OffsetFlag offset = OffsetFlag::kOpen;
  PriceType price_type = PriceType::kLimit;
  Fixed limit_price = 0;             // 限价（kAmountScale）
  Fixed prev_settlement_price = 0;   // **昨结算价**（kAmountScale）；0 = 无可用基准价
  Fixed volume = 0;                  // 手数（kQtyScale）；≤ 0 = 非法，拒绝
  std::uint32_t operator_id = 0;     // 操作人（0 = 非法，拒绝）

  // ---- 期限红线（`R-RISK-6`）输入；yyyymmdd ----
  std::int64_t open_date = 0;              // 建仓日（起算点）；0 = 由计划 / 时钟推定
  std::int64_t term_end_date = 0;          // 计划期限止；0 = 回退到计划 `term_end_date`
  std::int64_t hedge_contract_expiry = 0;  // 对应实货合同到期日；0 = 无（仅用建仓日 + 12 个月）
  bool term_extension_approved = false;    // 超期**已审批**（否则超期未审批 ⇒ 阻断新开仓）

  // ---- 规模红线（`R-RISK-4`）年度键与判定时点 ----
  std::int64_t year = 0;      // 年度（规模基数键）；0 = 由建仓日 / 时钟推定
  std::int64_t now_ms = 0;    // 判定时点（毫秒）；0 = 取可注入时钟
};

// 入参非法性自检（§3.3 备注「非法判定值一律拒绝」）：返回 nullptr = 合法，
// 否则返回**指向静态字符串**的非法原因（无分配）。
const char* orderRequestInvalidReason(const OrderRequest& req);

}  // namespace risk_engine
}  // namespace hg

#endif  // HG_RISK_ENGINE_ORDER_REQUEST_H
