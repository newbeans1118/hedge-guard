// T-035 平仓豁免回归单测（BUG-002 / TE-T035-001）
//   铁律「修一类缺陷必须查全同类」⇒ 对每一条**需要**平仓豁免的规则给**最小对偶**：
//     ① 额度 / 上限 / 规模 / 敞口 / 期限**已耗尽或超限**时，开仓 ⇒ 必拒（对应错误码）；
//     ② **仅把 `offset` 改为 `kClose`，其余入参逐字节不变** ⇒ 必放行（`kPass` 且无 entries）。
//   依据：`R-RISK-5`（额度阻断 / 限制新开仓 / 加总对账阻断一律不得阻断平仓）+ §3.3 规则表
//         「平仓不受限 / 减仓方向不受限 / 不阻断平仓与展期置换」。
//   需要豁免的规则（5 条）：`R-RISK-2` 三层限额 / `R-RISK-3` 持仓上限 / `R-RISK-4` 规模红线 /
//         `R-RISK-5` 时点净持仓 / `R-RISK-6` 期限红线。
//   不豁免（3 条，见 `order_request.h` 注释与 BUG-002 修复记录）：`R-RISK-1` 白名单 /
//         `R-RISK-7` 权限与授权 / `R-RISK-8` 价格偏离 —— 非「限制类处置」。
//   ⚠️ 本目录属 E4 扫描根：不得出现锁原语 / 同步 I/O 调用形态。
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::LimitLayer;
using hg::risk_engine::OffsetFlag;
using hg::risk_engine::OrderDirection;
using hg::risk_engine::OrderRequest;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::exposure;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_engine::testutil::posLimit;
using hg::risk_engine::testutil::quota;
using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::Category;
using hg::risk_state::ExposureDirection;
using hg::risk_state::Fixed;
using hg::risk_state::LoadBatch;
using hg::risk_state::PositionDirection;
using hg::risk_state::PositionEntry;
using hg::risk_state::PositionType;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskState;
using hg::risk_state::ScaleScope;

const Fixed BIG = 1000000;
const Fixed TINY = 1;  // 0 + 10000 > 1 ⇒ 超限

// 1 手 = 1.0 实物量（inc = 10000）；D = 1000000（100.0）
const Fixed D = 1000000;

LoadBatch quotaBatch(Fixed l1, Fixed l2, Fixed l3) {
  LoadBatch b = happyBatch(1);
  b.putQuota(QuotaLayer::kSubProduct, 1, 10, 0, quota(l1, 0, 0, "LOT"));
  b.putQuota(QuotaLayer::kSubInstrument, 1, 10, 100, quota(l2, 0, 0, "LOT"));
  b.putQuota(QuotaLayer::kAccountProduct, 0, 10, 0, quota(l3, 0, 0, "LOT"));
  return b;
}

AnnualScaleEntry scale(Fixed expected, Fixed accumulated) {
  AnnualScaleEntry a;
  a.expected_spot_business_qty = expected;
  a.accumulated_hedge_qty = accumulated;
  a.scale_ratio_limit = 0;  // 用分类默认比例（商品类 90%）
  a.category = Category::kCommodity;
  a.scope = ScaleScope::kSubAccount;
  return a;
}

PositionEntry pos(Fixed qty) {
  PositionEntry p;
  p.position_qty = qty;
  p.available_qty = qty;
  return p;
}

// ---- 最小对偶 harness -------------------------------------------------------
// 同一快照下：① 开仓（入参 `open_req`）⇒ 必拒（rule / code / layer 精确匹配）；
//             ② **仅把 `offset` 改为 `kClose`** ⇒ 必放行且无命中项。
void expectOpenRejectClosePass(const LoadBatch& b, std::int64_t today, const OrderRequest& open_req,
                               RuleId rule, std::uint32_t code, LimitLayer layer) {
  RiskState st;
  FakeClock clk(today);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult ro = eng.check(open_req);  // 开仓
  HG_CHECK_EQ(ro.decision, RiskDecision::kReject);
  HG_CHECK_EQ(ro.rule, rule);
  HG_CHECK_EQ(ro.code, code);
  HG_CHECK_EQ(ro.limit_layer, layer);
  OrderRequest close_req = open_req;  // 最小对偶：仅改 offset
  close_req.offset = OffsetFlag::kClose;
  const RiskResult rc = eng.check(close_req);
  HG_CHECK_EQ(rc.decision, RiskDecision::kPass);
  HG_CHECK_EQ(rc.rule, RuleId::kNone);
  HG_CHECK_EQ(rc.entry_count, 0);
}

// ---- R-RISK-2 三层限额（本缺陷的修复对象）----------------------------------
void test_quota_layer1_close_exempt() {
  expectOpenRejectClosePass(quotaBatch(TINY, BIG, BIG), 20260101, baseOrder(), RuleId::kQuota,
                            static_cast<std::uint32_t>(0x1004), LimitLayer::kSubProduct);
}

void test_quota_layer2_close_exempt() {
  expectOpenRejectClosePass(quotaBatch(BIG, TINY, BIG), 20260101, baseOrder(), RuleId::kQuota,
                            static_cast<std::uint32_t>(0x1017), LimitLayer::kSubInstrument);
}

void test_quota_layer3_close_exempt() {
  expectOpenRejectClosePass(quotaBatch(BIG, BIG, TINY), 20260101, baseOrder(), RuleId::kQuota,
                            static_cast<std::uint32_t>(0x1005), LimitLayer::kAccountProduct);
}

void test_quota_all_three_close_exempt() {
  // 三层全超：开仓回层②（全序最细者）；平仓三层全豁免
  expectOpenRejectClosePass(quotaBatch(TINY, TINY, TINY), 20260101, baseOrder(), RuleId::kQuota,
                            static_cast<std::uint32_t>(0x1017), LimitLayer::kSubInstrument);
}

void test_quota_sell_close_exempt() {
  // 减仓（方向 = 卖出）同样豁免：层①超限的开仓卖单被拒，改平仓即放行
  OrderRequest sell_open = baseOrder();
  sell_open.direction = OrderDirection::kSell;
  expectOpenRejectClosePass(quotaBatch(TINY, BIG, BIG), 20260101, sell_open, RuleId::kQuota,
                            static_cast<std::uint32_t>(0x1004), LimitLayer::kSubProduct);
}

// ---- R-RISK-3 持仓上限 -----------------------------------------------------
void test_position_limit_close_exempt() {
  LoadBatch b = happyBatch(1);
  // 上限 1（kQty 标度 1e-4 手）；开仓 1 手 = 10000 ⇒ 预计(0+10000) > 1 ⇒ 拒
  b.putPositionLimit(1, 10, 100, posLimit(1));
  expectOpenRejectClosePass(b, 20260101, baseOrder(), RuleId::kPositionLimit,
                            static_cast<std::uint32_t>(0x1006), LimitLayer::kNone);
}

// ---- R-RISK-4 规模红线 -----------------------------------------------------
void test_scale_close_exempt() {
  LoadBatch b = happyBatch(1);
  b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, scale(D, 999999));  // 远超 90% 红线
  expectOpenRejectClosePass(b, 20260101, baseOrder(), RuleId::kScaleRedline,
                            static_cast<std::uint32_t>(0x1007), LimitLayer::kNone);
}

// ---- R-RISK-5 时点净持仓 ---------------------------------------------------
void test_net_position_close_exempt() {
  LoadBatch b = happyBatch(1);
  b.putExposure(1, 10, ExposureDirection::kBuy, exposure(10000, 555));  // 敞口 1.0
  b.putPosition(1, 100, PositionDirection::kLong, PositionType::kToday, pos(10000));  // 已净多 1.0
  expectOpenRejectClosePass(b, 20260101, baseOrder(), RuleId::kNetPosition,
                            static_cast<std::uint32_t>(0x1009), LimitLayer::kNone);
}

// ---- R-RISK-6 期限红线 -----------------------------------------------------
void test_term_close_exempt() {
  OrderRequest overdue = baseOrder();
  overdue.open_date = 20260101;
  overdue.term_end_date = 20270201;  // > 建仓日 + 12 个月（20270101）
  expectOpenRejectClosePass(happyBatch(1), 20260110, overdue, RuleId::kTerm,
                            static_cast<std::uint32_t>(0x100A), LimitLayer::kNone);
}

}  // namespace

int main() {
  // R-RISK-2（本缺陷修复对象）：层①/②/③ + 三层全超 + 减仓方向
  test_quota_layer1_close_exempt();
  test_quota_layer2_close_exempt();
  test_quota_layer3_close_exempt();
  test_quota_all_three_close_exempt();
  test_quota_sell_close_exempt();
  // 其余四条本已实现平仓豁免的规则（逐条对偶，防回归 / 防口径漂移）
  test_position_limit_close_exempt();
  test_scale_close_exempt();
  test_net_position_close_exempt();
  test_term_close_exempt();
  return hg::risk_engine::testutil::report("risk_engine_close_exemption_test");
}
