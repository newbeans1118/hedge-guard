// T-035 纯函数式（无副作用）单测（ARCHITECTURE.md §3.3：`check` 为 const 快照 + 入参，无副作用）
//   · 可对 `const RiskEngine&` 求值（编译期保证不改引擎）
//   · 同一快照 + 同一入参 ⇒ 结果逐字节一致（可重复求值）
//   · 求值**不改变**快照内容与引擎阈值
//   · 交替求值不互相污染（无隐藏状态）
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::OrderRequest;
using hg::risk_engine::OffsetFlag;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::RiskState;

bool same(const RiskResult& a, const RiskResult& b) {
  if (a.decision != b.decision || a.rule != b.rule || a.code != b.code) return false;
  if (a.limit_layer != b.limit_layer || a.has_limit_layer != b.has_limit_layer) return false;
  if (a.price_deviation_na != b.price_deviation_na) return false;
  if (a.entry_count != b.entry_count) return false;
  for (int i = 0; i < a.entry_count; ++i) {
    if (a.entries[i].rule != b.entries[i].rule || a.entries[i].code != b.entries[i].code) {
      return false;
    }
    if (a.entries[i].layer != b.entries[i].layer) return false;
    if (a.entries[i].current != b.entries[i].current) return false;
    if (a.entries[i].threshold != b.entries[i].threshold) return false;
  }
  return true;
}

void test_const_call_and_determinism() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));

  const RiskEngine& ceng = eng;  // 编译期：check 为 const
  const RiskResult r1 = ceng.check(baseOrder());
  const RiskResult r2 = ceng.check(baseOrder());
  HG_CHECK_EQ(r1.decision, RiskDecision::kPass);
  HG_CHECK(same(r1, r2));  // 可重复求值结果一致
}

void test_reject_case_determinism() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  hg::risk_state::LoadBatch b = happyBatch(1);
  b.putQuota(hg::risk_state::QuotaLayer::kSubProduct, 1, 10, 0,
             hg::risk_state::QuotaEntry());
  b.putThreshold("risk.price.block_pct", "0.01");
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.limit_price = 1060000;  // 偏离 6% ⇒ 阻断
  const RiskResult r1 = eng.check(q);
  const RiskResult r2 = eng.check(q);
  HG_CHECK_EQ(r1.decision, RiskDecision::kReject);
  HG_CHECK(same(r1, r2));
}

void test_no_observable_state_change() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));

  const std::size_t before_records = st.snapshot().record_count();
  const hg::risk_state::Fixed before_limit =
      st.snapshot().findQuota(hg::risk_state::QuotaLayer::kSubProduct, 1, 10, 0)->limit;
  const hg::risk_engine::RiskThresholds before_th = eng.thresholds();

  (void)eng.check(baseOrder());
  OrderRequest bad = baseOrder();
  bad.volume = 0;  // 非法
  (void)eng.check(bad);

  HG_CHECK_EQ(st.snapshot().record_count(), before_records);
  HG_CHECK_EQ(st.snapshot().findQuota(hg::risk_state::QuotaLayer::kSubProduct, 1, 10, 0)->limit,
              before_limit);
  HG_CHECK_EQ(eng.thresholds().price_block_pct, before_th.price_block_pct);
  HG_CHECK_EQ(eng.thresholds().term_max_months, before_th.term_max_months);
}

void test_alternating_orders_no_cross_talk() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));

  const RiskResult a1 = eng.check(baseOrder());

  OrderRequest other = baseOrder();
  other.sub_account_id = 2;  // 未白名单 ⇒ 拒绝
  const RiskResult b = eng.check(other);
  HG_CHECK_EQ(b.decision, RiskDecision::kReject);

  const RiskResult a2 = eng.check(baseOrder());
  HG_CHECK(same(a1, a2));  // 中间插入另一笔指令不得改变结果
}

void test_two_engines_same_snapshot_same_result() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine e1(&clk);
  RiskEngine e2(&clk);
  HG_CHECK(bind(&st, &e1, happyBatch(1)));
  e2.applySnapshot(st.snapshot());
  HG_CHECK(same(e1.check(baseOrder()), e2.check(baseOrder())));
}

void test_not_ready_and_invalid_input() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);  // 未 applySnapshot
  const RiskResult nr = eng.check(baseOrder());
  HG_CHECK_EQ(nr.decision, RiskDecision::kReject);
  HG_CHECK_EQ(nr.code, static_cast<std::uint32_t>(0x0401));

  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.sub_account_id = 0;  // 非法
  const RiskResult iv = eng.check(q);
  HG_CHECK_EQ(iv.decision, RiskDecision::kReject);
  HG_CHECK_EQ(iv.code, static_cast<std::uint32_t>(0x3003));
}

}  // namespace

int main() {
  test_const_call_and_determinism();
  test_reject_case_determinism();
  test_no_observable_state_change();
  test_alternating_orders_no_cross_talk();
  test_two_engines_same_snapshot_same_result();
  test_not_ready_and_invalid_input();
  return hg::risk_engine::testutil::report("risk_engine_purity_test");
}
