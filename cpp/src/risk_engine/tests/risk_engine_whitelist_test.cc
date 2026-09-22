// T-035 R-RISK-1 品种白名单 + 合约归属 + 境内场内 单测（FR-RISK-1 / ARCHITECTURE.md §3.3）
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::OrderRequest;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_engine::testutil::instrument;
using hg::risk_state::RiskState;

void test_happy_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
  HG_CHECK_EQ(r.rule, RuleId::kNone);
}

void test_product_not_whitelisted() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.sub_account_id = 2;  // (2, 10) 未列白名单
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kWhitelist);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1001));
}

void test_instrument_product_mismatch() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  // 加入归属 product 11 的合约 101；指令 product=10（已白名单）但合约 101 属 11
  hg::risk_state::LoadBatch b = happyBatch(1);
  b.putInstrument(instrument(101, 11, 10000, "SHFE"));
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.instrument_id = 101;
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1002));
}

void test_instrument_unknown() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.instrument_id = 999;
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1002));
}

void test_non_domestic_exchange() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  hg::risk_state::LoadBatch b = happyBatch(1);
  b.putInstrument(instrument(100, 10, 10000, "LME"));  // 境外
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1003));
}

}  // namespace

int main() {
  test_happy_passes();
  test_product_not_whitelisted();
  test_instrument_product_mismatch();
  test_instrument_unknown();
  test_non_domestic_exchange();
  return hg::risk_engine::testutil::report("risk_engine_whitelist_test");
}
