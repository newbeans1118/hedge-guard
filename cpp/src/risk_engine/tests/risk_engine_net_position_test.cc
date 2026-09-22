// T-035 R-RISK-5 时点净持仓单测（FR-RISK-5；同口径换算；**减仓方向不受限**；标注快照时点）
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
using hg::risk_engine::testutil::exposure;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::ExposureDirection;
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

void test_within_exposure_passes_and_annotates_time() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putExposure(1, 10, ExposureDirection::kBuy, exposure(1000000, 7777));
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());  // 净持仓 0 + 1 手 ≤ 敞口
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
  HG_CHECK(r.has_exposure_snapshot_time);
  HG_CHECK_EQ(r.exposure_snapshot_time_ms, static_cast<std::int64_t>(7777));
}

void test_net_over_exposure_blocks() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putExposure(1, 10, ExposureDirection::kBuy, exposure(10000, 555));  // 敞口 1.0
  b.putPosition(1, 100, PositionDirection::kLong, PositionType::kToday, pos(10000));  // 已净多 1.0
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());  // 0 + 1.0(已) + 1.0(本次) = 2.0 > 1.0
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kNetPosition);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1009));
  HG_CHECK(r.has_exposure_snapshot_time);
}

void test_short_side_net() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putExposure(1, 10, ExposureDirection::kSell, exposure(10000, 555));
  b.putPosition(1, 100, PositionDirection::kShort, PositionType::kYesterday, pos(10000));
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.direction = hg::risk_engine::OrderDirection::kSell;
  const RiskResult r = eng.check(q);  // 空头净 1.0 + 1.0 = 2.0 > 1.0
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1009));
}

void test_close_is_exempt() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putExposure(1, 10, ExposureDirection::kBuy, exposure(1, 555));  // 敞口极小
  b.putPosition(1, 100, PositionDirection::kLong, PositionType::kToday, pos(1000000));
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.offset = OffsetFlag::kClose;
  HG_CHECK_EQ(eng.check(q).decision, RiskDecision::kPass);
}

}  // namespace

int main() {
  test_within_exposure_passes_and_annotates_time();
  test_net_over_exposure_blocks();
  test_short_side_net();
  test_close_is_exempt();
  return hg::risk_engine::testutil::report("risk_engine_net_position_test");
}
