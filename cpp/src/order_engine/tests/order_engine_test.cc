// T-036 order_engine 状态机单测（零依赖 harness，与 risk_engine 测试同形）。
// 覆盖：受理主链 / R-TRADE-1 前置 / 0x2007 幂等 / 风控拒绝(0x1015, 0x1004) /
//       冻结账本超额守卫 / 确认门卫(0x0305/0x0307/0x2001/0x2002/0x2003) /
//       落流水先于发单（§4.2.6(4) ⑨→⑩）/ 0x2004 退回保留可重试 / 回报与部分成交 /
//       回报幂等 / 撤单 / 超时失效（CFG-28 60s）。
#include <cstdio>
#include <string>

#include "hg/ctp_adapter/ct_types.h"
#include "hg/flow_store/flow_store.h"
#include "hg/order_engine/order_engine.h"
#include "hg/risk_engine/risk_engine.h"
#include "hg/risk_state/risk_state.h"
#include "risk_engine_test_util.h"

// 断言：复用 risk_engine testutil 的 HG_CHECK / HG_CHECK_EQ（字符串/枚举可比、
// 表达式单次求值）；本文件仅补 HG_ABORT_IF（夹具构造失败即中止）。
#define HG_ABORT_IF(cond)                                                  \
  do {                                                                     \
    if (cond) {                                                            \
      std::printf("[ABORT] %s:%d\n", __FILE__, __LINE__);                  \
      std::exit(2);                                                        \
    }                                                                      \
  } while (0)

namespace {

using hg::flow_store::FlowRecord;
using hg::flow_store::FlowState;
using hg::flow_store::FlowStore;
using hg::order_engine::AcceptResult;
using hg::order_engine::CancelResult;
using hg::order_engine::ConfirmResult;
using hg::order_engine::EngineEvent;
using hg::order_engine::ITradeGateway;
using hg::order_engine::OrderEngine;
using hg::order_engine::OrderInput;
using hg::order_engine::OrderStatus;
using hg::order_engine::SubmitAck;
using hg::risk_engine::OrderDirection;
using hg::risk_engine::OffsetFlag;
using hg::risk_engine::PriceType;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::RiskState;

// 假发送面：记录调用与「发单时刻流水条数」（⑨→⑩顺序不变量的观测面）。
class FakeGateway : public ITradeGateway {
 public:
  bool connected = true;
  int submit_calls = 0;
  int cancel_calls = 0;
  std::size_t flow_count_at_submit = 0;
  std::string last_submitted_ref;
  FlowStore* flow = nullptr;  // 观测面：发单时刻的流水条数（⑨→⑩顺序）

  SubmitAck submitOrder(const hg::ctp_adapter::CtOrderReq& req) override {
    ++submit_calls;
    last_submitted_ref = req.client_order_ref;
    if (flow != nullptr) flow_count_at_submit = flow->record_count();
    SubmitAck a;
    if (!connected) {
      a.code = 1;
      a.retained = true;  // 0x2004 语义：退回保留
      return a;
    }
    a.code = 0;
    a.order_local_id = "LOCAL-1";
    return a;
  }
  int reqCancel(const hg::ctp_adapter::CtCancelReq& req) override {
    ++cancel_calls;
    (void)req;
    return 0;
  }
};

OrderInput baseInput(const char* ref) {
  OrderInput in;
  in.client_order_ref = ref;
  in.trade_plan_id = 1000;
  in.exposure_id = 5;
  in.sub_account_id = 1;
  in.product_id = 10;
  in.instrument_id = "cu2601";
  in.instrument_key = 100;
  in.direction = OrderDirection::kBuy;
  in.offset = OffsetFlag::kOpen;
  in.price_type = PriceType::kLimit;
  in.limit_price = 1000000;  // 1.0（1e6）
  in.volume = 10;            // 10 手
  in.operator_id = 7;
  return in;
}

struct Rig {
  RiskState state;
  FakeClock clk{20260101, 1000};
  hg::risk_engine::RiskEngine risk{&clk};
  FlowStore flow;
  FakeGateway gw;
  OrderEngine eng{&risk, &flow, &gw};

  Rig() {
    HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));
    HG_ABORT_IF(!flow.open("/root/t036-flow.log"));
    gw.flow = &flow;
    eng.setPrevSettlement("cu2601", 1000000);  // 与限价一致 ⇒ 偏离 0
  }
};

// ---- 用例 -------------------------------------------------------------------

void test_accept_happy_path() {
  Rig r;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(a.accepted);
  HG_CHECK(!a.duplicate);
  HG_CHECK(!a.warn);
  HG_CHECK_EQ(a.order_id, 1);
  HG_CHECK_EQ(a.confirm_token.size(), 16);
  HG_CHECK_EQ(a.effective_timeout_ms, 60000);  // CFG-28 缺省
  HG_CHECK_EQ(r.eng.pending_count(), 1);
  const hg::order_engine::OrderRecord* rec = r.eng.find(1);
  HG_CHECK(rec != nullptr);
  HG_CHECK_EQ(rec->status, OrderStatus::kPendingConfirm);
  HG_CHECK_EQ(r.flow.record_count(), 1);  // PLACED 落流水
  // 冻结账本：三层各挂 10 手（LOT，1e4 定点）。
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 10 * 10000);
  HG_CHECK_EQ(r.eng.frozenOf(2, 1, 100), 10 * 10000);
  HG_CHECK_EQ(r.eng.frozenOf(3, 0, 10), 10 * 10000);
}

void test_accept_rtrade1_missing_fields() {
  Rig r;
  OrderInput in = baseInput("HG1");
  in.trade_plan_id = 0;
  const AcceptResult a = r.eng.accept(in, 1000);
  HG_CHECK(!a.accepted);
  HG_CHECK_EQ(a.risk.rule, RuleId::kInput);
  HG_CHECK_EQ(a.risk.code, 0x3003);
  HG_CHECK_EQ(r.eng.order_count(), 0);

  OrderInput in2 = baseInput("HG2");
  in2.sub_account_id = 0;
  HG_CHECK(!r.eng.accept(in2, 1000).accepted);

  OrderInput in3 = baseInput("HG3");
  in3.exposure_id = 0;
  HG_CHECK(!r.eng.accept(in3, 1000).accepted);
  HG_CHECK_EQ(r.eng.order_count(), 0);
}

void test_accept_duplicate_ref_idempotent() {
  Rig r;
  const AcceptResult a1 = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(a1.accepted);
  const AcceptResult a2 = r.eng.accept(baseInput("HG1"), 2000);  // 同 ref 重放
  HG_CHECK(a2.duplicate);
  HG_CHECK_EQ(a2.order_id, a1.order_id);
  HG_CHECK_EQ(a2.confirm_token, a1.confirm_token);  // 幂等：返回既有受理
  HG_CHECK_EQ(r.eng.order_count(), 1);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 10 * 10000);  // 不重复冻结
  HG_CHECK_EQ(r.flow.record_count(), 1);
}

void test_accept_reject_no_prev_settlement() {
  Rig r;
  OrderInput in = baseInput("HG1");
  // 不注入昨结算价 ⇒ 0x1015（无可用基准价，限价单阻断）。
  in.instrument_id = "rb2605";
  in.instrument_key = 100;  // 仍用已建档合约，只缺基准价
  const AcceptResult a = r.eng.accept(in, 1000);
  HG_CHECK(!a.accepted);
  HG_CHECK_EQ(a.risk.rule, RuleId::kPriceDeviation);
  HG_CHECK_EQ(a.risk.code, 0x1015);
  HG_CHECK_EQ(r.eng.order_count(), 0);
}

void test_accept_reject_quota_with_ledger() {
  Rig r;  // 层①限 100 手（1000000 / 1e4）。
  OrderInput big = baseInput("HG1");
  big.volume = 60;
  HG_CHECK(r.eng.accept(big, 1000).accepted);
  OrderInput big2 = baseInput("HG2");
  big2.volume = 60;  // 60 + 账本 60 > 100 ⇒ 守卫拒（快照 used+frozen 仍为 0）
  const AcceptResult a = r.eng.accept(big2, 1001);
  HG_CHECK(!a.accepted);
  // 层②（子账户+合约）在全序中最细 ⇒ 首中上报 0x1017（§4.4.1 层② > 层① > 层③）。
  HG_CHECK_EQ(a.risk.rule, RuleId::kQuota);
  HG_CHECK_EQ(a.risk.code, 0x1017);
  HG_CHECK(a.risk.limit_layer == hg::risk_engine::LimitLayer::kSubInstrument);
  HG_CHECK_EQ(r.eng.order_count(), 1);  // 拒单不占号
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 60 * 10000);
  HG_CHECK_EQ(r.eng.frozenOf(2, 1, 100), 60 * 10000);
}

void test_confirm_guards() {
  Rig r;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(a.accepted);

  HG_CHECK_EQ(r.eng.confirm(999, 7, a.confirm_token, 1001).reason_code, 0x2001);  // 不存在
  HG_CHECK_EQ(r.eng.confirm(1, 8, a.confirm_token, 1001).reason_code, 0x0305);   // 代确认
  HG_CHECK_EQ(r.eng.confirm(1, 7, "bad token", 1001).reason_code, 0x0307);       // token 错
  HG_CHECK_EQ(r.gw.submit_calls, 0);  // 全部被门卫拦下，未触 CTP

  // 超时确认：过 60s（CFG-28）⇒ 0x2003 + 失效 + 释放 + 不发 CTP。
  const ConfirmResult t = r.eng.confirm(1, 7, a.confirm_token, 1000 + 60000 + 1);
  HG_CHECK_EQ(t.reason_code, 0x2003);
  HG_CHECK(!t.sent);
  HG_CHECK_EQ(r.gw.submit_calls, 0);
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kExpired);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);  // 冻结已释放
  HG_CHECK_EQ(r.eng.confirm(1, 7, a.confirm_token, 1000 + 60000 + 2).reason_code,
              0x2002);  // 终态再确认
}

void test_confirm_happy_flow_before_submit() {
  Rig r;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(a.accepted);
  const ConfirmResult c = r.eng.confirm(1, 7, a.confirm_token, 1001);
  HG_CHECK(c.sent);
  HG_CHECK_EQ(c.reason_code, 0);
  HG_CHECK_EQ(r.gw.submit_calls, 1);
  HG_CHECK_EQ(r.gw.last_submitted_ref, "HG1");
  // ⑨→⑩ 顺序不变量：发单调用时刻，SENT 流水已落盘（PLACED + SENT = 2 条）。
  HG_CHECK_EQ(r.gw.flow_count_at_submit, 2);
  // token 单次消费：同 token 再确认 ⇒ 状态不允许（已提交）。
  HG_CHECK_EQ(r.eng.confirm(1, 7, a.confirm_token, 1002).reason_code, 0x2002);
}

void test_confirm_ctp_not_connected_retry() {
  Rig r;
  r.gw.connected = false;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(a.accepted);
  const ConfirmResult c = r.eng.confirm(1, 7, a.confirm_token, 1001);
  HG_CHECK(!c.sent);
  HG_CHECK(c.retained);
  HG_CHECK_EQ(c.reason_code, 0x2004);
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kPendingConfirm);  // 退回保留
  // token 未消费 ⇒ CTP 恢复后同 token 重试成功（FR-TRADE-2 验收 4）。
  r.gw.connected = true;
  const ConfirmResult c2 = r.eng.confirm(1, 7, a.confirm_token, 1002);
  HG_CHECK(c2.sent);
  HG_CHECK_EQ(r.gw.submit_calls, 2);
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kSubmitted);
}

void test_report_and_partial_fill() {
  Rig r;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(r.eng.confirm(1, 7, a.confirm_token, 1001).sent);

  // 委托回报（已受理）。
  hg::ctp_adapter::CtOrderEvent ev;
  ev.kind = hg::ctp_adapter::CtOrderEventKind::kOrderReturn;
  ev.client_order_ref = "HG1";
  ev.order_sys_id = "SYS-9";
  ev.order_local_id = "LOCAL-1";
  ev.status = '3';  // NoTradeQueueing
  ev.volume_total = 10;
  ev.volume_traded = 0;
  ev.volume_left = 10;
  ev.report_ts_ms = 1100;
  EngineEvent out;
  HG_CHECK(r.eng.onOrderEvent(ev, &out));
  HG_CHECK(out.kind == EngineEvent::Kind::kReport);
  HG_CHECK_EQ(out.report.order_id, 1);
  HG_CHECK(out.report.order_sys_id == "SYS-9");
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kSubmitted);

  // 同形回报重复推送 ⇒ 幂等跳过（不重复变更/不产事件）。
  EngineEvent dup;
  HG_CHECK(!r.eng.onOrderEvent(ev, &dup));
  HG_CHECK(dup.kind == EngineEvent::Kind::kNone);

  // 部分成交（4/10）。
  hg::ctp_adapter::CtOrderEvent fill = ev;
  fill.kind = hg::ctp_adapter::CtOrderEventKind::kTradeReturn;
  fill.trade_id = "TR-1";
  fill.price = 1.0;  // double 语义（CtOrderEvent 原样承载 CTP 价）
  fill.trade_volume = 4;
  fill.report_ts_ms = 1200;
  EngineEvent fo;
  HG_CHECK(r.eng.onOrderEvent(fill, &fo));
  HG_CHECK(fo.kind == EngineEvent::Kind::kFill);
  HG_CHECK_EQ(fo.fill.volume, 4);
  HG_CHECK_EQ(fo.fill.price, 1000000);  // double → 1e6 定点
  HG_CHECK_EQ(fo.fill.trade_plan_id, 1000);
  HG_CHECK_EQ(fo.fill.sub_account_id, 1);
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kPartiallyFilled);

  // 同 trade_id 重复 ⇒ 幂等跳过。
  EngineEvent fdup;
  HG_CHECK(!r.eng.onOrderEvent(fill, &fdup));

  // 剩余全成（6/10）⇒ FILLED 终态 + 冻结全释放。
  hg::ctp_adapter::CtOrderEvent fill2 = fill;
  fill2.trade_id = "TR-2";
  fill2.trade_volume = 6;
  EngineEvent fo2;
  HG_CHECK(r.eng.onOrderEvent(fill2, &fo2));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kFilled);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);
}

void test_ctp_reject_releases_freeze() {
  Rig r;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(r.eng.confirm(1, 7, a.confirm_token, 1001).sent);
  hg::ctp_adapter::CtOrderEvent ev;
  ev.kind = hg::ctp_adapter::CtOrderEventKind::kOrderReturn;
  ev.client_order_ref = "HG1";
  ev.order_sys_id = "SYS-9";
  ev.status = '5';  // CTP REJECTED
  ev.ctp_error_id = 36;
  ev.ctp_error_msg = "insufficient margin";
  ev.volume_total = 10;
  ev.volume_traded = 0;
  ev.volume_left = 10;
  ev.report_ts_ms = 1100;
  EngineEvent out;
  HG_CHECK(r.eng.onOrderEvent(ev, &out));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kRejected);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);  // 拒单释放冻结
}

// TE-T036-002 守卫：撤单完成（'5' = THOST_FTDC_OST_Canceled，无 ErrorID）⇒
// kCancelled + EVT_ORDER_CANCEL_RESULT，且冻结释放保留（复核 V-3）。
void test_ctp_cancel_completed_classified_cancelled() {
  Rig r;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(r.eng.confirm(1, 7, a.confirm_token, 1001).sent);
  hg::ctp_adapter::CtOrderEvent ev;
  ev.kind = hg::ctp_adapter::CtOrderEventKind::kOrderReturn;
  ev.client_order_ref = "HG1";
  ev.order_sys_id = "SYS-7";
  ev.status = '5';  // THOST_FTDC_OST_Canceled（撤单完成，无错误）
  ev.ctp_error_id = 0;
  ev.volume_total = 10;
  ev.volume_traded = 0;
  ev.volume_left = 10;
  ev.report_ts_ms = 1100;
  EngineEvent out;
  HG_CHECK(r.eng.onOrderEvent(ev, &out));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kCancelled);
  HG_CHECK(out.kind == EngineEvent::Kind::kCancelResult);  // EVT_ORDER_CANCEL_RESULT
  HG_CHECK_EQ(out.cancel_out.order_id, 1);
  HG_CHECK_EQ(out.cancel_out.cancelled_volume, 10);
  HG_CHECK_EQ(out.cancel_out.result, 0);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);   // 释放不丢（V-3）
  HG_CHECK_EQ(r.eng.frozenOf(2, 1, 100), 0);
  HG_CHECK_EQ(r.eng.frozenOf(3, 0, 10), 0);
  // 终态守卫联动：同形回报重复推送 ⇒ 幂等跳过，状态不再变更。
  EngineEvent dup;
  HG_CHECK(!r.eng.onOrderEvent(ev, &dup));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kCancelled);
}

// TE-T036-001 守卫：累计成交单调 + 终态后委托回报只记流水（不迁移状态、不释放、
// 不回退快照）。覆盖缺陷链：成交回报计满 ⇒ 随附最终委托回报（AllTraded '0'）。
void test_report_monotonic_and_post_terminal_guard() {
  Rig r;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(r.eng.confirm(1, 7, a.confirm_token, 1001).sent);

  hg::ctp_adapter::CtOrderEvent fill;
  fill.kind = hg::ctp_adapter::CtOrderEventKind::kTradeReturn;
  fill.client_order_ref = "HG1";
  fill.trade_id = "TR-1";
  fill.price = 1.0;
  fill.trade_volume = 4;
  fill.report_ts_ms = 1100;
  EngineEvent fo;
  HG_CHECK(r.eng.onOrderEvent(fill, &fo));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kPartiallyFilled);

  // 迟到/乱序委托回报（非终态）：traded=0 的早期形态 ⇒ 不得回退累计成交（V-1）。
  hg::ctp_adapter::CtOrderEvent stale;
  stale.kind = hg::ctp_adapter::CtOrderEventKind::kOrderReturn;
  stale.client_order_ref = "HG1";
  stale.order_sys_id = "SYS-9";
  stale.status = '3';
  stale.volume_total = 10;
  stale.volume_traded = 0;
  stale.volume_left = 10;
  stale.report_ts_ms = 1150;
  EngineEvent so;
  HG_CHECK(r.eng.onOrderEvent(stale, &so));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kPartiallyFilled);  // 不回退 kSubmitted
  HG_CHECK_EQ(r.eng.find(1)->volume_traded, 4);
  HG_CHECK_EQ(r.eng.find(1)->volume_left, 6);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 10 * 10000);  // 仍冻结

  // 全成 ⇒ kFilled + 冻结释放。
  hg::ctp_adapter::CtOrderEvent fill2 = fill;
  fill2.trade_id = "TR-2";
  fill2.trade_volume = 6;
  fill2.report_ts_ms = 1200;
  EngineEvent fo2;
  HG_CHECK(r.eng.onOrderEvent(fill2, &fo2));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kFilled);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);

  // 终态后回报①：随附的最终委托回报（AllTraded '0'，缺陷链触发形态）⇒ 只记流水，
  // 不产事件、不二次释放（TE-T036-001）。
  hg::ctp_adapter::CtOrderEvent fin;
  fin.kind = hg::ctp_adapter::CtOrderEventKind::kOrderReturn;
  fin.client_order_ref = "HG1";
  fin.order_sys_id = "SYS-9";
  fin.status = '0';  // THOST_FTDC_OST_AllTraded
  fin.volume_total = 10;
  fin.volume_traded = 10;
  fin.volume_left = 0;
  fin.report_ts_ms = 1300;
  EngineEvent out1;
  HG_CHECK(!r.eng.onOrderEvent(fin, &out1));
  HG_CHECK(out1.kind == EngineEvent::Kind::kNone);
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kFilled);
  HG_CHECK_EQ(r.eng.find(1)->volume_traded, 10);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);   // 三层账本均不被二次释放
  HG_CHECK_EQ(r.eng.frozenOf(2, 1, 100), 0);
  HG_CHECK_EQ(r.eng.frozenOf(3, 0, 10), 0);

  // 终态后回报②：迟到早期形态（traded=0）⇒ 不得回退累计成交/状态。
  hg::ctp_adapter::CtOrderEvent late = fin;
  late.status = '3';
  late.volume_traded = 0;
  late.volume_left = 10;
  late.report_ts_ms = 1400;
  EngineEvent out2;
  HG_CHECK(!r.eng.onOrderEvent(late, &out2));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kFilled);
  HG_CHECK_EQ(r.eng.find(1)->volume_traded, 10);
  HG_CHECK_EQ(r.eng.find(1)->volume_left, 0);

  // 终态后回报③：撤单完成 '5' 迟到 ⇒ 不得改判 kCancelled（状态不迁移）。
  hg::ctp_adapter::CtOrderEvent lateCancel = fin;
  lateCancel.status = '5';
  lateCancel.ctp_error_id = 0;
  lateCancel.report_ts_ms = 1500;
  EngineEvent out3;
  HG_CHECK(!r.eng.onOrderEvent(lateCancel, &out3));
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kFilled);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);
}

// MUT-A 守卫（复核：confirm 跳过「再跑完整风控」原存活）：确认期状态变化
// （昨结算价上移 10% > 5% block 阈值）⇒ 确认时再校验必须拒绝且不发 CTP。
void test_confirm_reruns_full_risk() {
  Rig r;
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(a.accepted);
  r.eng.setPrevSettlement("cu2601", 1100000);  // 确认期：偏离 10% > block 5%
  const ConfirmResult c = r.eng.confirm(1, 7, a.confirm_token, 1001);
  HG_CHECK(!c.sent);
  HG_CHECK_EQ(c.reason_code, 0x1010);
  HG_CHECK(c.risk.rule == RuleId::kPriceDeviation);
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kRejected);  // 再校验拒绝 ⇒ 终态
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);                    // 冻结释放
  HG_CHECK_EQ(r.gw.submit_calls, 0);                           // 不发 CTP
}

// MUT-B 守卫（复核：confirm 跳过账本复核扣自身原存活）：账本已含本单冻结时，
// confirm 复核须扣除自身，否则 60+60 > 100 误拒。
void test_confirm_ledger_excludes_self_freeze() {
  Rig r;  // 三层限额各 100 手
  OrderInput in = baseInput("HG1");
  in.volume = 60;
  const AcceptResult a = r.eng.accept(in, 1000);
  HG_CHECK(a.accepted);
  const ConfirmResult c = r.eng.confirm(1, 7, a.confirm_token, 1001);
  HG_CHECK(c.sent);  // 自扣后 0 + 0(快照) + 60 ≤ 100 ⇒ 不得误拒 0x1017
  HG_CHECK_EQ(c.reason_code, 0);
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kSubmitted);
  HG_CHECK_EQ(r.gw.submit_calls, 1);
}

void test_cancel_paths() {
  Rig r;
  HG_CHECK_EQ(r.eng.cancel(42, 7, "", 1000, nullptr).reason_code, 0x2001);  // 不存在

  // 待确认撤单：无 CTP 轨，直接撤销 + 释放 + 事件。
  const AcceptResult a = r.eng.accept(baseInput("HG1"), 1000);
  HG_CHECK(a.accepted);
  EngineEvent ce;
  const CancelResult c = r.eng.cancel(1, 7, "manual", 1005, &ce);
  HG_CHECK(c.cancelled);
  HG_CHECK(!c.requested);
  HG_CHECK_EQ(r.gw.cancel_calls, 0);
  HG_CHECK_EQ(ce.kind, EngineEvent::Kind::kCancelResult);
  HG_CHECK_EQ(ce.cancel_out.cancelled_volume, 10);
  HG_CHECK_EQ(r.eng.find(1)->status, OrderStatus::kCancelled);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);

  // 已发 CTP 撤单：走网关。
  const AcceptResult b = r.eng.accept(baseInput("HG2"), 1010);
  HG_CHECK(r.eng.confirm(2, 7, b.confirm_token, 1011).sent);
  const CancelResult c2 = r.eng.cancel(2, 7, "", 1012, nullptr);
  HG_CHECK(c2.requested);
  HG_CHECK_EQ(r.gw.cancel_calls, 1);

  // 终态再撤 ⇒ 0x2002。
  HG_CHECK_EQ(r.eng.cancel(1, 7, "", 1013, nullptr).reason_code, 0x2002);
}

void test_expire_pending_sweep() {
  Rig r;
  HG_CHECK(r.eng.accept(baseInput("HG1"), 1000).accepted);
  HG_CHECK(r.eng.accept(baseInput("HG2"), 1000).accepted);
  HG_CHECK_EQ(r.eng.pending_count(), 2);
  EngineEvent evs[4];
  const std::size_t n = r.eng.expirePending(1000 + 60000 + 1, evs, 4);
  HG_CHECK_EQ(n, 2);
  HG_CHECK_EQ(evs[0].kind, EngineEvent::Kind::kExpired);
  HG_CHECK_EQ(evs[0].expired.timeout_ms, 60000);
  HG_CHECK_EQ(r.eng.pending_count(), 0);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);       // 全部释放
  HG_CHECK_EQ(r.eng.frozenOf(3, 0, 10), 0);
  HG_CHECK_EQ(r.gw.submit_calls, 0);               // 不发 CTP（验收 3）
  // 边界内不误杀：deadline == now 不失效。
  HG_CHECK(r.eng.accept(baseInput("HG3"), 50000).accepted);
  HG_CHECK_EQ(r.eng.expirePending(50000 + 60000, evs, 4), 0);
  HG_CHECK_EQ(r.eng.expirePending(50000 + 60000 + 1, evs, 4), 1);
}

void test_flow_store_primitive() {
  FlowStore fs;
  HG_CHECK(!fs.is_open());
  HG_CHECK_EQ(fs.state(), FlowState::kNotReady);
  FlowRecord fr;
  HG_CHECK(!fs.append(fr));  // 未打开 ⇒ false
  HG_CHECK(fs.open("/root/t036-flow-prim.log"));
  HG_CHECK_EQ(fs.state(), FlowState::kReady);
  fr.kind = hg::flow_store::FlowKind::kSent;
  fr.order_id = 7;
  fr.client_order_ref = "HG|X";  // 消毒：| → /
  fr.ts_ms = 1234;
  fr.detail = "a\nb";             // 消毒：\n → 空格
  HG_CHECK(fs.append(fr));
  HG_CHECK_EQ(fs.record_count(), 1);
  fs.close();
  HG_CHECK_EQ(fs.state(), FlowState::kNotReady);
}

}  // namespace

int main() {
  using hg::risk_engine::testutil::report;
  test_accept_happy_path();
  test_accept_rtrade1_missing_fields();
  test_accept_duplicate_ref_idempotent();
  test_accept_reject_no_prev_settlement();
  test_accept_reject_quota_with_ledger();
  test_confirm_guards();
  test_confirm_happy_flow_before_submit();
  test_confirm_ctp_not_connected_retry();
  test_report_and_partial_fill();
  test_ctp_reject_releases_freeze();
  test_ctp_cancel_completed_classified_cancelled();
  test_report_monotonic_and_post_terminal_guard();
  test_confirm_reruns_full_risk();
  test_confirm_ledger_excludes_self_freeze();
  test_cancel_paths();
  test_expire_pending_sweep();
  test_flow_store_primitive();
  return report("order_engine.order_engine");
}
