// T-041 恢复未闭环门禁单测（hgcp 侧，真实 protobuf 编解码；与 replay_gate_test 同形）。
// 覆盖（授权批复 3 约束）：
//   · 约束 1 负控：recovery_ 未注入 ⇒ 门禁行为与 T-038/T-037 基线逐字节等价
//     （参数就绪 + 重放 READY ⇒ 放行；参数未就绪 ⇒ 0x0401）；
//   · 约束 2 先后序：hook 注入 + unknown 未闭环 ⇒ 0x0401；manualRelease 闭环
//     ⇒ 台账清零 ⇒ 放行（「闭环后台账未清 ⇒ 仍拒」探针由隔离变异 M5 覆盖）；
//   · 合取序：unknown 阻断独立于 replay 状态（READY 但 unknown open ⇒ 仍拒）。
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "hg/flow_store/flow_store.h"
#include "hg/flow_store/recovery.h"
#include "hg/flow_store/replay.h"
#include "hg/hgcp/command_handler.h"
#include "hg/hgcp/protocol.h"
#include "hg/hgcp/payload_codec.h"
#include "hg/order_engine/order_engine.h"
#include "hg/order_engine/recovery_hook.h"
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
using hg::flow_store::FlowKind;
using hg::flow_store::FlowRecord;
using hg::flow_store::FlowRuntimeConfig;
using hg::flow_store::FlowStore;
using hg::flow_store::InflightOrder;
using hg::flow_store::IOrderStateQuery;
using hg::flow_store::ManualReleaseError;
using hg::flow_store::ManualReleaseRequest;
using hg::flow_store::OrderQueryResult;
using hg::flow_store::RecoveryCoordinator;
using hg::flow_store::ReplayController;
using hg::flow_store::ReplayReport;
using hg::order_engine::OrderEngine;
using hg::order_engine::RecoveryHook;
using hg::hgcp::CommandHandler;
using hg::hgcp::CommandOutcome;
using hg::hgcp::MessageType;
using hg::hgcp::PbPayloadCodec;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::happyBatch;
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

// 假 CTP 查询：缺省 kTimeout（状态未知 ⇒ unknown 台账必开）。
class FakeQueryTimeout : public IOrderStateQuery {
 public:
  int calls = 0;
  OrderQueryResult query(const InflightOrder& o, std::string* detail) override {
    ++calls;
    (void)o;
    if (detail != nullptr) *detail = "timeout; conservative";
    return OrderQueryResult::kTimeout;
  }
};

class FakeGateway : public hg::order_engine::ITradeGateway {
 public:
  hg::order_engine::SubmitAck submitOrder(const hg::ctp_adapter::CtOrderReq& req) override {
    (void)req;
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

const char* kDir = "/root/t041-gate-test";

struct Rig {
  RiskState state;
  hg::risk_engine::testutil::FakeClock clk{20260101, 1000};
  RiskEngine risk{&clk};
  FlowStore flow;
  FakeGateway gw;
  OrderEngine eng{&risk, &flow, &gw};
  RecoveryCoordinator ledger;
  RecoveryHook hook{&eng, &ledger};
  ReplayController replay;
  CommandHandler h{&state};

  explicit Rig(const std::string& flow_dir) {
    HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));
    HG_ABORT_IF(!flow.open(hg::flow_store::flowShardPath(flow_dir, 20261001)));
    FlowRuntimeConfig cfg;
    cfg.flow_dir = flow_dir;
    replay.set_runtime_config(cfg);
    h.set_replay_controller(&replay);
    h.set_recovery_hook(&hook);  // 可空持有语义的「注入态」
  }
};

void mkclean(const std::string& d) {
  const std::string cmd = std::string("rm -rf '") + d + "'; mkdir -p '" + d + "'";
  HG_ABORT_IF(std::system(cmd.c_str()) != 0);
}

// 写一条在途流水（SENT 无终态），使重放对账产生 unknown。
void writeSentShard(const std::string& d) {
  FlowStore fs;
  HG_ABORT_IF(!fs.open(hg::flow_store::flowShardPath(d, 20261001)));
  FlowRecord r;
  r.kind = FlowKind::kPlaced;
  r.order_id = 11;
  r.client_order_ref = "HG11";
  r.ts_ms = 100;
  r.detail = "accepted pending confirm";
  HG_ABORT_IF(!fs.append(r));
  r.kind = FlowKind::kSent;
  r.ts_ms = 200;
  r.detail = "submitted to CTP";
  HG_ABORT_IF(!fs.append(r));
  fs.close();
}

pbv1::CmdPlaceOrder basePlace(const char* ref) {
  pbv1::CmdPlaceOrder m;
  m.set_client_order_ref(ref);
  m.set_trade_plan_id(1000);
  m.set_exposure_id(5);
  m.set_sub_account_id(1);
  m.set_product_id(10);
  m.set_instrument_id("100");
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

pbv1::CmdTriggerReplay baseTrigger() {
  pbv1::CmdTriggerReplay m;
  m.set_reason("test");
  m.set_operator_id(7);
  return m;
}

// ── 1. 约束 1 负控：hook 未注入 ⇒ T-038/T-037 基线语义逐字节等价 ─────────────
void test_unwired_hook_regression() {
  // ① 参数未就绪 + hook 未注入 ⇒ 0x0401（T-038 语义）。
  {
    RiskState st;  // 空快照 = 未就绪
    CommandHandler h(&st);
    const CommandOutcome o =
        h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 1, 1000);
    HG_CHECK(is_err(o, 0x0401));
  }
  // ② 参数就绪 + hook 未注入（且重放接缝未注入）⇒ T-038 语义放行（受理回执）。
  {
    RiskState st2;
    hg::risk_engine::testutil::FakeClock clk(20260101);
    RiskEngine risk(&clk);
    HG_CHECK(bind(&st2, &risk, happyBatch(1)));
    CommandHandler h2(&st2);
    const CommandOutcome o2 =
        h2.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 2, 1000);
    HG_CHECK(ack_detail(o2).find("accepted (path pending T-036)") != std::string::npos);
  }
  // ③ 参数就绪 + 重放 READY + hook 未注入 ⇒ 放行（T-037 两合取项语义不变）。
  {
    const std::string d = std::string(kDir) + "/unwired-hook";
    mkclean(d);
    // 无 unknown 流水（空目录重放 ⇒ READY 且台账零）。
    RiskState st3;
    hg::risk_engine::testutil::FakeClock clk(20260101);
    RiskEngine risk(&clk);
    HG_CHECK(bind(&st3, &risk, happyBatch(1)));
    ReplayController replay;
    FlowRuntimeConfig cfg;
    cfg.flow_dir = d;
    replay.set_runtime_config(cfg);
    CommandHandler h3(&st3);
    h3.set_replay_controller(&replay);  // hook 仍不注入
    const CommandOutcome t =
        h3.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 2, 1000);
    HG_CHECK(replay.ready());
    const CommandOutcome o3 =
        h3.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 3, 1000);
    HG_CHECK(!is_err(o3, 0x0401));
  }
}

// ── 2. 约束 2：hook 注入 + unknown 未闭环 ⇒ 0x0401；人工闭环 ⇒ 放行 ──────────
void test_unknown_open_blocks_until_manual_close() {
  const std::string d = std::string(kDir) + "/unknown";
  mkclean(d);
  writeSentShard(d);
  Rig r(d);

  // 重放前（hook 注入、台账空）⇒ 阻断来自 NOT_READY（等价基线）。
  const CommandOutcome o0 =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 1, 1000);
  HG_CHECK(is_err(o0, 0x0401));

  // 触发重放：kTimeout ⇒ unknown 台账登记（台账 open=1）。
  const CommandOutcome t =
      r.h.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 2, 1791216000000LL);
  HG_CHECK(r.replay.ready());          // 重放本身 OK（对账无矛盾——timeout 是保守非矛盾）
  // 装配编排（T-039 形态）：run → 决策执行（hook.execute 消费决策 + 登记台账）。
  const ReplayReport& rep = r.replay.last_report();
  r.hook.execute(rep, 1791216000000LL);
  HG_CHECK(r.ledger.has_unknown_open());  // 但 unknown 未闭环

  // READY 但 unknown open ⇒ 第三合取项拦截 0x0401（验收 4 核心断言）。
  const CommandOutcome o1 =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 3, 1000);
  HG_CHECK(is_err(o1, 0x0401));
  // CMD_CONFIRM_ORDER / CMD_CANCEL_ORDER 同被拦（「继续拒绝新指令」全指令面）。
  pbv1::CmdConfirmOrder cm;
  cm.set_order_id(11);
  cm.set_operator_id(7);
  cm.set_confirm_token("x");
  const CommandOutcome o2 = r.h.handle(mt(MessageType::kCmdConfirmOrder), enc(cm), 4, 1000);
  HG_CHECK(is_err(o2, 0x0401));

  // 人工核验闭环（双人）⇒ 台账清零 ⇒ 放行（先后序：闭环先于门禁读点）。
  ManualReleaseRequest req;
  req.order_id = 11;
  req.client_order_ref = "HG11";
  req.operator_a_id = 11;
  req.operator_b_id = 22;
  req.reason = "verified";
  hg::order_engine::EngineEvent ev;
  ManualReleaseError err = ManualReleaseError::kNone;
  HG_CHECK(r.hook.manualRelease(req, 2000, &err, &ev));
  HG_CHECK(err == ManualReleaseError::kNone);
  HG_CHECK(!r.ledger.has_unknown_open());  // 约束 2：闭环后台账已清（可观测）
  const CommandOutcome o3 =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 5, 3000);
  HG_CHECK(!is_err(o3, 0x0401));  // 人工闭环 ⇒ 门禁解除
}

// ── 3. 合取序：unknown 阻断独立于重放状态（hook 注入、重放未跑）──────────────
void test_unknown_block_independent_of_replay_state() {
  const std::string d = std::string(kDir) + "/indep";
  mkclean(d);
  Rig r(d);  // 空目录；重放未跑（state=NOT_READY）
  // 直接向台账注入一条 unknown（不经重放——验证合取项与重放状态解耦）。
  ReplayReport rep;
  hg::flow_store::ReplayDecision dec;
  dec.kind = hg::flow_store::ReplayDecision::kUnknownKeepFrozen;
  dec.order.order_id = 99;
  dec.order.client_order_ref = "HG99";
  dec.ctp_result = OrderQueryResult::kTimeout;
  rep.decisions.push_back(dec);
  r.ledger.consume(rep, 1000);
  HG_CHECK(r.ledger.has_unknown_open());
  const CommandOutcome o =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 1, 1000);
  HG_CHECK(is_err(o, 0x0401));  // NOT_READY 与 unknown 双阻断（单一门卫合取，无短路放行）
}

// ── 4. CMD_QUERY_STATUS 的 ready 面反映第三合取项（单一判定源）────────────────
void test_query_status_reflects_unknown() {
  const std::string d = std::string(kDir) + "/qstatus";
  mkclean(d);
  writeSentShard(d);
  Rig r(d);
  const CommandOutcome t =
      r.h.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 2, 1791216000000LL);
  HG_CHECK(r.replay.ready());
  const ReplayReport& repq = r.replay.last_report();
  r.hook.execute(repq, 1791216000000LL);
  {
    pbv1::CmdQueryStatus q;
    const CommandOutcome o = r.h.handle(mt(MessageType::kCmdQueryStatus), enc(q), 3, 1000);
    // unknown 未闭环 ⇒ ready=false（:1126/:1214 消费点同源）。
    HG_CHECK(ack_detail(o).find("ready=false") != std::string::npos);
  }
  ManualReleaseRequest req;
  req.order_id = 11;
  req.client_order_ref = "HG11";
  req.operator_a_id = 11;
  req.operator_b_id = 22;
  req.reason = "verified";
  ManualReleaseError errq = ManualReleaseError::kNone;
  HG_CHECK(r.hook.manualRelease(req, 2000, &errq, nullptr));
  {
    pbv1::CmdQueryStatus q;
    const CommandOutcome o = r.h.handle(mt(MessageType::kCmdQueryStatus), enc(q), 4, 1000);
    HG_CHECK(ack_detail(o).find("ready=true") != std::string::npos);
  }
}

}  // namespace

int main() {
  if (std::system("rm -rf /root/t041-gate-test; mkdir -p /root/t041-gate-test") != 0) {
    std::printf("[ABORT] setup failed\n");
    return 2;
  }
  test_unwired_hook_regression();
  test_unknown_open_blocks_until_manual_close();
  test_unknown_block_independent_of_replay_state();
  test_query_status_reflects_unknown();
  return hg::risk_engine::testutil::report("hgcp.recovery_gate");
}
