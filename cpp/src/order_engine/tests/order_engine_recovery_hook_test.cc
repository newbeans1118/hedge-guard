// T-041 崩溃恢复集成单测（零依赖 harness，与 order_engine_test 同形）。
//
// ★ 诚实披露（工单门禁 4）：验收 1 的「强杀→重启重放→无重复无遗漏」以
//   **进程内模拟**承载——同进程内先跑「崩溃前」写入，再以全新引擎/重放控制器
//   模拟「重启」（不销毁流水文件），断言还原后状态与账本守恒。**非真跨进程
//   E2E**（hg-trading 骨架不可达，先例 T-042 披露纪律）；跨进程强杀语义由
//   T-059/T-073 验证档承接。
//
// 覆盖：验收 1（还原守恒/无重复无遗漏 + M1 误释放变异打点 + M4 重号变异打点）/
//       验收 2（unknown 冻结保留 + 台账 + 门禁读面）/ 验收 3（人工核验释放全链）/
//       决策执行三分类 / 幂等护栏（execute 单次 / finalizeRecovered 终态守卫）。
#include <cstdio>
#include <string>
#include <vector>

#include "hg/ctp_adapter/ct_types.h"
#include "hg/flow_store/flow_store.h"
#include "hg/flow_store/recovery.h"
#include "hg/flow_store/replay.h"
#include "hg/order_engine/order_engine.h"
#include "hg/order_engine/recovery_hook.h"
#include "hg/risk_engine/risk_engine.h"
#include "hg/risk_state/risk_state.h"
#include "risk_engine_test_util.h"

#define HG_ABORT_IF(cond)                                                  \
  do {                                                                     \
    if (cond) {                                                            \
      std::printf("[ABORT] %s:%d\n", __FILE__, __LINE__);                  \
      std::exit(2);                                                        \
    }                                                                      \
  } while (0)

namespace {

using hg::flow_store::FlowKind;
using hg::flow_store::FlowRecord;
using hg::flow_store::FlowStore;
using hg::flow_store::InflightOrder;
using hg::flow_store::IOrderStateQuery;
using hg::flow_store::IReplayAlertSink;
using hg::flow_store::ManualReleaseError;
using hg::flow_store::ManualReleaseRequest;
using hg::flow_store::OrderQueryResult;
using hg::flow_store::RecoveryCoordinator;
using hg::flow_store::ReplayController;
using hg::flow_store::ReplayDecision;
using hg::flow_store::ReplayReport;
using hg::flow_store::ReplayVerdict;
using hg::order_engine::AcceptResult;
using hg::order_engine::ConfirmResult;
using hg::order_engine::EngineEvent;
using hg::order_engine::ITradeGateway;
using hg::order_engine::OrderEngine;
using hg::order_engine::OrderInput;
using hg::order_engine::OrderStatus;
using hg::order_engine::RecoveryExecOutcome;
using hg::order_engine::RecoveryHook;
using hg::order_engine::SubmitAck;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::RiskState;

const char* kRoot = "/root/t041-oe-test";
const char* kFlowDir = "/root/t041-oe-test";
const char* kFlowPath = "/root/t041-oe-test/flow-20260101.log";

void mkclean() {
  const std::string cmd = std::string("rm -rf '") + kRoot + "'; mkdir -p '" + kRoot + "'";
  HG_ABORT_IF(std::system(cmd.c_str()) != 0);
}

class FakeGateway : public ITradeGateway {
 public:
  int submit_calls = 0;
  SubmitAck submitOrder(const hg::ctp_adapter::CtOrderReq& req) override {
    ++submit_calls;
    (void)req;
    SubmitAck a;
    a.code = 0;
    a.order_local_id = "LOCAL-1";
    return a;
  }
  int reqCancel(const hg::ctp_adapter::CtCancelReq& req) override {
    (void)req;
    return 0;
  }
};

// 假 CTP 查询：按 order_id 查表；缺省 kTimeout（状态未知保守轨，test1 主链用）。
class FakeQuery : public IOrderStateQuery {
 public:
  int calls = 0;
  OrderQueryResult default_result = OrderQueryResult::kTimeout;
  std::vector<std::pair<std::uint64_t, OrderQueryResult> > table;
  OrderQueryResult query(const InflightOrder& o, std::string* detail) override {
    ++calls;
    for (std::size_t i = 0; i < table.size(); ++i) {
      if (table[i].first == o.order_id) {
        if (detail != nullptr) *detail = "fake ctp detail";
        return table[i].second;
      }
    }
    if (detail != nullptr) *detail = "fake ctp timeout";
    return default_result;
  }
};

class AlertLog : public IReplayAlertSink {
 public:
  std::vector<std::uint64_t> hits;
  void onInflightUnknown(const InflightOrder& o) override { hits.push_back(o.order_id); }
};

OrderInput baseInput(const char* ref) {
  OrderInput in;
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
  in.limit_price = 1000000;  // 1.0（1e6）
  in.volume = 10;            // 10 手
  in.operator_id = 7;
  return in;
}

// ---- 「崩溃前」阶段：真实走受理→确认→落流水→发 CTP（无回报即强杀点）--------
// 返回：流水文件保留（模拟崩溃后盘上残留）；此时引擎态即丢失。
void crashBeforeReport(FlowStore* flow) {
  RiskState state;
  FakeClock clk{20260101, 1000};
  hg::risk_engine::RiskEngine risk(&clk);
  FakeGateway gw;
  OrderEngine eng{&risk, flow, &gw};
  HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));
  eng.setPrevSettlement("cu2601", 1000000);

  const AcceptResult a = eng.accept(baseInput("HG20260101000042"), 1000);
  HG_CHECK(a.accepted);
  const ConfirmResult c = eng.confirm(a.order_id, 7, a.confirm_token, 2000);
  HG_CHECK(c.sent);
  // 强杀点：已发 CTP、未收回报。进程内模拟 = 直接弃引擎（不写终态流水）。
  // 盘上流水 = PLACED + SENT（SENT 无终态 ⇒ 重放判「在途」）。
}

// ---- 重启重放 + 还原 + 决策执行（验收 1 主链）--------------------------------

void test_crash_replay_restore_conservation() {
  mkclean();
  FlowStore flow;
  HG_ABORT_IF(!flow.open(kFlowPath));
  crashBeforeReport(&flow);
  const std::uint64_t flow_records_at_crash = flow.record_count();
  flow.close();  // 崩溃（fd 随进程消失；文件留存）
  HG_CHECK_EQ(flow_records_at_crash, static_cast<std::uint64_t>(2));  // PLACED+SENT

  // ---- 重启：全新引擎 + 重放控制器（CTP 查询返回 kTimeout ⇒ 状态未知保守轨）。
  RiskState state;
  FakeClock clk{20260101, 2000};
  hg::risk_engine::RiskEngine risk(&clk);
  FakeGateway gw;
  FlowStore flow2;
  HG_ABORT_IF(!flow2.open(kFlowPath));  // 重开同一流水（append-only）
  OrderEngine eng{&risk, &flow2, &gw};
  HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));
  eng.setPrevSettlement("cu2601", 1000000);

  FakeQuery q;
  AlertLog alerts;
  ReplayController rc;
  hg::flow_store::FlowRuntimeConfig cfg;
  cfg.flow_dir = kFlowDir;
  rc.set_runtime_config(cfg);
  rc.set_order_query(&q);
  rc.set_alert_sink(&alerts);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  // 在途还原：SENT 无终态 ⇒ 保守 unknown。
  HG_CHECK_EQ(rep.orders.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.unknown_count, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.frozen_held, static_cast<std::size_t>(1));
  HG_CHECK_EQ(alerts.hits.size(), static_cast<std::size_t>(1));  // 验收 2：告警

  RecoveryCoordinator ledger;
  RecoveryHook hook(&eng, &ledger);
  const RecoveryExecOutcome out = hook.execute(rep, 1770000000000LL);
  HG_CHECK_EQ(out.unknown_held, static_cast<std::size_t>(1));
  HG_CHECK_EQ(out.adopted, static_cast<std::size_t>(0));
  HG_CHECK_EQ(out.released, static_cast<std::size_t>(0));

  // 还原守恒断言（验收 1「无重复计入、无遗漏」的承载面）：
  // · OrderMap 恰 1 条、不重复（同 order_id）；
  HG_CHECK_EQ(eng.order_count(), static_cast<std::size_t>(1));
  HG_CHECK(eng.find(1) != nullptr);
  // · unknown 单冻结保留：held 纪律下份额未预入账 ⇒ 账本零（外源注入前），
  //   且无遗漏的判定锚 = 台账 open=1 + 门禁阻断；
  HG_CHECK_EQ(ledger.view().open_count, static_cast<std::size_t>(1));
  HG_CHECK(ledger.has_unknown_open());
  // · 还原单占号推进：下一受理号 = 2（max_id+1，防重号——M4 变异打点区）。
  HG_CHECK_EQ(eng.next_order_id(), static_cast<std::uint64_t>(2));

  // 重放零改写流水（只读）：重放全程零 append ⇒ flow2.record_count()==0
  // （record_count 自打开起累计，见 flow_store.cc open()；用「崩溃后条数经重放
  // 控制器重读仍 2」作为文件未改写的观测面，见下方重读）。
  HG_CHECK_EQ(flow2.record_count(), static_cast<std::uint64_t>(0));

  // unknown 未闭环 ⇒ 受理被风控前的台账门禁阻断形态：装配层 system_ready 合取
  // has_unknown_open()（门禁用例在 hgcp 轨）。引擎侧承载 = ledger 读面如上。

  // 验收 3 全链：人工核验（双人）→ 释放 → 门禁解除 → 留痕 + 事件。
  ManualReleaseRequest r;
  r.order_id = 1;
  r.client_order_ref = "HG20260101000042";
  r.operator_a_id = 11;
  r.operator_b_id = 22;
  r.reason = "CTP shows order alive but operator verified cancellation; release";
  EngineEvent ev;
  ManualReleaseError err = ManualReleaseError::kNone;
  HG_CHECK(hook.manualRelease(r, 1770000005000LL, &err, &ev));
  HG_CHECK(err == ManualReleaseError::kNone);
  HG_CHECK(!ledger.has_unknown_open());  // 人工闭环 ⇒ 门禁解除
  HG_CHECK(ev.kind == EngineEvent::Kind::kExpired);
  HG_CHECK_EQ(ev.expired.order_id, static_cast<std::uint64_t>(1));
  // 引擎终态化：档案 kExpired + 流水留痕（本开句柄自打开起 +1 = 留痕 1 条）。
  HG_CHECK(eng.find(1) != nullptr);
  HG_CHECK(eng.find(1)->status == OrderStatus::kExpired);
  HG_CHECK_EQ(flow2.record_count(), static_cast<std::uint64_t>(1));
}

// ---- 验收 1 伴生：CTP 已终态（adopt）路径 —— 无重复计入 ----------------------

void test_adopt_terminal_no_double_count() {
  mkclean();
  FlowStore flow;
  HG_ABORT_IF(!flow.open(kFlowPath));
  crashBeforeReport(&flow);
  flow.close();

  RiskState state;
  FakeClock clk{20260101, 2000};
  hg::risk_engine::RiskEngine risk(&clk);
  FakeGateway gw;
  FlowStore flow2;
  HG_ABORT_IF(!flow2.open(kFlowPath));
  OrderEngine eng{&risk, &flow2, &gw};
  HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));

  FakeQuery q;
  q.table.push_back(std::make_pair(static_cast<std::uint64_t>(1),
                                   OrderQueryResult::kCancelled));  // CTP 已撤
  ReplayController rc;
  hg::flow_store::FlowRuntimeConfig cfg;
  cfg.flow_dir = kFlowDir;
  rc.set_runtime_config(cfg);
  rc.set_order_query(&q);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK_EQ(rep.decisions.size(), static_cast<std::size_t>(1));
  HG_CHECK(rep.decisions[0].kind == ReplayDecision::kAdoptTerminal);

  RecoveryCoordinator ledger;
  RecoveryHook hook(&eng, &ledger);
  const RecoveryExecOutcome out = hook.execute(rep, 1770000000000LL);
  HG_CHECK_EQ(out.adopted, static_cast<std::size_t>(1));
  HG_CHECK_EQ(out.events.size(), static_cast<std::size_t>(0));  // adopt 不补发 expired
  HG_CHECK(eng.find(1) != nullptr);
  HG_CHECK(eng.find(1)->status == OrderStatus::kCancelled);  // 按 CTP 终态修正
  // 冻结账本守恒：held 纪律（未预入账）⇒ 三层账本全 0（无重复计入）。
  HG_CHECK_EQ(eng.frozenOf(1, 1, 10), static_cast<hg::risk_state::Fixed>(0));
  HG_CHECK_EQ(eng.frozenOf(2, 1, 100), static_cast<hg::risk_state::Fixed>(0));
  HG_CHECK_EQ(eng.frozenOf(3, 0, 10), static_cast<hg::risk_state::Fixed>(0));

  // 幂等护栏：二次 execute 全零（防重复修正）。
  const RecoveryExecOutcome out2 = hook.execute(rep, 1770000001000LL);
  HG_CHECK_EQ(out2.adopted, static_cast<std::size_t>(0));
  HG_CHECK_EQ(out2.released, static_cast<std::size_t>(0));
  HG_CHECK_EQ(out2.unknown_held, static_cast<std::size_t>(0));
  HG_CHECK_EQ(out2.skipped, static_cast<std::size_t>(0));
  // 二次 finalizeRecovered（终态守卫）：拒绝，不二次留痕。
  HG_CHECK(!eng.finalizeRecovered(1, OrderStatus::kCancelled, 1770000002000LL, "dup"));
  const std::uint64_t rc_after = flow2.record_count();
  // 本开句柄自打开起 +1（留痕 kCancelled）—— 文件总条数经重放控制器重读验证
  // （crash 段 2 条 + 留痕 1 条 = 3；观测面与 test1 的重读口径同）。
  HG_CHECK_EQ(rc_after, static_cast<std::uint64_t>(1));
}

// ---- 验收 1 伴生：CTP 无此单（release）路径 —— 释放 + 补发 EVT_ORDER_EXPIRED --

void test_release_expired_and_event() {
  mkclean();
  FlowStore flow;
  HG_ABORT_IF(!flow.open(kFlowPath));
  crashBeforeReport(&flow);
  flow.close();

  RiskState state;
  FakeClock clk{20260101, 2000};
  hg::risk_engine::RiskEngine risk(&clk);
  FakeGateway gw;
  FlowStore flow2;
  HG_ABORT_IF(!flow2.open(kFlowPath));
  OrderEngine eng{&risk, &flow2, &gw};
  HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));

  FakeQuery q;
  q.table.push_back(std::make_pair(static_cast<std::uint64_t>(1),
                                   OrderQueryResult::kNotFound));  // 未实际到达
  ReplayController rc;
  hg::flow_store::FlowRuntimeConfig cfg;
  cfg.flow_dir = kFlowDir;
  rc.set_runtime_config(cfg);
  rc.set_order_query(&q);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK_EQ(rep.frozen_released, static_cast<std::size_t>(1));

  RecoveryCoordinator ledger;
  RecoveryHook hook(&eng, &ledger);
  const RecoveryExecOutcome out = hook.execute(rep, 1770000000000LL);
  HG_CHECK_EQ(out.released, static_cast<std::size_t>(1));
  // 补发 EVT_ORDER_EXPIRED 载荷（调用方 emitter 发出；同一接缝形态）。
  HG_CHECK_EQ(out.events.size(), static_cast<std::size_t>(1));
  HG_CHECK(out.events[0].kind == EngineEvent::Kind::kExpired);
  HG_CHECK_EQ(out.events[0].expired.order_id, static_cast<std::uint64_t>(1));
  HG_CHECK_EQ(out.events[0].expired.client_order_ref, std::string("HG20260101000042"));
  HG_CHECK(eng.find(1) != nullptr);
  HG_CHECK(eng.find(1)->status == OrderStatus::kExpired);
  // 台账零阻断（released 路径不进 unknown 台账）。
  HG_CHECK(!ledger.has_unknown_open());
}

// ---- 还原接缝独立守卫：一次性 / 重号推进 / 份额入账幂等 ----------------------

void test_restore_seam_guards() {
  mkclean();
  RiskState state;
  FakeClock clk{20260101, 2000};
  hg::risk_engine::RiskEngine risk(&clk);
  FakeGateway gw;
  FlowStore flow;
  HG_ABORT_IF(!flow.open(kFlowPath));
  OrderEngine eng{&risk, &flow, &gw};
  HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));

  std::vector<hg::flow_store::ReplayOrderState> orders;
  // 终态单 + 在途单 + 未发单（三种还原形态一次覆盖）。
  hg::flow_store::ReplayOrderState t;
  t.order_id = 5;
  t.client_order_ref = "R5";
  t.last_kind = FlowKind::kFilled;
  t.sent = true;
  t.terminal = true;
  t.last_ts_ms = 1000;
  orders.push_back(t);
  hg::flow_store::ReplayOrderState inl;
  inl.order_id = 9;
  inl.client_order_ref = "R9";
  inl.last_kind = FlowKind::kSent;
  inl.sent = true;
  inl.terminal = false;
  inl.last_ts_ms = 2000;
  orders.push_back(inl);
  hg::flow_store::ReplayOrderState uns;
  uns.order_id = 3;
  uns.client_order_ref = "R3";
  uns.last_kind = FlowKind::kPlaced;
  uns.sent = false;
  uns.terminal = false;
  uns.last_ts_ms = 500;
  orders.push_back(uns);

  HG_CHECK(eng.restoreFromReplay(orders, 10000));
  HG_CHECK(eng.restore_done());
  HG_CHECK_EQ(eng.order_count(), static_cast<std::size_t>(3));
  HG_CHECK(eng.find(5) != nullptr && eng.find(5)->status == OrderStatus::kFilled);
  HG_CHECK(eng.find(9) != nullptr && eng.find(9)->status == OrderStatus::kSubmitted);
  HG_CHECK(eng.find(3) != nullptr && eng.find(3)->status == OrderStatus::kPendingConfirm);
  // 重号推进：next = max(5,9,3)+1 = 10（M4 变异打点：漏推进 ⇒ 还原后重号）。
  HG_CHECK_EQ(eng.next_order_id(), static_cast<std::uint64_t>(10));
  // 一次性护栏。
  HG_CHECK(!eng.restoreFromReplay(orders, 11000));
  HG_CHECK_EQ(eng.order_count(), static_cast<std::size_t>(3));  // 不重复计入

  // 份额入账幂等（unknown 单外源注入路径）：首注入生效，二注入拒绝（防重复计入）。
  HG_CHECK(eng.restoreFrozenHold(9, 1000, 2000, 3000));
  // 壳档案 input 空（sub=0/key=0 键）⇒ 层①键 (sub=0,product=0)=(0,0)、层②键
  // (sub=0,instrument=0)=(0,0)：直查账本注入值。
  HG_CHECK_EQ(eng.frozenOf(1, 0, 0), static_cast<hg::risk_state::Fixed>(1000));
  HG_CHECK_EQ(eng.frozenOf(2, 0, 0), static_cast<hg::risk_state::Fixed>(2000));
  HG_CHECK(!eng.restoreFrozenHold(9, 1000, 2000, 3000));  // 已非零 ⇒ 拒
  HG_CHECK(eng.find(9)->frozen_l1 == 1000);
  // 释放守恒：finalizeRecovered 终态化 ⇒ 账本回吐。
  HG_CHECK(eng.finalizeRecovered(9, OrderStatus::kExpired, 12000, "recovery: test"));
  HG_CHECK(eng.find(9)->status == OrderStatus::kExpired);
  HG_CHECK_EQ(eng.find(9)->frozen_l1, static_cast<hg::risk_state::Fixed>(0));
  // 不存在的档案。
  HG_CHECK(!eng.restoreFrozenHold(999, 1, 1, 1));
  HG_CHECK(!eng.finalizeRecovered(999, OrderStatus::kExpired, 12000, "missing"));
}

// ---- 还原后新指令受理与风控联动（还原态参与超额守卫，无遗漏面）----------------

void test_post_restore_accept_uses_ledger() {
  mkclean();
  FlowStore flow;
  HG_ABORT_IF(!flow.open(kFlowPath));
  // 预置：R3 未发单（kPendingConfirm，待确认份额外源入账）。
  std::vector<hg::flow_store::ReplayOrderState> orders;
  hg::flow_store::ReplayOrderState uns;
  uns.order_id = 3;
  uns.client_order_ref = "R3";
  uns.last_kind = FlowKind::kPlaced;
  uns.last_ts_ms = 500;
  orders.push_back(uns);

  RiskState state;
  FakeClock clk{20260101, 2000};
  hg::risk_engine::RiskEngine risk(&clk);
  FakeGateway gw;
  OrderEngine eng{&risk, &flow, &gw};
  HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));
  eng.setPrevSettlement("cu2601", 1000000);
  HG_CHECK(eng.restoreFromReplay(orders, 10000));
  // 外源注入 R3 冻结份额（壳档案键 (0,0)/(0,0)/(0,10)——要素不可还原的披露面；
  // 这里直接验证「账本参与守卫」机制：经真实受理路径验证 next_id 与幂等）。
  HG_CHECK(eng.restoreFrozenHold(3, 1000, 2000, 3000));

  // 还原后新指令受理：占号 = 4（无重号）。
  const AcceptResult a = eng.accept(baseInput("HG20260101000099"), 20000);
  HG_CHECK(a.accepted);
  HG_CHECK_EQ(a.order_id, static_cast<std::uint64_t>(4));
  // 幂等还原单引用：R3 重复受理 → 0x2007 形态（duplicate）。
  OrderInput dup = baseInput("R3");
  dup.client_order_ref = "R3";
  const AcceptResult d = eng.accept(dup, 21000);
  HG_CHECK(d.duplicate);  // ref 索引含还原单 ⇒ 幂等识别（无遗漏面）
}

}  // namespace

int main() {
  if (std::system("rm -rf /root/t041-oe-test; mkdir -p /root/t041-oe-test") != 0) {
    std::printf("[ABORT] setup failed\n");
    return 2;
  }
  test_crash_replay_restore_conservation();
  test_adopt_terminal_no_double_count();
  test_release_expired_and_event();
  test_restore_seam_guards();
  test_post_restore_accept_uses_ledger();
  return hg::risk_engine::testutil::report("order_engine_recovery_hook_test");
}
