// T-035 R-RISK-8 价格偏离单测（FR-RISK-8；基准 = 昨结算价；>5% 阻断；(2%,5%] 告警；市价单不适用）
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::OrderRequest;
using hg::risk_engine::PriceType;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::LoadBatch;
using hg::risk_state::RiskState;

// 昨结算价 1.0（= 1000000，kAmountScale）
const hg::risk_state::Fixed BASE = 1000000;

void test_zero_deviation_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  HG_CHECK_EQ(eng.check(baseOrder()).decision, RiskDecision::kPass);
}

void test_within_2pct_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.limit_price = 1010000;  // +1%
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kPass);
  q.limit_price = 1020000;  // 恰 +2% ⇒ 不在 (2%,5%] ⇒ 通过
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kPass);
}

void test_warn_band_needs_confirm() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.limit_price = 1030000;  // +3%
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kWarn);
  HG_CHECK_EQ(r.rule, RuleId::kPriceDeviation);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1018));
}

void test_boundary_5pct_is_warn_not_block() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.limit_price = 1050000;  // 恰 +5% ⇒ (2%,5%] 告警
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kWarn);
}

void test_over_5pct_blocks() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.limit_price = 1060000;  // +6%
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1010));
}

void test_market_order_na() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.price_type = PriceType::kMarket;
  q.limit_price = 0;
  q.prev_settlement_price = 0;
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
  HG_CHECK(r.price_deviation_na);  // 显式标注：不适用价格偏离校验
}

void test_limit_without_base_price_blocks() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.prev_settlement_price = 0;
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1015));
}

void test_threshold_override_applies() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putThreshold("risk.price.block_pct", "0.01");  // 1% 阻断
  b.putThreshold("risk.price.warn_pct", "0.005");  // 0.5% 告警
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.limit_price = 1020000;  // +2% > 1% ⇒ 阻断
  HG_CHECK_EQ(eng.check(q).code, static_cast<std::uint32_t>(0x1010));
}

}  // namespace

int main() {
  test_zero_deviation_passes();
  test_within_2pct_passes();
  test_warn_band_needs_confirm();
  test_boundary_5pct_is_warn_not_block();
  test_over_5pct_blocks();
  test_market_order_na();
  test_limit_without_base_price_blocks();
  test_threshold_override_applies();
  return hg::risk_engine::testutil::report("risk_engine_price_deviation_test");
}
