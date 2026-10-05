// T-176 SEQ 回退 / 重复检出（`0x0106 ERR_SEQ_OUT_OF_ORDER`）—— M2 / `ARCHITECTURE.md` §4.3.2:1343。
//
// 覆盖设计用例 docs/TEST_PLAN/M2.md：
//   · TC-M2-24 `SEQ` 回退 / 重复 ⇒ 命中 `0x0106`（触发条件明文含「`SEQ` ≤ 已收最大」）
//   · TC-M2-23 `SEQ` 跳号（缺口）：既有 gap 机制**保持不变**（本任务不在此回 `0x0106`）
//   · TC-M2-25 乱序：回退部分被检出并回码（**不做缓冲/重排** —— ARCH 未要求，勿自行扩scope）
//
// 判定面：
//   · **状态机层** `HgcpSession::note_received_seq`：回退 ⇒ `seq_out_of_order_`，
//     与前向缺口 `seq_gap_` **互斥**；正常前向两者皆不置；`last_received_seq_` 不回退。
//   · **装配层** `HgcpServerLoop`：回退 ⇒ 线上 `MSG_ERROR{0x0106}`（缺省
//     `PbTransportPolicy` 编码真实 `MsgError{err_code}`，§4.2.8.2）；**连接保持**。
#include "hg/hgcp/server_loop.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

#include "hg/hgcp/frame.h"
#include "hg/hgcp/payload_codec.h"
#include "hg/hgcp/protocol.h"
#include "hg/hgcp/session.h"
#include "hg/hgcp/tcp.h"
#include "hgcp.pb.h"
#include "hgcp_test_util.h"

// 前置断言：**单次求值**（避免 pump 等有副作用表达式被求值两次）。
#define HG_REQUIRE(cond)                                                       \
  do {                                                                         \
    const bool _hg_ok = static_cast<bool>(cond);                               \
    ::hg::hgcp::testutil::check_impl(_hg_ok, #cond, __FILE__, __LINE__);       \
    if (!_hg_ok) {                                                             \
      return;                                                                  \
    }                                                                          \
  } while (0)
#define HG_REQUIRE_EQ(a, b)                                                    \
  do {                                                                         \
    const long long _hg_a = static_cast<long long>(a);                         \
    const long long _hg_b = static_cast<long long>(b);                         \
    ::hg::hgcp::testutil::check_impl(_hg_a == _hg_b, #a " == " #b, __FILE__,   \
                                     __LINE__);                                \
    if (_hg_a != _hg_b) {                                                      \
      return;                                                                  \
    }                                                                          \
  } while (0)

namespace {

using hg::hgcp::BindStatus;
using hg::hgcp::ErrorCode;
using hg::hgcp::Frame;
using hg::hgcp::FrameStatus;
using hg::hgcp::HgcpServerLoop;
using hg::hgcp::HgcpSession;
using hg::hgcp::MessageType;
using hg::hgcp::PbPayloadCodec;
using hg::hgcp::TcpConfig;

namespace pbv1 = ::hgcp::v1;

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }
std::uint16_t fl(hg::hgcp::FlagBits b) { return static_cast<std::uint16_t>(b); }

std::vector<std::uint8_t> empty_body() { return std::vector<std::uint8_t>(); }

TcpConfig any_port_cfg() {
  TcpConfig c = hg::hgcp::default_tcp_config();
  c.port = 0;  // 内核分配（见 tcp.h「校验层级」）
  return c;
}

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
        return false;  // 帧内错误
      }
      if (!wait_readable(fd_, timeout_ms)) {
        return false;
      }
      std::uint8_t buf[1024];
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
  hg::hgcp::FrameStreamReader reader_;
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

std::uint16_t start_loop(HgcpServerLoop* srv) {
  if (srv->start() != BindStatus::kOk) {
    return 0;
  }
  return srv->bound_port();
}

bool send_opaque_seq(TestClient* cli, std::uint64_t seq) {
  std::vector<std::uint8_t> w;
  if (hg::hgcp::make_frame(0x0101, 0u, seq, seq, empty_body(), &w) != FrameStatus::kOk) {
    return false;
  }
  return cli->send_wire(w);
}

bool do_handshake(HgcpServerLoop* srv, TestClient* cli, std::vector<Frame>* in) {
  std::vector<std::uint8_t> hw;
  if (hg::hgcp::make_frame(mt(MessageType::kMsgHello), 0u, 1u, 1u, empty_body(), &hw) !=
      FrameStatus::kOk) {
    return false;
  }
  if (!cli->send_wire(hw)) {
    return false;
  }
  in->clear();
  if (srv->pump(300, 0, in) != 1) {
    return false;
  }
  Frame ack;
  return cli->recv_frame(1000, &ack);
}

// ── 状态机层：回退与前向缺口**互斥**；正常前向皆不置；last_received_seq 不回退 ──
void test_session_backstep_detection() {
  HgcpSession s;
  s.on_connected(0);

  // 正常前向 1→2→3：无缺口、无乱序
  s.note_received_seq(1);
  s.note_received_seq(2);
  s.note_received_seq(3);
  HG_CHECK_EQ(s.last_received_seq(), 3u);
  HG_CHECK(!s.seq_gap_pending());
  HG_CHECK(!s.seq_out_of_order());

  // 回退 / 重复：3 ≤ 已收最大 3 ⇒ 乱序信号；**不**被误判为前向缺口
  s.note_received_seq(3);
  HG_CHECK(s.seq_out_of_order());
  HG_CHECK(!s.seq_gap_pending());
  HG_CHECK_EQ(s.last_received_seq(), 3u);  // 已收最大不回退
  s.clear_seq_out_of_order();              // 一次性信号：装配层回码后清除
  HG_CHECK(!s.seq_out_of_order());

  // 前向跳号 5（缺 4）：置既有 gap 信号；**不**置乱序（不误伤既有机制）
  s.note_received_seq(5);
  HG_CHECK(s.seq_gap_pending());
  HG_CHECK(!s.seq_out_of_order());
  HG_CHECK_EQ(s.last_received_seq(), 5u);

  // 跳号后的回退：4 ≤ 5 ⇒ 乱序信号
  s.note_received_seq(4);
  HG_CHECK(s.seq_out_of_order());
  HG_CHECK_EQ(s.last_received_seq(), 5u);

  // on_connected 复位两个信号
  s.on_connected(0);
  HG_CHECK(!s.seq_out_of_order());
  HG_CHECK(!s.seq_gap_pending());
}

// ── 装配层：回退 ⇒ 线上 `MSG_ERROR{0x0106}`；前向/正常**不误报**；连接保持 ──
void test_server_emits_0x0106_on_backstep() {
  HgcpServerLoop srv(any_port_cfg());
  // **不注入**策略 ⇒ 缺省 `PbTransportPolicy` ⇒ 线上 ERROR body = 真实
  // `MsgError{err_code}`（CH-013 / §4.2.8），可逐字段断言码值。
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept
  HG_REQUIRE(do_handshake(&srv, &cli, &in));  // HELLO seq=1 ⇒ last_received_seq=1

  // 正常前向 2→3：不误报（无 0x0106）。逐帧 pump（避免同批 TCP 合帧的时序差异）。
  HG_REQUIRE(send_opaque_seq(&cli, 2u));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(!srv.session().seq_out_of_order());
  HG_REQUIRE(send_opaque_seq(&cli, 3u));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(!srv.session().seq_gap_pending());
  HG_CHECK(!srv.session().seq_out_of_order());
  Frame none;
  HG_CHECK(!cli.recv_frame(150, &none));

  // 前向缺口：seq=5（缺 4）⇒ 既有 gap 机制；**不**回 0x0106（既有行为保持）
  HG_REQUIRE(send_opaque_seq(&cli, 5u));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(srv.session().seq_gap_pending());
  HG_CHECK(!srv.session().seq_out_of_order());
  HG_CHECK(!cli.recv_frame(150, &none));

  // 回退 / 重复：seq=3（≤ 已收最大 5）⇒ 线上 `MSG_ERROR{0x0106}`
  HG_REQUIRE(send_opaque_seq(&cli, 3u));
  in.clear();
  // 回退帧仍交付（本层不做缓冲/重排 —— ARCH 未要求）
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(!srv.session().seq_out_of_order());  // 回码后即清（一次性）

  Frame err;
  HG_REQUIRE(cli.recv_frame(1000, &err));
  HG_CHECK_EQ(err.header.type, mt(MessageType::kMsgError));
  HG_CHECK((err.header.flags & fl(hg::hgcp::kFlagIsError)) != 0u);
  pbv1::MsgError em;
  HG_REQUIRE(PbPayloadCodec::decode(err.body, &em));
  HG_CHECK_EQ(em.err_code(), static_cast<std::uint32_t>(0x0106));
  HG_CHECK_EQ(static_cast<std::uint32_t>(ErrorCode::kErrSeqOutOfOrder), 0x0106u);

  // 连接保持（不误伤），且已收最大不回退
  HG_CHECK(srv.has_connection());
  HG_CHECK_EQ(srv.session().last_received_seq(), 5u);

  // 回码后继续正常前向（6）⇒ 不再误报
  HG_REQUIRE(send_opaque_seq(&cli, 6u));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(!srv.session().seq_out_of_order());
  HG_CHECK(!cli.recv_frame(150, &none));

  cli.close();
}

}  // namespace

int main() {
  test_session_backstep_detection();
  test_server_emits_0x0106_on_backstep();
  return hg::hgcp::testutil::report("seq_out_of_order_test");
}
