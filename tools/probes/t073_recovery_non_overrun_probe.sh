#!/usr/bin/env bash
# =============================================================================
# T-073 探针：崩溃恢复「不超额下单」组合语义实测（工单 §执行形态·验收 4）
#
# 主张：T-041 恢复链组件级测试齐备（restoreFrozenHold 入账 / quotaLedgerViolation
#       账本超额拒单 / post_restore_accept_uses_ledger 幂等还原），但
#       「还原在途单冻结 → 恢复后新单受理 → 三层账本累计 ⇒ 超额新单被拒（不发 CTP）」
#       的**组合语义**无既有用例承载。本探针以一次性编译源组装该组合并实证，
#       不改动任何既有测试件与实现（单写者纪律）。
#
# 判定（对应 M3.md §7 / TC-M3-32 额度面语义）：
#   ① 还原在途单（restoreFrozenHold 60 手）后 frozenOf 三层 == 60 万（1e4 定点）
#   ② 恢复后新单 50 手受理成功（60+50 ≤ 100 ⇒ 不得误拒——防误拒面）
#   ③ 再一单 50 手被拒：risk.code ∈ {0x1004,0x1005,0x1017} 且决策 kReject
#      （quotaLedgerViolation 账本守卫：60+50+50 > 100）
#   ④ 被拒单不占号（order_count 恰 2）、CTP submit_calls == 0（不发 CTP）
#   ⑤ 拒单后冻结账本不变（frozenOf 仍 110 万）——拒单不产生冻结副作用
#
# 用后即弃：产物在 /tmp（TMPDIR=/）下 mktemp 目录，退出前清理。
# =============================================================================
set -u
REPO=/root/myspace/hedge-guard
CPP=$REPO/cpp
WORK=$(mktemp -d /tmp/t073-probe.XXXXXX)
OUT_LOG=$WORK/probe_main.cc
BUILD=$WORK/build

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

cat > "$OUT_LOG" <<'EOF'
// T-073 探针：崩溃恢复不超额下单组合语义（一次性编译源，不入树）
//
// 设计事实（T-041 披露面，本探针据此构造）：
//   · restoreFromReplay 产出壳档案（仅 client_order_ref；sub/product/instrument
//     要素不可还原）⇒ restoreFrozenHold 入账键 = 壳键 (0,0)/(0,0)/(0,0)；
//   · unknown 未闭环 ⇒ 装配层就绪门禁 0x0401 阻断一切新指令（hgcp recovery_gate
//     已有 ctest 覆盖）⇒ 壳键盲区不构成超额窗口；
//   · 人工闭环（双人核验撤销，FR-TRADE-15）⇒ 份额自壳键释放 ⇒ 新单基线回归正确。
//
// 场景：崩溃前 SENT 在途 60 手（层限 100 手）→ 重放(kTimeout 保守轨) → unknown 持有
//   → 双人核验释放（该单经核验已撤销 ⇒ 60 手额度正确腾出）→ 恢复后新单三连：
//   ① 50 手受理（占号 2，无重号）；② 50 手受理（50+50=100 ≤ 100，不误拒）；
//   ③ 50 手被账本守卫拒（150 > 100 ⇒ kReject，不发 CTP，不占号，无冻结副作用）。
#include <cstdio>
#include <string>
#include <vector>

#include "hg/order_engine/order_engine.h"
#include "hg/order_engine/recovery_hook.h"
#include "hg/flow_store/recovery.h"
#include "hg/risk_engine/risk_engine.h"
#include "hg/risk_state/risk_state.h"
#include "risk_engine_test_util.h"

namespace {
using hg::risk_state::Fixed;
using hg::risk_engine::RiskDecision;

class FakeGateway : public hg::order_engine::ITradeGateway {
 public:
  int submit_calls = 0;
  hg::order_engine::SubmitAck submitOrder(
      const hg::ctp_adapter::CtOrderReq& req) override {
    (void)req;
    ++submit_calls;
    hg::order_engine::SubmitAck a;
    a.code = 0;
    a.order_local_id = "LOCAL-1";
    return a;
  }
  int reqCancel(const hg::ctp_adapter::CtCancelReq& req) override {
    (void)req;
    return 0;
  }
};

class TimeoutQuery : public hg::flow_store::IOrderStateQuery {
 public:
  int calls = 0;
  hg::flow_store::OrderQueryResult query(const hg::flow_store::InflightOrder& o,
                                         std::string* detail) override {
    (void)o;
    ++calls;
    if (detail != nullptr) *detail = "probe: ctp timeout";
    return hg::flow_store::OrderQueryResult::kTimeout;
  }
};
}  // namespace

static hg::order_engine::OrderInput mkInput(const char* ref, int volume) {
  hg::order_engine::OrderInput in;
  in.client_order_ref = ref;
  in.trade_plan_id = 1000;
  in.exposure_id = 5;
  in.related_plan_id = 1000;
  in.related_exposure_id = 5;
  in.sub_account_id = 1;
  in.product_id = 10;
  in.instrument_id = "cu2601";
  in.instrument_key = 100;
  in.direction = hg::risk_engine::OrderDirection::kBuy;
  in.offset = hg::risk_engine::OffsetFlag::kOpen;
  in.price_type = hg::risk_engine::PriceType::kLimit;
  in.limit_price = 1000000;  // 1.0（1e6 定点）
  in.volume = volume;
  in.operator_id = 7;
  return in;
}

int main() {
  namespace fs = hg::flow_store;
  const std::string flow_dir = "/tmp/t073-probe-flow-dir";

  // ── 阶段 1：崩溃前——受理并发 SENT 在途单（60 手冻结），不写终态 ──
  {
    hg::risk_state::RiskState st;
    hg::risk_engine::testutil::FakeClock clk{20260101, 1000};
    hg::risk_engine::RiskEngine risk(&clk);
    hg::flow_store::FlowStore flow;
    FakeGateway gw;
    hg::order_engine::OrderEngine eng(&risk, &flow, &gw);
    HG_CHECK(flow.open(hg::flow_store::flowShardPath(flow_dir, 20260101)));
    HG_CHECK(hg::risk_engine::testutil::bind(
        &st, &risk, hg::risk_engine::testutil::happyBatch(1)));
    eng.setPrevSettlement("cu2601", 1000000);

    const hg::order_engine::AcceptResult a =
        eng.accept(mkInput("T073-RECOVERED", 60), 1000);
    HG_CHECK(a.accepted);
    const hg::order_engine::ConfirmResult c =
        eng.confirm(a.order_id, 7, a.confirm_token, 2000);
    HG_CHECK(c.sent);
    // 强杀点：弃引擎（fd 随进程消失；盘上流水 PLACED+SENT 无终态 ⇒ 重放判「在途」）。
    flow.close();
  }

  // ── 阶段 2：重启——重放(kTimeout ⇒ unknown 保守持有) ──
  hg::risk_state::RiskState st2;
  hg::risk_engine::testutil::FakeClock clk2{20260101, 90000};
  hg::risk_engine::RiskEngine risk2(&clk2);
  hg::flow_store::FlowStore flow2;
  FakeGateway gw2;
  hg::order_engine::OrderEngine eng2(&risk2, &flow2, &gw2);
  HG_CHECK(flow2.open(hg::flow_store::flowShardPath(flow_dir, 20260101)));
  HG_CHECK(hg::risk_engine::testutil::bind(
      &st2, &risk2, hg::risk_engine::testutil::happyBatch(1)));
  eng2.setPrevSettlement("cu2601", 1000000);

  fs::ReplayController rc;
  fs::FlowRuntimeConfig cfg;
  cfg.flow_dir = flow_dir;
  rc.set_runtime_config(cfg);
  TimeoutQuery q;
  rc.set_order_query(&q);
  const fs::ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == fs::ReplayVerdict::kOk);
  HG_CHECK_EQ(rep.orders.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.unknown_count, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.frozen_held, static_cast<std::size_t>(1));   // 冻结保留不释放
  HG_CHECK_EQ(rep.frozen_released, static_cast<std::size_t>(0));

  hg::flow_store::RecoveryCoordinator ledger;
  hg::order_engine::RecoveryHook hook(&eng2, &ledger);
  const hg::order_engine::RecoveryExecOutcome out =
      hook.execute(rep, 1770000000000LL);
  HG_CHECK_EQ(out.unknown_held, static_cast<std::size_t>(1));
  HG_CHECK_EQ(out.released, static_cast<std::size_t>(0));
  HG_CHECK_EQ(out.adopted, static_cast<std::size_t>(0));
  HG_CHECK(ledger.has_unknown_open());  // 门禁前置：unknown 未闭环 ⇒ 0x0401（hgcp 轨覆盖）

  // 还原冻结入账（外源推定份额；壳档案键 (0,0)——要素不可还原披露面）。
  const std::uint64_t recovered_id = rep.orders[0].order_id;
  HG_CHECK(eng2.restoreFrozenHold(recovered_id, 600000, 600000, 600000));
  HG_CHECK(!eng2.restoreFrozenHold(recovered_id, 600000, 600000, 600000));  // 幂等护栏
  HG_CHECK_EQ(eng2.frozenOf(1, 0, 0), static_cast<Fixed>(600000));  // 壳键在账
  HG_CHECK_EQ(eng2.frozenOf(1, 1, 10), static_cast<Fixed>(0));      // 真实键无入账（披露）

  // 占号推进：还原单占 1 ⇒ 下一受理号 2（防重号）。
  HG_CHECK_EQ(eng2.next_order_id(), static_cast<std::uint64_t>(2));

  // ── 阶段 3：人工闭环（双人核验撤销）⇒ 60 手腾出 ⇒ 新单按正确基线受理 ──
  hg::flow_store::ManualReleaseRequest r;
  r.order_id = recovered_id;
  r.client_order_ref = "T073-RECOVERED";
  r.operator_a_id = 11;
  r.operator_b_id = 22;
  r.reason = "T073 probe: CTP timeout; operator verified cancellation at exchange";
  HG_CHECK(hook.manualRelease(r, 1770000005000LL, nullptr, nullptr));
  HG_CHECK(!ledger.has_unknown_open());                       // 门禁解除
  HG_CHECK_EQ(eng2.frozenOf(1, 0, 0), static_cast<Fixed>(0)); // 壳键份额已释放
  HG_CHECK_EQ(eng2.frozenOf(1, 1, 10), static_cast<Fixed>(0));

  // ① 恢复后新单 50 手：基线 0 ⇒ 放行，占号 2。
  const hg::order_engine::AcceptResult a1 =
      eng2.accept(mkInput("T073-NEW-1", 50), 91000);
  HG_CHECK(a1.accepted);
  HG_CHECK_EQ(a1.order_id, static_cast<std::uint64_t>(2));
  HG_CHECK_EQ(eng2.frozenOf(1, 1, 10), static_cast<Fixed>(500000));

  // ② 再一单 50 手：50+50 = 100 ≤ 100 ⇒ 不误拒（防误拒面）。
  const hg::order_engine::AcceptResult a2 =
      eng2.accept(mkInput("T073-NEW-2", 50), 92000);
  HG_CHECK(a2.accepted);
  HG_CHECK_EQ(a2.order_id, static_cast<std::uint64_t>(3));

  // ③ 第三单 50 手：150 > 100 ⇒ 账本守卫拒（kReject / kQuota），不发 CTP。
  const hg::order_engine::AcceptResult a3 =
      eng2.accept(mkInput("T073-NEW-3", 50), 93000);
  HG_CHECK(!a3.accepted);
  HG_CHECK(a3.risk.decision == RiskDecision::kReject);
  HG_CHECK(a3.risk.rule == hg::risk_engine::RuleId::kQuota);
  const std::uint32_t code = a3.risk.code;
  HG_CHECK(code == 0x1004 || code == 0x1005 || code == 0x1017);

  // ④ 被拒单不占号、无冻结副作用；全程 CTP 零提交（阶段1 的提交在崩溃前的
  //    独立引擎上，本引擎 gw2 只服务恢复后指令）。
  HG_CHECK_EQ(eng2.order_count(), static_cast<std::size_t>(3));  // 还原+新1+新2
  HG_CHECK_EQ(gw2.submit_calls, 0);
  HG_CHECK_EQ(eng2.frozenOf(1, 1, 10), static_cast<Fixed>(1000000));
  HG_CHECK_EQ(eng2.frozenOf(2, 1, 100), static_cast<Fixed>(1000000));
  HG_CHECK_EQ(eng2.frozenOf(3, 0, 10), static_cast<Fixed>(1000000));

  std::printf(
      "t073_probe: PASS  crash_with_inflight_60  replay_unknown_held=1  "
      "manual_release_freed_60  new1(order=2,50)  new2(order=3,50)  "
      "new3_rejected(code=0x%X)  order_count=3  submit_calls=0  "
      "frozen_real_keys=1000000x3\n", code);
  return 0;
}
EOF

echo "== configure =="
cmake -S "$CPP" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release > "$WORK/cmake.log" 2>&1 \
  || { tail -20 "$WORK/cmake.log"; exit 2; }
echo "== build lib targets =="
cmake --build "$BUILD" --target hg_risk_engine hg_risk_state hg_order_engine \
  hg_flow_store > "$WORK/build.log" 2>&1 \
  || { tail -30 "$WORK/build.log"; exit 2; }

echo "== link probe =="
PROBE=$WORK/t073_probe
g++ -std=c++11 -Wall -Wextra -Werror -O2 \
  -I"$CPP/src/risk_engine/include" -I"$CPP/src/risk_state/include" \
  -I"$CPP/src/flow_store/include" -I"$CPP/src/order_engine/include" \
  -I"$CPP/src/ctp_adapter/include" -I"$CPP/src/risk_engine/tests" \
  "$OUT_LOG" -o "$PROBE" \
  "$BUILD/src/order_engine/libhg_order_engine.a" \
  "$BUILD/src/risk_engine/libhg_risk_engine.a" \
  "$BUILD/src/risk_state/libhg_risk_state.a" \
  "$BUILD/src/flow_store/libhg_flow_store.a" \
  -lpthread \
  || { echo "link failed"; exit 2; }

rm -rf /tmp/t073-probe-flow-dir
mkdir -p /tmp/t073-probe-flow-dir
echo "== run =="
"$PROBE"
rc=$?
rm -rf /tmp/t073-probe-flow-dir
echo "probe rc=$rc"
exit $rc
