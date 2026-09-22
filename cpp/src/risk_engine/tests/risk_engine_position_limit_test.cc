// T-035 R-RISK-3 持仓上限单测（FR-RISK-3；**平仓不受限**）
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
using hg::risk_engine::testutil::instrument;
using hg::risk_engine::testutil::posLimit;
using hg::risk_state::LoadBatch;
using hg::risk_state::PositionDirection;
using hg::risk_state::PositionEntry;
using hg::risk_state::PositionType;
using hg::risk_state::RiskState;

hg::risk_state::PositionEntry pos(hg::risk_state::Fixed qty) {
  PositionEntry p;
  p.position_qty = qty;
  p.available_qty = qty;
  return p;
}

void test_open_exceeds_limit() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putPositionLimit(1, 10, 100, posLimit(10000));  // 上限 1 手
  b.putPosition(1, 100, PositionDirection::kLong, PositionType::kToday, pos(10000));
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());  // 开仓 1 手 ⇒ 预计 2 手 > 1
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kPositionLimit);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1006));
}

void test_close_is_exempt() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putPositionLimit(1, 10, 100, posLimit(1));  // 极小上限
  b.putPosition(1, 100, PositionDirection::kLong, PositionType::kToday, pos(100000));
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.offset = OffsetFlag::kClose;  // 平仓
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);  // 平仓不受持仓上限限制
}

void test_boundary_equal_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putPositionLimit(1, 10, 100, posLimit(20000));  // 上限 2 手
  b.putPosition(1, 100, PositionDirection::kLong, PositionType::kToday, pos(10000));
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());  // 预计 2 手 == 上限 ⇒ 放行
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
}

void test_product_level_fallback_limit() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putInstrument(instrument(102, 10, 10000, "SHFE"));
  b.putPositionLimit(1, 10, 0, posLimit(1));  // 品种级上限（instrument=0）
  b.putPosition(1, 102, PositionDirection::kLong, PositionType::kToday, pos(100000));
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.instrument_id = 102;  // 无合约级上限 ⇒ 回退品种级
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1006));
}

void test_sell_opens_short_direction() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putPositionLimit(1, 10, 100, posLimit(10000));
  b.putPosition(1, 100, PositionDirection::kShort, PositionType::kToday, pos(10000));
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.direction = hg::risk_engine::OrderDirection::kSell;  // 开空：比对空头上限
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1006));
}

}  // namespace

int main() {
  test_open_exceeds_limit();
  test_close_is_exempt();
  test_boundary_equal_passes();
  test_product_level_fallback_limit();
  test_sell_opens_short_direction();
  return hg::risk_engine::testutil::report("risk_engine_position_limit_test");
}
