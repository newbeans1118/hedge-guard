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
//   · 帧超限回 0x0308 后断连（§4.2.8.8 `I-2` 行为面）
//   · 心跳到点发送 + 连续 3 次未 ACK 判死（0x0301 告警）
//   · 断线重连退避序列（500→8s 上限，≥10 次严重告警）
//   · 补数批次装配（按 last_received_seq 回放；单批 ≤ 500）
//   · 补数体按 §4.2.8.2 `MsgResumeData` 编码（`from_seq`/`to_seq`/`is_last`/`frames`；往返）
//
// ⚠️ 本文件主要**验证帧层/承载层**（不解释字段）：握手 / 控制帧 / 补数批的 body 多由
//    `TestPolicy` 注入**不透明字节**；而**服务端缺省策略** = `PbTransportPolicy`
//    （基于 `PbPayloadCodec` / `proto/hgcp.proto`，`CH-013` / §4.2.8）——握手门禁
//    `0x0107` 的线上 ERROR 帧由它编码为真实 `MsgError{err_code}`（见 `test_not_handshaken_rejected`）。
#include "hg/hgcp/server_loop.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "hg/hgcp/auth.h"
#include "hg/hgcp/payload_codec.h"
#include "hg/hgcp/pb_transport_policy.h"
#include "hgcp_test_util.h"

// T-208：跨侧共享 golden 向量路径（configure 期注入绝对路径；见 CMakeLists.txt）。
#ifndef HG_HELLO_ACK_GOLDEN
#define HG_HELLO_ACK_GOLDEN "hello_ack_bodies.tsv"
#endif

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
using hg::hgcp::PbPayloadCodec;
using hg::hgcp::PbTransportPolicy;
using hg::hgcp::ReplayStatus;
using hg::hgcp::TcpConfig;
using hg::hgcp::TcpListener;
using hg::hgcp::TransportPolicy;

// protoc 生成命名空间（proto `package hgcp.v1` ⇒ `::hgcp::v1`）
namespace pbv1 = ::hgcp::v1;

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }
std::uint16_t fl(hg::hgcp::FlagBits b) { return static_cast<std::uint16_t>(b); }
std::vector<std::uint8_t> empty_body() { return std::vector<std::uint8_t>(); }

// T-208：TSV 解析（# 与空行跳过；列以 \t 分隔；与 risk_engine golden 同形）。
//   仅用于读**跨侧共享夹具**（非判定路径）。
std::vector<std::vector<std::string> > goldenRows(const char* path) {
  std::vector<std::vector<std::string> > rows;
  std::ifstream in(path);
  if (!in.is_open()) {
    std::printf("[FAIL] golden 文件不可读: %s\n", path);
    ++hg::hgcp::testutil::failures();
    return rows;
  }
  std::string line;
  while (std::getline(in, line)) {
    if (!line.empty() && line[0] == '\xEF') {  // 剥离 UTF-8 BOM（若编辑工具引入）
      line = line.substr(3);
    }
    if (line.empty() || line[0] == '#') continue;
    std::vector<std::string> cols;
    std::string cur;
    std::istringstream ss(line);
    while (std::getline(ss, cur, '\t')) cols.push_back(cur);
    if (!cols.empty() && cols[0] == "label") continue;  // 表头行
    rows.push_back(cols);
  }
  return rows;
}

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
  virtual std::vector<std::uint8_t> frame_too_large_body() const {
    return std::vector<std::uint8_t>(5, 0x08);  // 占位标记（非权威）
  }
  virtual std::vector<std::uint8_t> error_body(std::uint32_t err_code) const {
    // 占位标记（非权威）：4 字节大端 code；用于验证接缝确实被调用。
    std::vector<std::uint8_t> v(4, 0u);
    v[0] = static_cast<std::uint8_t>((err_code >> 24) & 0xFFu);
    v[1] = static_cast<std::uint8_t>((err_code >> 16) & 0xFFu);
    v[2] = static_cast<std::uint8_t>((err_code >> 8) & 0xFFu);
    v[3] = static_cast<std::uint8_t>(err_code & 0xFFu);
    return v;
  }
  virtual std::vector<std::uint8_t> resume_data_body(
      std::uint64_t /*from_seq*/, std::uint64_t /*to_seq*/, bool /*is_last*/,
      const std::vector<std::vector<std::uint8_t> >& frames) const {
    // 占位标记（非权威）：非空不透明 body，仅验证 `TransportPolicy` 接缝确实被调用。
    // （真实 `MsgResumeData` 编码见 `PbTransportPolicy` / `test_resume_data_body_schema`。）
    std::vector<std::uint8_t> v;
    v.push_back(0x52);  // 'R' 占位
    v.push_back(static_cast<std::uint8_t>(frames.size() & 0xFFu));
    return v;
  }
  // T-208 出站侧接缝：非空**可判别**占位 body（前 4 字节 'H','A','C','K'）——
  //   仅用于验证 `hello_ack_body` 接缝确实被调用（且**不再走 empty_body**）。
  //   真实 `MsgHelloAck` 逐字段编码见 `PbTransportPolicy` / `test_hello_ack_body_schema`。
  virtual std::vector<std::uint8_t> hello_ack_body(
      bool accepted, std::uint32_t /*server_proto_version*/,
      const std::string& /*session_id*/, std::int32_t /*server_state*/,
      std::uint64_t /*param_version*/, std::uint32_t /*reason_code*/) const {
    std::vector<std::uint8_t> v;
    v.push_back(0x48);  // 'H'
    v.push_back(0x41);  // 'A'
    v.push_back(0x43);  // 'C'
    v.push_back(0x4B);  // 'K'
    v.push_back(accepted ? 0x01 : 0x00);  // 承接 `accepted`（占位；非权威编码）
    return v;
  }
  // T-171 入站侧接缝：测试策略返回空串（不供真实 token）；无 token 语义的
  // 既有用例不配 verifier ⇒ 恒不触达。
  virtual std::string hello_auth_token(
      const std::vector<std::uint8_t>& /*hello_body*/) const {
    return std::string();
  }
  // T-175 入站侧接缝：测试策略不解析（版本返回 0 / 启动标识空串）。
  virtual std::uint32_t hello_token_version(
      const std::vector<std::uint8_t>& /*hello_body*/) const {
    return 0u;
  }
  virtual std::string hello_client_boot_id(
      const std::vector<std::uint8_t>& /*hello_body*/) const {
    return std::string();
  }
  virtual std::vector<std::uint8_t> auth_fail_body(std::uint32_t /*reason_code*/) const {
    return std::vector<std::uint8_t>(1, 0x41);  // 'A' 占位标记
  }
  // T-039 入站侧接缝：测试策略不解析（返回 false ⇒ 调用方按 0 = 从头补数）。
  virtual bool resume_req_last_seq(const std::vector<std::uint8_t>& /*body*/,
                                   std::uint64_t* /*last_received_seq*/) const {
    return false;
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
  // T-208：**内容断言**（原仅断言帧类型 ⇒ 本缺陷长期静默的判别力缺口）。
  //   注入策略的 `hello_ack_body` 恒产出「'HACK' + accepted 字节」的非空体 ⇒
  //   若产生点退回 `empty_body()`（变异①）此处必红。真实 `MsgHelloAck` 编码见
  //   `test_hello_ack_body_schema`。
  HG_CHECK(!ack.body.empty());
  HG_REQUIRE_EQ(ack.body.size(), 5u);
  HG_CHECK_EQ(ack.body[0], static_cast<std::uint8_t>(0x48));  // 'H'
  HG_CHECK_EQ(ack.body[1], static_cast<std::uint8_t>(0x41));  // 'A'
  HG_CHECK_EQ(ack.body[2], static_cast<std::uint8_t>(0x43));  // 'C'
  HG_CHECK_EQ(ack.body[3], static_cast<std::uint8_t>(0x4B));  // 'K'
  HG_CHECK_EQ(ack.body[4], static_cast<std::uint8_t>(0x01));  // accepted = true

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
  // **不注入**策略 ⇒ 采用服务端缺省 `PbTransportPolicy`（CH-013 / §4.2.8）：
  // 线上 ERROR 帧 body = 真实 protobuf `MsgError{err_code}`。
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
  // O-1 延伸（CH-013）：**线上 ERROR 帧须携带错误码** ⇒ 握手前业务帧拒 = `0x0107`
  pbv1::MsgError em;
  HG_REQUIRE(PbPayloadCodec::decode(err.body, &em));
  HG_CHECK_EQ(em.err_code(), 0x0107u);
  HG_CHECK_EQ(static_cast<std::uint32_t>(ErrorCode::kErrNotHandshaken), 0x0107u);
  cli.close();
}

// --- T-171（FR-CONFIG-9 验收 4）：服务端令牌校验接线 ---

// 测试审计接收方：只记「结果 / 对端 / 时刻」，不承载令牌信息（对齐 §4.2.6 `:947`）。
class RecordingAuditSink : public hg::hgcp::AuthAuditSink {
 public:
  RecordingAuditSink() : calls(0), last_result(hg::hgcp::AuthResult::kAccepted) {}
  virtual void on_auth_result(hg::hgcp::AuthResult result, const std::string& peer,
                              std::int64_t now_ms) {
    ++calls;
    last_result = result;
    last_peer = peer;
    last_now_ms = now_ms;
  }
  int calls;
  hg::hgcp::AuthResult last_result;
  std::string last_peer;
  std::int64_t last_now_ms;
};

// 辅助：组一帧 `MsgHello`（CH-013 / §4.2.8.2）并上线发送。
bool send_hello_full(TestClient* cli, const std::string& token,
                     const std::string& boot_id, std::uint32_t token_version) {
  pbv1::MsgHello h;
  h.set_proto_version(hg::hgcp::kWireVersion);
  h.set_component_id("hg-biz");
  h.set_component_role(pbv1::ROLE_JAVA);
  h.set_auth_token(token);
  h.set_client_boot_id(boot_id);
  h.set_last_received_seq(0u);
  h.set_token_version(token_version);
  std::vector<std::uint8_t> body;
  if (!PbPayloadCodec::encode(h, &body)) {
    return false;
  }
  std::vector<std::uint8_t> w;
  if (hg::hgcp::make_frame(mt(MessageType::kMsgHello), 0u, 1u, 1u, body, &w) !=
      FrameStatus::kOk) {
    return false;
  }
  return cli->send_wire(w);
}

// 兼容既有用例：缺省 boot_id = "boot-t171"、token_version = 1。
bool send_hello(TestClient* cli, const std::string& token) {
  return send_hello_full(cli, token, "boot-t171", 1u);
}

// 用例 1：错 token ⇒ `MSG_AUTH_FAIL`(0x0003, body=MsgAuthFail{0x0201}) + 断连 + 审计留痕。
void test_auth_bad_token_rejected() {
  HgcpServerLoop srv(any_port_cfg());
  const hg::hgcp::TokenVerifier verifier = hg::hgcp::TokenVerifier::from_token("s3cret-token");
  RecordingAuditSink sink;
  srv.set_token_verifier(&verifier);
  srv.set_auth_audit_sink(&sink);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  HG_REQUIRE_EQ(srv.pump(1000, 0, &in), 0);  // accept
  HG_REQUIRE(send_hello(&cli, "wrong-token"));
  in.clear();
  // HELLO（计 1 帧）+ AUTH_FAIL 回发（尽力发，本回环下可达 ⇒ 也可能计 2）
  const int d = srv.pump(300, 0, &in);
  HG_REQUIRE(d == 1 || d == 2);
  HG_CHECK(!srv.has_connection());  // 已断连（§4.2.6 `:956` 立即关闭）
  HG_CHECK_EQ(srv.disconnects(), 1);

  Frame fail;
  HG_REQUIRE(cli.recv_frame(1000, &fail));
  HG_CHECK_EQ(fail.header.type, mt(MessageType::kMsgAuthFail));
  HG_CHECK((fail.header.flags & fl(hg::hgcp::kFlagIsError)) != 0u);
  pbv1::MsgAuthFail af;
  HG_REQUIRE(PbPayloadCodec::decode(fail.body, &af));
  HG_CHECK_EQ(af.reason_code(), 0x0201u);  // ERR_AUTH_FAILED（error_code_for(kBadToken)）
  HG_CHECK(af.detail().find("s3cret") == std::string::npos);   // 令牌不落 detail（NFR-SEC-1）
  HG_CHECK(af.detail().find("wrong-token") == std::string::npos);

  // 审计留痕：verify_audited 失败落痕一次；结果/对端/时刻由 sink 承载。
  HG_CHECK_EQ(sink.calls, 1);
  HG_CHECK(sink.last_result == hg::hgcp::AuthResult::kBadToken);
  HG_CHECK(!sink.last_peer.empty());
  cli.close();
}

// 用例 2：缺 token（空串）⇒ `MSG_AUTH_FAIL`(`MsgAuthFail{0x0202}`) + 断连。
void test_auth_missing_token_rejected() {
  HgcpServerLoop srv(any_port_cfg());
  const hg::hgcp::TokenVerifier verifier = hg::hgcp::TokenVerifier::from_token("s3cret-token");
  RecordingAuditSink sink;
  srv.set_token_verifier(&verifier);
  srv.set_auth_audit_sink(&sink);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  HG_REQUIRE_EQ(srv.pump(1000, 0, &in), 0);  // accept
  HG_REQUIRE(send_hello(&cli, ""));          // auth_token 为空（schema 允许，鉴权判缺）
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(!srv.has_connection());
  HG_CHECK_EQ(srv.disconnects(), 1);

  Frame fail;
  HG_REQUIRE(cli.recv_frame(1000, &fail));
  HG_CHECK_EQ(fail.header.type, mt(MessageType::kMsgAuthFail));
  pbv1::MsgAuthFail af;
  HG_REQUIRE(PbPayloadCodec::decode(fail.body, &af));
  HG_CHECK_EQ(af.reason_code(), 0x0202u);  // ERR_AUTH_TOKEN_MISSING（error_code_for(kMissingToken)）
  HG_CHECK_EQ(sink.calls, 1);
  HG_CHECK(sink.last_result == hg::hgcp::AuthResult::kMissingToken);
  cli.close();
}

// 用例 3：正确 token ⇒ `HelloAck`、连接保持；审计**不**落痕（成功不留痕）。
void test_auth_good_token_accepted() {
  HgcpServerLoop srv(any_port_cfg());
  const hg::hgcp::TokenVerifier verifier = hg::hgcp::TokenVerifier::from_token("s3cret-token");
  RecordingAuditSink sink;
  srv.set_token_verifier(&verifier);
  srv.set_auth_audit_sink(&sink);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  HG_REQUIRE_EQ(srv.pump(1000, 0, &in), 0);  // accept
  HG_REQUIRE(send_hello(&cli, "s3cret-token"));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(srv.has_connection());  // 未断连
  HG_CHECK_EQ(srv.disconnects(), 0);

  Frame ack;
  HG_REQUIRE(cli.recv_frame(1000, &ack));
  HG_CHECK_EQ(ack.header.type, mt(MessageType::kMsgHelloAck));
  HG_CHECK((ack.header.flags & fl(hg::hgcp::kFlagIsReply)) != 0u);
  HG_CHECK_EQ(sink.calls, 0);  // 成功不落痕（§4.2.6 `:947` / auth.h 契约）
  cli.close();
}

// 用例 4（回归守卫）：未配置 verifier ⇒ 维持既有帧级门禁（不透明 body 的 HELLO 也放行）。
void test_auth_unconfigured_keeps_frame_gate() {
  HgcpServerLoop srv(any_port_cfg());
  // **不**调 set_token_verifier（缺省 null = 不启用）；审计 sink 同理。
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  HG_REQUIRE_EQ(srv.pump(1000, 0, &in), 0);  // accept
  // 既有形态：HELLO body 不透明（test_handshake_and_loopback 同构），无 token 语义 ⇒ 放行
  const std::vector<std::uint8_t> hello = {0xAA, 0xBB, 0xCC};
  std::vector<std::uint8_t> w;
  HG_REQUIRE(hg::hgcp::make_frame(mt(MessageType::kMsgHello), 0u, 1u, 7u, hello, &w) ==
             FrameStatus::kOk);
  HG_REQUIRE(cli.send_wire(w));
  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
  HG_CHECK(srv.has_connection());
  HG_CHECK_EQ(srv.disconnects(), 0);
  Frame ack;
  HG_REQUIRE(cli.recv_frame(1000, &ack));
  HG_CHECK_EQ(ack.header.type, mt(MessageType::kMsgHelloAck));
  cli.close();
}

// 用例 5（T-175 / TC-M2-10）：token_version 不符 ⇒ `MSG_AUTH_FAIL{0x0203}` + 断连 + 审计。
void test_auth_token_version_mismatch_rejected() {
  HgcpServerLoop srv(any_port_cfg());
  const hg::hgcp::TokenVerifier verifier = hg::hgcp::TokenVerifier::from_token("s3cret-token");
  RecordingAuditSink sink;
  srv.set_token_verifier(&verifier);
  srv.set_expected_token_version(2u);  // 期望版本 2
  srv.set_auth_audit_sink(&sink);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  HG_REQUIRE_EQ(srv.pump(1000, 0, &in), 0);  // accept
  HG_REQUIRE(send_hello_full(&cli, "s3cret-token", "boot-v", 1u));  // 版本 1 ≠ 2
  in.clear();
  const int d = srv.pump(300, 0, &in);
  HG_REQUIRE(d == 1 || d == 2);
  HG_CHECK(!srv.has_connection());
  HG_CHECK_EQ(srv.disconnects(), 1);

  Frame fail;
  HG_REQUIRE(cli.recv_frame(1000, &fail));
  HG_CHECK_EQ(fail.header.type, mt(MessageType::kMsgAuthFail));
  pbv1::MsgAuthFail af;
  HG_REQUIRE(PbPayloadCodec::decode(fail.body, &af));
  HG_CHECK_EQ(af.reason_code(), 0x0203u);  // ERR_AUTH_TOKEN_VERSION
  HG_CHECK_EQ(sink.calls, 1);
  HG_CHECK(sink.last_result == hg::hgcp::AuthResult::kTokenVersionMismatch);
  cli.close();
}

// 用例 6（T-175 / TC-M2-11）：同一 client_boot_id 在窗口内重复建连 ⇒ 第 2 次 0x0204。
void test_auth_replay_same_boot_id_rejected() {
  HgcpServerLoop srv(any_port_cfg());
  const hg::hgcp::TokenVerifier verifier = hg::hgcp::TokenVerifier::from_token("s3cret-token");
  RecordingAuditSink sink;
  srv.set_token_verifier(&verifier);
  srv.set_expected_token_version(1u);
  srv.set_replay_window_ms(1000);
  srv.set_auth_audit_sink(&sink);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  // 第 1 次：通过（boot_id 台账登记于 now=1000）
  {
    TestClient cli(connect_client(port));
    HG_REQUIRE(cli.valid());
    std::vector<Frame> in;
    HG_REQUIRE_EQ(srv.pump(1000, 1000, &in), 0);  // accept
    HG_REQUIRE(send_hello_full(&cli, "s3cret-token", "boot-rep", 1u));
    in.clear();
    HG_REQUIRE_EQ(srv.pump(300, 1000, &in), 1);
    HG_CHECK(srv.has_connection());
    Frame ack;
    HG_REQUIRE(cli.recv_frame(1000, &ack));
    HG_CHECK_EQ(ack.header.type, mt(MessageType::kMsgHelloAck));
    HG_CHECK_EQ(sink.calls, 0);
    cli.close();
  }
  {  // 服务端观测对端关闭（释放单连接守卫）
    std::vector<Frame> in;
    srv.pump(300, 1000, &in);
  }
  HG_CHECK(!srv.has_connection());
  // 第 2 次：同一 boot_id、窗口内（1100 - 1000 = 100 < 1000）⇒ 重放
  {
    TestClient cli(connect_client(port));
    HG_REQUIRE(cli.valid());
    std::vector<Frame> in;
    HG_REQUIRE_EQ(srv.pump(1000, 1100, &in), 0);  // accept
    HG_REQUIRE(send_hello_full(&cli, "s3cret-token", "boot-rep", 1u));
    in.clear();
    srv.pump(300, 1100, &in);
    HG_CHECK(!srv.has_connection());
    Frame fail;
    HG_REQUIRE(cli.recv_frame(1000, &fail));
    HG_CHECK_EQ(fail.header.type, mt(MessageType::kMsgAuthFail));
    pbv1::MsgAuthFail af;
    HG_REQUIRE(PbPayloadCodec::decode(fail.body, &af));
    HG_CHECK_EQ(af.reason_code(), 0x0204u);  // ERR_AUTH_REPLAY
    HG_CHECK_EQ(sink.calls, 1);
    HG_CHECK(sink.last_result == hg::hgcp::AuthResult::kReplay);
    cli.close();
  }
}

// 用例 7（T-175）：版本相符 + 新 boot_id ⇒ 通过；同一 boot_id 超出窗口后亦通过。
void test_auth_version_and_boot_normal_accepted() {
  HgcpServerLoop srv(any_port_cfg());
  const hg::hgcp::TokenVerifier verifier = hg::hgcp::TokenVerifier::from_token("s3cret-token");
  RecordingAuditSink sink;
  srv.set_token_verifier(&verifier);
  srv.set_expected_token_version(1u);
  srv.set_replay_window_ms(500);
  srv.set_auth_audit_sink(&sink);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  {
    TestClient cli(connect_client(port));
    HG_REQUIRE(cli.valid());
    std::vector<Frame> in;
    HG_REQUIRE_EQ(srv.pump(1000, 0, &in), 0);
    HG_REQUIRE(send_hello_full(&cli, "s3cret-token", "boot-w", 1u));
    in.clear();
    HG_REQUIRE_EQ(srv.pump(300, 0, &in), 1);
    Frame ack;
    HG_REQUIRE(cli.recv_frame(1000, &ack));
    HG_CHECK_EQ(ack.header.type, mt(MessageType::kMsgHelloAck));
    cli.close();
  }
  { std::vector<Frame> in; srv.pump(300, 0, &in); }
  HG_CHECK(!srv.has_connection());
  {  // 同一 boot_id，但 1000 - 0 = 1000 > 500 ⇒ 已出窗口，通过
    TestClient cli(connect_client(port));
    HG_REQUIRE(cli.valid());
    std::vector<Frame> in;
    HG_REQUIRE_EQ(srv.pump(1000, 1000, &in), 0);
    HG_REQUIRE(send_hello_full(&cli, "s3cret-token", "boot-w", 1u));
    in.clear();
    HG_REQUIRE_EQ(srv.pump(300, 1000, &in), 1);
    HG_CHECK(srv.has_connection());
    Frame ack;
    HG_REQUIRE(cli.recv_frame(1000, &ack));
    HG_CHECK_EQ(ack.header.type, mt(MessageType::kMsgHelloAck));
    HG_CHECK_EQ(sink.calls, 0);  // 全程成功不落痕
    cli.close();
  }
}

// 缺省策略（`PbTransportPolicy`）的出站 body 可被真实 `PbPayloadCodec` 解码
// （`MSG_HEARTBEAT` / `MSG_ERROR` 的 `err_code`）。
void test_pb_transport_policy_bodies() {
  PbTransportPolicy pol;
  pbv1::MsgError e;
  HG_REQUIRE(PbPayloadCodec::decode(pol.duplicate_connection_body(), &e));
  HG_CHECK_EQ(e.err_code(), 0x0302u);  // ERR_DUPLICATE_CONNECTION
  HG_REQUIRE(PbPayloadCodec::decode(pol.heartbeat_timeout_body(), &e));
  HG_CHECK_EQ(e.err_code(), 0x0301u);  // ERR_HEARTBEAT_TIMEOUT
  HG_REQUIRE(PbPayloadCodec::decode(pol.error_body(0x0107u), &e));
  HG_CHECK_EQ(e.err_code(), 0x0107u);

  pbv1::MsgHeartbeat hb;
  HG_REQUIRE(PbPayloadCodec::decode(pol.heartbeat_body(1758384000000LL, 7u), &hb));
  HG_CHECK_EQ(hb.sent_ts_ms(), 1758384000000LL);
  HG_CHECK_EQ(hb.last_received_seq(), 7u);
  HG_CHECK(hb.component_state() == pbv1::COMPONENT_STATE_READY);

  HG_REQUIRE(PbPayloadCodec::decode(pol.frame_too_large_body(), &e));
  HG_CHECK_EQ(e.err_code(), 0x0308u);  // ERR_FRAME_TOO_LARGE
  HG_CHECK(!e.err_msg().empty());      // 「提示使用分片」经 err_msg 承载
}

// §4.2.8.8 `I-2`（行为面裁定）：`LEN > 1 MiB` ⇒ 尽力回 `MSG_ERROR{err_code=0x0308}`
// （提示分片经 `err_msg`）→ 随后**关闭连接**。
void test_frame_too_large_replies_0x0308() {
  HgcpServerLoop srv(any_port_cfg());
  // 不注入策略 ⇒ 服务端缺省 `PbTransportPolicy`（真实 protobuf body）。
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept

  // 手工构造「帧头声明 LEN > 1 MiB」的 32 字节头
  // （`decode_header` 在 CRC 之前即判超限；无需真实超大 payload）。
  hg::hgcp::FrameHeader h;
  h.ver = hg::hgcp::kWireVersion;
  h.type = 0x0101;
  h.flags = 0;
  h.reserved = 0;
  h.seq = 1;
  h.msg_id = 1;
  h.len = hg::hgcp::kMaxPayloadSize + 1u;  // 越限
  std::uint8_t hdr[hg::hgcp::kHeaderSize];
  hg::hgcp::encode_header(h, hdr);
  HG_REQUIRE(
      cli.send_wire(std::vector<std::uint8_t>(hdr, hdr + hg::hgcp::kHeaderSize)));

  in.clear();
  HG_REQUIRE_EQ(srv.pump(300, 0, &in), 0);
  HG_CHECK(!srv.has_connection());  // 已关闭（I-2：回码后关闭）

  Frame err;
  HG_REQUIRE(cli.recv_frame(1000, &err));
  HG_CHECK_EQ(err.header.type, mt(MessageType::kMsgError));
  HG_CHECK((err.header.flags & fl(hg::hgcp::kFlagIsError)) != 0u);
  pbv1::MsgError em;
  HG_REQUIRE(PbPayloadCodec::decode(err.body, &em));
  HG_CHECK_EQ(em.err_code(), 0x0308u);
  HG_CHECK_EQ(static_cast<std::uint32_t>(ErrorCode::kErrFrameTooLarge), 0x0308u);
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

// T-177：重连累计失败跨入严重档（>= 10，0x0303 ERR_RECONNECT_LIMIT）告警产生点。
//   断言：跨档首刻产生一帧 MSG_ERROR{0x0303} + 运维告警回调一次；同一严重档不重复刷屏；
//   未达严重档不产生；reset_reconnect 后可重入。
class RecAlarmSink : public hg::hgcp::ReconnectAlarmSink {
 public:
  RecAlarmSink() : calls(0), last_attempts(0) {}
  virtual void on_reconnect_severe(int attempts) {
    ++calls;
    last_attempts = attempts;
  }
  int calls;
  int last_attempts;
};

void test_reconnect_severe_alarm_0x0303() {
  HgcpServerLoop srv(any_port_cfg());
  TestPolicy pol;
  srv.set_policy(&pol);
  RecAlarmSink sink;
  srv.set_reconnect_alarm_sink(&sink);
  const std::uint16_t port = start_loop(&srv);
  HG_REQUIRE(port != 0);
  TestClient cli(connect_client(port));
  HG_REQUIRE(cli.valid());

  std::vector<Frame> in;
  srv.pump(1000, 0, &in);  // accept
  HG_REQUIRE(do_handshake(&srv, &cli, &in));

  // A) 未达严重档（前 9 次）⇒ 不产生：无告警帧、无回调。
  for (int i = 0; i < 9; ++i) {
    srv.next_reconnect_delay_ms();
  }
  HG_CHECK(!srv.reconnect_severe());
  HG_CHECK(!srv.reconnect_severe_alarm_pending());
  srv.pump(0, 0, &in);
  Frame none;
  HG_CHECK(!cli.recv_frame(120, &none));
  HG_CHECK_EQ(sink.calls, 0);

  // B) 第 10 次 ⇒ 跨入严重档：一次性待发信号置位。
  HG_CHECK_EQ(srv.next_reconnect_delay_ms(), static_cast<std::int64_t>(8000));
  HG_CHECK(srv.reconnect_severe());
  HG_CHECK(srv.reconnect_severe_alarm_pending());

  // C) pump 消费 ⇒ 回 MSG_ERROR{0x0303}（帧型 + 码值）+ 运维告警一次。
  srv.pump(0, 0, &in);
  Frame warn;
  HG_REQUIRE(cli.recv_frame(1000, &warn));
  HG_CHECK_EQ(warn.header.type, mt(MessageType::kMsgError));
  HG_CHECK((warn.header.flags & fl(hg::hgcp::kFlagIsError)) != 0u);
  HG_REQUIRE_EQ(warn.body.size(), 4u);
  HG_CHECK_EQ(static_cast<std::uint32_t>(ErrorCode::kErrReconnectLimit), 0x0303u);
  HG_CHECK_EQ(warn.body[0], 0x00);
  HG_CHECK_EQ(warn.body[2], 0x03);
  HG_CHECK_EQ(warn.body[3], 0x03);
  HG_CHECK_EQ(sink.calls, 1);
  HG_CHECK_EQ(sink.last_attempts, 10);
  HG_CHECK(!srv.reconnect_severe_alarm_pending());  // 已消费

  // D) 继续失败（第 11/12 次）⇒ 同一严重档不重复刷屏。
  srv.next_reconnect_delay_ms();
  srv.next_reconnect_delay_ms();
  HG_CHECK(srv.reconnect_severe());
  srv.pump(0, 0, &in);
  HG_CHECK(!cli.recv_frame(120, &none));
  HG_CHECK_EQ(sink.calls, 1);

  // E) 重连成功复位 ⇒ 下一轮失败可再次告警（一次性语义可重入）。
  srv.reset_reconnect();
  HG_CHECK_EQ(srv.reconnect_attempts(), 0);
  for (int i = 0; i < 10; ++i) {
    srv.next_reconnect_delay_ms();
  }
  srv.pump(0, 0, &in);
  HG_REQUIRE(cli.recv_frame(1000, &warn));
  HG_CHECK_EQ(warn.header.type, mt(MessageType::kMsgError));
  HG_REQUIRE_EQ(warn.body.size(), 4u);
  HG_CHECK_EQ(warn.body[3], 0x03);
  HG_CHECK_EQ(sink.calls, 2);

  cli.close();
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

  // 生产侧补数装配：经 `send_resume` 回放为 MSG_RESUME_DATA。
  // ⚠️ **订正（2026-09-22，`T-032` 残余收口）**：原注释写「body = 批内帧字节**拼接**」，
  //    系 `send_resume` **未接 schema** 时的**中间态**行为（该中间态即缺陷）。现 body 经
  //    `TransportPolicy` 接缝注入 —— 本用例注入 `TestPolicy`（**不透明占位**）；真实
  //    `MsgResumeData{from_seq,to_seq,is_last,frames}` 编码见 `test_resume_data_body_schema`。
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

// -----------------------------------------------------------------------------
// T-208：`MSG_HELLO_ACK` body 的**字段级**断言（真实 `PbTransportPolicy` 编码）。
//   本体：`server_loop.cc` 曾以 `empty_body()` 回 ACK ⇒ proto3 解出全默认值
//   （`accepted=false` / `server_proto_version=0` / `server_state=SERVER_STATE_UNSET`）
//   ⇒ Java `HgcpClient` 以 `accepted` 判握手 ⇒ **合法握手被判 AUTH_FAILED**（真联阻断）。
//   本用例是**该缺口的击杀器**：断言 body 可解析且 `accepted==true` +
//   `server_proto_version==0x0100` + `server_state` 与**注入状态源**一致 +
//   `param_version` 随状态源变化（非硬编码常量）。
// -----------------------------------------------------------------------------

// 注入用组件状态源（可读写；`server_state` / `param_version` 由用例驱动）。
struct ComponentStateHarness {
  std::int32_t server_state;
  std::uint64_t param_version;
  int calls;

  ComponentStateHarness() : server_state(1), param_version(0), calls(0) {}

  static void provide(void* user, std::int32_t* st, std::uint64_t* pv) {
    ComponentStateHarness* h = static_cast<ComponentStateHarness*>(user);
    if (h == 0) return;
    ++h->calls;
    if (st != 0) *st = h->server_state;
    if (pv != 0) *pv = h->param_version;
  }
};

// 单次握手并取 ACK body：起循环（缺省 `PbTransportPolicy`）→ 连接 → HELLO → 解 ACK。
bool handshake_ack_body(HgcpServerLoop* srv, std::vector<std::uint8_t>* ack_body,
                        std::uint16_t* ack_type) {
  const std::uint16_t port = start_loop(srv);
  if (port == 0) return false;
  TestClient cli(connect_client(port));
  if (!cli.valid()) return false;
  std::vector<Frame> in;
  srv->pump(1000, 0, &in);  // accept
  if (!send_hello(&cli, "")) return false;  // 未启用鉴权 ⇒ 帧级门禁放行
  in.clear();
  if (srv->pump(300, 0, &in) != 1) return false;
  Frame ack;
  if (!cli.recv_frame(1000, &ack)) return false;
  if (ack_body != 0) *ack_body = ack.body;
  if (ack_type != 0) *ack_type = ack.header.type;
  cli.close();
  srv->stop();
  return true;
}

void test_hello_ack_body_schema() {
  // A0) **跨侧共享 golden 向量**（`tests/golden/hello_ack_bodies.tsv`；单一事实源，T-186 先例）：
  //     逐行断言 `PbTransportPolicy::hello_ack_body(②..⑦) == ⑧body_hex` ——
  //     向量与**产出方**任一漂移即红（防「两侧各自漂移」正是本缺陷的根因）。
  {
    const std::vector<std::vector<std::string> > rows = goldenRows(HG_HELLO_ACK_GOLDEN);
    HG_REQUIRE(!rows.empty());
    PbTransportPolicy pol;
    for (std::size_t i = 0; i < rows.size(); ++i) {
      const std::vector<std::string>& r = rows[i];
      HG_REQUIRE_EQ(r.size(), 8u);
      const bool accepted = (r[1] == "true");
      const std::uint32_t spv =
          static_cast<std::uint32_t>(std::strtoul(r[2].c_str(), 0, 10));
      const std::string sid = (r[3] == "<empty>") ? std::string() : r[3];
      const std::int32_t st = static_cast<std::int32_t>(std::strtol(r[4].c_str(), 0, 10));
      const std::uint64_t pv =
          static_cast<std::uint64_t>(std::strtoull(r[5].c_str(), 0, 10));
      const std::uint32_t rc =
          static_cast<std::uint32_t>(std::strtoul(r[6].c_str(), 0, 10));
      const std::vector<std::uint8_t> body =
          pol.hello_ack_body(accepted, spv, sid, st, pv, rc);
      HG_CHECK(hg::hgcp::testutil::to_hex(body.empty() ? 0 : &body[0], body.size()) == r[7]);
    }
  }

  // A) 缺省策略 = 真实 `PbTransportPolicy`；注入状态源 = READY / param_version=42。
  {
    HgcpServerLoop srv(any_port_cfg());
    ComponentStateHarness h;
    h.server_state = pbv1::SERVER_STATE_READY;  // = 3
    h.param_version = 42u;
    srv.set_component_state_fn(&ComponentStateHarness::provide, &h);

    std::vector<std::uint8_t> body;
    std::uint16_t type = 0;
    HG_REQUIRE(handshake_ack_body(&srv, &body, &type));
    HG_CHECK_EQ(type, mt(MessageType::kMsgHelloAck));

    // ⚠️ 关键：body **非空**（`empty_body()` 回退 ⇒ 此处必红；变异①击杀器）。
    HG_REQUIRE(!body.empty());
    pbv1::MsgHelloAck a;
    HG_REQUIRE(PbPayloadCodec::decode(body, &a));  // 可解析为真实 `MsgHelloAck`
    HG_CHECK(a.accepted());                                    // 合法握手必为 true
    HG_CHECK_EQ(a.server_proto_version(),
                static_cast<std::uint32_t>(hg::hgcp::kWireVersion));  // = 0x0100
    HG_CHECK_EQ(a.server_proto_version(), static_cast<std::uint32_t>(0x0100));
    HG_CHECK(a.server_state() == pbv1::SERVER_STATE_READY);    // 与注入值一致
    HG_CHECK_EQ(a.param_version(), static_cast<std::uint64_t>(42));
    HG_CHECK_EQ(a.reason_code(), static_cast<std::uint32_t>(0));
    HG_CHECK(h.calls >= 1);  // 状态源确实被查询
  }

  // B) `param_version` / `server_state` **随状态源变化**（非硬编码）——
  //    注入另一组取值（NOT_READY / 7）⇒ ACK 须随动（摘掉状态源接线 ⇒ 必红）。
  {
    HgcpServerLoop srv(any_port_cfg());
    ComponentStateHarness h;
    h.server_state = pbv1::SERVER_STATE_NOT_READY;  // = 1
    h.param_version = 7u;
    srv.set_component_state_fn(&ComponentStateHarness::provide, &h);

    std::vector<std::uint8_t> body;
    HG_REQUIRE(handshake_ack_body(&srv, &body, 0));
    pbv1::MsgHelloAck a;
    HG_REQUIRE(PbPayloadCodec::decode(body, &a));
    HG_CHECK(a.accepted());
    HG_CHECK(a.server_state() == pbv1::SERVER_STATE_NOT_READY);
    HG_CHECK_EQ(a.param_version(), static_cast<std::uint64_t>(7));
  }

  // C) **未注入**状态源 ⇒ 保守 = NOT_READY / 0（不得伪装 READY）。
  {
    HgcpServerLoop srv(any_port_cfg());
    std::vector<std::uint8_t> body;
    HG_REQUIRE(handshake_ack_body(&srv, &body, 0));
    pbv1::MsgHelloAck a;
    HG_REQUIRE(PbPayloadCodec::decode(body, &a));
    HG_CHECK(a.accepted());  // 鉴权通过 ⇒ accepted 仍为 true（与状态源无关）
    HG_CHECK(a.server_state() == pbv1::SERVER_STATE_NOT_READY);
    HG_CHECK_EQ(a.param_version(), static_cast<std::uint64_t>(0));
  }
}

// `T-032` 残余收口（2026-09-22）：`MSG_RESUME_DATA` 的 body 须按 §4.2.8.2 `MsgResumeData`
// 编码（经 `TransportPolicy` 接缝注入）—— 字段 `from_seq`/`to_seq`/`is_last`/`frames`。
void test_resume_data_body_schema() {

  // A) E2E：**缺省策略**（`PbTransportPolicy`）下 `send_resume` 的线上 body 可被真实
  //    `PbPayloadCodec` 解码为 `MsgResumeData`，且 `frames` = 各帧**原始帧字节**（可复解）。
  {
    HgcpServerLoop srv(any_port_cfg());
    // 不注入策略 ⇒ 缺省 `PbTransportPolicy`（真实 protobuf body）。
    const std::uint16_t port = start_loop(&srv);
    HG_REQUIRE(port != 0);
    TestClient cli(connect_client(port));
    HG_REQUIRE(cli.valid());
    std::vector<Frame> in;
    srv.pump(1000, 0, &in);  // accept
    HG_REQUIRE(do_handshake(&srv, &cli, &in));

    // 服务端发 5 帧（通道 SEQ 2..6：HELLO_ACK 占 SEQ 1）并取空
    for (int i = 0; i < 5; ++i) {
      HG_REQUIRE(srv.send_frame(0x0203, 0u,
                                std::vector<std::uint8_t>(1, static_cast<std::uint8_t>(i))));
    }
    for (int i = 0; i < 5; ++i) {
      Frame ev;
      HG_REQUIRE(cli.recv_frame(1000, &ev));
    }

    HG_CHECK(srv.send_resume(1) == ReplayStatus::kOk);
    Frame rd;
    HG_REQUIRE(cli.recv_frame(1000, &rd));
    HG_CHECK_EQ(rd.header.type, mt(MessageType::kMsgResumeData));
    HG_CHECK((rd.header.flags & fl(hg::hgcp::kFlagIsResumeData)) != 0u);

    pbv1::MsgResumeData d;
    HG_REQUIRE(PbPayloadCodec::decode(rd.body, &d));
    HG_CHECK_EQ(d.from_seq(), 2u);
    HG_CHECK_EQ(d.to_seq(), 6u);
    HG_CHECK(d.is_last());  // 单批 ⇒ 最后一批
    HG_CHECK_EQ(d.frames_size(), 5);
    for (int k = 0; k < d.frames_size(); ++k) {
      const std::string& w = d.frames(k);
      Frame f;
      std::size_t consumed = 0;
      HG_REQUIRE(hg::hgcp::parse_frame(reinterpret_cast<const std::uint8_t*>(&w[0]), w.size(),
                                       &f, &consumed) == FrameStatus::kOk);
      HG_CHECK_EQ(consumed, w.size());
      HG_CHECK_EQ(f.header.seq, static_cast<std::uint64_t>(2 + k));
      HG_CHECK_EQ(f.header.type, 0x0203);
      HG_REQUIRE_EQ(f.body.size(), 1u);
      HG_CHECK_EQ(f.body[0], static_cast<std::uint8_t>(k));
    }
    cli.close();
  }

  // A2) E2E 多批：501 帧 ⇒ `send_resume` 产出 2 批（500 + 1）——
  //     第 1 批 `is_last=false`、第 2 批 `is_last=true`（覆盖「恰好 500 帧」边界与前后批标记）。
  {
    HgcpServerLoop srv(any_port_cfg());
    const std::uint16_t port = start_loop(&srv);
    HG_REQUIRE(port != 0);
    TestClient cli(connect_client(port));
    HG_REQUIRE(cli.valid());
    std::vector<Frame> in;
    srv.pump(1000, 0, &in);  // accept
    HG_REQUIRE(do_handshake(&srv, &cli, &in));

    const int kN = 501;  // HELLO_ACK 占 SEQ 1 ⇒ 业务帧 SEQ 2..502
    for (int i = 0; i < kN; ++i) {
      HG_REQUIRE(srv.send_frame(0x0203, 0u,
                                std::vector<std::uint8_t>(1, static_cast<std::uint8_t>(i & 0xFF))));
    }
    for (int i = 0; i < kN; ++i) {
      Frame ev;
      HG_REQUIRE(cli.recv_frame(2000, &ev));
    }

    HG_CHECK(srv.send_resume(1) == ReplayStatus::kOk);
    for (int b = 0; b < 2; ++b) {
      Frame rd;
      HG_REQUIRE(cli.recv_frame(2000, &rd));
      HG_CHECK_EQ(rd.header.type, mt(MessageType::kMsgResumeData));
      pbv1::MsgResumeData d;
      HG_REQUIRE(PbPayloadCodec::decode(rd.body, &d));
      if (b == 0) {
        HG_CHECK(!d.is_last());  // 非最后一批
        HG_CHECK_EQ(d.from_seq(), static_cast<std::uint64_t>(2));
        HG_CHECK_EQ(d.to_seq(), static_cast<std::uint64_t>(501));
        HG_CHECK_EQ(d.frames_size(), 500);
      } else {
        HG_CHECK(d.is_last());   // 最后一批
        HG_CHECK_EQ(d.from_seq(), static_cast<std::uint64_t>(502));
        HG_CHECK_EQ(d.to_seq(), static_cast<std::uint64_t>(502));
        HG_CHECK_EQ(d.frames_size(), 1);
      }
    }
    cli.close();
  }

  // B) `is_last` 语义 + 「单批 ≤ 500」边界（session 级，直连 `PbTransportPolicy`）。
  {
    PbTransportPolicy pol;
    {  // 恰好 500 帧 ⇒ 单批（is_last=true）
      HgcpSession s;
      s.on_connected(0);
      for (int i = 0; i < 500; ++i) {
        Frame f;
        HG_REQUIRE(s.make_outbound(0x0203, 0u, std::vector<std::uint8_t>(1, 0x11), &f) ==
                   FrameStatus::kOk);
      }
      std::vector<std::vector<BufferedFrame> > bs;
      HG_REQUIRE(s.build_resume(0, &bs) == ReplayStatus::kOk);
      HG_REQUIRE_EQ(bs.size(), 1u);
      HG_REQUIRE_EQ(bs[0].size(), static_cast<std::size_t>(500));
      std::vector<std::vector<std::uint8_t> > frames;
      for (std::size_t k = 0; k < bs[0].size(); ++k) {
        frames.push_back(bs[0][k].bytes);
      }
      pbv1::MsgResumeData d;
      HG_REQUIRE(PbPayloadCodec::decode(
          pol.resume_data_body(bs[0][0].seq, bs[0][bs[0].size() - 1u].seq, true, frames), &d));
      HG_CHECK(d.is_last());
      HG_CHECK_EQ(d.frames_size(), 500);
      HG_CHECK_EQ(d.from_seq(), static_cast<std::uint64_t>(1));
      HG_CHECK_EQ(d.to_seq(), static_cast<std::uint64_t>(500));
    }
    {  // 600 帧 ⇒ 2 批（500 + 100）：第 1 批 is_last=false、第 2 批 is_last=true
      HgcpSession s;
      s.on_connected(0);
      for (int i = 0; i < 600; ++i) {
        Frame f;
        HG_REQUIRE(s.make_outbound(0x0203, 0u, std::vector<std::uint8_t>(1, 0x22), &f) ==
                   FrameStatus::kOk);
      }
      std::vector<std::vector<BufferedFrame> > bs;
      HG_REQUIRE(s.build_resume(0, &bs) == ReplayStatus::kOk);
      HG_REQUIRE_EQ(bs.size(), 2u);
      for (std::size_t i = 0; i < bs.size(); ++i) {
        std::vector<std::vector<std::uint8_t> > frames;
        for (std::size_t k = 0; k < bs[i].size(); ++k) {
          frames.push_back(bs[i][k].bytes);
        }
        const bool is_last = (i + 1u == bs.size());
        pbv1::MsgResumeData d;
        HG_REQUIRE(PbPayloadCodec::decode(
            pol.resume_data_body(bs[i][0].seq, bs[i][bs[i].size() - 1u].seq, is_last, frames),
            &d));
        HG_CHECK_EQ(d.is_last(), is_last);
        HG_CHECK_EQ(d.from_seq(), bs[i][0].seq);
        HG_CHECK_EQ(d.to_seq(), bs[i][bs[i].size() - 1u].seq);
        HG_CHECK_EQ(static_cast<std::size_t>(d.frames_size()), bs[i].size());
      }
    }
  }

  // C) 往返：`resume_data_body` 编码 → **同一 codec** 解码 ⇒ 逐字段与输入相等
  //    （含空帧元素 / 含 `0x00` / 300 字节大帧）。
  {
    PbTransportPolicy pol;
    std::vector<std::vector<std::uint8_t> > frames;
    frames.push_back(std::vector<std::uint8_t>());            // 空帧（proto `bytes` 合法）
    frames.push_back(std::vector<std::uint8_t>(1, 0xAB));
    frames.push_back(std::vector<std::uint8_t>(3, 0x00));     // 含 0x00
    frames.push_back(std::vector<std::uint8_t>(300, 0x7F));   // 大帧
    pbv1::MsgResumeData d;
    HG_REQUIRE(PbPayloadCodec::decode(pol.resume_data_body(41u, 44u, false, frames), &d));
    HG_CHECK_EQ(d.from_seq(), 41u);
    HG_CHECK_EQ(d.to_seq(), 44u);
    HG_CHECK(!d.is_last());
    HG_REQUIRE_EQ(d.frames_size(), 4);
    for (std::size_t k = 0; k < frames.size(); ++k) {
      const std::string want(frames[k].begin(), frames[k].end());
      HG_CHECK(d.frames(static_cast<int>(k)) == want);
    }
  }
}

}  // namespace

int main() {
  test_non_loopback_and_port0();
  test_handshake_and_loopback();
  test_not_handshaken_rejected();
  test_pb_transport_policy_bodies();
  test_frame_too_large_replies_0x0308();
  test_half_and_sticky();
  test_duplicate_connection_rejected();
  test_heartbeat_send_and_timeout_death();
  test_reconnect_backoff();
  test_reconnect_severe_alarm_0x0303();
  test_resume_batches();
  test_resume_data_body_schema();
  test_hello_ack_body_schema();
  test_auth_bad_token_rejected();
  test_auth_missing_token_rejected();
  test_auth_good_token_accepted();
  test_auth_unconfigured_keeps_frame_gate();
  test_auth_token_version_mismatch_rejected();
  test_auth_replay_same_boot_id_rejected();
  test_auth_version_and_boot_normal_accepted();
  return hg::hgcp::testutil::report("transport_test");
}
