// T-035 R-RISK-6 期限红线单测（FR-RISK-6；取「建仓日+12 个月」与「合同到期+30 天」较早者；
//   三级处置；**平仓与展期不受阻断**）
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::OffsetFlag;
using hg::risk_engine::OrderRequest;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::RiskState;

void test_within_limit_passes() {
  RiskState st;
  FakeClock clk(20260110);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();  // open 20260101, term_end 20260601, today 20260110
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kPass);
}

void test_open_plus_12m_blocks() {
  RiskState st;
  FakeClock clk(20260110);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.open_date = 20260101;
  q.hedge_contract_expiry = 0;
  q.term_end_date = 20270201;  // > 20270101（open+12M）
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kTerm);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100A));
}

void test_contract_plus_grace_is_earlier() {
  RiskState st;
  FakeClock clk(20260110);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.open_date = 20260101;
  q.hedge_contract_expiry = 20261001;  // 建仓 + 10 个月
  q.term_end_date = 20261101;          // 上限 = min(20270101, 20261031) = 20261031 ⇒ 阻断
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100A));
}

void test_contract_limit_not_exceeded_passes() {
  RiskState st;
  FakeClock clk(20260110);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.open_date = 20260101;
  q.hedge_contract_expiry = 20261001;
  q.term_end_date = 20261015;  // ≤ 20261031 ⇒ 放行
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kPass);
}

void test_remaining_within_30d_warns() {
  RiskState st;
  FakeClock clk(20261215);  // 距 20270101 仅 17 天
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.open_date = 20260101;
  q.term_end_date = 20261220;  // ≤ 上限
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kWarn);
  HG_CHECK_EQ(r.rule, RuleId::kTerm);
}

void test_approved_extension_passes() {
  RiskState st;
  FakeClock clk(20260110);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.open_date = 20260101;
  q.term_end_date = 20270201;
  q.term_extension_approved = true;  // 风控 + 财务双人特批
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kPass);
}

void test_close_is_exempt() {
  RiskState st;
  FakeClock clk(20260110);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.open_date = 20200101;      // 早已超期
  q.term_end_date = 20200101;
  q.offset = OffsetFlag::kClose;
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kPass);
}

}  // namespace

int main() {
  test_within_limit_passes();
  test_open_plus_12m_blocks();
  test_contract_plus_grace_is_earlier();
  test_contract_limit_not_exceeded_passes();
  test_remaining_within_30d_warns();
  test_approved_extension_passes();
  test_close_is_exempt();
  return hg::risk_engine::testutil::report("risk_engine_term_test");
}
