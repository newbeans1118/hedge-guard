// T-037 重放门卫与触发接线单测（hgcp 侧，真实 protobuf 编解码）。
// 覆盖：0x0401 单一门卫扩展（REPLAYING/FAILED ⇒ 0x0401；READY 放行；
//       接缝未注入 = T-038 回归）/ CMD_TRIGGER_REPLAY 真实触发（ACK +
//       EVT_REPLAY_PROGRESS + EVT_SYSTEM_STATE）/ 失败回码 0x3002·0x0405·0x3001 /
//       进度帧字段级解码断言。
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "hg/flow_store/flow_store.h"
#include "hg/flow_store/replay.h"
#include "hg/hgcp/command_handler.h"
#include "hg/hgcp/protocol.h"
#include "hg/hgcp/payload_codec.h"
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
using hg::flow_store::FlowState;
using hg::flow_store::FlowStore;
using hg::flow_store::InflightOrder;
using hg::flow_store::IOrderStateQuery;
using hg::flow_store::OrderQueryResult;
using hg::flow_store::ReplayController;
using hg::flow_store::ReplayVerdict;
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

class FakeQueryConflict : public IOrderStateQuery {
 public:
  OrderQueryResult query(const InflightOrder& o, std::string* detail) override {
    (void)o;
    if (detail != nullptr) *detail = "CTP says unknown local id";
    return OrderQueryResult::kConflict;
  }
};


// 就绪夹具：参数收齐（RiskState::ready = true）+ 流水分片一条 SENT（在途）。
const char* kDir = "/root/t037-gate-test";

struct Rig {
  RiskState state;
  hg::risk_engine::testutil::FakeClock clk{20260101, 1000};
  RiskEngine risk{&clk};
  ReplayController replay;
  CommandHandler h{&state};

  explicit Rig(const std::string& flow_dir) {
    HG_ABORT_IF(!bind(&state, &risk, happyBatch(1)));
    FlowRuntimeConfig cfg;
    cfg.flow_dir = flow_dir;
    replay.set_runtime_config(cfg);
    h.set_replay_controller(&replay);
  }
};

void mkclean(const std::string& d) {
  const std::string cmd =
      std::string("rm -rf '") + d + "'; mkdir -p '" + d + "'";
  HG_ABORT_IF(std::system(cmd.c_str()) != 0);
}

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

// ── 1. REPLAYING ⇒ 交易指令 0x0401（验收 3 门卫）─────────────────────────────
void test_replaying_gate_blocks() {
  const std::string d = std::string(kDir) + "/replaying";
  mkclean(d);
  writeSentShard(d);
  Rig r(d);  // 参数就绪；重放未跑 ⇒ REPLAYING 前 = NOT_READY，同样拒
  const CommandOutcome o0 =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 1, 1000);
  HG_CHECK(is_err(o0, 0x0401));

  // 触发重放（查询未注入 ⇒ 在途按超时保守）⇒ READY ⇒ 放行（受理回执）。
  const CommandOutcome t =
      r.h.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 2, 1000);
  HG_CHECK(r.replay.ready());
  const CommandOutcome o1 =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 3, 1000);
  HG_CHECK(!is_err(o1, 0x0401));  // READY 后不再被就绪门卫拦截
  (void)t;
}

// ── 2. 接缝未注入 = T-038 语义（回归守卫）；参数未收齐仍 0x0401 ─────────────
void test_unwired_regression() {
  RiskState st;  // 空快照 = 未就绪
  CommandHandler h(&st);  // 未注入 replay 接缝
  const CommandOutcome o =
      h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 1, 1000);
  HG_CHECK(is_err(o, 0x0401));
  // 参数收齐 + 未注入重放接缝 ⇒ T-038 语义（放行到受理回执）。
  RiskState st2;
  hg::risk_engine::testutil::FakeClock clk(20260101);
  RiskEngine risk(&clk);
  HG_CHECK(bind(&st2, &risk, happyBatch(1)));
  CommandHandler h2(&st2);
  const CommandOutcome o2 =
      h2.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 2, 1000);
  HG_CHECK(ack_detail(o2).find("accepted (path pending T-036)") != std::string::npos);
}

// ── 3. 重放失败（损坏）⇒ 拒一切交易指令（FAILED 承载）+ 0x3002 --------------
void test_failed_gate_blocks() {
  const std::string d = std::string(kDir) + "/failed";
  mkclean(d);
  writeSentShard(d);
  {
    FILE* f = std::fopen(hg::flow_store::flowShardPath(d, 20261001).c_str(), "ab");
    HG_ABORT_IF(f == nullptr);
    std::fputs("junk|line\n", f);
    std::fclose(f);
  }
  Rig r(d);
  const CommandOutcome t =
      r.h.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 2, 1000);
  HG_CHECK(!r.replay.ready());
  HG_CHECK(r.replay.failed());
  HG_CHECK(is_err(t, 0x3002));  // 结构损坏（§4.5.1）
  const CommandOutcome o =
      r.h.handle(mt(MessageType::kCmdPlaceOrder), enc(basePlace("HG1")), 3, 1000);
  HG_CHECK(is_err(o, 0x0401));  // FAILED ⇒ 拒一切服务
}

// ── 4. 对账矛盾 ⇒ 0x0405；目录不可读 ⇒ 0x3001 ────────────────────────────────
void test_mismatch_and_openfail_codes() {
  {
    const std::string d = std::string(kDir) + "/mismatch";
    mkclean(d);
    writeSentShard(d);
    Rig r(d);
    FakeQueryConflict q;
    r.replay.set_order_query(&q);
    const CommandOutcome t =
        r.h.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 2, 1000);
    HG_CHECK(is_err(t, 0x0405));
    HG_CHECK(!r.replay.ready());
  }
  {
    Rig r(std::string(kDir) + "/missing-dir");
    const CommandOutcome t =
        r.h.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 2, 1000);
    HG_CHECK(is_err(t, 0x3001));
    HG_CHECK(!r.replay.ready());
  }
}

// ── 5. CMD_TRIGGER_REPLAY 成功：ACK + 进度帧 + SYSTEM_STATE(READY) 字段级断言
void test_trigger_success_frames() {
  const std::string d = std::string(kDir) + "/frames";
  mkclean(d);
  writeSentShard(d);
  Rig r(d);
  const CommandOutcome t =
      r.h.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 9, 1234);
  HG_CHECK(t.consumed);
  HG_CHECK(ack_detail(t).find("replay done") != std::string::npos);
  // 进度帧 + 终态帧 + SYSTEM_STATE 帧。
  HG_CHECK_EQ(t.events.size(), static_cast<std::size_t>(3));
  bool saw_progress = false;
  bool saw_final = false;
  bool saw_sysstate = false;
  for (std::size_t i = 0; i < t.events.size(); ++i) {
    if (t.events[i].type == mt(MessageType::kEvtReplayProgress)) {
      pbv1::EvtReplayProgress e;
      HG_CHECK(PbPayloadCodec::decode(t.events[i].body, &e));
      if (e.result() == pbv1::REPLAY_RESULT_OK) {
        saw_final = true;
        HG_CHECK(e.state() == pbv1::SERVER_STATE_READY);
        HG_CHECK_EQ(e.replayed_records(), 2);
        HG_CHECK_EQ(e.total_records(), 2);
        HG_CHECK_EQ(e.started_ts_ms(), 1234);
        HG_CHECK_EQ(e.finished_ts_ms(), 1234);
      } else {
        saw_progress = true;
        HG_CHECK_EQ(e.replayed_records(), 0);
        HG_CHECK_EQ(e.total_records(), 2);
      }
    }
    if (t.events[i].type == mt(MessageType::kEvtSystemState)) {
      pbv1::EvtSystemState e;
      HG_CHECK(PbPayloadCodec::decode(t.events[i].body, &e));
      saw_sysstate = true;
      HG_CHECK(e.state() == pbv1::SERVER_STATE_READY);
      HG_CHECK_EQ(e.param_version_applied(),
                  static_cast<std::uint64_t>(r.state.param_version()));
    }
  }
  HG_CHECK(saw_progress);
  HG_CHECK(saw_final);
  HG_CHECK(saw_sysstate);
}

// ── 6. 失败终态进度帧：result=MISMATCH + mismatch_detail ────────────────────
void test_mismatch_progress_frame() {
  const std::string d = std::string(kDir) + "/mm-frame";
  mkclean(d);
  writeSentShard(d);
  Rig r(d);
  FakeQueryConflict q;
  r.replay.set_order_query(&q);
  const CommandOutcome t =
      r.h.handle(mt(MessageType::kCmdTriggerReplay), enc(baseTrigger()), 9, 1234);
  HG_CHECK(is_err(t, 0x0405));
  bool saw_mm = false;
  for (std::size_t i = 0; i < t.events.size(); ++i) {
    if (t.events[i].type == mt(MessageType::kEvtReplayProgress)) {
      pbv1::EvtReplayProgress e;
      HG_CHECK(PbPayloadCodec::decode(t.events[i].body, &e));
      if (e.result() == pbv1::REPLAY_RESULT_MISMATCH) {
        saw_mm = true;
        HG_CHECK(e.mismatch_detail().find("order_id=11") != std::string::npos);
        HG_CHECK(e.state() == pbv1::SERVER_STATE_REPLAYING);  // FAILED 承载于 failed_
      }
    }
  }
  HG_CHECK(saw_mm);
}



}  // namespace

int main() {
  if (std::system("rm -rf /root/t037-gate-test; mkdir -p /root/t037-gate-test") != 0) {
    std::printf("[ABORT] setup failed\n");
    return 2;
  }
  test_replaying_gate_blocks();
  test_unwired_regression();
  test_failed_gate_blocks();
  test_mismatch_and_openfail_codes();
  test_trigger_success_frames();
  test_mismatch_progress_frame();
  return hg::risk_engine::testutil::report("hgcp.replay_gate");
}
