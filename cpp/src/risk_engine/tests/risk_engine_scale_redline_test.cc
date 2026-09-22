// T-035 R-RISK-4 规模红线单测（FR-RISK-4；商品类 90% / 贸易类 80%；子账户+全账户双算取严者；
//   **平仓不受限**）
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::OrderRequest;
using hg::risk_engine::OffsetFlag;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::Category;
using hg::risk_state::LoadBatch;
using hg::risk_state::RiskState;
using hg::risk_state::ScaleScope;

// 1 手 = 1.0 实物量（inc = 10000）；D = 1000000（100.0）
const hg::risk_state::Fixed D = 1000000;

AnnualScaleEntry scale(hg::risk_state::Fixed expected, hg::risk_state::Fixed accumulated) {
  AnnualScaleEntry a;
  a.expected_spot_business_qty = expected;
  a.accumulated_hedge_qty = accumulated;
  a.scale_ratio_limit = 0;  // 用分类默认比例
  a.category = Category::kCommodity;
  a.scope = ScaleScope::kSubAccount;
  return a;
}

void test_commodity_below_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, scale(D, 800000));  // 0.81
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
}

void test_commodity_reaches_90_blocks() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, scale(D, 890000));  // (890000+10000)/1e6 = 90%
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kScaleRedline);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1007));
}

void test_trade_class_uses_80() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putSubProductCategory(1, 10, Category::kTrade);  // 贸易类 → 80%
  b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, scale(D, 790000));  // 80%
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1008));
}

void test_commodity_trade_not_mixed() {
  // 同一数字（80%）在商品类下不越线（90%）、在贸易类下越线（80%）
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, scale(D, 790000));  // 0.80
  HG_CHECK(bind(&st, &eng, b));
  HG_CHECK_EQ(eng.check(baseOrder()).decision, RiskDecision::kPass);  // 商品类 90% 未触及
}

void test_double_scope_take_stricter() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  // 子账户口径不越线
  b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, scale(D, 800000));  // 0.81
  // 总账户口径越线（子账户分量用哨兵 0）
  b.putAnnualScale(ScaleScope::kTotalAccount, 0, 10, 2026, scale(D, 895000));  // 0.905
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1007));
}

void test_close_is_exempt() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, scale(D, 999999));  // 远超红线
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.offset = OffsetFlag::kClose;  // 平仓不受限
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kPass);
}

}  // namespace

int main() {
  test_commodity_below_passes();
  test_commodity_reaches_90_blocks();
  test_trade_class_uses_80();
  test_commodity_trade_not_mixed();
  test_double_scope_take_stricter();
  test_close_is_exempt();
  return hg::risk_engine::testutil::report("risk_engine_scale_redline_test");
}
