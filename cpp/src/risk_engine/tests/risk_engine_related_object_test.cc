// T-173 / CH-020 交易理由关联对象兼容性单测（0x1019；FR-TRADE-10 / R-TRADE-9）
//   ② 层 C++ 兜底：纯内存独立实现（不采信 Java 结论）。
//   判据：① 关联对象存在性（related_plan_id / related_exposure_id 非零）
//         ② 关联计划归属（related_plan_id 可在快照 PlanState 解析时，其 sub_account_id 须与指令一致）
//   边界（诚实披露）：快照 ExposureState 键为 (sub, product, dir)，无 exposure_id
//     ⇒ related_exposure_id 的 id 级归属 C++ 不可判（由 Java 第①/③层保证）。
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
using hg::risk_state::LoadBatch;
using hg::risk_state::PlanEntry;
using hg::risk_state::PlanStatus;
using hg::risk_state::RiskState;

const std::uint32_t kErrRelatedObjectSubMismatch = 0x1019;

// happyBatch + 一个属于 sub=2 的外来计划 2000（跨子账户关联对象用）。
LoadBatch batchWithForeignPlan() {
  LoadBatch b = happyBatch(1);
  PlanEntry p2;
  p2.sub_account_id = 2;
  p2.product_id = 10;
  p2.status = PlanStatus::kActive;
  p2.term_start_date = 20260101;
  p2.term_end_date = 20260601;
  b.putPlan(2000, p2);
  return b;
}

// 正控：关联对象齐备且同子账户 ⇒ 放行。
void test_same_sub_account_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
}

// 命中①：缺关联计划 ⇒ 拒（0x1019）。
void test_missing_related_plan_rejected() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.related_plan_id = 0;
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kRelatedObject);
  HG_CHECK_EQ(r.code, kErrRelatedObjectSubMismatch);
}

// 命中①：缺关联敞口 ⇒ 拒（0x1019）。
void test_missing_related_exposure_rejected() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.related_exposure_id = 0;
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kRelatedObject);
  HG_CHECK_EQ(r.code, kErrRelatedObjectSubMismatch);
}

// 命中②：关联计划属于另一子账户 ⇒ 拒（0x1019）；同批中同子账户计划仍放行。
void test_foreign_related_plan_rejected() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, batchWithForeignPlan()));

  OrderRequest bad = baseOrder();
  bad.related_plan_id = 2000;  // plan 2000 @ sub=2，指令 sub=1
  const RiskResult rb = eng.check(bad);
  HG_CHECK_EQ(rb.decision, RiskDecision::kReject);
  HG_CHECK_EQ(rb.rule, RuleId::kRelatedObject);
  HG_CHECK_EQ(rb.code, kErrRelatedObjectSubMismatch);

  OrderRequest good = baseOrder();
  good.related_plan_id = 1000;  // plan 1000 @ sub=1
  HG_CHECK_EQ(eng.check(good).decision, RiskDecision::kPass);
}

// 边界：关联计划未随快照下发 ⇒ 不可判定 ≠ 不一致 ⇒ 不误拒（诚实边界，已披露）。
void test_unresolvable_related_plan_does_not_falsely_reject() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.related_plan_id = 999999;  // 不在快照
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
}

// 平仓不豁免：关联对象兼容性属入参完整性（非限额类），平仓单同样复核。
void test_close_order_also_checked() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, batchWithForeignPlan()));
  OrderRequest q = baseOrder();
  q.offset = OffsetFlag::kClose;
  q.related_plan_id = 2000;  // 跨子账户
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, kErrRelatedObjectSubMismatch);
}

}  // namespace

int main() {
  test_same_sub_account_passes();
  test_missing_related_plan_rejected();
  test_missing_related_exposure_rejected();
  test_foreign_related_plan_rejected();
  test_unresolvable_related_plan_does_not_falsely_reject();
  test_close_order_also_checked();
  return hg::risk_engine::testutil::report("risk_engine_related_object_test");
}
