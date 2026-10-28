// hg-trading 启动编排与主循环实现（T-207）。
//
// 时间一律由 `now_ms()` 注入（缺省系统实时 ms），主循环单步内**只取一次**，
// 供 server_loop / CommandHandler / OrderEngine / ReplayController 共用
// （ARCH §2.4.2「无隐藏时钟」）。
#include "trading_app.h"

#include <sys/stat.h>
#include <sys/types.h>

#include <time.h>

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "hg/hgcp/payload_codec.h"
#include "hg/hgcp/protocol.h"
#include "hg/hgcp/timing.h"
#include "hg/risk_engine/date_util.h"

namespace pbv1 = ::hgcp::v1;

namespace hg {
namespace app {

namespace {

std::uint16_t mt(hg::hgcp::MessageType m) { return static_cast<std::uint16_t>(m); }

// 装配期配置派生（缺省值来自 §11.2；此处只做形变，不做校验——校验在 validateAppConfig）。
hg::order_engine::OrderEngineConfig makeEngineConfig(const AppConfig& cfg) {
  hg::order_engine::OrderEngineConfig c;
  c.confirm_timeout_ms = cfg.confirm_timeout_ms;  // CFG-28
  return c;
}

hg::hgcp::TcpConfig makeTcpConfig(const AppConfig& cfg) {
  hg::hgcp::TcpConfig t;
  t.bind_address = cfg.tcp_bind;  // CFG-20（白名单校验在 validateAppConfig）
  t.port = cfg.tcp_port;
  return t;
}

void logLine(const std::string& s) {
  std::printf("[hg-trading] %s\n", s.c_str());
  std::fflush(stdout);
}

// UTC 日 → yyyymmdd（与 ReplayController 分片命名同源口径：epoch_ms / 86400000）。
int todayYmd(std::int64_t now_ms) {
  return static_cast<int>(hg::risk_engine::civilFromDays(now_ms / 86400000LL));
}

// 递归建目录（POSIX mkdir；本文件不在 E4 扫描根，I/O 合法）。
bool ensureDir(const std::string& path, std::string* error) {
  if (path.empty()) {
    if (error != 0) *error = "目录路径为空";
    return false;
  }
  std::string cur;
  std::size_t i = 0;
  if (path[0] == '/') {
    cur = "/";
    i = 1;
  }
  while (i <= path.size()) {
    const std::string::size_type next = path.find('/', i);
    const std::string::size_type end = (next == std::string::npos) ? path.size() : next;
    const std::string seg = path.substr(i, end - i);
    if (!seg.empty()) {
      if (!cur.empty() && cur[cur.size() - 1] != '/') cur += "/";
      cur += seg;
      if (::mkdir(cur.c_str(), 0755) != 0 && errno != EEXIST) {
        if (error != 0) {
          *error = "无法创建流水目录：" + cur + "（errno=" + std::to_string(errno) + "）";
        }
        return false;
      }
    }
    if (next == std::string::npos) break;
    i = next + 1;
  }
  return true;
}

// CTP 侧在途单查询接缝缺位（生产查询归 T-041/T-039 装配；见回执披露）⇒
// 本装配不注入 query ⇒ ReplayController 对在途单一律按 kTimeout 保守处理
// （状态未知、冻结不释放、台账登记、需人工核验）——「保守优于冒进」。
class ReplayAlertToLog : public hg::flow_store::IReplayAlertSink {
 public:
  virtual void onInflightUnknown(const hg::flow_store::InflightOrder& order) {
    logLine("ALERT 在途单状态未知（冻结保留，需人工核验）：order_id=" +
            std::to_string(static_cast<unsigned long long>(order.order_id)) + " ref=" +
            order.client_order_ref);
  }
};

ReplayAlertToLog g_replay_alert;

void emitterAlert(void* /*user*/, const char* what) {
  logLine(std::string("ALERT 事件队列：") + (what == 0 ? "unknown" : what));
}

// 定点 → 十进制字符串（金额/价格线上为十进制字符串，§4.2.5）。
// 与 command_handler.cc 的 `fixed_to_str` 同形（第二映射点，回执 O-1 披露）。
std::string fixedToStr(hg::risk_state::Fixed v, std::int64_t scale) {
  if (scale <= 1) return std::to_string(v);
  const bool neg = v < 0;
  const hg::risk_state::Fixed a = neg ? -v : v;
  const std::int64_t ip = a / scale;
  std::int64_t frac = a % scale;
  std::string fs;
  std::int64_t s = scale;
  s /= 10;
  while (s > 0) {
    fs.push_back(static_cast<char>(48 + (frac / s) % 10));
    s /= 10;
  }
  std::size_t keep = fs.size();
  while (keep > 1 && fs[keep - 1] == 48) --keep;
  fs.resize(keep);
  return (neg ? std::string("-") : std::string()) + std::to_string(ip) + "." + fs;
}

pbv1::OrderStatus orderStatusToPb(hg::order_engine::OrderStatus s) {
  switch (s) {
    case hg::order_engine::OrderStatus::kPendingConfirm:
      return pbv1::ORDER_STATUS_PENDING_CONFIRM;
    case hg::order_engine::OrderStatus::kSubmitted:
      return pbv1::ORDER_STATUS_SENT_CTP;
    case hg::order_engine::OrderStatus::kPartiallyFilled:
      return pbv1::ORDER_STATUS_PARTIAL_FILLED;
    case hg::order_engine::OrderStatus::kFilled:
      return pbv1::ORDER_STATUS_ALL_FILLED;
    case hg::order_engine::OrderStatus::kCancelled:
      return pbv1::ORDER_STATUS_CANCELLED;
    case hg::order_engine::OrderStatus::kExpired:
      return pbv1::ORDER_STATUS_EXPIRED;
    case hg::order_engine::OrderStatus::kRejected:
      return pbv1::ORDER_STATUS_REJECTED_CTP;
    default:
      return pbv1::ORDER_STATUS_UNSET;
  }
}

// 重放失败 → 组件错误码（§4.5.1 / §4.5.3 ⑥；与 command_handler.cc:1205-1212 同口径）。
std::uint32_t replayErrorCode(hg::flow_store::ReplayVerdict v) {
  if (v == hg::flow_store::ReplayVerdict::kCorrupt) {
    return static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kMemoryStateCorrupted);  // 0x3002
  }
  if (v == hg::flow_store::ReplayVerdict::kMismatch) {
    return static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kErrReplayMismatch);  // 0x0405
  }
  return static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kFlowPersistFailed);  // 0x3001
}

std::int64_t systemNowMs(void* /*user*/) {
  const std::chrono::system_clock::duration d = std::chrono::system_clock::now().time_since_epoch();
  return static_cast<std::int64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(d).count());
}

// 待确认超时扫描节拍（R-TRADE-6；CommandHandler 亦会机会性驱动一次）。
const std::int64_t kExpireScanPeriodMs = 200;

// 停机排空：回报静默窗口（连续该时长无新回报 ⇒ 认为在途已清空）与轮询切片。
const std::int64_t kStopQuietMs = 200;
const long kStopSliceNs = 10L * 1000L * 1000L;  // 10 ms

}  // namespace

const char* appStateName(AppState s) {
  switch (s) {
    case AppState::kNotReady: return "NOT_READY";
    case AppState::kReplaying: return "REPLAYING";
    case AppState::kReady: return "READY";
    case AppState::kBlocked: return "BLOCKED";
    case AppState::kFailed: return "FAILED";
    case AppState::kStopping: return "STOPPING";
    default: return "UNKNOWN";
  }
}

AppStats::AppStats()
    : frames_in(0),
      commands(0),
      replies(0),
      events(0),
      events_deferred(0),
      ctp_events(0),
      quotes_dropped(0),
      expire_events(0),
      replay_runs(0) {}

TradingApp::TradingApp(const AppConfig& cfg)
    : cfg_(cfg),
      ct_(),
      flow_(),
      replay_(),
      risk_state_(),
      risk_(),
      bridge_(&ct_),
      engine_(&risk_, &flow_, &bridge_, makeEngineConfig(cfg)),
      ledger_(),
      recovery_(&engine_, &ledger_),
      loop_(makeTcpConfig(cfg)),
      emitter_(&loop_),
      rebuilder_(&risk_state_),
      handler_(&risk_state_),
      auth_verifier_(),
      state_(AppState::kNotReady),
      stats_(),
      last_error_(),
      started_(false),
      ctp_degraded_(false),
      ctp_login_attempted_(false),
      ctp_logged_in_observed_(false),
      last_expire_ms_(0),
      clock_fn_(&systemNowMs),
      clock_user_(0) {
  // ---- 重放配置（CFG-29）----
  hg::flow_store::FlowRuntimeConfig fc;
  fc.flow_dir = cfg_.flow_dir;
  bool fsync_ok = false;
  fc.fsync_requested = hg::flow_store::parseFlowFsyncMode(cfg_.flow_fsync_mode.c_str(), &fsync_ok);
  // 生效值恒 EVERY_RECORD（T-037 裁定：BATCH/ASYNC 的 barrier 接线不在本单写域）；
  // 降级以 fsync_clamped 显式披露（解析失败时 validate 已拒绝启动）。
  fc.fsync_effective = hg::flow_store::FlowFsyncMode::kEveryRecord;
  fc.fsync_clamped = fsync_ok && (fc.fsync_requested != fc.fsync_effective);
  fc.retain_days = cfg_.flow_retain_days;
  fc.replay_query_timeout_ms = cfg_.replay_query_timeout_ms;
  fc.replay_progress_every = cfg_.replay_progress_every;
  replay_.set_runtime_config(fc);
  replay_.set_order_query(0);  // CTP 查询接缝缺位 ⇒ 在途按 kTimeout 保守处理（披露）
  replay_.set_alert_sink(&g_replay_alert);

  // ---- 交易路径 / 重放 / 恢复 三接缝（单一门卫 system_ready()）----
  handler_.set_order_engine(&engine_);
  handler_.set_replay_controller(&replay_);
  handler_.set_recovery_hook(&recovery_);
  ledger_.set_flow_store(&flow_);

  // ---- 事件层（T6 装配）----
  emitter_.attach();
  emitter_.set_rebuilder(&rebuilder_);
  emitter_.set_alert_sink(&emitterAlert, 0);

  // ---- 会话参数（§4.2.6：心跳间隔 / 丢失阈值 3 / 补数缓冲容量）----
  loop_.configure(cfg_.heartbeat_ms, hg::hgcp::kHeartbeatMissThreshold,
                  hg::hgcp::kEventQueueCapacity);

  // ---- 鉴权（可选；令牌仅内存，摘要持有）----
  if (!cfg_.hgcp_auth_token.empty()) {
    auth_verifier_ = hg::hgcp::TokenVerifier::from_token(cfg_.hgcp_auth_token);
    loop_.set_token_verifier(&auth_verifier_);
  }
}

TradingApp::~TradingApp() { stop(); }

void TradingApp::set_clock(ClockFn fn, void* user) {
  clock_fn_ = (fn == 0) ? &systemNowMs : fn;
  clock_user_ = user;
}

std::int64_t TradingApp::now_ms() const {
  return (clock_fn_ == 0) ? systemNowMs(0) : clock_fn_(clock_user_);
}

void TradingApp::setState(AppState s, const std::string& note) {
  state_ = s;
  std::string line = std::string("state=") + appStateName(s);
  if (s == AppState::kReady) {
    line += " param_version=" +
            std::to_string(static_cast<unsigned long long>(risk_state_.param_version()));
  }
  if (!note.empty()) line += " reason=" + note;
  // 状态迁移每次都留痕（调用点均已按迁移条件判重 ⇒ 不会刷屏）；
  // 部署脚本的就绪等待依赖 `state=READY` / `listen=` 两类日志行。
  logLine(line);
}

bool TradingApp::start(std::string* error) {
  const ConfigOutcome v = validateAppConfig(cfg_);
  if (!v.ok) {
    if (error != 0) *error = v.error;
    return false;
  }
  logLine("config: " + redactedSummary(cfg_));

  const std::int64_t now = now_ms();

  // ② 打开流水目录并校验（§4.5.3 ②；不可用 ⇒ 拒绝启动，不得静默继续）
  if (!ensureDir(cfg_.flow_dir, error)) return false;
  const std::string shard =
      hg::flow_store::flowShardPath(cfg_.flow_dir, todayYmd(now));
  if (!flow_.open(shard)) {
    if (error != 0) *error = "打开流水分片失败：" + shard;
    return false;
  }
  logLine("flow shard=" + shard);

  // ③ 建立回环监听（不接业务指令：由 system_ready() 单一门卫承载）
  const hg::hgcp::BindStatus bs = loop_.start();
  if (bs != hg::hgcp::BindStatus::kOk) {
    if (error != 0) {
      *error = std::string("HGCP 监听建立失败（") + hg::hgcp::to_string(bs) + "）：" +
               cfg_.tcp_bind + ":" + std::to_string(static_cast<unsigned>(cfg_.tcp_port));
    }
    flow_.close();
    return false;
  }
  logLine("listen=" + cfg_.tcp_bind + ":" +
          std::to_string(static_cast<unsigned>(loop_.bound_port())));

  // CTP 登录（不可用不阻塞启动 ⇒ DEGRADED + EVT_CTP_STATE，见 §4.2.6）
  std::string ctp_reason;
  if (cfg_.ctp_trade_front.empty()) {
    ctp_degraded_ = true;
    ctp_reason = "CTP 前置未配置（hg.ctp.trade_front 为空）";
  } else {
    ctp_login_attempted_ = true;
    const hg::ctp_adapter::CtLoginResult lr = ct_.login(cfg_.toCtConfig());
    if (lr.code != hg::ctp_adapter::kCtOk) {
      ctp_degraded_ = true;
      ctp_reason = "CTP 登录请求失败：" + lr.error_msg;
    } else if (!ct_.isLoggedIn()) {
      // 异步模型：结果以 SPI 回调为准，此刻尚未登录 ⇒ 如实标 DEGRADED。
      ctp_degraded_ = true;
      ctp_reason = "CTP 登录结果待回调（异步模型，当前未登录）";
    }
  }
  // 观测基线：异步登录结果以 isLoggedIn() 为准（后续迁移由 refreshCtpState 处理）。
  ctp_logged_in_observed_ = ct_.isLoggedIn();
  ctp_degraded_ = !ctp_logged_in_observed_;
  if (ctp_degraded_) logLine("ctp=DEGRADED " + ctp_reason);

  started_ = true;
  setState(AppState::kNotReady, "等待 Java 下发 CMD_LOAD_RISK_PARAMS");
  emitCtpState(now, ctp_reason);
  return true;
}

void TradingApp::stop() {
  if (state_ == AppState::kStopping) return;
  if (!started_) return;
  started_ = false;
  setState(AppState::kStopping, "收到退出信号/主动停机");

  // ⑤ 停机语义（TASKS T-207 ⑤「拒新指令、已发指令处理完**或超时**」）：
  //   · 拒新指令：返回后不再 pump 入站（调用方 main 此时已退出主循环）；
  //   · 已发指令处理完或超时：**有界排空**在途 CTP 回报——双闸 =
  //       ① 回报静默窗口 `kStopQuietMs`（连续该时长无新回报 ⇒ 视为已清空）；
  //       ② 预算 = `hg.cmd_timeout_ms`（CFG-28；超时即放弃等待，**不无界阻塞**）。
  //     未回报/未闭环部分由**下次启动重放对账**承接（确认路径已 append+fsync）。
  drainCtpOnStop(cfg_.cmd_timeout_ms);

  emitter_.detach();
  loop_.stop();
  ct_.logout();
  flow_.close();
  logLine("stopped");
}

void TradingApp::drainCtpOnStop(std::int64_t budget_ms) {
  const std::int64_t start = now_ms();
  std::int64_t last_event_ms = 0;
  bool saw_event = false;
  std::uint64_t drained = 0;
  for (;;) {
    bool any = false;
    hg::ctp_adapter::CtOrderEvent ev;
    while (ct_.pollOrderEvent(&ev)) {
      any = true;
      ++stats_.ctp_events;
      ++drained;
      hg::order_engine::EngineEvent out;
      if (engine_.onOrderEvent(ev, &out)) emitEngineEvent(out);
    }
    const std::int64_t now = now_ms();
    if (any) {
      saw_event = true;
      last_event_ms = now;
    }
    if (!saw_event) break;                          // 无在途回报可等 ⇒ 立即结束
    if (now - last_event_ms >= kStopQuietMs) break;  // 回报静默 ⇒ 视为已清空
    if (now - start >= budget_ms) {                  // 预算耗尽 ⇒ 放弃等待（有界）
      logLine("stop drain: budget exhausted（未回报部分由下次启动重放对账承接）");
      break;
    }
    struct timespec ts;
    ts.tv_sec = 0;
    ts.tv_nsec = kStopSliceNs;
    (void)::nanosleep(&ts, 0);
  }
  if (saw_event) {
    logLine("stop drain: ctp_events=" +
            std::to_string(static_cast<unsigned long long>(drained)));
  }
}

int TradingApp::pump(int timeout_ms) {
  if (!started_ || state_ == AppState::kStopping) return 0;
  const std::int64_t now = now_ms();
  std::vector<hg::hgcp::Frame> inbound;
  const int delivered = loop_.pump(timeout_ms < 0 ? 0 : timeout_ms, now, &inbound);
  stats_.frames_in += static_cast<std::uint64_t>(delivered);
  for (std::size_t i = 0; i < inbound.size(); ++i) handleCommand(inbound[i], now);
  pollCtp(now);
  refreshCtpState(now);
  driveExpire(now);
  advanceStartup();
  return delivered;
}

void TradingApp::handleCommand(const hg::hgcp::Frame& f, std::int64_t now) {
  // 非控制指令（HELLO / 心跳 / 补数请求等）由承载层消费 ⇒ consumed=false，无回执。
  const hg::hgcp::CommandOutcome out =
      handler_.handle(f.header.type, f.body, f.header.msg_id, now);
  if (!out.consumed) return;
  ++stats_.commands;

  if (out.reply.type != 0) {
    if (loop_.send_frame(out.reply.type, out.reply.flags, out.reply.body)) {
      ++stats_.replies;
    } else if (cfg_.verbose) {
      logLine("WARN 回执未能上线（无连接）：msg_id=" +
              std::to_string(static_cast<unsigned long long>(f.header.msg_id)));
    }
  }
  for (std::size_t i = 0; i < out.events.size(); ++i) {
    dispatchEvent(out.events[i].type, out.events[i].body);
  }
}

void TradingApp::dispatchEvent(std::uint16_t type, const std::vector<std::uint8_t>& body) {
  // §4.5.3 ⑦：READY 只能由启动编排在「重放一致 + 门卫通过」后发布。
  // CommandHandler 在参数生效时会产出 EVT_SYSTEM_STATE(READY)（参数面口径）；
  // 本装配在未 READY 前抑制该帧，由 advanceStartup() 统一补发权威帧
  // （重放未完成/失败 ⇒ 永不对外宣布 READY；回执 O-1 披露该分层）。
  if (type == mt(hg::hgcp::MessageType::kEvtSystemState) && state_ != AppState::kReady) {
    pbv1::EvtSystemState e;
    if (hg::hgcp::PbPayloadCodec::decode(body, &e) && e.state() == pbv1::SERVER_STATE_READY) {
      ++stats_.events_deferred;
      return;
    }
  }
  if (emitter_.emit(type, body) != hg::hgcp::EmitStatus::kNoLoop) ++stats_.events;
}

void TradingApp::emitSystemState(int pb_server_state, const std::string& reason) {
  pbv1::EvtSystemState e;
  e.set_state(static_cast<pbv1::ServerState>(pb_server_state));
  e.set_param_version_applied(risk_state_.param_version());
  e.set_ts_ms(now_ms());
  if (!reason.empty()) e.set_reason(reason);
  std::vector<std::uint8_t> body;
  if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
  if (emitter_.emit(mt(hg::hgcp::MessageType::kEvtSystemState), body) !=
      hg::hgcp::EmitStatus::kNoLoop) {
    ++stats_.events;
  }
}

void TradingApp::emitAlert(std::uint32_t alert_code, int pb_alert_level,
                           const std::string& detail) {
  pbv1::EvtTradingAlert e;
  e.set_alert_code(alert_code);
  e.set_level(static_cast<pbv1::AlertLevel>(pb_alert_level));
  e.set_detail(detail);
  e.set_alert_ts_ms(now_ms());
  std::vector<std::uint8_t> body;
  if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
  if (emitter_.emit(mt(hg::hgcp::MessageType::kEvtTradingAlert), body) !=
      hg::hgcp::EmitStatus::kNoLoop) {
    ++stats_.events;
  }
}

void TradingApp::emitCtpState(std::int64_t now, const std::string& reason) {
  pbv1::EvtCtpState e;
  e.set_trade_connected(ct_.isConnected());
  e.set_md_connected(ct_.isMdConnected());
  if (!cfg_.ctp_trade_front.empty()) e.set_front_addr(cfg_.ctp_trade_front);
  e.set_state_change_ts_ms(now);
  if (!reason.empty()) e.set_reason(reason);
  std::vector<std::uint8_t> body;
  if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
  if (emitter_.emit(mt(hg::hgcp::MessageType::kEvtCtpState), body) !=
      hg::hgcp::EmitStatus::kNoLoop) {
    ++stats_.events;
  }
}

void TradingApp::emitEngineEvent(const hg::order_engine::EngineEvent& ev) {
  std::uint16_t type = 0;
  std::vector<std::uint8_t> body;
  switch (ev.kind) {
    case hg::order_engine::EngineEvent::Kind::kReport: {
      pbv1::EvtOrderReport e;
      e.set_order_id(ev.report.order_id);
      if (!ev.report.order_sys_id.empty()) e.set_order_sys_id(ev.report.order_sys_id);
      if (!ev.report.order_local_id.empty()) e.set_order_local_id(ev.report.order_local_id);
      e.set_status(orderStatusToPb(ev.report.status));
      e.set_volume_total(ev.report.volume_total);
      e.set_volume_traded(ev.report.volume_traded);
      e.set_volume_left(ev.report.volume_left);
      if (ev.report.ctp_error_id != 0) e.set_ctp_error_id(ev.report.ctp_error_id);
      if (!ev.report.ctp_error_msg.empty()) e.set_ctp_error_msg(ev.report.ctp_error_msg);
      e.set_report_ts_ms(ev.report.report_ts_ms);
      type = mt(hg::hgcp::MessageType::kEvtOrderReport);
      if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
      break;
    }
    case hg::order_engine::EngineEvent::Kind::kFill: {
      pbv1::EvtTradeFill e;
      e.set_trade_id(ev.fill.trade_id);
      e.set_order_id(ev.fill.order_id);
      e.set_order_sys_id(ev.fill.order_sys_id);
      e.set_instrument_id(ev.fill.instrument_id);
      e.set_direction(ev.fill.is_sell ? pbv1::DIRECTION_SELL : pbv1::DIRECTION_BUY);
      e.set_offset_flag(ev.fill.is_close ? pbv1::OFFSET_FLAG_CLOSE : pbv1::OFFSET_FLAG_OPEN);
      e.set_price(fixedToStr(ev.fill.price, 1000000));
      e.set_volume(ev.fill.volume);
      e.set_trade_ts_ms(ev.fill.trade_ts_ms);
      e.set_sub_account_id(ev.fill.sub_account_id);
      e.set_trade_plan_id(ev.fill.trade_plan_id);
      e.set_exposure_id(ev.fill.exposure_id);
      type = mt(hg::hgcp::MessageType::kEvtTradeFill);
      if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
      break;
    }
    case hg::order_engine::EngineEvent::Kind::kExpired: {
      pbv1::EvtOrderExpired e;
      e.set_order_id(ev.expired.order_id);
      e.set_client_order_ref(ev.expired.client_order_ref);
      e.set_expired_ts_ms(ev.expired.expired_ts_ms);
      e.set_timeout_ms(ev.expired.timeout_ms);
      type = mt(hg::hgcp::MessageType::kEvtOrderExpired);
      if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
      break;
    }
    case hg::order_engine::EngineEvent::Kind::kCancelResult: {
      pbv1::EvtOrderCancelResult e;
      e.set_order_id(ev.cancel_out.order_id);
      e.set_cancelled_volume(ev.cancel_out.cancelled_volume);
      e.set_result(ev.cancel_out.result);
      if (ev.cancel_out.result != 0) e.set_reason_code(ev.cancel_out.reason_code);
      type = mt(hg::hgcp::MessageType::kEvtOrderCancelResult);
      if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
      break;
    }
    default:
      return;
  }
  if (type != 0 && emitter_.emit(type, body) != hg::hgcp::EmitStatus::kNoLoop) {
    ++stats_.events;
  }
}

void TradingApp::pollCtp(std::int64_t /*now*/) {
  // CTP 回报 → OrderEngine（T4 单写者）：状态迁移/落流水/事件载荷由引擎产出。
  hg::ctp_adapter::CtOrderEvent ev;
  while (ct_.pollOrderEvent(&ev)) {
    ++stats_.ctp_events;
    hg::order_engine::EngineEvent out;
    if (engine_.onOrderEvent(ev, &out)) emitEngineEvent(out);
  }
  // 行情：发布（T8 节流 + EVT_MARKET_QUOTE）未在本单装配 ⇒ 排空并计数（回执披露）。
  hg::ctp_adapter::CtQuoteEvent q;
  while (ct_.pollQuoteEvent(&q)) ++stats_.quotes_dropped;
}

// CTP 登录态观测：异步登录成功 / 断连都会改变 `isLoggedIn()`。
// 迁移时**清除或置位** `ctp_degraded_` 并补发 `EVT_CTP_STATE`（§4.2.3 #37 状态变更事件），
// ⇒ DEGRADED 不是「一次性粘滞标记」，CTP 恢复后自动解除（见回执 O-10）。
void TradingApp::refreshCtpState(std::int64_t now) {
  const bool logged = ct_.isLoggedIn();
  if (logged == ctp_logged_in_observed_) return;
  ctp_logged_in_observed_ = logged;
  ctp_degraded_ = !logged;
  const std::string reason = logged ? "CTP 登录成功（DEGRADED 解除）"
                                    : "CTP 未登录/连接断开（进入 DEGRADED）";
  logLine(std::string("ctp=") + (logged ? "LOGGED_IN " : "DEGRADED ") + reason);
  emitCtpState(now, reason);
}

void TradingApp::driveExpire(std::int64_t now) {
  if (last_expire_ms_ != 0 && now - last_expire_ms_ < kExpireScanPeriodMs) return;
  last_expire_ms_ = now;
  hg::order_engine::EngineEvent evs[8];
  const std::size_t n = engine_.expirePending(now, evs, 8);
  for (std::size_t i = 0; i < n; ++i) {
    ++stats_.expire_events;
    emitEngineEvent(evs[i]);
  }
}

// 重放失败终态帧（FR-TRADE-15 验收 3：重放结果可审计；§4.5.3 ⑥ MISMATCH 口径）。
void TradingApp::emitReplayTerminal(const std::string& detail) {
  pbv1::EvtReplayProgress e;
  e.set_state(pbv1::SERVER_STATE_NOT_READY);
  e.set_replayed_records(0);
  e.set_total_records(0);
  e.set_started_ts_ms(now_ms());
  e.set_result(pbv1::REPLAY_RESULT_MISMATCH);
  e.set_mismatch_detail(detail);
  e.set_finished_ts_ms(now_ms());
  std::vector<std::uint8_t> body;
  if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
  if (emitter_.emit(mt(hg::hgcp::MessageType::kEvtReplayProgress), body) !=
      hg::hgcp::EmitStatus::kNoLoop) {
    ++stats_.events;
  }
}

void TradingApp::replayProgress(void* user, hg::flow_store::FlowState st, std::uint64_t done,
                                std::uint64_t total) {  TradingApp* app = static_cast<TradingApp*>(user);
  if (app == 0) return;
  pbv1::EvtReplayProgress e;
  switch (st) {
    case hg::flow_store::FlowState::kReady: e.set_state(pbv1::SERVER_STATE_READY); break;
    case hg::flow_store::FlowState::kReplaying: e.set_state(pbv1::SERVER_STATE_REPLAYING); break;
    default: e.set_state(pbv1::SERVER_STATE_NOT_READY); break;
  }
  e.set_replayed_records(static_cast<std::int64_t>(done));
  e.set_total_records(static_cast<std::int64_t>(total));
  e.set_started_ts_ms(app->now_ms());
  e.set_result(pbv1::REPLAY_RESULT_OK);
  std::vector<std::uint8_t> body;
  if (!hg::hgcp::PbPayloadCodec::encode(e, &body)) return;
  if (app->emitter_.emit(mt(hg::hgcp::MessageType::kEvtReplayProgress), body) !=
      hg::hgcp::EmitStatus::kNoLoop) {
    ++app->stats_.events;
  }
}

bool TradingApp::advanceStartup() {
  if (state_ == AppState::kStopping) return false;
  if (!started_) return false;

  if (state_ == AppState::kNotReady) {
    // ④ 等参数收齐：RiskState.ready() 且 param_version > 0（§4.5.3 ④）
    if (!risk_state_.ready() || risk_state_.param_version() == 0) return false;

    // ⑤ REPLAYING：重放 + 在途对账 + 恢复台账
    setState(AppState::kReplaying,
             "param_version=" +
                 std::to_string(static_cast<unsigned long long>(risk_state_.param_version())));
    ++stats_.replay_runs;
    const std::int64_t now = now_ms();
    const hg::flow_store::ReplayReport rep =
        replay_.run(now, &TradingApp::replayProgress, this);
    const hg::order_engine::RecoveryExecOutcome rec = recovery_.execute(rep, now);
    for (std::size_t i = 0; i < rec.events.size(); ++i) emitEngineEvent(rec.events[i]);

    if (rep.verdict != hg::flow_store::ReplayVerdict::kOk || replay_.failed()) {
      const std::string detail =
          rep.detail.empty() ? std::string(hg::flow_store::replayVerdictName(rep.verdict))
                             : rep.detail;
      last_error_ = detail;
      setState(AppState::kFailed, detail);
      emitReplayTerminal(detail);
      emitAlert(replayErrorCode(rep.verdict), pbv1::ALERT_LEVEL_CRITICAL, detail);
      emitSystemState(pbv1::SERVER_STATE_NOT_READY, detail);
      return true;
    }
    logLine("replay verdict=OK records=" +
            std::to_string(static_cast<unsigned long long>(rep.replayed_records)) +
            " frozen_held=" +
            std::to_string(static_cast<unsigned long long>(rep.frozen_held)) +
            " unknown=" + std::to_string(static_cast<unsigned long long>(rep.unknown_count)));
  }

  // ⑥ 门卫为准（`CommandHandler::system_ready()` 是 0x0401 的**单一判定源**）：
  //   参数收齐 ∧ 重放就绪 ∧ 无未闭环未知在途。
  if (handler_.system_ready()) {
    if (state_ == AppState::kReady) return false;
    setState(AppState::kReady, "重放一致且门卫通过");
    emitSystemState(pbv1::SERVER_STATE_READY, "");
    return true;
  }

  if (state_ == AppState::kReplaying || state_ == AppState::kReady) {
    const std::string why = "存在未闭环未知在途单（需人工双人核验）";
    setState(AppState::kBlocked, why);
    emitAlert(static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kErrSystemNotReady),
              pbv1::ALERT_LEVEL_WARNING, why);
    emitSystemState(pbv1::SERVER_STATE_NOT_READY, why);
    return true;
  }
  // kBlocked / kFailed 且门卫仍未过 ⇒ 保持现状（不回退、不宣告任何就绪）。
  return false;
}

}  // namespace app
}  // namespace hg
