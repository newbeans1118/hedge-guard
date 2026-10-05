// T-181 0x1011 交易暂停 / OPEN_ONLY 限仓单测
//   （ARCHITECTURE.md §3.11:567-568「限制新开仓 → CMD_SET_TRADING_SUSPEND(OPEN_ONLY)
//      → C++ 对开仓回 0x1011，平仓放行」；§4.3.2:1378；
//      处置原则 R-RISK-5 @ REQUIREMENTS/RISK.md §4「只限开仓、不限平仓」）
//
//   触发源：控制指令 CMD_SET_TRADING_SUSPEND → 阈值键
//     risk.trading.suspend（sub=0 全账户）/ risk.trading.suspend.sub.<sub_id>（子账户），
//     由 command_handler.cc map_trading_suspend 写入；判定侧消费点 = 本单（T-181）。
//
//   判据：
//     ① 全账户暂停 ⇒ 开仓拒（rule=kTradingSuspend / code=0x1011）；
//     ② 子账户暂停仅影响该子账户（不误伤他子账户）；
//     ③ **平仓放行**（R-RISK-5；不得阻断风险释放）；
//     ④ 未暂停 / 显式解除 ⇒ 不产 0x1011；
//     ⑤ 生产流：暂停指令生效（重建 + re-apply）后即拒。
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
using hg::risk_state::LoadBatch;
using hg::risk_state::RiskState;

const std::uint32_t kErrTradingSuspended = 0x1011;

LoadBatch suspendAll(LoadBatch b) {
  b.putThreshold("risk.trading.suspend", "true");
  return b;
}

LoadBatch unsuspendAll(LoadBatch b) {
  b.putThreshold("risk.trading.suspend", "false");
  return b;
}

LoadBatch suspendSub(LoadBatch b, std::uint32_t sub) {
  b.putThreshold("risk.trading.suspend.sub." + std::to_string(sub), "true");
  return b;
}

OrderRequest closeOrder() {
  OrderRequest q = baseOrder();
  q.offset = OffsetFlag::kClose;
  return q;
}

// ① 全账户暂停 ⇒ 开仓拒（0x1011）。
void test_account_wide_suspend_blocks_open() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, suspendAll(happyBatch(1))));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kTradingSuspend);
  HG_CHECK_EQ(r.code, kErrTradingSuspended);
}

// ③ 全账户暂停 ⇒ **平仓放行**（R-RISK-5）。
void test_account_wide_suspend_allows_close() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, suspendAll(happyBatch(1))));
  const RiskResult r = eng.check(closeOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0));
}

// ② 子账户暂停仅影响该子账户。
void test_sub_account_suspend_scoped() {
  // 子账户 1 暂停 ⇒ 指令（sub=1）拒。
  {
    RiskState st;
    FakeClock clk(20260101);
    RiskEngine eng(&clk);
    HG_CHECK(bind(&st, &eng, suspendSub(happyBatch(1), 1)));
    const RiskResult r = eng.check(baseOrder());
    HG_CHECK_EQ(r.decision, RiskDecision::kReject);
    HG_CHECK_EQ(r.code, kErrTradingSuspended);
  }
  // 暂停的是**他子账户**（sub=2）⇒ 指令（sub=1）不误伤。
  {
    RiskState st;
    FakeClock clk(20260101);
    RiskEngine eng(&clk);
    HG_CHECK(bind(&st, &eng, suspendSub(happyBatch(1), 2)));
    const RiskResult r = eng.check(baseOrder());
    HG_CHECK_EQ(r.decision, RiskDecision::kPass);
  }
}

// ④ 未暂停 ⇒ 不产 0x1011（基线）。
void test_no_suspend_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
}

// ④ 显式解除（suspended=false）⇒ 不产 0x1011。
void test_explicit_unsuspend_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, unsuspendAll(happyBatch(1))));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
}

// ⑤ 生产流：CMD_SET_TRADING_SUSPEND 生效（重建快照 + re-apply）后，开仓即拒。
void test_suspend_takes_effect_after_reapply() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  HG_CHECK_EQ(eng.check(baseOrder()).decision, RiskDecision::kPass);
  HG_CHECK(bind(&st, &eng, suspendAll(happyBatch(2))));  // 暂停指令生效
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, kErrTradingSuspended);
}

}  // namespace

int main() {
  test_account_wide_suspend_blocks_open();
  test_account_wide_suspend_allows_close();
  test_sub_account_suspend_scoped();
  test_no_suspend_passes();
  test_explicit_unsuspend_passes();
  test_suspend_takes_effect_after_reapply();
  return hg::risk_engine::testutil::report("risk_engine_trading_suspend_test");
}
