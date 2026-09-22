// T-032 承载层 —— **真实回环 socket** 端到端测试（**驱动器**）。
//
// 被测对象 = **生产侧** `HgcpServerLoop`（`src/server_loop.cc`：accept/读循环/握手门禁/
// 心跳/退避/补数/单连接 的装配）+ `tcp` 原语 + `session` 状态机。
// 本文件**只**负责：建连通（回环 socket）、注入时间、断言；**不承载任何生产装配逻辑**。
//
// 覆盖项（一项不少）：
//   · 回环绑定白名单（非回环拒启动）+ 端口 0（内核分配）
//   · 帧级握手（HELLO → HELLO_ACK）与回环收发
//   · 半包 / 粘包增量解码
//   · 重复连接拒 0x0302（既有连接不受影响）
//   · 心跳到点发送 + 连续 3 次未 ACK 判死（0x0301 告警）
//   · 断线重连退避序列（500→8s 上限，≥10 次严重告警）
//   · 补数批次装配（按 last_received_seq 回放；单批 ≤ 500）
//
// ⚠️ 全程**不解析消息字段**：字段级 schema 待 `CH-013`（见 `T032_GAP_ANALYSIS.md`）。
//    握手 / 控制帧 / 补数批的 body 均由 `TransportPolicy` 接缝注入**不透明字节**。
#include "hg/hgcp/server_loop.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "hgcp_test_util.h"

// 前置断言：失败即记 FAIL 并**从用例返回**，避免后续对空容器/未初始化帧取值（UB）。
// `HG_CHECK` 用于「可继续观察」的断言；`HG_REQUIRE` 用于「失败则后续无意义」的前置条件。
// ⚠️ **单次求值**（`cond` 只会被执行一次）——否则 `HG_REQUIRE(cli.send_wire(...))` 之类
//    带副作用的表达式会被执行两次。
#define HG_REQUIRE(cond)                                                        \
  do {                                                                          \
    const bool _hg_ok = static_cast<bool>(cond);                                \
    ::hg::hgcp::testutil::check_impl(_hg_ok, #cond, __FILE__, __LINE__);        \
    if (!_hg_ok) {                                                              \
      return;                                                                   \
    }                                                                           \
  } while (0)

// 整数/尺寸前置断言：**单次求值**（避免 pump 等有副作用表达式被求值两次）。
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

namespace {

using hg::hgcp::BindStatus;
using hg::hgcp::BufferedFrame;
using hg::hgcp::ErrorCode;
using hg::hgcp::Frame;
using hg::hgcp::FrameStatus;
using hg::hgcp::HgcpServerLoop;
using hg::hgcp::HgcpSession;
using hg::hgcp::MessageType;
using hg::hgcp::ReplayStatus;
using hg::hgcp::TcpConfig;
using hg::hgcp::TcpListener;
using hg::hgcp::TransportPolicy;

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }
std::uint16_t fl(hg::hgcp::FlagBits b) { return static_cast<std::uint16_t>(b); }
std::vector<std::uint8_t> empty_body() { return std::vector<std::uint8_t>(); }

// 端口 0 = 内核分配（测试用；见 tcp.h「校验层级」）。
TcpConfig any_port_cfg() {
  TcpConfig c = hg::hgcp::default_tcp_config();
  c.port = 0;
  return c;
}

// --- 测试侧：有界轮询（预算内等待可读；已可读则立即返回）---
bool wait_readable(int fd, int budget_ms) {
  if (budget_ms <= 0) {
    return hg::hgcp::poll_readable(fd, 0) == 1;
  }
  int waited = 0;
  while (waited < budget_ms) {
    const int slice = 5;
    if (hg::hgcp::poll_readable(fd, slice) == 1) {
      return true;
    }
    waited += slice;
  }
  return false;
}

// --- 测试客户端：回环连接 + 流式收帧（保留跨调用残余，粘包不丢帧）---
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

// 测试策略：为控制帧注入**非空**不透明 body（验证 schema 接缝确实被调用）。
class TestPolicy : public TransportPolicy {
 public:
  virtual std::vector<std::uint8_t> heartbeat_body(std::int64_t now_ms,
                                                   std::uint64_t /*last_received_seq*/) const {
    std::vector<std::uint8_t> v;
    v.push_back(0x50);  // 占位标记（非权威）
    v.push_back(static_cast<std::uint8_t>(now_ms & 0xFF));
    return v;
  }
  virtual std::vector<std::uint8_t> duplicate_connection_body() const {
    return std::vector<std::uint8_t>(3, 0x02);  // 占位标记
  }
  virtual std::vector<std::uint8_t> heartbeat_timeout_body() const {
    return std::vector<std::uint8_t>(1, 0x01);
  }
};

// 辅助：启动生产侧服务端循环（端口 0）并返回其端口。
std::uint16_t start_loop(HgcpServerLoop* srv) {
  if (srv->start() != BindStatus::kOk) {
    return 0;
  }
  return srv->bound_port();
}

// 辅助：完成一次帧级握手（客户端发 HELLO，服务端回 HELLO_ACK）。
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
  return cli->recv_frame(1000, &ack) && ack.header.type == mt(MessageType::kMsgHelloAck);
}

// -----------------------------------------------------------------------------
// 用例
// -----------------------------------------------------------------------------

void test_non_loopback_and_port0() {
  TcpListener l;
  TcpConfig bad = hg::hgcp::default_tcp_config();
  bad.bind_address = "0.0.0.0";
  HG_CHECK(l.start(bad) == BindStatus::kNonLoopbackRefused);  // 拒绝启动
  HG_CHECK(!l.listening());

  TcpConfig p0 = hg::hgcp::default_tcp_config();
  p0.port = 0;
  // 配置级口径不变（§4.2.1 端口来自配置）：`validate_bind` 视端口 0 为非法。
  HG_CHECK(hg::hgcp::validate_bind(p0) == BindStatus::kInvalidPort);
  // 原语层契约（tcp.h）：端口 0 = 内核分配，实际端口回填。
  HG_REQUIRE(l.start(p0) == BindStatus::kOk);
  HG_CHECK(l.listening());
  HG_CHECK(l.bound_port() != 0);
  l.stop();
  HG_CHECK(!l.listening());
}

void test_handshake_and_loopback() {
  HgcpServerLoop srv(any_port_cfg());
  TestPolicy pol;
  srv.set_policy(&pol);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);

  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  HG_REQUIRE_EQ(srv.pump(1000, 0, &in), 0);  // accept
  HG_CHECK(srv.has_connection());
  HG_CHECK_EQ(srv.accepted_connections(), 1);

  // 客户端发 MSG_HELLO（不透明 body）⇒ 帧级握手 ⇒ 回 HELLO_ACK
  const std::vector<std::uint8_t> hello = {0xAA, 0xBB, 0xCC};
  std::vector<std::uint8_t> w;
  HG_REQUIRE(hg::hgcp::make_frame(mt(MessageType::kMsgHello), 0u, 1u, 7u, hello, &w) ==
             FrameStatus::kOk);
  HG_REQUIRE(cli.send_wire(w));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_REQUIRE_EQ(in.size(), 1u);
  HG_CHECK_EQ(in[0].header.type, mt(MessageType::kMsgHello));
  HG_CHECK_EQ(in[0].header.msg_id, 7u);
  HG_CHECK(in[0].body == hello);  // 不透明 body 逐字节透传

  Frame ack;
  HG_REQUIRE(cli.recv_frame(1000, &ack));
  HG_CHECK_EQ(ack.header.type, mt(MessageType::kMsgHelloAck));
  HG_CHECK((ack.header.flags & fl(hg::hgcp::kFlagIsReply)) != 0u);
  HG_CHECK_EQ(ack.header.seq, 1u);  // 通道级 SEQ 从 1 起

  // 服务端 → 客户端（事件帧，不透明 body）
  const std::vector<std::uint8_t> evt = {1, 2, 3, 4};
  HG_REQUIRE(srv.send_frame(0x0203, 0u, evt));
  Frame got;
  HG_REQUIRE(cli.recv_frame(1000, &got));
  HG_CHECK_EQ(got.header.type, static_cast<std::uint16_t>(0x0203));
  HG_CHECK_EQ(got.header.seq, 2u);
  HG_CHECK(got.body == evt);

  cli.close();
}

void test_not_handshaken_rejected() {
  HgcpServerLoop srv(any_port_cfg());
  TestPolicy pol;
  srv.set_policy(&pol);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept

  // 握手前发业务帧（非 HELLO）⇒ 服务端按 §4.3.2 0x0107 拒并断连
  std::vector<std::uint8_t> bw;
  HG_REQUIRE(hg::hgcp::make_frame(0x0101, 0u, 1u, 5u, empty_body(), &bw) == FrameStatus::kOk);
  HG_REQUIRE(cli.send_wire(bw));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(!srv.has_connection());
  Frame err;
  HG_REQUIRE(cli.recv_frame(1000, &err));
  HG_CHECK_EQ(err.header.type, mt(MessageType::kMsgError));
  HG_CHECK((err.header.flags & fl(hg::hgcp::kFlagIsError)) != 0u);
  cli.close();
}

void test_half_and_sticky() {
  HgcpServerLoop srv(any_port_cfg());
  TestPolicy pol;
  srv.set_policy(&pol);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept
  HG_REQUIRE(do_handshake(&srv, &cli, &in));

  // 半包：先发 15 字节（不足 32B 帧头）⇒ 无完整帧
  const std::vector<std::uint8_t> body = {0xDE, 0xAD, 0xBE, 0xEF, 0x00};
  std::vector<std::uint8_t> f1;
  HG_REQUIRE(hg::hgcp::make_frame(0x0101, 0x0002, 2u, 11u, body, &f1) == FrameStatus::kOk);
  HG_REQUIRE(cli.send_wire(std::vector<std::uint8_t>(f1.begin(), f1.begin() + 15)));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 0);
  HG_CHECK_EQ(srv.frames_in(), 1);  // 仅握手那一帧
  // 补其余 ⇒ 一帧
  HG_REQUIRE(cli.send_wire(std::vector<std::uint8_t>(f1.begin() + 15, f1.end())));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK_EQ(in[0].header.seq, 2u);
  HG_CHECK(in[0].body == body);
  HG_CHECK_EQ(srv.session().last_received_seq(), 2u);

  // 粘包：一次写两帧 ⇒ 逐帧取出
  std::vector<std::uint8_t> f2, f3, both;
  HG_REQUIRE(hg::hgcp::make_frame(0x0102, 0u, 3u, 22u, body, &f2) == FrameStatus::kOk);
  HG_REQUIRE(hg::hgcp::make_frame(0x0103, 0u, 4u, 33u, body, &f3) == FrameStatus::kOk);
  both.insert(both.end(), f2.begin(), f2.end());
  both.insert(both.end(), f3.begin(), f3.end());
  HG_REQUIRE(cli.send_wire(both));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 2);
  HG_CHECK_EQ(in[0].header.seq, 3u);
  HG_CHECK_EQ(in[1].header.seq, 4u);
  HG_CHECK_EQ(srv.session().last_received_seq(), 4u);

  cli.close();
}

void test_duplicate_connection_rejected() {
  HgcpServerLoop srv(any_port_cfg());
  TestPolicy pol;
  srv.set_policy(&pol);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);

  TestClient c1(connect_client(port));
  HG_REQUIRE(c1.valid());
  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept c1
  HG_REQUIRE(do_handshake(&srv, &c1, &in));

  // 第二条连接 ⇒ 拒 0x0302 + 回 MSG_ERROR{IS_ERROR} 后关闭
  TestClient c2(connect_client(port));
  HG_REQUIRE(c2.valid());
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 0);
  HG_REQUIRE_EQ(srv.rejected_duplicates(), 1);
  HG_CHECK_EQ(srv.accepted_connections(), 1);  // 未接受第二条
  HG_CHECK(srv.has_connection());              // 既有连接不受影响

  Frame err;
  HG_REQUIRE(c2.recv_frame(1000, &err));
  HG_CHECK_EQ(err.header.type, mt(MessageType::kMsgError));
  HG_CHECK((err.header.flags & fl(hg::hgcp::kFlagIsError)) != 0u);
  HG_REQUIRE_EQ(err.body.size(), 3u);  // 策略注入的 dup body
  HG_CHECK_EQ(err.body[0], static_cast<std::uint8_t>(0x02));
  HG_CHECK_EQ(static_cast<std::uint32_t>(ErrorCode::kErrDuplicateConnection), 0x0302u);

  // 既有连接仍可收发
  HG_REQUIRE(srv.send_frame(0x0203, 0u, std::vector<std::uint8_t>(2, 0x5A)));
  Frame ev;
  HG_REQUIRE(c1.recv_frame(1000, &ev));
  HG_CHECK_EQ(ev.header.type, static_cast<std::uint16_t>(0x0203));

  c2.close();
  c1.close();
}

void test_heartbeat_send_and_timeout_death() {
  HgcpServerLoop srv(any_port_cfg());
  TestPolicy pol;
  srv.set_policy(&pol);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept @t=0
  HG_REQUIRE(do_handshake(&srv, &cli, &in));

  // t=1000 ⇒ 到点发心跳（body 由 TestPolicy 注入）
  HG_REQUIRE_EQ(srv.pump(50, 1000, &in), 0);
  Frame hb;
  HG_REQUIRE(cli.recv_frame(1000, &hb));
  HG_CHECK_EQ(hb.header.type, mt(MessageType::kMsgHeartbeat));
  HG_REQUIRE_EQ(hb.body.size(), 2u);
  HG_CHECK_EQ(hb.body[0], static_cast<std::uint8_t>(0x50));

  // 服务端主动发业务帧（不透明 body）
  HG_REQUIRE(srv.send_frame(0x0203, 0u, std::vector<std::uint8_t>(2, 0x11)));
  Frame ev;
  HG_REQUIRE(cli.recv_frame(1000, &ev));
  HG_CHECK_EQ(ev.header.type, static_cast<std::uint16_t>(0x0203));

  // 连续 3 次未收 ACK（t=3000）⇒ 判死：尽力回 0x0301 告警（body 由接缝注入）后关闭
  HG_REQUIRE_EQ(srv.pump(50, 3000, &in), 0);
  HG_CHECK(!srv.has_connection());
  Frame warn;
  HG_REQUIRE(cli.recv_frame(1000, &warn));
  HG_CHECK_EQ(warn.header.type, mt(MessageType::kMsgError));
  HG_CHECK((warn.header.flags & fl(hg::hgcp::kFlagIsError)) != 0u);
  HG_REQUIRE_EQ(warn.body.size(), 1u);
  HG_CHECK_EQ(warn.body[0], static_cast<std::uint8_t>(0x01));
  HG_CHECK_EQ(static_cast<std::uint32_t>(ErrorCode::kErrHeartbeatTimeout), 0x0301u);

  cli.close();
}

void test_reconnect_backoff() {
  HgcpServerLoop srv(any_port_cfg());
  TestPolicy pol;
  srv.set_policy(&pol);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept
  HG_REQUIRE(do_handshake(&srv, &cli, &in));

  // 客户端断开 ⇒ 服务端检出并装配退避序列
  cli.close();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 0);
  HG_REQUIRE(!srv.has_connection());
  HG_CHECK_EQ(srv.disconnects(), 1);
  HG_CHECK_EQ(srv.next_reconnect_delay_ms(), static_cast<std::int64_t>(500));
  HG_CHECK_EQ(srv.next_reconnect_delay_ms(), static_cast<std::int64_t>(1000));
  HG_CHECK_EQ(srv.next_reconnect_delay_ms(), static_cast<std::int64_t>(2000));
  HG_CHECK_EQ(srv.next_reconnect_delay_ms(), static_cast<std::int64_t>(4000));
  HG_CHECK_EQ(srv.next_reconnect_delay_ms(), static_cast<std::int64_t>(8000));
  HG_CHECK_EQ(srv.next_reconnect_delay_ms(), static_cast<std::int64_t>(8000));  // 封顶
  for (int i = 0; i < 5; ++i) {
    srv.next_reconnect_delay_ms();
  }
  HG_CHECK(srv.reconnect_severe());  // ≥10 次 ⇒ 严重告警
  srv.reset_reconnect();
  HG_CHECK_EQ(srv.reconnect_attempts(), 0);
}

void test_resume_batches() {
  // A) E2E：出站帧入补数缓冲 ⇒ 按对端 last_received_seq 装配补数批并回放
  HgcpServerLoop srv(any_port_cfg());
  TestPolicy pol;
  srv.set_policy(&pol);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());
  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept
  HG_REQUIRE(do_handshake(&srv, &cli, &in));
  HG_CHECK_EQ(srv.session().resume_buffered(), static_cast<std::size_t>(1));  // HELLO_ACK

  // 服务端发 5 帧（通道 SEQ 2..6，全部入补数缓冲）
  for (int i = 0; i < 5; ++i) {
    HG_REQUIRE(srv.send_frame(0x0203, 0u,
                              std::vector<std::uint8_t>(1, static_cast<std::uint8_t>(i))));
  }
  for (int i = 0; i < 5; ++i) {
    Frame ev;
    HG_REQUIRE(cli.recv_frame(1000, &ev));
  }
  HG_CHECK_EQ(srv.session().resume_buffered(), static_cast<std::size_t>(6));
  HG_CHECK_EQ(srv.session().last_sent_seq(), static_cast<std::uint64_t>(6));

  // 结构核验（session 级）：对端声明「已收到 SEQ=1」⇒ 回放 SEQ 2..6 = 1 批 5 帧
  std::vector<std::vector<BufferedFrame> > batches;
  HG_REQUIRE(srv.session().build_resume(1, &batches) == ReplayStatus::kOk);
  HG_REQUIRE_EQ(batches.size(), 1u);
  HG_REQUIRE_EQ(batches[0].size(), 5u);
  HG_CHECK_EQ(batches[0][0].seq, static_cast<std::uint64_t>(2));
  HG_CHECK_EQ(batches[0][4].seq, static_cast<std::uint64_t>(6));

  // 生产侧补数装配：经 `send_resume` 回放为 MSG_RESUME_DATA（body = 批内帧字节拼接）
  HG_CHECK(srv.send_resume(1) == ReplayStatus::kOk);
  Frame rd;
  HG_REQUIRE(cli.recv_frame(1000, &rd));
  HG_CHECK_EQ(rd.header.type, mt(MessageType::kMsgResumeData));
  HG_CHECK((rd.header.flags & fl(hg::hgcp::kFlagIsResumeData)) != 0u);
  HG_CHECK(!rd.body.empty());
  // 已齐 ⇒ 无补数（补数批本身亦占通道 SEQ：1×HELLO_ACK + 5×EVT + 1×RESUME_DATA = 7）
  const std::uint64_t sent = srv.session().last_sent_seq();
  HG_CHECK_EQ(sent, static_cast<std::uint64_t>(7));
  HG_CHECK(srv.send_resume(sent) == ReplayStatus::kNothingToReplay);
  cli.close();

  // B) 批次上限（单批 ≤ 500）：600 帧 ⇒ 2 批（500 + 100）
  HgcpSession s;
  s.on_connected(0);
  for (int i = 0; i < 600; ++i) {
    Frame f;
    HG_REQUIRE(s.make_outbound(0x0203, 0u, std::vector<std::uint8_t>(1, 0x7F), &f) ==
               FrameStatus::kOk);
  }
  HG_CHECK_EQ(s.resume_buffered(), static_cast<std::size_t>(600));
  std::vector<std::vector<BufferedFrame> > bs;
  HG_REQUIRE(s.build_resume(0, &bs) == ReplayStatus::kOk);
  HG_REQUIRE_EQ(bs.size(), 2u);
  HG_CHECK_EQ(bs[0].size(), static_cast<std::size_t>(500));
  HG_CHECK_EQ(bs[1].size(), static_cast<std::size_t>(100));
  HG_CHECK_EQ(bs[0][0].seq, static_cast<std::uint64_t>(1));
  HG_CHECK_EQ(bs[1][99].seq, static_cast<std::uint64_t>(600));
  HG_CHECK(s.build_resume(600, &bs) == ReplayStatus::kNothingToReplay);
}

}  // namespace

int main() {
  test_non_loopback_and_port0();
  test_handshake_and_loopback();
  test_not_handshaken_rejected();
  test_half_and_sticky();
  test_duplicate_connection_rejected();
  test_heartbeat_send_and_timeout_death();
  test_reconnect_backoff();
  test_resume_batches();
  return hg::hgcp::testutil::report("transport_test");
}
