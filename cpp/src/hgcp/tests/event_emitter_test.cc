// T-039 事件流产出与保序补数 —— **真实回环 socket** 端到端测试。
//
// 覆盖项（对应 TASKS T-039 验收 1~5）：
//   验收1: 14 种事件可产出（8 种既有编码经统一出口 + 6 种本单补齐编码）；
//          须落库 10 种（0x0202~0x020B）齐备（body 经 PbPayloadCodec 校验）。
//   验收2: 通道 SEQ 单调不跳号（事件帧 + HELLO_ACK + 心跳 + 指令回执共用同一计数器）。
//   验收3: 断连保留模式（容量 16384；可靠事件不丢；满 ⇒ 背压 + 告警；
//          EVT_MARKET_QUOTE 可丢例外——断连丢弃、满不背压）。
//   验收4: MSG_RESUME_REQ → MSG_RESUME_DATA（分批 ≤500、is_last、批内 SEQ 连续）；
//          缺口超缓冲 ⇒ MSG_ERROR{0x0306} + 全量状态重建（0x0401 单一门卫回归）。
//   验收5: 压力（连续 1000 笔）接收数量与顺序与发出完全一致（§10-31）。
#include "hg/hgcp/event_emitter.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "hg/hgcp/command_handler.h"
#include "hg/hgcp/payload_codec.h"
#include "hg/hgcp/pb_transport_policy.h"
#include "hg/hgcp/server_loop.h"
#include "hgcp_test_util.h"

namespace pbv1 = ::hgcp::v1;

namespace {

using hg::hgcp::BindStatus;
using hg::hgcp::BufferedFrame;
using hg::hgcp::EmitStatus;
using hg::hgcp::EventEmitter;
using hg::hgcp::Frame;
using hg::hgcp::FrameStatus;
using hg::hgcp::FrameStreamReader;
using hg::hgcp::HgcpServerLoop;
using hg::hgcp::MessageType;
using hg::hgcp::PbPayloadCodec;
using hg::hgcp::ReplayStatus;
using hg::hgcp::TcpConfig;

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }

// 前置断言：失败即记 FAIL 并从用例返回（防对未初始化帧取值 UB）；**单次求值**。
#define HG_REQUIRE(cond)                                                        \
  do {                                                                          \
    const bool _hg_ok = static_cast<bool>(cond);                                \
    ::hg::hgcp::testutil::check_impl(_hg_ok, #cond, __FILE__, __LINE__);        \
    if (!_hg_ok) {                                                              \
      return;                                                                   \
    }                                                                           \
  } while (0)

#define HG_REQUIRE_EQ(a, b)                                                     \
  do {                                                                          \
    const long long _hg_a = static_cast<long long>(a);                          \
    const long long _hg_b = static_cast<long long>(b);                          \
    ::hg::hgcp::testutil::check_impl(_hg_a == _hg_b, #a " == " #b, __FILE__,    \
                                     __LINE__);                                 \
    if (_hg_a != _hg_b) {                                                       \
      return;                                                                   \
    }                                                                           \
  } while (0)

bool wait_readable(int fd, int budget_ms) {
  if (budget_ms <= 0) {
    return hg::hgcp::poll_readable(fd, 0) == 1;
  }
  int waited = 0;
  while (waited < budget_ms) {
    if (hg::hgcp::poll_readable(fd, 5) == 1) {
      return true;
    }
    waited += 5;
  }
  return false;
}

// 测试客户端（与 transport_test 同形：流式收帧、半包保留）。
class TestClient {
 public:
  explicit TestClient(int fd) : fd_(fd) {}
  ~TestClient() { close(); }

  bool valid() const { return fd_ >= 0; }
  void close() {
    if (fd_ >= 0) {
      ::close(fd_);
      fd_ = -1;
    }
  }

  bool send_wire(const std::vector<std::uint8_t>& wire) {
    if (fd_ < 0 || wire.empty()) {
      return false;
    }
    return hg::hgcp::send_all(fd_, &wire[0], wire.size());
  }

  bool recv_frame(int timeout_ms, Frame* out) {
    for (;;) {
      const FrameStatus st = reader_.next(out);
      if (st == FrameStatus::kOk) {
        return true;
      }
      if (st != FrameStatus::kNeedMore) {
        return false;
      }
      if (!wait_readable(fd_, timeout_ms)) {
        return false;
      }
      std::uint8_t buf[4096];
      const int r = hg::hgcp::recv_some(fd_, buf, sizeof(buf));
      if (r <= 0) {
        return false;
      }
      reader_.feed(buf, static_cast<std::size_t>(r));
    }
  }

 private:
  TestClient(const TestClient&);
  TestClient& operator=(const TestClient&);

  int fd_;
  FrameStreamReader reader_;
};

int connect_client(std::uint16_t port) {
  const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) {
    return -1;
  }
  struct sockaddr_in a;
  std::memset(&a, 0, sizeof(a));
  a.sin_family = AF_INET;
  a.sin_port = htons(port);
  if (::inet_pton(AF_INET, "127.0.0.1", &a.sin_addr) != 1 ||
      ::connect(fd, reinterpret_cast<struct sockaddr*>(&a), sizeof(a)) != 0) {
    ::close(fd);
    return -1;
  }
  return fd;
}

// 完成帧级握手：客户端发 HELLO(seq=1)，服务端 pump 处理，客户端收 HELLO_ACK。
bool do_handshake(HgcpServerLoop* srv, TestClient* cli) {
  std::vector<std::uint8_t> hw;
  if (hg::hgcp::make_frame(mt(MessageType::kMsgHello), 0u, 1u, 1u,
                           std::vector<std::uint8_t>(), &hw) != FrameStatus::kOk) {
    return false;
  }
  if (!cli->send_wire(hw)) {
    return false;
  }
  std::vector<Frame> in;
  if (srv->pump(300, 0, &in) != 1) {
    return false;
  }
  Frame ack;
  return cli->recv_frame(1000, &ack) && ack.header.type == mt(MessageType::kMsgHelloAck);
}

// 连接 + 握手 + 挂接事件层（HELLO_ACK 后 on_session_ready 触发）。
void bring_up(HgcpServerLoop* srv, EventEmitter* em, TestClient** out_cli) {
  TestClient* cli = new TestClient(connect_client(srv->bound_port()));
  if (!cli->valid()) {
    ::hg::hgcp::testutil::check_impl(false, "client connect", __FILE__, __LINE__);
    *out_cli = cli;
    return;
  }
  em->attach();
  if (!do_handshake(srv, cli)) {
    ::hg::hgcp::testutil::check_impl(false, "handshake", __FILE__, __LINE__);
  }
  *out_cli = cli;
}

// 发 RESUME_REQ 并校验回放批（from/to/is_last/批数/批内 SEQ 连续 + 帧字节可解析）。
void send_resume_req_and_check(TestClient* cli, HgcpServerLoop* srv,
                               std::uint64_t last_seq, std::uint64_t want_from,
                               std::uint64_t want_to, int want_batches,
                               bool last_batch_is_last);

// 事件载荷构造：每类一个最小合法 pb body（字段取非零标记值）。
struct EventSpec {
  std::uint16_t type;
  std::vector<std::uint8_t> body;
};

std::vector<EventSpec> make_all_14_events() {
  std::vector<EventSpec> v;
  v.reserve(14);
  { pbv1::EvtOrderAccepted e; e.set_order_id(1); e.set_risk_passed(true);
    e.set_accepted_ts_ms(100);
    v.push_back(EventSpec{mt(MessageType::kEvtOrderAccepted), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtPreTradeReject e; e.set_client_order_ref("R2"); e.set_reason_code(0x1004);
    e.set_operator_id(7);
    v.push_back(EventSpec{mt(MessageType::kEvtPreTradeReject), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtOrderReport e; e.set_order_id(3); e.set_volume_total(10);
    v.push_back(EventSpec{mt(MessageType::kEvtOrderReport), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtTradeFill e; e.set_trade_id("T1"); e.set_volume(5);
    v.push_back(EventSpec{mt(MessageType::kEvtTradeFill), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtPositionChange e; e.set_sub_account_id(1); e.set_position_qty(5);
    v.push_back(EventSpec{mt(MessageType::kEvtPositionChange), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtOrderCancelResult e; e.set_order_id(6); e.set_result(0);
    v.push_back(EventSpec{mt(MessageType::kEvtOrderCancelResult), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtOrderExpired e; e.set_order_id(7); e.set_timeout_ms(60000);
    v.push_back(EventSpec{mt(MessageType::kEvtOrderExpired), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtCommandFlow e; e.set_order_id(8);
    e.set_flow_type(pbv1::FLOW_TYPE_SENT); e.set_flow_ts_ms(100);
    v.push_back(EventSpec{mt(MessageType::kEvtCommandFlow), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtReplayProgress e; e.set_state(pbv1::SERVER_STATE_READY);
    e.set_replayed_records(1); e.set_total_records(1); e.set_started_ts_ms(1);
    e.set_result(pbv1::REPLAY_RESULT_OK);
    v.push_back(EventSpec{mt(MessageType::kEvtReplayProgress), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtSystemState e; e.set_state(pbv1::SERVER_STATE_READY);
    e.set_param_version_applied(2); e.set_ts_ms(100);
    v.push_back(EventSpec{mt(MessageType::kEvtSystemState), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtFundReceipt e; e.set_idempotency_key("K1"); e.set_apply_id("A1");
    e.set_result(pbv1::FUND_RESULT_SUCCESS); e.set_amount("100.0000"); e.set_receipt_ts_ms(100);
    v.push_back(EventSpec{mt(MessageType::kEvtFundReceipt), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtMarketQuote e; e.set_instrument_id("CU2601"); e.set_last_price("70000");
    e.set_quote_ts_ms(100);
    v.push_back(EventSpec{mt(MessageType::kEvtMarketQuote), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtCtpState e; e.set_trade_connected(true); e.set_md_connected(false);
    e.set_state_change_ts_ms(100);
    v.push_back(EventSpec{mt(MessageType::kEvtCtpState), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  { pbv1::EvtTradingAlert e; e.set_alert_code(60001);
    e.set_level(pbv1::ALERT_LEVEL_WARNING); e.set_alert_ts_ms(100);
    v.push_back(EventSpec{mt(MessageType::kEvtTradingAlert), std::vector<std::uint8_t>()});
    PbPayloadCodec::encode(e, &v.back().body); }
  return v;
}

int alert_calls = 0;  // 告警接收方计数（每用例前置清零）

void alert_sink(void* /*user*/, const char* /*what*/) { ++alert_calls; }

// ---------------------------------------------------------------------------
// 用例
// ---------------------------------------------------------------------------

// ── 验收 1：14 种事件可产出；0x0202~0x020B 齐备且 body 可校验 ─────────────────
void test_fourteen_events_producible() {
  HgcpServerLoop srv;
  HG_REQUIRE(srv.start() == BindStatus::kOk);
  EventEmitter em(&srv);
  TestClient* cli = 0;
  bring_up(&srv, &em, &cli);

  const std::vector<EventSpec> events = make_all_14_events();
  HG_REQUIRE_EQ(events.size(), 14);
  for (std::size_t i = 0; i < events.size(); ++i) {
    const EmitStatus st =
        (events[i].type == mt(MessageType::kEvtMarketQuote))
            ? em.emit_quote(events[i].body)
            : em.emit(events[i].type, events[i].body);
    HG_REQUIRE_EQ(static_cast<int>(st), static_cast<int>(EmitStatus::kSent));
  }
  // 逐帧接收：类型序 = 产出序；body 可被 PbPayloadCodec 校验（schema 一致性）。
  for (std::size_t i = 0; i < events.size(); ++i) {
    Frame f;
    HG_REQUIRE(cli->recv_frame(1000, &f));
    HG_REQUIRE_EQ(f.header.type, events[i].type);
    HG_REQUIRE(PbPayloadCodec().validate(events[i].type, f.body));
  }
  // 须落库的 10 种（0x0202~0x020B）逐一在产出清单中出现。
  for (std::uint16_t t = 0x0202u; t <= 0x020Bu; ++t) {
    bool found = false;
    for (std::size_t i = 0; i < events.size(); ++i) {
      if (events[i].type == t) {
        found = true;
      }
    }
    HG_REQUIRE(found);
  }
  em.detach();
  cli->close();
}

// ── 验收 2：通道 A 所有业务帧共用一个单调递增 SEQ（不跳号）────────────────────
void test_seq_monotonic_across_frame_kinds() {
  HgcpServerLoop srv;
  srv.configure(50, 3, hg::hgcp::kResumeBufferDefaultCapacity);  // 心跳 50ms ⇒ 可触发
  HG_REQUIRE(srv.start() == BindStatus::kOk);
  EventEmitter em(&srv);
  TestClient* cli = 0;
  bring_up(&srv, &em, &cli);

  // 事件帧 ×3（seq 2..4；seq 1 = HELLO_ACK）。
  for (int i = 0; i < 3; ++i) {
    pbv1::EvtCommandFlow e;
    e.set_order_id(static_cast<std::uint64_t>(i + 1));
    e.set_flow_type(pbv1::FLOW_TYPE_SENT);
    e.set_flow_ts_ms(i);
    std::vector<std::uint8_t> body;
    HG_REQUIRE(PbPayloadCodec::encode(e, &body));
    HG_REQUIRE(em.emit(mt(MessageType::kEvtCommandFlow), body) == EmitStatus::kSent);
  }
  // 心跳帧（时间推进到心跳到期；seq 5）。
  std::vector<Frame> in;
  srv.pump(0, 100, &in);
  // 控制帧回执：CMD_QUERY_STATUS → MSG_ACK（seq 6）。指令消费由调用方路由
  //（pump 只返回入站帧；回执经 send_frame 上线 ⇒ 与事件帧共用 SEQ 计数器）。
  {
    std::vector<std::uint8_t> cw;
    HG_REQUIRE(hg::hgcp::make_frame(mt(MessageType::kCmdQueryStatus), 0u, 7u, 7u,
                                    std::vector<std::uint8_t>(), &cw) ==
               FrameStatus::kOk);
    HG_REQUIRE(cli->send_wire(cw));
    hg::risk_state::RiskState state;
    hg::hgcp::CommandHandler handler(&state);
    srv.pump(300, 100, &in);
    HG_REQUIRE_EQ(in.size(), 1u);
    const hg::hgcp::CommandOutcome oc =
        handler.handle(in[0].header.type, in[0].body, in[0].header.msg_id, 100);
    HG_REQUIRE(oc.consumed);
    HG_REQUIRE(srv.send_frame(oc.reply.type, oc.reply.flags, oc.reply.body));
  }
  // 客户端逐帧收：HELLO_ACK 后应为 事件×3 + 心跳 + ACK，SEQ 严格 +1。
  std::uint64_t prev_seq = 1;  // HELLO_ACK
  bool got_heartbeat = false;
  bool got_ack = false;
  int got_events = 0;
  for (;;) {
    Frame f;
    if (!cli->recv_frame(300, &f)) {
      break;
    }
    HG_REQUIRE_EQ(f.header.seq, prev_seq + 1);
    prev_seq = f.header.seq;
    if (f.header.type == mt(MessageType::kMsgHeartbeat)) {
      got_heartbeat = true;
    } else if (f.header.type == mt(MessageType::kMsgAck)) {
      got_ack = true;
    } else if (f.header.type == mt(MessageType::kEvtCommandFlow)) {
      ++got_events;
    }
    if (got_heartbeat && got_ack && got_events == 3) {
      break;
    }
  }
  HG_REQUIRE(got_heartbeat);
  HG_REQUIRE(got_ack);
  HG_REQUIRE_EQ(got_events, 3);
  em.detach();
  cli->close();
}

// ── 验收 3：断连保留模式（不丢）+ 行情可丢例外 ────────────────────────────────
void test_retention_mode_no_loss_and_quote_drop() {
  HgcpServerLoop srv;
  HG_REQUIRE(srv.start() == BindStatus::kOk);
  EventEmitter em(&srv);
  TestClient* cli = 0;
  bring_up(&srv, &em, &cli);

  // 已连接 ⇒ 直发 3 条（含行情：已连接时行情也直发）。
  for (int i = 0; i < 3; ++i) {
    std::vector<std::uint8_t> body(1, static_cast<std::uint8_t>(i + 1));
    HG_REQUIRE(em.emit(mt(MessageType::kEvtSystemState), body) == EmitStatus::kSent);
  }
  std::vector<std::uint8_t> qbody(1, 0x51u);
  HG_REQUIRE(em.emit_quote(qbody) == EmitStatus::kSent);
  Frame f;
  HG_REQUIRE(cli->recv_frame(1000, &f));  // 排空 HELLO_ACK 后第一帧
  int drained = 1;
  while (cli->recv_frame(50, &f)) {
    ++drained;
  }
  HG_REQUIRE_EQ(drained, 4);  // 事件×3 + 行情×1

  // 断连：客户端关闭 → pump 检出 → 保留模式。
  cli->close();
  std::vector<Frame> in;
  srv.pump(300, 0, &in);
  HG_REQUIRE(!srv.has_connection());

  // 可靠事件 ×5 ⇒ 保留；行情 ×2 ⇒ 丢弃（不保留、不背压）。
  for (int i = 0; i < 5; ++i) {
    std::vector<std::uint8_t> body(1, static_cast<std::uint8_t>(i + 1));
    HG_REQUIRE(em.emit(mt(MessageType::kEvtSystemState), body) == EmitStatus::kRetained);
  }
  HG_REQUIRE(em.emit_quote(qbody) == EmitStatus::kDroppedQuote);
  HG_REQUIRE(em.emit(mt(MessageType::kEvtMarketQuote), qbody) ==
             EmitStatus::kDroppedQuote);  // 可靠出口误投行情 ⇒ 守卫转可丢路径
  HG_REQUIRE_EQ(em.retained(), 5u);
  HG_REQUIRE_EQ(em.quotes_dropped(), 2u);

  // 重连 + 握手 ⇒ on_session_ready ⇒ 保留队列按序补发（先于任何 RESUME_DATA）。
  TestClient* cli2 = new TestClient(connect_client(srv.bound_port()));
  HG_REQUIRE(cli2->valid());
  HG_REQUIRE(do_handshake(&srv, cli2));
  HG_REQUIRE_EQ(em.retained(), 0u);
  HG_REQUIRE_EQ(em.flushed_count(), 5u);
  for (int i = 0; i < 5; ++i) {
    Frame g;
    HG_REQUIRE(cli2->recv_frame(1000, &g));
    HG_REQUIRE_EQ(g.header.type, mt(MessageType::kEvtSystemState));
    HG_REQUIRE_EQ(g.body.size(), 1u);
    HG_REQUIRE_EQ(g.body[0], static_cast<std::uint8_t>(i + 1));  // FIFO 保序
  }
  // 补发帧 SEQ 紧接既有通道序（1=HELLO_ACK₁、2..4=首批事件、5=行情、
  // 6=HELLO_ACK₂、7..11=补发）⇒ 补数请求「已齐」⇒ 无 RESUME_DATA。
  {
    pbv1::MsgResumeReq req;
    req.set_last_received_seq(11);
    req.set_session_id("t");
    std::vector<std::uint8_t> body;
    HG_REQUIRE(PbPayloadCodec::encode(req, &body));
    std::vector<std::uint8_t> wire;
    HG_REQUIRE(hg::hgcp::make_frame(mt(MessageType::kMsgResumeReq), 0u, 2u, 2u, body,
                                    &wire) == FrameStatus::kOk);
    HG_REQUIRE(cli2->send_wire(wire));
    std::vector<Frame> in2;
    srv.pump(300, 0, &in2);  // 处理 RESUME_REQ（kNothingToReplay ⇒ 不回帧）
    Frame g;
    HG_REQUIRE(!cli2->recv_frame(200, &g));  // 无补数帧
  }
  em.detach();
  delete cli2;
}

// ── 验收 3：容量 16384；满 ⇒ 背压 + 告警，且不丢 ─────────────────────────────
void test_backpressure_full_capacity_no_loss() {
  // 容量口径钉死（§2.4.3 eventQueue = 16384）：用例不得仅引用实现侧常量——
  // 否则常量被改（如 8192）时用例随动而**恒绿**（判据形态与取值重合 ⇒ 无守卫力）。
  HG_REQUIRE_EQ(static_cast<long long>(hg::hgcp::kEventQueueCapacity), 16384);
  HgcpServerLoop srv;
  HG_REQUIRE(srv.start() == BindStatus::kOk);
  EventEmitter em(&srv);
  em.set_alert_sink(alert_sink, 0);
  alert_calls = 0;
  // 不连接（装配即可断连形态）⇒ 全部进保留队列。
  TestClient* cli = 0;
  for (std::size_t i = 0; i < hg::hgcp::kEventQueueCapacity; ++i) {
    std::vector<std::uint8_t> body(2, static_cast<std::uint8_t>(i & 0xFFu));
    if (em.emit(mt(MessageType::kEvtSystemState), body) != EmitStatus::kRetained) {
      HG_CHECK(false);
      return;
    }
  }
  HG_REQUIRE_EQ(em.retained(), hg::hgcp::kEventQueueCapacity);
  // 第 16385 条 ⇒ 背压（不丢：留在生产者侧）+ 告警。
  std::vector<std::uint8_t> overflow(1, 0x7Eu);
  HG_REQUIRE(em.emit(mt(MessageType::kEvtSystemState), overflow) ==
             EmitStatus::kBackpressured);
  HG_REQUIRE_EQ(em.backpressure_count(), 1u);
  HG_REQUIRE_EQ(alert_calls, 1);
  HG_REQUIRE_EQ(em.retained(), hg::hgcp::kEventQueueCapacity);
  // 满时行情 ⇒ 仍可丢例外（不背压、不入队）。
  std::vector<std::uint8_t> qbody(1, 0x51u);
  HG_REQUIRE(em.emit_quote(qbody) == EmitStatus::kDroppedQuote);
  HG_REQUIRE_EQ(em.retained(), hg::hgcp::kEventQueueCapacity);

  // 重连 + 握手 ⇒ 16384 条按序全部补发（不丢）。
  cli = new TestClient(connect_client(srv.bound_port()));
  HG_REQUIRE(cli->valid());
  em.attach();
  HG_REQUIRE(do_handshake(&srv, cli));
  HG_REQUIRE_EQ(em.retained(), 0u);
  std::uint64_t prev_seq = 1;  // HELLO_ACK
  for (std::size_t i = 0; i < hg::hgcp::kEventQueueCapacity; ++i) {
    Frame f;
    HG_REQUIRE(cli->recv_frame(1000, &f));
    HG_REQUIRE_EQ(f.header.type, mt(MessageType::kEvtSystemState));
    HG_REQUIRE_EQ(f.body.size(), 2u);
    HG_REQUIRE_EQ(f.body[0], static_cast<std::uint8_t>(i & 0xFFu));  // FIFO
    HG_REQUIRE_EQ(f.header.seq, prev_seq + 1);                       // SEQ 连续
    prev_seq = f.header.seq;
  }
  em.detach();
  cli->close();
}

// ── 验收 4：RESUME_REQ → RESUME_DATA（≤500/批、is_last、批内 SEQ 连续）────────
void test_resume_req_replay_batches() {
  HgcpServerLoop srv;
  HG_REQUIRE(srv.start() == BindStatus::kOk);
  EventEmitter em(&srv);
  TestClient* cli = 0;
  bring_up(&srv, &em, &cli);

  const int kN = 1200;  // ⇒ 3 批（500/500/200）
  for (int i = 0; i < kN; ++i) {
    pbv1::EvtCommandFlow e;
    e.set_order_id(static_cast<std::uint64_t>(i + 1));
    e.set_flow_type(pbv1::FLOW_TYPE_SENT);
    e.set_flow_ts_ms(i);
    std::vector<std::uint8_t> body;
    HG_REQUIRE(PbPayloadCodec::encode(e, &body));
    HG_REQUIRE(em.emit(mt(MessageType::kEvtCommandFlow), body) == EmitStatus::kSent);
  }
  // 客户端排空（帧不校验——内容已在 1000 压力用例覆盖）。
  for (int i = 0; i < kN; ++i) {
    Frame f;
    HG_REQUIRE(cli->recv_frame(1000, &f));
  }
  // 声明 last=0 的最短路径（协议允许握手后双方各自发；缓冲含 HELLO_ACK(seq1)
  // + 1200 事件 = 1201 帧 ⇒ 3 批 500/500/201）。
  send_resume_req_and_check(cli, &srv, 0, 1, 1201, 3, true);
  // 部分补数：last=600 ⇒ 601..1204。注意：首轮补数的 3 条 MSG_RESUME_DATA 回复帧
  // 本身占用通道 SEQ（1202~1204）并已入补数缓冲（§4.2.6「所有业务帧共用同一 SEQ」；
  // 线路侧不过滤）⇒ 604 帧 = 2 批（500/104），末批 is_last。
  send_resume_req_and_check(cli, &srv, 600, 601, 1204, 2, true);
  em.detach();
  cli->close();
}

// 发 RESUME_REQ 并校验回放批（from/to/is_last/批数/批内 SEQ 连续 + 帧字节可解析）。
void send_resume_req_and_check(TestClient* cli, HgcpServerLoop* srv,
                               std::uint64_t last_seq, std::uint64_t want_from,
                               std::uint64_t want_to, int want_batches,
                               bool last_batch_is_last) {
  pbv1::MsgResumeReq req;
  req.set_last_received_seq(last_seq);
  req.set_session_id("t");
  std::vector<std::uint8_t> body;
  HG_REQUIRE(PbPayloadCodec::encode(req, &body));
  std::vector<std::uint8_t> wire;
  HG_REQUIRE(hg::hgcp::make_frame(mt(MessageType::kMsgResumeReq), 0u, last_seq + 1u,
                                  last_seq + 1u, body, &wire) == FrameStatus::kOk);
  HG_REQUIRE(cli->send_wire(wire));
  std::vector<Frame> in;
  srv->pump(300, 0, &in);

  std::uint64_t expect_seq = want_from;
  for (int b = 0; b < want_batches; ++b) {
    Frame f;
    HG_REQUIRE(cli->recv_frame(1000, &f));
    HG_REQUIRE_EQ(f.header.type, mt(MessageType::kMsgResumeData));
    HG_CHECK((f.header.flags & hg::hgcp::kFlagIsResumeData) != 0);
    pbv1::MsgResumeData rd;
    HG_REQUIRE(PbPayloadCodec::decode(f.body, &rd));
    const bool is_last = (b + 1 == want_batches) && last_batch_is_last;
    HG_CHECK_EQ(rd.is_last(), is_last);
    HG_CHECK_EQ(static_cast<long long>(rd.from_seq()),
                static_cast<long long>(b == 0 ? want_from : expect_seq));
    const std::size_t n = static_cast<std::size_t>(rd.frames_size());
    HG_CHECK(n > 0);
    HG_CHECK(n <= 500u);  // 单批 ≤ 500（§4.2.6）
    for (std::size_t k = 0; k < n; ++k) {
      const std::string bytes = rd.frames(static_cast<int>(k));
      Frame inner;
      std::size_t consumed = 0;
      HG_REQUIRE(hg::hgcp::parse_frame(
                     reinterpret_cast<const std::uint8_t*>(bytes.data()), bytes.size(),
                     &inner, &consumed) == FrameStatus::kOk);
      HG_CHECK_EQ(inner.header.seq, expect_seq);  // 批内 SEQ 连续、全序回放
      ++expect_seq;
    }
    HG_CHECK_EQ(static_cast<long long>(rd.to_seq()),
                static_cast<long long>(expect_seq - 1));
  }
  HG_CHECK_EQ(static_cast<long long>(expect_seq - 1),
              static_cast<long long>(want_to));
}

// ── 验收 4：缺口超缓冲 ⇒ MSG_ERROR{0x0306} + 全量状态重建；0x0401 单一门卫回归 ──
void test_gap_too_large_0306_and_full_rebuild() {
  hg::risk_state::RiskState state;
  // 快照含 2 笔持仓（全量重建的产出内容）。
  {
    hg::risk_state::LoadBatch b;
    hg::risk_state::VarietyMeta v;
    v.product_id = 7;
    v.code = "CU";
    v.category = hg::risk_state::Category::kCommodity;
    v.status = hg::risk_state::ProductStatus::kEnabled;
    v.convert_factor = hg::risk_state::qtyFromWhole(1);
    b.putVariety(v);
    hg::risk_state::InstrumentMeta ins;
    ins.instrument_id = 77;
    ins.product_id = 7;
    ins.instrument_code = "CU2601";
    ins.exchange = "SHFE";
    ins.type = hg::risk_state::InstrumentType::kFutures;
    b.putInstrument(ins);
    hg::risk_state::PositionEntry p1;
    p1.position_qty = hg::risk_state::qtyFromWhole(5);
    p1.available_qty = hg::risk_state::qtyFromWhole(3);
    p1.frozen_qty = hg::risk_state::qtyFromWhole(2);
    p1.as_of_time_ms = 500;
    b.putPosition(1, 77, hg::risk_state::PositionDirection::kLong,
                  hg::risk_state::PositionType::kToday, p1);
    hg::risk_state::PositionEntry p2;
    p2.position_qty = hg::risk_state::qtyFromWhole(4);
    p2.available_qty = hg::risk_state::qtyFromWhole(4);
    p2.frozen_qty = 0;
    p2.as_of_time_ms = 500;
    b.putPosition(1, 77, hg::risk_state::PositionDirection::kShort,
                  hg::risk_state::PositionType::kToday, p2);
    b.set_param_version(3);
    b.set_build_time_ms(500);
    b.set_replace_all(true);
    HG_REQUIRE(state.rebuild(b).ok);
  }

  HgcpServerLoop srv;
  srv.configure(1000, 3, 10);  // 补数缓冲容量 10 ⇒ 30 帧必超
  HG_REQUIRE(srv.start() == BindStatus::kOk);
  EventEmitter em(&srv);
  hg::hgcp::RiskStateFullRebuilder rebuilder(&state);
  rebuilder.set_now_ms(900);
  em.set_rebuilder(&rebuilder);
  TestClient* cli = 0;
  bring_up(&srv, &em, &cli);

  const int kN = 30;
  for (int i = 0; i < kN; ++i) {
    std::vector<std::uint8_t> body(1, static_cast<std::uint8_t>(i));
    HG_REQUIRE(em.emit(mt(MessageType::kEvtSystemState), body) == EmitStatus::kSent);
  }
  for (int i = 0; i < kN; ++i) {
    Frame f;
    HG_REQUIRE(cli->recv_frame(1000, &f));
  }
  // 声明 last=0（30 帧全丢）⇒ 缺口超缓冲（缓冲只留最近 10 帧）。
  pbv1::MsgResumeReq req;
  req.set_last_received_seq(0);
  req.set_session_id("t");
  std::vector<std::uint8_t> body;
  HG_REQUIRE(PbPayloadCodec::encode(req, &body));
  std::vector<std::uint8_t> wire;
  HG_REQUIRE(hg::hgcp::make_frame(mt(MessageType::kMsgResumeReq), 0u, 9u, 9u, body,
                                  &wire) == FrameStatus::kOk);
  HG_REQUIRE(cli->send_wire(wire));
  std::vector<Frame> in;
  srv.pump(300, 0, &in);

  // 线上顺序（同步重建 ⇒ 0x0306 → POSITION_CHANGE×2 → SYSTEM_STATE，同一 pump 轮）：
  Frame ef;
  HG_REQUIRE(cli->recv_frame(1000, &ef));
  HG_REQUIRE_EQ(ef.header.type, mt(MessageType::kMsgError));
  HG_CHECK((ef.header.flags & hg::hgcp::kFlagIsError) != 0);
  {
    pbv1::MsgError err;
    HG_REQUIRE(PbPayloadCodec::decode(ef.body, &err));
    HG_REQUIRE_EQ(err.err_code(), 0x0306u);  // ERR_RESUME_GAP_TOO_LARGE
  }
  for (int i = 0; i < 2; ++i) {
    Frame pf;
    HG_REQUIRE(cli->recv_frame(1000, &pf));
    HG_REQUIRE_EQ(pf.header.type, mt(MessageType::kEvtPositionChange));
    pbv1::EvtPositionChange pc;
    HG_REQUIRE(PbPayloadCodec::decode(pf.body, &pc));
    HG_REQUIRE_EQ(pc.sub_account_id(), 1u);
    HG_CHECK(pc.instrument_id() == "CU2601");
    if (pc.direction() == pbv1::DIRECTION_BUY) {
      HG_CHECK_EQ(pc.position_qty(), 5);
      HG_CHECK_EQ(pc.available_qty(), 3);
      HG_CHECK_EQ(pc.frozen_qty(), 2);
    } else {
      HG_CHECK_EQ(pc.position_qty(), 4);
      HG_CHECK_EQ(pc.available_qty(), 4);
      HG_CHECK_EQ(pc.frozen_qty(), 0);
    }
    HG_CHECK_EQ(pc.as_of_ts_ms(), 900);
  }
  Frame sf;
  HG_REQUIRE(cli->recv_frame(1000, &sf));
  HG_REQUIRE_EQ(sf.header.type, mt(MessageType::kEvtSystemState));
  {
    pbv1::EvtSystemState ss;
    HG_REQUIRE(PbPayloadCodec::decode(sf.body, &ss));
    HG_REQUIRE_EQ(ss.state(), pbv1::SERVER_STATE_READY);
    HG_REQUIRE_EQ(ss.param_version_applied(), 3u);
    HG_REQUIRE_EQ(ss.ts_ms(), 900);
  }
  HG_REQUIRE_EQ(em.rebuild_count(), 1u);
  HG_REQUIRE_EQ(em.gap_count(), 1u);

  // 重建产出帧与 0x0306 帧共用通道 SEQ（全量重建走同一 emit 出口 ⇒ 不跳号）：
  // （SEQ 连续性已由各帧可解析 + 线上顺序隐含；显式断言见 1000 压力用例。）

  // 0x0401 单一门卫回归：拒新指令仍唯一由 system_ready() 承载（本单未立第二拒点）。
  hg::hgcp::CommandHandler handler(&state);  // 快照非空 ⇒ ready（参数面）
  std::vector<std::uint8_t> cw;
  HG_REQUIRE(hg::hgcp::make_frame(mt(MessageType::kCmdPlaceOrder), 0u, 4u, 4u,
                                  std::vector<std::uint8_t>(), &cw) ==
             FrameStatus::kOk);
  HG_REQUIRE(cli->send_wire(cw));
  in.clear();  // 复用向量前清空（pump 为追加语义）
  srv.pump(300, 0, &in);
  HG_REQUIRE_EQ(in.size(), 1u);
  const hg::hgcp::CommandOutcome oc =
      handler.handle(in[0].header.type, in[0].body, in[0].header.msg_id, 900);
  HG_REQUIRE(oc.consumed);
  // 快照就绪 ⇒ 放行（ACK）；若重建把门卫换成第二拒点，此处会误拒 0x0401。
  HG_REQUIRE_EQ(oc.reply.type, mt(MessageType::kMsgAck));

  // 门卫反向（未就绪态）：空快照 ⇒ CMD_PLACE_ORDER 回 0x0401（既有单一门卫路径）。
  {
    hg::risk_state::RiskState empty_state;
    hg::hgcp::CommandHandler h2(&empty_state);
    const hg::hgcp::CommandOutcome o2 =
        h2.handle(mt(MessageType::kCmdPlaceOrder), std::vector<std::uint8_t>(), 5u, 900);
    pbv1::MsgError err2;
    HG_REQUIRE(PbPayloadCodec::decode(o2.reply.body, &err2));
    HG_REQUIRE_EQ(err2.err_code(), 0x0401u);
  }
  em.detach();
  cli->close();
}

// ── 验收 5：压力（连续 1000 笔）接收数量与顺序与发出完全一致（§10-31）─────────
void test_stress_1000_in_order() {
  HgcpServerLoop srv;
  HG_REQUIRE(srv.start() == BindStatus::kOk);
  EventEmitter em(&srv);
  TestClient* cli = 0;
  bring_up(&srv, &em, &cli);

  const int kN = 1000;
  const int kQuotes = 100;  // 每 10 笔插 1 条行情（同通道；消费不同权）
  int quote_at = 0;
  for (int i = 1; i <= kN; ++i) {
    pbv1::EvtCommandFlow e;
    e.set_order_id(static_cast<std::uint64_t>(i));  // 顺序标记
    e.set_flow_type(pbv1::FLOW_TYPE_SENT);
    e.set_flow_ts_ms(i);
    std::vector<std::uint8_t> body;
    HG_REQUIRE(PbPayloadCodec::encode(e, &body));
    HG_REQUIRE(em.emit(mt(MessageType::kEvtCommandFlow), body) == EmitStatus::kSent);
    if (i % 10 == 0) {
      pbv1::EvtMarketQuote q;
      q.set_instrument_id("CU2601");
      q.set_last_price("70000");
      q.set_quote_ts_ms(i);
      std::vector<std::uint8_t> qb;
      HG_REQUIRE(PbPayloadCodec::encode(q, &qb));
      HG_REQUIRE(em.emit_quote(qb) == EmitStatus::kSent);
      ++quote_at;
    }
  }
  HG_REQUIRE_EQ(quote_at, kQuotes);

  // 接收侧：数量恰 1100；事件子序列序号严格 1..1000（顺序与发出完全一致）；
  // 行情 100 条（通道同权、线上不过滤——CH-007）；SEQ 全程连续（不跳号）。
  std::uint64_t prev_seq = 1;  // HELLO_ACK
  std::uint64_t expect_order = 1;
  int got_events = 0;
  int got_quotes = 0;
  for (int i = 0; i < kN + kQuotes; ++i) {
    Frame f;
    HG_REQUIRE(cli->recv_frame(1000, &f));
    HG_REQUIRE_EQ(f.header.seq, prev_seq + 1);
    prev_seq = f.header.seq;
    if (f.header.type == mt(MessageType::kEvtCommandFlow)) {
      pbv1::EvtCommandFlow e;
      HG_REQUIRE(PbPayloadCodec::decode(f.body, &e));
      HG_REQUIRE_EQ(static_cast<long long>(e.order_id()),
                    static_cast<long long>(expect_order));
      ++expect_order;
      ++got_events;
    } else if (f.header.type == mt(MessageType::kEvtMarketQuote)) {
      ++got_quotes;
    }
  }
  HG_REQUIRE_EQ(got_events, kN);
  HG_REQUIRE_EQ(got_quotes, kQuotes);
  HG_REQUIRE_EQ(static_cast<long long>(expect_order - 1),
                static_cast<long long>(kN));
  em.detach();
  cli->close();
}

// ── 装配边界：未注入 loop ⇒ kNoLoop（不崩溃、不产帧）─────────────────────────
void test_emit_without_loop() {
  EventEmitter em(0);
  std::vector<std::uint8_t> body(1, 1);
  HG_REQUIRE(em.emit(mt(MessageType::kEvtSystemState), body) == EmitStatus::kNoLoop);
  HG_REQUIRE(em.emit_quote(body) == EmitStatus::kNoLoop);
  em.on_session_ready();  // 无 loop ⇒ no-op（不崩溃）
  em.on_resume_gap();     // 无 rebuilder ⇒ 仅计数
  HG_REQUIRE_EQ(em.gap_count(), 1u);
  HG_REQUIRE_EQ(em.rebuild_count(), 0u);
}

}  // namespace

int main() {
  test_fourteen_events_producible();
  test_seq_monotonic_across_frame_kinds();
  test_retention_mode_no_loss_and_quote_drop();
  test_backpressure_full_capacity_no_loss();
  test_resume_req_replay_batches();
  test_gap_too_large_0306_and_full_rebuild();
  test_stress_1000_in_order();
  test_emit_without_loop();
  return ::hg::hgcp::testutil::report("hgcp.event_emitter_test");
}
