// T-207 启动编排单测（app.*）—— 进程内驱动 `TradingApp`（真实回环 socket + 真实 protobuf）。
//
// 覆盖（对应 TASKS T-207 验收 6 的 ctest 清单）：
//   app.config          配置优先级（CLI/env/属性文件/缺省）+ 敏感项拒绝 + 非回环/非法端口拒启
//   app.listen         正常启动 + 回环监听可连 + `SIGTERM` 语义在进程级用例（startup_signal.sh）
//   app.not_ready      参数未收齐 ⇒ 下单回 0x0401（不入队）
//   app.ready          参数收齐 → 重放 → READY（EVT_SYSTEM_STATE 只由启动编排发布）
//   app.corrupt_flow   流水损坏 ⇒ 永不 READY（FAILED）+ 下单回 0x0401
//   app.secret         口令/令牌零回显（脱敏摘要 + 错误信息）
//
// 纪律：本文件只做「建连 + 组帧 + 注入时刻 + 断言」，不复制生产装配逻辑。
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include "app_config.h"
#include "hg/flow_store/replay.h"
#include "hg/hgcp/frame.h"
#include "hg/hgcp/payload_codec.h"
#include "hg/hgcp/tcp.h"
#include "hg/risk_engine/date_util.h"
#include "hgcp_test_util.h"
#include "trading_app.h"

namespace pbv1 = ::hgcp::v1;

namespace {

using hg::app::AppConfig;
using hg::app::AppState;
using hg::app::ConfigOutcome;
using hg::app::TradingApp;
using hg::hgcp::Frame;
using hg::hgcp::FrameStreamReader;
using hg::hgcp::MessageType;
using hg::hgcp::PbPayloadCodec;

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

// 临时目录（mkdtemp；用例结束由调用方 rm -rf）。
std::string makeTempDir(const char* tag) {
  std::string tmpl = std::string("/tmp/") + tag + "-XXXXXX";
  std::vector<char> buf(tmpl.begin(), tmpl.end());
  buf.push_back('\0');
  char* p = ::mkdtemp(&buf[0]);
  if (p == 0) {
    std::printf("[FATAL] mkdtemp 失败\n");
    std::exit(2);
  }
  return std::string(p);
}

// 端口探测：用原语层端口 0 取内核分配端口后立即释放（测试专用；配置口径端口 0 非法）。
std::uint16_t probeFreePort() {
  hg::hgcp::TcpConfig c = hg::hgcp::default_tcp_config();
  c.port = 0;
  hg::hgcp::TcpListener l;
  if (l.start(c) != hg::hgcp::BindStatus::kOk) return 0;
  const std::uint16_t p = l.bound_port();
  l.stop();
  return p;
}

// 与 ReplayController 分片命名同源（epoch_ms / 86400000 → yyyymmdd）。
int todayYmd() { return static_cast<int>(hg::risk_engine::civilFromDays(
    static_cast<std::int64_t>(::time(0)) * 1000LL / 86400000LL)); }

// 极简 HGCP 客户端（回环连接 + 流式收帧）。
class Client {
 public:
  Client() : fd_(-1) {}
  ~Client() { close(); }

  bool connect(std::uint16_t port) {
    fd_ = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd_ < 0) return false;
    struct sockaddr_in a;
    std::memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    if (::inet_pton(AF_INET, "127.0.0.1", &a.sin_addr) != 1 ||
        ::connect(fd_, reinterpret_cast<struct sockaddr*>(&a), sizeof(a)) != 0) {
      close();
      return false;
    }
    return true;
  }

  bool ok() const { return fd_ >= 0; }

  void close() {
    if (fd_ >= 0) {
      ::close(fd_);
      fd_ = -1;
    }
  }

  bool send(std::uint16_t type, std::uint64_t msg_id, const std::vector<std::uint8_t>& body) {
    std::vector<std::uint8_t> wire;
    if (hg::hgcp::make_frame(type, 0u, msg_id, msg_id, body, &wire) !=
        hg::hgcp::FrameStatus::kOk) {
      return false;
    }
    return hg::hgcp::send_all(fd_, &wire[0], wire.size());
  }

  // 非阻塞取帧（成功 ⇒ true 且 *out 有效）。
  bool try_recv(Frame* out) {
    for (;;) {
      const hg::hgcp::FrameStatus st = reader_.next(out);
      if (st == hg::hgcp::FrameStatus::kOk) return true;
      if (st != hg::hgcp::FrameStatus::kNeedMore) return false;
      if (hg::hgcp::poll_readable(fd_, 0) != 1) return false;
      std::uint8_t buf[512];
      const int r = hg::hgcp::recv_some(fd_, buf, sizeof(buf));
      if (r <= 0) return false;
      reader_.feed(buf, static_cast<std::size_t>(r));
    }
  }

 private:
  Client(const Client&);
  Client& operator=(const Client&);

  int fd_;
  FrameStreamReader reader_;
};

// 泵 + 收帧的组合驱动：pump 至多 budget_ms，期间把客户端收到的帧存入 queue。
class Harness {
 public:
  Harness(TradingApp* app, Client* cli) : app_(app), cli_(cli), seq_(1) {}

  void pump(int steps, int timeout_ms) {
    for (int i = 0; i < steps; ++i) {
      app_->pump(timeout_ms);
      drain();
    }
  }

  // 等待某类型帧（预算内 pump 驱动；返回命中帧）。
  bool waitFrame(std::uint16_t type, Frame* out, int budget_ms) {
    int waited = 0;
    for (;;) {
      drain();
      for (std::size_t i = 0; i < queue_.size(); ++i) {
        if (queue_[i].header.type == type) {
          if (out != 0) *out = queue_[i];
          queue_.erase(queue_.begin() + static_cast<long>(i));
          return true;
        }
      }
      if (waited >= budget_ms) return false;
      app_->pump(10);
      waited += 10;
    }
  }

  // 尽力取「最后一条」某类型帧（帧已在上面的 waitFrame 中被取走时不适用）。
  bool sendHello() {
    pbv1::MsgHello h;
    h.set_proto_version(0x0100u);
    h.set_component_id("hg-biz");
    h.set_component_role(pbv1::ROLE_JAVA);
    h.set_auth_token("");
    h.set_client_boot_id("boot-t207");
    h.set_last_received_seq(0);
    h.set_token_version(1);
    return cli_->send(mt(MessageType::kMsgHello), seq_++, enc(h));
  }

  bool sendCommand(std::uint16_t type, const std::vector<std::uint8_t>& body) {
    return cli_->send(type, seq_++, body);
  }

  const std::vector<Frame>& queue() const { return queue_; }

  // T-210：把队列中某类型的帧**全部**移入 `out`（不等待；返回本次移出条数）。
  //   与 `waitFrame`（取走首条并返回）互补 —— 本用例需逐帧比对全部进度帧字段。
  std::size_t takeAll(std::uint16_t type, std::vector<Frame>* out) {
    drain();
    std::size_t n = 0;
    for (std::size_t i = 0; i < queue_.size();) {
      if (queue_[i].header.type == type) {
        out->push_back(queue_[i]);
        queue_.erase(queue_.begin() + static_cast<long>(i));
        ++n;
      } else {
        ++i;
      }
    }
    return n;
  }

 private:
  void drain() {
    Frame f;
    while (cli_->try_recv(&f)) queue_.push_back(f);
  }

  TradingApp* app_;
  Client* cli_;
  std::uint64_t seq_;
  std::vector<Frame> queue_;
};

// T-210：确定性推进时钟 —— 每次取值 `+step`（缺省 1ms）。
//   目的：旧实现 `replayProgress` **每帧取 `now_ms()`** ⇒ 同一重放会话内各帧
//   `started_ts_ms` **必不同**（回归可判）；修复后取**会话起点** ⇒ 多帧同值。
struct TestClock {
  std::int64_t base;
  std::int64_t step;
  int calls;
};

std::int64_t testClockFn(void* user) {
  TestClock* c = static_cast<TestClock*>(user);
  const std::int64_t v = c->base + c->step * static_cast<std::int64_t>(c->calls);
  ++c->calls;
  return v;
}

// 最小合法全量批（品种 10 / 子账户 1 / 操作人 7；可过快照不变量）——
// 与 command_handler_test 的夹具同形（同源字段集，避免第二套语义）。
pbv1::CmdLoadRiskParams makeLoadBatch(std::uint64_t ver) {
  pbv1::CmdLoadRiskParams b;
  b.set_param_version(ver);
  b.set_batch_index(0);
  b.set_batch_total(1);
  pbv1::VarietySpec* v = b.add_varieties();
  v->set_product_id(10);
  v->set_product_code("cu");
  v->set_category(pbv1::PRODUCT_CATEGORY_COMMODITY);
  v->set_status(pbv1::PRODUCT_STATUS_ENABLED);
  v->set_unit_factor("1.0000");
  pbv1::WhitelistEntry* w = b.add_whitelist();
  w->set_sub_account_id(1);
  w->add_product_ids(10);
  pbv1::QuotaEntry* q = b.add_quotas();
  q->set_limit_layer(pbv1::LIMIT_LAYER_SUB_PRODUCT);
  q->set_sub_account_id(1);
  q->set_product_id(10);
  q->set_quota_limit("100.0000");
  q->set_used("0.0000");
  q->set_frozen("0.0000");
  pbv1::PermissionEntry* p = b.add_permissions();
  p->set_operator_id(7);
  p->set_sub_account_id(1);
  p->add_product_ids(10);
  p->set_is_enterprise_leader(false);
  p->set_is_management_role(false);
  p->set_has_risk_role(false);
  p->set_has_settle_role(false);
  p->set_has_trading_post(true);
  p->set_quota_limit("50.0000");
  return b;
}

std::uint32_t errorCodeOf(const Frame& f) {
  pbv1::MsgError e;
  if (!PbPayloadCodec::decode(f.body, &e)) return 0xFFFFFFFFu;
  return e.err_code();
}

// CLI 参数装载辅助（argv 为只读字面量 ⇒ 以 char* 视图传入；被测代码不修改取值）。
ConfigOutcome loadWith(const std::vector<const char*>& items, AppConfig* cfg) {
  std::vector<char*> argv;
  for (std::size_t i = 0; i < items.size(); ++i) {
    argv.push_back(const_cast<char*>(items[i]));
  }
  return hg::app::loadAppConfig(static_cast<int>(argv.size()),
                                argv.empty() ? 0 : &argv[0], cfg);
}

AppConfig baseConfig(const std::string& flow_dir, std::uint16_t port) {
  AppConfig cfg;
  cfg.flow_dir = flow_dir;
  cfg.tcp_port = port;
  cfg.ctp_trade_front.clear();  // 不登录 CTP ⇒ DEGRADED（不阻塞启动）
  cfg.ctp_md_front.clear();
  return cfg;
}

// T-211：写文件并显式设置权限位（管理入口要求恰 0600；0644 用于负例）。
void writeFileWithMode(const std::string& path, const std::string& text, unsigned mode) {
  std::FILE* f = std::fopen(path.c_str(), "w");
  HG_CHECK(f != 0);
  if (f != 0) {
    if (!text.empty()) std::fwrite(text.data(), 1, text.size(), f);
    std::fclose(f);
  }
  HG_CHECK_EQ(::chmod(path.c_str(), static_cast<mode_t>(mode)), 0);
}

// T-211：构造释放请求 JSON（平面对象；字段名逐项对齐 `flow_store::ManualReleaseRequest`）。
std::string releaseJson(std::uint64_t oid, const std::string& ref, std::uint64_t a,
                        std::uint64_t b, const std::string& reason) {
  std::string s = "{";
  s += "\"order_id\": " + std::to_string(static_cast<unsigned long long>(oid));
  s += ", \"client_order_ref\": \"" + ref + "\"";
  s += ", \"operator_a_id\": " + std::to_string(static_cast<unsigned long long>(a));
  s += ", \"operator_b_id\": " + std::to_string(static_cast<unsigned long long>(b));
  s += ", \"reason\": \"" + reason + "\"";
  s += "}";
  return s;
}

// T-211：泵驱动直到 admin 处理计数达 target（预算内）；返回是否达成。
//   · 以**注入确定性时钟**（每 `now_ms()` 调用 +1000ms）驱动 ⇒ 低频轮询节拍确定推进；
//   · **每步经 Harness drain 客户端**（否则大量 pump 不读 socket ⇒ 服务端发送缓冲耗尽
//     ⇒ 断连 ⇒ 可靠事件转保留队列、READY 帧不上线）。
bool pumpUntilAdmin(Harness* h, TradingApp* app, std::uint64_t target_requests, int max_steps) {
  for (int i = 0; i < max_steps && app->stats().admin_requests < target_requests; ++i) {
    h->pump(1, 0);
  }
  return app->stats().admin_requests >= target_requests;
}

// ── 1. 配置：优先级 + 敏感项拒绝 + 启动校验 ─────────────────────────────────
void test_config() {
  // 属性文件（非敏感）先建，env 覆盖之，CLI 再覆盖之 ⇒ CLI > env > 文件。
  const std::string dir = makeTempDir("hgt207cfg");
  const std::string path = dir + "/hg.properties";
  {
    std::FILE* f = std::fopen(path.c_str(), "w");
    HG_CHECK(f != 0);
    if (f != 0) {
      std::fprintf(f, "hg.tcp.port=28001\nhg.flow.dir=%s\n", dir.c_str());
      std::fclose(f);
    }
  }
  ::setenv("HG_TCP_PORT", "28002", 1);
  {
    // 三源同场：文件 28001 / env 28002 / CLI 28003 ⇒ CLI 胜；flow.dir 取文件值。
    std::vector<const char*> args;
    args.push_back("--config");
    args.push_back(path.c_str());
    args.push_back("--hg.tcp.port");
    args.push_back("28003");
    AppConfig cfg;
    const ConfigOutcome oc = loadWith(args, &cfg);
    HG_CHECK(oc.ok);
    HG_CHECK_EQ(cfg.tcp_port, static_cast<std::uint16_t>(28003));  // CLI 胜
    HG_CHECK_EQ(cfg.flow_dir, dir);                               // 文件落值
  }
  {
    // 文件 + env（无 CLI）⇒ env 的 28002 胜文件 28001
    std::vector<const char*> args;
    args.push_back("--config");
    args.push_back(path.c_str());
    AppConfig cfg;
    const ConfigOutcome oc = loadWith(args, &cfg);
    HG_CHECK(oc.ok);
    HG_CHECK_EQ(cfg.tcp_port, static_cast<std::uint16_t>(28002));  // env 胜文件
  }
  ::unsetenv("HG_TCP_PORT");
  {
    // 仅文件（无 env/CLI）⇒ 文件的 28001
    std::vector<const char*> args;
    args.push_back("--config");
    args.push_back(path.c_str());
    AppConfig cfg;
    const ConfigOutcome oc = loadWith(args, &cfg);
    HG_CHECK(oc.ok);
    HG_CHECK_EQ(cfg.tcp_port, static_cast<std::uint16_t>(28001));  // 文件（无 env/CLI）
  }

  // 属性文件承载口令 ⇒ 拒绝装载（零落盘纪律）
  const std::string bad = dir + "/bad.properties";
  {
    std::FILE* f = std::fopen(bad.c_str(), "w");
    HG_CHECK(f != 0);
    if (f != 0) {
      std::fprintf(f, "hg.ctp.password=SECRET-IN-FILE\n");
      std::fclose(f);
    }
  }
  {
    std::vector<const char*> args;
    args.push_back("--config");
    args.push_back(bad.c_str());
    AppConfig cfg;
    const ConfigOutcome oc = loadWith(args, &cfg);
    HG_CHECK(!oc.ok);
  }
  // CLI 承载敏感项 ⇒ 拒绝（错误信息不得回显该值）
  {
    std::vector<const char*> args;
    args.push_back("--hg.ctp.password=SECRET-IN-CLI");
    AppConfig cfg;
    const ConfigOutcome oc = loadWith(args, &cfg);
    HG_CHECK(!oc.ok);
    HG_CHECK(oc.error.find("SECRET-IN-CLI") == std::string::npos);
  }

  // 启动校验：非回环 bind ⇒ 拒绝；端口 0 ⇒ 拒绝；非法 fsync 模式 ⇒ 拒绝
  {
    AppConfig cfg;
    cfg.tcp_bind = "0.0.0.0";
    HG_CHECK(!hg::app::validateAppConfig(cfg).ok);
    cfg.tcp_bind = "127.0.0.1";
    HG_CHECK(hg::app::validateAppConfig(cfg).ok);
    cfg.tcp_port = 0;
    HG_CHECK(!hg::app::validateAppConfig(cfg).ok);
    cfg.tcp_port = 27400;
    cfg.flow_fsync_mode = "BOGUS";
    HG_CHECK(!hg::app::validateAppConfig(cfg).ok);
    cfg.flow_fsync_mode = "BATCH";
    cfg.ctp_env_type = "PRODUCTION";
    HG_CHECK(hg::app::validateAppConfig(cfg).ok);
    cfg.ctp_env_type = "simulation";  // 值域大小写敏感（CFG-22）
    HG_CHECK(!hg::app::validateAppConfig(cfg).ok);
  }
}

// ── 2. 口令/令牌零回显（脱敏摘要）────────────────────────────────────────
void test_secret_redaction() {
  ::setenv("HG_CTP_PASSWORD", "TOP-SECRET-PWD-207", 1);
  ::setenv("HG_HGCP_AUTH_TOKEN", "TOP-SECRET-TOKEN-207", 1);
  std::vector<const char*> args;
  args.push_back("--hg.ctp.user_id");
  args.push_back("tester");
  AppConfig cfg;
  const ConfigOutcome oc = loadWith(args, &cfg);
  HG_CHECK(oc.ok);
  HG_CHECK_EQ(cfg.ctp_password, std::string("TOP-SECRET-PWD-207"));
  HG_CHECK_EQ(cfg.hgcp_auth_token, std::string("TOP-SECRET-TOKEN-207"));

  const std::string summary = hg::app::redactedSummary(cfg);
  HG_CHECK(summary.find("TOP-SECRET-PWD-207") == std::string::npos);
  HG_CHECK(summary.find("TOP-SECRET-TOKEN-207") == std::string::npos);
  HG_CHECK(summary.find("hg.ctp.password=<redacted>") != std::string::npos);
  HG_CHECK(summary.find("hg.hgcp.auth_token=<redacted>") != std::string::npos);
  HG_CHECK(summary.find("hg.ctp.user_id=tester") != std::string::npos);

  ::unsetenv("HG_CTP_PASSWORD");
  ::unsetenv("HG_HGCP_AUTH_TOKEN");
}

// ── 3. 启动 + 监听 + 未就绪下单 0x0401 ─────────────────────────────────────
void test_start_and_not_ready() {
  const std::string dir = makeTempDir("hgt207start");
  const std::uint16_t port = probeFreePort();
  HG_CHECK(port != 0);

  TradingApp app(baseConfig(dir, port));
  std::string err;
  HG_CHECK(app.start(&err));
  HG_CHECK(app.listening());
  HG_CHECK_EQ(app.bound_port(), port);
  HG_CHECK(app.state() == AppState::kNotReady);
  HG_CHECK(!app.system_ready());  // 参数未收齐 ⇒ 门卫 false

  Client cli;
  HG_CHECK(cli.connect(port));
  Harness h(&app, &cli);
  HG_CHECK(h.sendHello());
  Frame ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgHelloAck), &ack, 2000));

  // T-208：**装配级**内容断言 —— 本 App **不注入**自定义策略 ⇒ `MSG_HELLO_ACK` 经**生产缺省**
  //   `PbTransportPolicy` 编码，且 `server_state`/`param_version` 经 `TradingApp::componentState`
  //   注入的状态源取得。修复前（`empty_body()`）此处必红：
  //   `accepted=false` / `server_proto_version=0` / `server_state=SERVER_STATE_UNSET`。
  //   （状态变化维度的断言见 `hgcp.transport_test::test_hello_ack_body_schema`；
  //     本处为启动早期 `state=kNotReady` ⇒ 权威就绪态 = NOT_READY。）
  {
    pbv1::MsgHelloAck ha;
    HG_CHECK(PbPayloadCodec::decode(ack.body, &ha));
    HG_CHECK(ha.accepted());  // 合法握手必为 true（本缺陷本体）
    HG_CHECK_EQ(ha.server_proto_version(),
                static_cast<std::uint32_t>(hg::hgcp::kWireVersion));  // 0x0100
    HG_CHECK(ha.server_state() == pbv1::SERVER_STATE_NOT_READY);  // 与 AppState::kNotReady 一致
    HG_CHECK_EQ(ha.param_version(), static_cast<std::uint64_t>(0));  // 参数未加载
  }

  // 参数未收齐 ⇒ 下单必须回 0x0401（且不入队：state 保持 NOT_READY）
  pbv1::CmdPlaceOrder po;
  po.set_client_order_ref("HG20261028000001");
  po.set_trade_plan_id(1);
  po.set_exposure_id(1);
  po.set_sub_account_id(1);
  po.set_product_id(10);
  po.set_instrument_id("cu2612");
  po.set_volume(1);
  po.set_operator_id(7);
  HG_CHECK(h.sendCommand(mt(MessageType::kCmdPlaceOrder), enc(po)));
  Frame reply;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgError), &reply, 2000));
  HG_CHECK_EQ(errorCodeOf(reply), static_cast<std::uint32_t>(0x0401));
  HG_CHECK(app.state() == AppState::kNotReady);

  app.stop();
  HG_CHECK(app.state() == AppState::kStopping);
}

// ── 4. 参数收齐 → 重放 → READY（READY 只由启动编排发布）────────────────────
void test_params_complete_ready() {
  const std::string dir = makeTempDir("hgt207ready");
  const std::uint16_t port = probeFreePort();
  HG_CHECK(port != 0);

  TradingApp app(baseConfig(dir, port));
  std::string err;
  HG_CHECK(app.start(&err));

  Client cli;
  HG_CHECK(cli.connect(port));
  Harness h(&app, &cli);
  HG_CHECK(h.sendHello());
  Frame ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgHelloAck), &ack, 2000));

  HG_CHECK(h.sendCommand(mt(MessageType::kCmdLoadRiskParams), enc(makeLoadBatch(1))));
  Frame load_ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgAck), &load_ack, 2000));

  // 重放（空目录，无在途）⇒ READY；EVT_SYSTEM_STATE(READY) 由启动编排统一发布
  bool ready_seen = false;
  int waited = 0;
  while (!ready_seen && waited < 3000) {
    Frame f;
    if (h.waitFrame(mt(MessageType::kEvtSystemState), &f, 50)) {
      pbv1::EvtSystemState e;
      if (PbPayloadCodec::decode(f.body, &e) && e.state() == pbv1::SERVER_STATE_READY) {
        ready_seen = true;
        HG_CHECK_EQ(e.param_version_applied(), static_cast<std::uint64_t>(1));
      }
    }
    waited += 50;
  }
  HG_CHECK(ready_seen);
  HG_CHECK(app.state() == AppState::kReady);
  HG_CHECK(app.system_ready());
  HG_CHECK_EQ(app.stats().replay_runs, static_cast<std::uint64_t>(1));
  // 参数生效时 CommandHandler 会产出「参数面 READY」，本装配在就绪前抑制之 ⇒ 至少 1 次
  HG_CHECK(app.stats().events_deferred >= 1);

  app.stop();
}

// ── 5a. T-210：启动自动重放的 `EVT_REPLAY_PROGRESS` 条件必填字段（§4.2.8.5）──
//   断言（两条都要，防「一律填」的过度修正）：
//     ① **成功重放的终态帧** `finished_ts_ms != 0`（且 state=READY / result=OK）；
//     ② **中间进度帧** `finished_ts_ms == 0`（缺省语义；条件不成立不得填）。
//   附加：`started_ts_ms` 在同一重放会话内**恒定**（= 会话起点，非逐帧 now_ms）——
//     本用例经确定性时钟使 `now_ms()` 每调用 +1ms ⇒ 旧实现（逐帧取值）必红。
//
//   构造：当日分片写入 3 条记录（同一 order_id 的 SENT/REPORTED/CANCELLED）⇒ 重放产生
//     ① 入口进度帧（state=REPLAYING, done=0）
//     ② 逐条进度帧（done % every == 0；`every=1`）共 3 帧
//     ③ 终态帧（state=READY, done=3, total=3）
//   为使中间帧确定可数：`replay_progress_every` 置 1（= 每条都发）。
void test_replay_progress_required_fields() {
  const std::string dir = makeTempDir("hgt210progress");
  const std::uint16_t port = probeFreePort();
  HG_CHECK(port != 0);

  // 确定性时钟：now_ms() 每次 +1ms（旧实现逐帧取值 ⇒ 各帧 started_ts_ms 必不同）。
  //   base 取整日边界 ⇒ 分片日期可**由时钟确定**（不依赖系统时间），与 app 自重放同日。
  TestClock clk;
  clk.base = 5000000;  // epoch ms → 1970-01-01
  clk.step = 1;
  clk.calls = 0;
  const int ymd = static_cast<int>(hg::risk_engine::civilFromDays(clk.base / 86400000LL));

  // 当日分片：3 条记录（order_id=77）—— 5 字段定列。
  const std::string shard = hg::flow_store::flowShardPath(dir, ymd);
  {
    std::FILE* f = std::fopen(shard.c_str(), "w");
    HG_CHECK(f != 0);
    if (f != 0) {
      std::fprintf(f, "1000|SENT|77|HG210-77|sent\n");
      std::fprintf(f, "1001|REPORTED|77|HG210-77|reported\n");
      std::fprintf(f, "1002|CANCELLED|77|HG210-77|cancelled\n");
      std::fclose(f);
    }
  }

  AppConfig cfg = baseConfig(dir, port);
  cfg.replay_progress_every = 1;  // 每条都发进度帧 ⇒ 中间帧确定可数
  TradingApp app(cfg);
  app.set_clock(&testClockFn, &clk);

  std::string err;
  HG_CHECK(app.start(&err));

  Client cli;
  HG_CHECK(cli.connect(port));
  Harness h(&app, &cli);
  HG_CHECK(h.sendHello());
  Frame ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgHelloAck), &ack, 2000));

  HG_CHECK(h.sendCommand(mt(MessageType::kCmdLoadRiskParams), enc(makeLoadBatch(1))));
  Frame load_ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgAck), &load_ack, 2000));

  // 推进到 READY（重放产出全部进度帧 + 终态帧）。
  int waited = 0;
  while (app.state() != AppState::kReady && waited < 3000) {
    app.pump(10);
    waited += 10;
  }
  HG_CHECK(app.state() == AppState::kReady);

  // 收集全部进度帧（可能仍留在 socket 缓冲 ⇒ 再泵几轮）。
  std::vector<Frame> frames;
  h.pump(5, 10);
  h.takeAll(mt(MessageType::kEvtReplayProgress), &frames);
  HG_CHECK(frames.size() >= static_cast<std::size_t>(2));  // 至少：中间帧 + 终态帧

  std::size_t mid = 0;
  std::size_t terminal = 0;
  std::int64_t started_of_terminal = -1;
  std::int64_t started_first = -1;
  bool started_constant = true;
  for (std::size_t i = 0; i < frames.size(); ++i) {
    pbv1::EvtReplayProgress e;
    HG_CHECK(PbPayloadCodec::decode(frames[i].body, &e));
    if (started_first < 0) started_first = e.started_ts_ms();
    if (e.started_ts_ms() != started_first) started_constant = false;

    if (e.state() == pbv1::SERVER_STATE_READY) {
      // 终态帧：条件必填 `finished_ts_ms` 必填（重放完成）；result=OK。
      HG_CHECK(e.result() == pbv1::REPLAY_RESULT_OK);
      HG_CHECK(e.finished_ts_ms() != 0);   // ★ 本缺陷本体：旧实现恒 0
      HG_CHECK(e.started_ts_ms() != 0);
      started_of_terminal = e.started_ts_ms();
      ++terminal;
    } else {
      // 中间进度帧：条件不成立 ⇒ `finished_ts_ms` **不得**填（缺省 = 0）。
      HG_CHECK(e.finished_ts_ms() == 0);   // ★ 防「一律填」的过度修正
      ++mid;
    }
  }
  HG_CHECK_EQ(terminal, static_cast<std::size_t>(1));  // 恰一帧终态
  HG_CHECK(mid >= static_cast<std::size_t>(1));        // 至少一帧中间进度
  // `started_ts_ms` 会话内恒定（= 重放会话起点）；旧实现逐帧 now_ms ⇒ 必红。
  HG_CHECK(started_constant);
  // 终态帧的 started_ts_ms 与中间帧同源（同一会话起点）。
  HG_CHECK_EQ(started_of_terminal, started_first);
  // 会话起点非 0（注入时钟基值 5000000 ⇒ `now_ms()` 恒 > 0）。
  HG_CHECK(started_first != 0);

  app.stop();
}

// ── 5. 流水损坏 ⇒ 永不 READY + 下单 0x0401 ─────────────────────────────────
void test_corrupt_flow_never_ready() {
  const std::string dir = makeTempDir("hgt207corrupt");
  // 当日分片写坏行（5 字段契约不满足）⇒ ReplayController verdict=kCorrupt
  const std::string shard =
      hg::flow_store::flowShardPath(dir, todayYmd());
  {
    std::FILE* f = std::fopen(shard.c_str(), "w");
    HG_CHECK(f != 0);
    if (f != 0) {
      std::fprintf(f, "not-a-flow-record\n");
      std::fclose(f);
    }
  }
  const std::uint16_t port = probeFreePort();
  HG_CHECK(port != 0);

  TradingApp app(baseConfig(dir, port));
  std::string err;
  HG_CHECK(app.start(&err));

  Client cli;
  HG_CHECK(cli.connect(port));
  Harness h(&app, &cli);
  HG_CHECK(h.sendHello());
  Frame ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgHelloAck), &ack, 2000));

  HG_CHECK(h.sendCommand(mt(MessageType::kCmdLoadRiskParams), enc(makeLoadBatch(1))));
  Frame load_ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgAck), &load_ack, 2000));

  // 推进到 FAILED（永不 READY）
  int waited = 0;
  while (app.state() != AppState::kFailed && waited < 3000) {
    app.pump(10);
    waited += 10;
  }
  HG_CHECK(app.state() == AppState::kFailed);
  HG_CHECK(!app.system_ready());
  HG_CHECK(!app.last_error().empty());

  // 损坏流水下下单仍回 0x0401（拒绝一切交易指令；不得带病运行）
  pbv1::CmdPlaceOrder po;
  po.set_client_order_ref("HG20261028000002");
  po.set_trade_plan_id(1);
  po.set_exposure_id(1);
  po.set_sub_account_id(1);
  po.set_product_id(10);
  po.set_instrument_id("cu2612");
  po.set_volume(1);
  po.set_operator_id(7);
  HG_CHECK(h.sendCommand(mt(MessageType::kCmdPlaceOrder), enc(po)));
  Frame reply;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgError), &reply, 2000));
  HG_CHECK_EQ(errorCodeOf(reply), static_cast<std::uint32_t>(0x0401));
  HG_CHECK(app.state() == AppState::kFailed);

  app.stop();
}

// ── 6. T-211：本地管理入口（「未知在途」人工核验释放）────────────────────────
//   ① 构造 unknown 在途 ⇒ `system_ready()==false` 且状态 `BLOCKED`；
//   ② 合法请求文件 ⇒ 台账清零 + `system_ready()==true` + 状态迁移 `READY` +
//      `EVT_SYSTEM_STATE(READY)` **恰 1 帧**（防双发）；
//   ③ 非法请求（缺 operator_b / 双人同人 / ref 不匹配 / 权限非 600）⇒ 零变更 + 仍 BLOCKED；
//   ④ 重复投递同一请求 ⇒ 第二次被拒（幂等）。
//
//   构造 unknown 在途：当日分片写一条 `SENT`（无终态）记录 ⇒ 装配 `query_==nullptr`
//   ⇒ `kTimeout ⇒ kUnknownKeepFrozen`（`replay.cc`）⇒ 台账登记 ⇒ 门卫第三合取 false。
void test_release_unknown_inflight() {
  const std::string dir = makeTempDir("hgt211release");
  const std::uint16_t port = probeFreePort();
  HG_CHECK(port != 0);

  // 当日分片：一条 SENT 无终态 ⇒ 在途单（order_id=77 / ref=HG211-77）。
  {
    const std::string shard = hg::flow_store::flowShardPath(dir, todayYmd());
    writeFileWithMode(shard, "1000|SENT|77|HG211-77|sent\n", 0600);
  }

  AppConfig cfg = baseConfig(dir, port);
  // 心跳间隔放宽到 60s：本用例注入**确定性快进时钟**驱动低频轮询节拍（见下），
  //   若不放宽，快进时钟会使心跳监视器误判超时 ⇒ 服务端主动断连（与轮询无关的噪声）。
  cfg.heartbeat_ms = 60000;
  TradingApp app(cfg);
  // 注入确定性时钟：每 `now_ms()` 调用 +50ms ⇒ 低频轮询（1000ms 节拍）**确定推进**，
  //   不依赖真实墙钟（真实 pump(0) 几乎不推进墙钟 ⇒ 原按墙钟的等待不可确定）。
  TestClock clk;
  clk.base = static_cast<std::int64_t>(::time(0)) * 1000LL;
  clk.step = 50;
  clk.calls = 0;
  app.set_clock(&testClockFn, &clk);
  std::string err;
  HG_CHECK(app.start(&err));

  Client cli;
  HG_CHECK(cli.connect(port));
  Harness h(&app, &cli);
  HG_CHECK(h.sendHello());
  Frame ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgHelloAck), &ack, 2000));

  HG_CHECK(h.sendCommand(mt(MessageType::kCmdLoadRiskParams), enc(makeLoadBatch(1))));
  Frame load_ack;
  HG_CHECK(h.waitFrame(mt(MessageType::kMsgAck), &load_ack, 2000));

  // ① 构造 unknown 在途 ⇒ BLOCKED
  int waited = 0;
  while (app.state() != AppState::kBlocked && waited < 3000) {
    app.pump(10);
    waited += 10;
  }
  HG_CHECK(app.state() == AppState::kBlocked);
  HG_CHECK(!app.system_ready());
  HG_CHECK_EQ(app.recovery_open(), static_cast<std::size_t>(1));        // 登记总数
  HG_CHECK_EQ(app.recovery_open_count(), static_cast<std::size_t>(1));  // 未闭环

  const std::string req = app.admin_dir() + "/release-request.json";
  std::uint64_t expect_requests = 0;

  // ③a 权限非 600（0644）⇒ 拒绝处理，台账零变更
  writeFileWithMode(req, releaseJson(77, "HG211-77", 101, 202, "ok"), 0644);
  HG_CHECK(pumpUntilAdmin(&h, &app, ++expect_requests, 3000));
  HG_CHECK_EQ(app.recovery_open_count(), static_cast<std::size_t>(1));
  HG_CHECK(app.state() == AppState::kBlocked);
  HG_CHECK(!app.system_ready());

  // ③b 缺 operator_b（必需键缺失 ⇒ 解析失败）
  writeFileWithMode(req,
                    "{\"order_id\":77,\"client_order_ref\":\"HG211-77\","
                    "\"operator_a_id\":101,\"reason\":\"ok\"}",
                    0600);
  HG_CHECK(pumpUntilAdmin(&h, &app, ++expect_requests, 3000));
  HG_CHECK_EQ(app.recovery_open_count(), static_cast<std::size_t>(1));
  HG_CHECK(app.state() == AppState::kBlocked);

  // ③c 双人同人（operator_a == operator_b）
  writeFileWithMode(req, releaseJson(77, "HG211-77", 101, 101, "ok"), 0600);
  HG_CHECK(pumpUntilAdmin(&h, &app, ++expect_requests, 3000));
  HG_CHECK_EQ(app.recovery_open_count(), static_cast<std::size_t>(1));
  HG_CHECK(app.state() == AppState::kBlocked);

  // ③d ref 不匹配
  writeFileWithMode(req, releaseJson(77, "WRONG-REF", 101, 202, "ok"), 0600);
  HG_CHECK(pumpUntilAdmin(&h, &app, ++expect_requests, 3000));
  HG_CHECK_EQ(app.recovery_open_count(), static_cast<std::size_t>(1));
  HG_CHECK(app.state() == AppState::kBlocked);
  HG_CHECK(!app.system_ready());
  HG_CHECK_EQ(app.stats().admin_released, static_cast<std::uint64_t>(0));  // 全程零释放

  // ③e 符号链接（指向 0600 的真实文件）⇒ 拒绝处理（防读域外文件）
  {
    const std::string real_file = dir + "/real-request.json";
    writeFileWithMode(real_file, releaseJson(77, "HG211-77", 101, 202, "sym"), 0600);
    ::unlink(req.c_str());
    HG_CHECK_EQ(::symlink(real_file.c_str(), req.c_str()), 0);
    HG_CHECK(pumpUntilAdmin(&h, &app, ++expect_requests, 3000));
    HG_CHECK_EQ(app.recovery_open_count(), static_cast<std::size_t>(1));
    HG_CHECK(app.state() == AppState::kBlocked);
    HG_CHECK_EQ(app.stats().admin_released, static_cast<std::uint64_t>(0));
    // 归档失败（符号链接指向的真实文件仍在）⇒ 清理请求路径防污染后续步骤。
    ::unlink(req.c_str());
  }

  // 失败路径告警留痕（**不得静默吞**）：每次拒绝必发 `EVT_TRADING_ALERT`
  //   （detail 含「手动核验释放被拒」+ 拒因）；码语义分层：
  //     权限/解析类 ⇒ 0x0104（前置非法）；五守卫拒绝 ⇒ 0x0401（系统仍未就绪）。
  {
    std::vector<Frame> alerts;
    h.takeAll(mt(MessageType::kEvtTradingAlert), &alerts);
    std::size_t reject_alerts = 0, code_0104 = 0, code_0401 = 0;
    for (std::size_t i = 0; i < alerts.size(); ++i) {
      pbv1::EvtTradingAlert e;
      if (!PbPayloadCodec::decode(alerts[i].body, &e)) continue;
      if (e.detail().find("手动核验释放被拒") == std::string::npos) continue;
      ++reject_alerts;
      if (e.alert_code() == 0x0104u) ++code_0104;
      if (e.alert_code() == 0x0401u) ++code_0401;
    }
    HG_CHECK(reject_alerts >= static_cast<std::size_t>(5));  // 5 次拒绝各留一条
    HG_CHECK(code_0104 >= static_cast<std::size_t>(1));      // 权限/解析类
    HG_CHECK(code_0401 >= static_cast<std::size_t>(1));      // 守卫类
  }

  // ② 合法请求 ⇒ 台账清零 + READY + EVT_SYSTEM_STATE(READY) 恰 1 帧
  writeFileWithMode(req, releaseJson(77, "HG211-77", 101, 202, "人工核验：CTP 无此单"), 0600);
  HG_CHECK(pumpUntilAdmin(&h, &app, ++expect_requests, 3000));
  waited = 0;
  while (app.state() != AppState::kReady && waited < 50) {
    h.pump(1, 0);
    ++waited;
  }
  HG_CHECK(app.state() == AppState::kReady);
  HG_CHECK(app.system_ready());
  HG_CHECK_EQ(app.recovery_open_count(), static_cast<std::size_t>(0));  // 未闭环清零
  HG_CHECK_EQ(app.recovery_open(), static_cast<std::size_t>(1));        // 登记总数不变（含已释放）
  HG_CHECK_EQ(app.stats().admin_released, static_cast<std::uint64_t>(1));

  // `EVT_SYSTEM_STATE(READY)` 恰 1 帧（全程仅启动编排补发一次）。
  h.pump(10, 10);
  std::vector<Frame> ss;
  h.takeAll(mt(MessageType::kEvtSystemState), &ss);
  std::size_t ready_frames = 0;
  for (std::size_t i = 0; i < ss.size(); ++i) {
    pbv1::EvtSystemState e;
    if (PbPayloadCodec::decode(ss[i].body, &e) && e.state() == pbv1::SERVER_STATE_READY) {
      ++ready_frames;
    }
  }
  HG_CHECK_EQ(ready_frames, static_cast<std::size_t>(1));

  // 释放成功时引擎终态化的外部通知经**既有** `emitEngineEvent` 通道补发
  //   `EVT_ORDER_EXPIRED`（与 kReleaseExpired 决策路径同形，不新开通道）。
  std::vector<Frame> exps;
  h.takeAll(mt(MessageType::kEvtOrderExpired), &exps);
  std::size_t expired_frames = 0;
  for (std::size_t i = 0; i < exps.size(); ++i) {
    pbv1::EvtOrderExpired e;
    if (PbPayloadCodec::decode(exps[i].body, &e) && e.order_id() == 77) ++expired_frames;
  }
  HG_CHECK_EQ(expired_frames, static_cast<std::size_t>(1));

  // ④ 重复投递同一请求 ⇒ 第二次被拒（幂等：台账已闭环 ⇒ AlreadyReleased/NotFound），
  //    状态保持 READY、台账仍清零（幂等拒绝不二次留痕、不重复释放）。
  const std::uint64_t rej_before = app.stats().admin_rejected;
  writeFileWithMode(req, releaseJson(77, "HG211-77", 101, 202, "dup"), 0600);
  HG_CHECK(pumpUntilAdmin(&h, &app, ++expect_requests, 3000));
  HG_CHECK_EQ(app.stats().admin_rejected, rej_before + 1);
  HG_CHECK_EQ(app.stats().admin_released, static_cast<std::uint64_t>(1));
  HG_CHECK_EQ(app.recovery_open_count(), static_cast<std::size_t>(0));
  HG_CHECK(app.state() == AppState::kReady);
  HG_CHECK(app.system_ready());

  // 归档可审计：处理过的请求文件已移出请求路径（幂等的物理承载）。
  struct stat st;
  HG_CHECK(::stat(req.c_str(), &st) != 0);  // 请求路径已无文件

  // ⑥ 低频轮询节流证据（**非每 pump 做文件 I/O**）：连续 400 次 pump（时钟每步 +50ms
  //    ⇒ 推进 20 s），节拍 1000ms ⇒ 轮询增量 ≈ 20，**远小于** 400。若去掉节流
  //    （每次 pump 都轮询）⇒ 增量 = 400 ⇒ 本断言必红。
  const std::uint64_t polls_before = app.stats().admin_polls;
  const int kPumpSteps = 400;
  for (int i = 0; i < kPumpSteps; ++i) h.pump(1, 0);
  const std::uint64_t d_polls = app.stats().admin_polls - polls_before;
  HG_CHECK(d_polls >= static_cast<std::uint64_t>(1));
  HG_CHECK(d_polls <= static_cast<std::uint64_t>(kPumpSteps / 10));  // 节流确有牙

  app.stop();
}

}  // namespace

// 用法：`hg_app_test [case]`
//   无参 / all ⇒ 全跑；否则只跑指定用例（ctest 一例一进程，失败定位精确）：
//   config | secret | listen | ready | progress | corrupt | release
int main(int argc, char** argv) {
  const std::string only = (argc > 1 && argv[1] != 0) ? std::string(argv[1]) : std::string("all");
  const bool all = (only == "all");
  if (all || only == "config") test_config();
  if (all || only == "secret") test_secret_redaction();
  if (all || only == "listen") test_start_and_not_ready();
  if (all || only == "ready") test_params_complete_ready();
  if (all || only == "progress") test_replay_progress_required_fields();
  if (all || only == "corrupt") test_corrupt_flow_never_ready();
  if (all || only == "release") test_release_unknown_inflight();
  if (!all && only != "config" && only != "secret" && only != "listen" && only != "ready" &&
      only != "progress" && only != "corrupt" && only != "release") {
    std::printf("[FATAL] 未知用例：%s\n", only.c_str());
    return 2;
  }
  return hg::hgcp::testutil::report("app_trading");
}
