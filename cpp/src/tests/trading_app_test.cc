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

}  // namespace

// 用法：`hg_app_test [case]`
//   无参 / all ⇒ 全跑；否则只跑指定用例（ctest 一例一进程，失败定位精确）：
//   config | secret | listen | ready | corrupt
int main(int argc, char** argv) {
  const std::string only = (argc > 1 && argv[1] != 0) ? std::string(argv[1]) : std::string("all");
  const bool all = (only == "all");
  if (all || only == "config") test_config();
  if (all || only == "secret") test_secret_redaction();
  if (all || only == "listen") test_start_and_not_ready();
  if (all || only == "ready") test_params_complete_ready();
  if (all || only == "corrupt") test_corrupt_flow_never_ready();
  if (!all && only != "config" && only != "secret" && only != "listen" && only != "ready" &&
      only != "corrupt") {
    std::printf("[FATAL] 未知用例：%s\n", only.c_str());
    return 2;
  }
  return hg::hgcp::testutil::report("app_trading");
}
