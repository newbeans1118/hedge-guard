// T-036 交易路径接缝集成单测（hgcp 侧，真实 protobuf 编解码）。
// 覆盖：CMD_PLACE_ORDER 受理主链（ACK order_id/confirm_token + EVT_ORDER_ACCEPTED）/
//       风控拒绝（MSG_ERROR + EVT_PRE_TRADE_REJECT）/ CMD_CONFIRM_ORDER 门卫与提交 /
//       CMD_CANCEL_ORDER / 引擎未注入=T-038 旧行为（回归）/ 未就绪 0x0401。
#include <cstdio>
#include <cstring>

#include "hg/hgcp/command_handler.h"
#include "hg/hgcp/protocol.h"
#include "hg/hgcp/payload_codec.h"
#include "hg/order_engine/order_engine.h"
#include "hg/risk_engine/risk_engine.h"
#include "hg/risk_state/risk_state.h"
#include "hgcp.pb.h"
#include "risk_engine_test_util.h"

#define HG_ABORT_IF(cond)                                                  \
  do {                                                                     \
    if (cond) {                                                            \
      std::printf("[ABORT] %s:%d\n", __FILE__, __LINE__);                  \
      std::exit(2);                                                        \
    }                                                                      \
  } while (0)

namespace {

namespace pbv1 = ::hgcp::v1;
using hg::flow_store::FlowStore;
using hg::hgcp::CommandHandler;
using hg::hgcp::CommandOutcome;
using hg::hgcp::MessageType;
using hg::hgcp::PbPayloadCodec;
using hg::order_engine::EngineEvent;
using hg::order_engine::ITradeGateway;
using hg::order_engine::OrderEngine;
using hg::order_engine::SubmitAck;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::testutil::bind;
using hg::risk_state::RiskState;

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }

template <typename M>
std::vector<std::uint8_t> enc(const M& m) {
  std::vector<std::uint8_t> b;
  if (!PbPayloadCodec::encode(m, &b)) {
    std::printf("[FATAL] encode failed\n");
    std::exit(2);
  }
  return b;
}

// 假发送面（受理即可）。
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

pbv1::CmdPlaceOrder basePlace(const char* ref) {
  pbv1::CmdPlaceOrder m;
  m.set_client_order_ref(ref);
  m.set_trade_plan_id(1000);
  m.set_exposure_id(5);
  m.set_sub_account_id(1);
  m.set_product_id(10);
  m.set_instrument_id("100");  // §4.4.1 对账映射约定：数值串
  m.set_instrument_type(pbv1::INSTRUMENT_TYPE_FUTURES);
  m.set_direction(pbv1::DIRECTION_BUY);
  m.set_offset_flag(pbv1::OFFSET_FLAG_OPEN);
  m.set_price_type(pbv1::PRICE_TYPE_LIMIT);
  m.set_limit_price("1.000000");
  m.set_volume(10);
  m.set_operator_id(7);
  m.set_trade_reason_code(pbv1::TRADE_REASON_CODE_PLAN_EXECUTION);
  m.set_trade_reason_time_desc(pbv1::TRADE_REASON_TIME_DESC_MONTHLY_REBALANCE);
  m.set_related_plan_id(1000);
  m.set_related_exposure_id(5);
  m.set_client_ts_ms(900);
  return m;
}

// 夹具：快照直载（T-038 引擎联动同形——快照含合约/计划/敞口/权限全量）。
struct Rig {
  RiskState state;
  hg::risk_engine::testutil::FakeClock clk{20260101, 1000};
  RiskEngine risk{&clk};
  FlowStore flow;
  FakeGateway gw;
  OrderEngine eng{&risk, &flow, &gw};
  CommandHandler h{&state};

  Rig() {
    // T-173：在基础批上加入属于 sub=2 的外来计划 2000（验证 0x1019 关联计划跨子账户拒绝链）。
    hg::risk_state::LoadBatch b = hg::risk_engine::testutil::happyBatch(1);
    hg::risk_state::PlanEntry p2;
    p2.sub_account_id = 2;
    p2.product_id = 10;
    p2.status = hg::risk_state::PlanStatus::kActive;
    p2.term_start_date = 20260101;
    p2.term_end_date = 20260601;
    b.putPlan(2000, p2);
    HG_ABORT_IF(!bind(&state, &risk, b));
    HG_ABORT_IF(!flow.open("/root/t036-path-flow.log"));
    eng.setPrevSettlement("100", 1000000);  // 昨结算 = 限价 ⇒ 偏离 0
    h.set_order_engine(&eng);
  }
};

bool is_err(const CommandOutcome& o, std::uint32_t want) {
  pbv1::MsgError e;
  if (!PbPayloadCodec::decode(o.reply.body, &e)) return false;
  return o.reply.type == mt(MessageType::kMsgError) && e.err_code() == want;
}

std::string ack_detail(const CommandOutcome& o) {
  pbv1::MsgAck a;
  if (o.reply.type != mt(MessageType::kMsgAck) || !PbPayloadCodec::decode(o.reply.body, &a)) {
    return "";
  }
  return a.detail();
}

// ── 1. 引擎未注入 ⇒ T-038 旧行为（回归守卫）─────────────────────────────────
void test_legacy_when_engine_null() {
  RiskState st;
  hg::risk_engine::testutil::FakeClock clk(20260101);
  RiskEngine risk(&clk);
  HG_CHECK(bind(&st, &risk, hg::risk_engine::testutil::happyBatch(1)));
  CommandHandler h(&st);
  const CommandOutcome o =
      h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 1, 1000);
  HG_CHECK(o.consumed);
  HG_CHECK(ack_detail(o).find("accepted (path pending T-036)") != std::string::npos);
  HG_CHECK(o.events.empty());
}

// ── 2. 未就绪 ⇒ 0x0401（引擎注入前后同门卫）─────────────────────────────────
void test_not_ready_rejected() {
  RiskState st;  // 空快照 = 未就绪
  CommandHandler h(&st);
  OrderEngine eng(nullptr, nullptr, nullptr);
  h.set_order_engine(&eng);
  const CommandOutcome o =
      h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 1, 1000);
  HG_CHECK(is_err(o, 0x0401));
}

// ── 3. PLACE 主链：ACK(order_id+token) + EVT_ORDER_ACCEPTED ─────────────────
void test_place_happy() {
  Rig r;
  const CommandOutcome o =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 10, 1000);
  HG_CHECK(o.consumed);
  const std::string d = ack_detail(o);
  HG_CHECK(d.find("order_id=1") != std::string::npos);
  HG_CHECK(d.find("confirm_token=") != std::string::npos);
  HG_CHECK_EQ(o.events.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(o.events[0].type, mt(MessageType::kEvtOrderAccepted));
  pbv1::EvtOrderAccepted e;
  HG_CHECK(PbPayloadCodec::decode(o.events[0].body, &e));
  HG_CHECK_EQ(e.order_id(), 1);
  HG_CHECK(e.risk_passed());
  HG_CHECK_EQ(e.effective_timeout_ms(), 60000);
  HG_CHECK_EQ(r.eng.pending_count(), 1);
}

// ── 4. PLACE 风控拒绝：MSG_ERROR + EVT_PRE_TRADE_REJECT（0x1015 无基准价）────
void test_place_reject() {
  Rig r;
  pbv1::CmdPlaceOrder m = basePlace("HG1");
  m.set_instrument_id("999");  // 未注入昨结算价的合约（同 999 也不在档案 ⇒ 0x1002 更早）
  // 为精确命中 0x1015：档案内合约 + 无基准价 ⇒ 用 instrument 100 但清缓存不可行；
  // 改用未建档合约 ⇒ 预期 0x1002（instrument unknown）。
  const CommandOutcome o = r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(m), 11, 1000);
  HG_CHECK(o.consumed);
  HG_CHECK(is_err(o, 0x1002));
  HG_CHECK_EQ(o.events.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(o.events[0].type, mt(MessageType::kEvtPreTradeReject));
  pbv1::EvtPreTradeReject e;
  HG_CHECK(PbPayloadCodec::decode(o.events[0].body, &e));
  HG_CHECK_EQ(e.rule_id(), std::string("R-RISK-1"));
  HG_CHECK_EQ(e.reason_code(), static_cast<std::uint32_t>(0x1002));
  HG_CHECK_EQ(e.client_order_ref(), std::string("HG1"));
  HG_CHECK_EQ(r.eng.order_count(), 0);
}

// ── 5. CONFIRM：token 错 0x0307 / 代确认 0x0305 / 正确提交 ACK ───────────────
void test_confirm_path() {
  Rig r;
  const CommandOutcome p =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 10, 1000);
  const std::string d = ack_detail(p);
  const std::size_t tp = d.find("confirm_token=") + std::strlen("confirm_token=");
  const std::size_t sp = d.find(';', tp);
  const std::string token = d.substr(tp, sp - tp);

  pbv1::CmdConfirmOrder c;
  c.set_order_id(1);
  c.set_operator_id(8);  // 代确认
  c.set_confirm_ts_ms(1001);
  c.set_confirm_token(token);
  HG_CHECK(r.h.handle(mt(MessageType::kCmdConfirmOrder), enc(c), 12, 1001)
              .reply.type == mt(MessageType::kMsgError));

  pbv1::CmdConfirmOrder c2 = c;
  c2.set_operator_id(7);
  c2.set_confirm_token("bogus");
  HG_CHECK(is_err(r.h.handle(mt(MessageType::kCmdConfirmOrder), enc(c2), 13, 1001), 0x0307));

  pbv1::CmdConfirmOrder c3 = c;
  c3.set_operator_id(7);
  c3.set_confirm_token(token);
  const CommandOutcome ok =
      r.h.handle(mt(MessageType::kCmdConfirmOrder), enc(c3), 14, 1002);
  HG_CHECK_EQ(ok.reply.type, mt(MessageType::kMsgAck));
  HG_CHECK(ack_detail(ok).find("submitted to CTP") != std::string::npos);
  HG_CHECK_EQ(r.gw.submit_calls, 1);
  HG_CHECK_EQ(r.flow.record_count(), 2);  // PLACED + SENT（⑨→⑩）
}

// ── 6. CANCEL：待确认撤单 → ACK + EVT_ORDER_CANCEL_RESULT ───────────────────
void test_cancel_path() {
  Rig r;
  HG_CHECK(r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 10, 1000)
               .reply.type == mt(MessageType::kMsgAck));
  pbv1::CmdCancelOrder c;
  c.set_order_id(1);
  c.set_operator_id(7);
  c.set_reason("manual");
  const CommandOutcome o = r.h.handle(mt(MessageType::kCmdCancelOrder), enc(c), 15, 1005);
  HG_CHECK_EQ(o.reply.type, mt(MessageType::kMsgAck));
  HG_CHECK_EQ(o.events.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(o.events[0].type, mt(MessageType::kEvtOrderCancelResult));
  pbv1::EvtOrderCancelResult e;
  HG_CHECK(PbPayloadCodec::decode(o.events[0].body, &e));
  HG_CHECK_EQ(e.order_id(), 1);
  HG_CHECK_EQ(e.cancelled_volume(), 10);
  HG_CHECK_EQ(r.eng.find(1)->status, hg::order_engine::OrderStatus::kCancelled);
  HG_CHECK_EQ(r.eng.frozenOf(1, 1, 10), 0);
}

// ── 7. 超时失效经惰性扫描产出 EVT_ORDER_EXPIRED ─────────────────────────────
void test_lazy_expire_event() {
  Rig r;
  HG_CHECK(r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 10, 1000)
               .reply.type == mt(MessageType::kMsgAck));
  // 61 秒后任意交易指令到达 ⇒ 先扫出 EVT_ORDER_EXPIRED。
  pbv1::CmdPlaceOrder m2 = basePlace("HG2");
  const CommandOutcome o =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(m2), 16, 1000 + 61000);
  HG_CHECK_EQ(o.events.size(), static_cast<std::size_t>(2));  // EXPIRED + ACCEPTED
  HG_CHECK_EQ(o.events[0].type, mt(MessageType::kEvtOrderExpired));
  pbv1::EvtOrderExpired e;
  HG_CHECK(PbPayloadCodec::decode(o.events[0].body, &e));
  HG_CHECK_EQ(e.order_id(), 1);
  HG_CHECK_EQ(e.timeout_ms(), 60000);
  HG_CHECK_EQ(r.eng.find(1)->status, hg::order_engine::OrderStatus::kExpired);
}

// ── 8. T-173：交易理由关联对象跨子账户 ⇒ 0x1019（承载链 pb→OrderInput→buildRequest→risk_engine）
void test_place_related_object_cross_sub_rejected() {
  Rig r;
  // 正控：related_plan_id=1000 @ sub=1（与指令同子账户）⇒ 受理。
  {
    const CommandOutcome ok =
        r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG9")), 9, 1000);
    HG_CHECK(ok.reply.type == mt(MessageType::kMsgAck));
    HG_CHECK_EQ(ok.events.size(), static_cast<std::size_t>(1));
    HG_CHECK_EQ(ok.events[0].type, mt(MessageType::kEvtOrderAccepted));
  }
  // 命中：related_plan_id=2000 @ sub=2（跨子账户）⇒ 0x1019 + EVT_PRE_TRADE_REJECT。
  {
    pbv1::CmdPlaceOrder m = basePlace("HG10");
    m.set_related_plan_id(2000);
    const CommandOutcome o = r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(m), 10, 1000);
    HG_CHECK(is_err(o, 0x1019));
    HG_CHECK_EQ(o.events.size(), static_cast<std::size_t>(1));
    HG_CHECK_EQ(o.events[0].type, mt(MessageType::kEvtPreTradeReject));
    pbv1::EvtPreTradeReject e;
    HG_CHECK(PbPayloadCodec::decode(o.events[0].body, &e));
    HG_CHECK_EQ(e.reason_code(), static_cast<std::uint32_t>(0x1019));
    HG_CHECK(e.rule_id() == "R-TRADE-9");
  }
}

}  // namespace

int main() {
  using hg::risk_engine::testutil::report;
  test_legacy_when_engine_null();
  test_not_ready_rejected();
  test_place_happy();
  test_place_reject();
  test_confirm_path();
  test_cancel_path();
  test_lazy_expire_event();
  test_place_related_object_cross_sub_rejected();
  return report("hgcp.order_path");
}
