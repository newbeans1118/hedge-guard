// T-035 双层校验单测（FR-TRADE-3 / FR-RISK-2 / FR-RISK-4）：子账户维度 + 总账户维度**均生效**
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::LimitLayer;
using hg::risk_engine::OrderRequest;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_engine::testutil::quota;
using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::Category;
using hg::risk_state::LoadBatch;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskState;
using hg::risk_state::ScaleScope;

const hg::risk_state::Fixed BIG = 1000000;
const hg::risk_state::Fixed TINY = 1;

AnnualScaleEntry scale(hg::risk_state::Fixed expected, hg::risk_state::Fixed accumulated) {
  AnnualScaleEntry a;
  a.expected_spot_business_qty = expected;
  a.accumulated_hedge_qty = accumulated;
  a.scale_ratio_limit = 0;
  a.category = Category::kCommodity;
  return a;
}

void test_quota_sub_dimension_only() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putQuota(QuotaLayer::kSubProduct, 1, 10, 0, quota(TINY, 0, 0, "LOT"));  // 层① 超
  b.putQuota(QuotaLayer::kAccountProduct, 0, 10, 0, quota(BIG, 0, 0, "LOT"));
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kSubProduct);  // 子账户维度
}

void test_quota_account_dimension_only() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putQuota(QuotaLayer::kSubProduct, 1, 10, 0, quota(BIG, 0, 0, "LOT"));
  b.putQuota(QuotaLayer::kSubInstrument, 1, 10, 100, quota(BIG, 0, 0, "LOT"));
  b.putQuota(QuotaLayer::kAccountProduct, 0, 10, 0, quota(TINY, 0, 0, "LOT"));  // 层③ 超
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kAccountProduct);  // 总账户维度
}

void test_quota_account_dimension_shared_across_sub_accounts() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.addWhitelist(2, 10);
  b.putQuota(QuotaLayer::kAccountProduct, 0, 10, 0, quota(TINY, 0, 0, "LOT"));
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.sub_account_id = 2;  // 子账户 2 无层①/②；账户级（层③）兜底命中
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kAccountProduct);
}

void test_scale_sub_dimension_only() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, scale(1000000, 890000));  // 90%
  HG_CHECK(bind(&st, &eng, b));
  HG_CHECK_EQ(eng.check(baseOrder()).decision, RiskDecision::kReject);  // 子账户口径
}

void test_scale_total_dimension_only() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putAnnualScale(ScaleScope::kTotalAccount, 0, 10, 2026, scale(1000000, 895000));  // 90.5%
  HG_CHECK(bind(&st, &eng, b));
  HG_CHECK_EQ(eng.check(baseOrder()).decision, RiskDecision::kReject);  // 总账户口径
}

}  // namespace

int main() {
  test_quota_sub_dimension_only();
  test_quota_account_dimension_only();
  test_quota_account_dimension_shared_across_sub_accounts();
  test_scale_sub_dimension_only();
  test_scale_total_dimension_only();
  return hg::risk_engine::testutil::report("risk_engine_double_layer_test");
}
