// hg-trading 启动编排与主循环实现（T-207）。
//
// 时间一律由 `now_ms()` 注入（缺省系统实时 ms），主循环单步内**只取一次**，
// 供 server_loop / CommandHandler / OrderEngine / ReplayController 共用
// （ARCH §2.4.2「无隐藏时钟」）。
#include "trading_app.h"

#include <dirent.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <time.h>

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <map>
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

// T-211：本地管理入口（人工核验释放）低频轮询节拍（≥ 1 s，见 trading_app.h 头注）。
//   目的：**不得在每次 `pump` 做文件 I/O** —— 交易判定路径零同步 I/O 的精神。
const std::int64_t kAdminPollPeriodMs = 1000;

// T-211 管理文件约定（路径由既有 CFG-29 `hg.flow.dir` 派生，**不新增配置键**）。
const char* const kAdminSubDir = "admin";
const char* const kAdminRequestName = "release-request.json";
const char* const kAdminProcessedSub = "processed";
const char* const kAdminFailedSub = "failed";

// T-211：读整文件（rb）。失败 ⇒ false。
bool readWholeFile(const std::string& path, std::string* out) {
  std::FILE* f = std::fopen(path.c_str(), "rb");
  if (f == 0) return false;
  char buf[4096];
  std::size_t n = 0;
  out->clear();
  while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) out->append(buf, n);
  std::fclose(f);
  return true;
}

// T-211：跳过空白（含 UTF-8 BOM 之外的常规空白）。
void skipWs(const std::string& s, std::size_t* i) {
  while (*i < s.size() && (s[*i] == ' ' || s[*i] == '\t' || s[*i] == '\r' || s[*i] == '\n')) {
    ++*i;
  }
}

// T-211：解析一个 JSON 字符串字面量（`"…"` ⇒ 内容）。识别 `\` 转义（原样保留下一字符）。
bool parseJsonString(const std::string& s, std::size_t* i, std::string* out) {
  if (*i >= s.size() || s[*i] != '"') return false;
  ++*i;
  out->clear();
  while (*i < s.size()) {
    const char c = s[*i];
    if (c == '\\') {
      if (*i + 1 >= s.size()) return false;
      out->push_back(s[*i + 1]);
      *i += 2;
      continue;
    }
    if (c == '"') {
      ++*i;
      return true;
    }
    out->push_back(c);
    ++*i;
  }
  return false;  // 未闭合
}

// T-211：解析**平面 JSON 对象**（仅 `{ "k": <string|bare> , … }`，无嵌套）。
//   · 值为 `"…"`（字符串）或裸词（数字/true/false，读到 `,`/`}`/空白 止）；
//   · 未知键**不报错**（忽略由调用方决定）；语法错 ⇒ false + *why。
//   动机：零新增依赖（不引入 JSON 库，PROJECT.md §5.1 / 红线「勿新增依赖」）。
bool parseFlatObject(const std::string& text, std::vector<std::pair<std::string, std::string> >* kv,
                     std::string* why) {
  std::size_t i = 0;
  skipWs(text, &i);
  if (i >= text.size() || text[i] != '{') {
    if (why != 0) *why = "JSON 顶层须为对象 {…}";
    return false;
  }
  ++i;
  skipWs(text, &i);
  if (i < text.size() && text[i] == '}') {
    ++i;
    return true;  // 空对象（必需键缺失由 parseReleaseRequest 判）
  }
  for (;;) {
    skipWs(text, &i);
    std::string key;
    if (!parseJsonString(text, &i, &key)) {
      if (why != 0) *why = "键名须为 JSON 字符串";
      return false;
    }
    skipWs(text, &i);
    if (i >= text.size() || text[i] != ':') {
      if (why != 0) *why = "键名后缺 ':'";
      return false;
    }
    ++i;
    skipWs(text, &i);
    std::string val;
    if (i < text.size() && text[i] == '"') {
      if (!parseJsonString(text, &i, &val)) {
        if (why != 0) *why = "字符串值未闭合";
        return false;
      }
    } else {
      // 裸词（数字 / true / false）：读到分隔符止。
      const std::size_t b = i;
      while (i < text.size() && text[i] != ',' && text[i] != '}') ++i;
      val = text.substr(b, i - b);
      // 去尾空白。
      while (!val.empty() && (val[val.size() - 1] == ' ' || val[val.size() - 1] == '\t' ||
                              val[val.size() - 1] == '\r' || val[val.size() - 1] == '\n')) {
        val.resize(val.size() - 1);
      }
      if (val.empty()) {
        if (why != 0) *why = "值缺失（键 " + key + "）";
        return false;
      }
    }
    kv->push_back(std::make_pair(key, val));
    skipWs(text, &i);
    if (i >= text.size()) {
      if (why != 0) *why = "对象未闭合（缺 '}'）";
      return false;
    }
    if (text[i] == ',') {
      ++i;
      continue;
    }
    if (text[i] == '}') {
      ++i;
      skipWs(text, &i);
      break;
    }
    if (why != 0) *why = "键值对之间须为 ',' 或 '}'";
    return false;
  }
  return true;
}

// T-211：无符号十进制解析（整串；空/非数字/溢出 ⇒ false）。
bool parseUint64Strict(const std::string& s, std::uint64_t* out) {
  if (s.empty()) return false;
  for (std::size_t i = 0; i < s.size(); ++i) {
    if (s[i] < '0' || s[i] > '9') return false;
  }
  errno = 0;
  char* end = 0;
  const unsigned long long v = std::strtoull(s.c_str(), &end, 10);
  if (errno == ERANGE || end == 0 || *end != '\0') return false;
  *out = static_cast<std::uint64_t>(v);
  return true;
}

// T-211：文件权限校验 —— **必须恰为 0600**（属主 rw、组/其他零），否则拒绝处理。
//   用 `lstat`（**不跟随符号链接**）：防经符号链接读取域外文件、绕开权限模型。
//   lstat 失败（不存在）或非普通文件 ⇒ ok=false。
bool checkAdminFileMode(const std::string& path, std::string* why) {
  struct stat st;
  if (::lstat(path.c_str(), &st) != 0) {
    if (why != 0) *why = "无法 lstat 请求文件（errno=" + std::to_string(errno) + "）";
    return false;
  }
  if (S_ISLNK(st.st_mode)) {
    if (why != 0) *why = "请求路径为符号链接（拒绝处理，防读域外文件）";
    return false;
  }
  if (!S_ISREG(st.st_mode)) {
    if (why != 0) *why = "请求路径非普通文件";
    return false;
  }
  const unsigned mode = static_cast<unsigned>(st.st_mode) & 0777u;
  if (mode != 0600u) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%04o", mode);
    if (why != 0) {
      *why = std::string("请求文件权限必须为 0600（NFR-DEPLOY-4），实得 ") + buf;
    }
    return false;
  }
  return true;
}

// T-211：把一个文件移入归档目录（同文件系统 rename）；目标重名 ⇒ 追加 `.N` 去重。
//   成功 ⇒ true 且 *moved_to 有效。归档动作**可审计**（文件保留于 processed/failed）。
bool archiveAdminFile(const std::string& src, const std::string& dst_dir,
                      std::string* moved_to) {
  const std::string::size_type slash = src.find_last_of('/');
  const std::string base = (slash == std::string::npos) ? src : src.substr(slash + 1);
  std::string dst = dst_dir + "/" + base;
  for (int n = 1; n < 1000; ++n) {
    struct stat st;
    if (::stat(dst.c_str(), &st) != 0) break;  // 目标不存在 ⇒ 可用
    dst = dst_dir + "/" + base + "." + std::to_string(n);
  }
  if (::rename(src.c_str(), dst.c_str()) != 0) return false;
  if (moved_to != 0) *moved_to = dst;
  return true;
}

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
      replay_runs(0),
      admin_requests(0),
      admin_released(0),
      admin_rejected(0),
      admin_polls(0) {}

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
      replay_started_ms_(0),
      admin_dir_(),
      last_admin_poll_ms_(0),
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

  // ---- T-211：本地管理入口目录（由 CFG-29 `hg.flow.dir` 派生；不新增配置键）----
  admin_dir_ = cfg_.flow_dir;
  if (!admin_dir_.empty() && admin_dir_[admin_dir_.size() - 1] == '/') {
    admin_dir_.resize(admin_dir_.size() - 1);
  }
  admin_dir_ += std::string("/") + kAdminSubDir;

  // ---- 事件层（T6 装配）----
  emitter_.attach();
  emitter_.set_rebuilder(&rebuilder_);
  emitter_.set_alert_sink(&emitterAlert, 0);

  // ---- 会话参数（§4.2.6：心跳间隔 / 丢失阈值 3 / 补数缓冲容量）----
  loop_.configure(cfg_.heartbeat_ms, hg::hgcp::kHeartbeatMissThreshold,
                  hg::hgcp::kEventQueueCapacity);

  // ---- T-208：组件状态源注入（`MSG_HELLO_ACK.server_state` / `param_version`）----
  //   `hgcp` 不反向依赖本类 ⇒ 经函数指针 + user 注入（本类为回调实现方）。
  //   状态源同步求值、无副作用；见 `componentState`。
  loop_.set_component_state_fn(&TradingApp::componentState, this);

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

  // T-211：本地管理入口目录（`<hg.flow.dir>/admin` + 归档子目录）。
  //   目录仅为**运维投递/归档**用途，不参与交易判定；创建失败不阻断启动
  //   （人工核验入口在流水分片不可写时更不重要，且不改变交易路径语义）——仅告警。
  {
    std::string derr;
    const std::string pdir = admin_dir_ + "/" + kAdminProcessedSub;
    const std::string fdir = admin_dir_ + "/" + kAdminFailedSub;
    if (!ensureDir(admin_dir_, &derr) || !ensureDir(pdir, &derr) || !ensureDir(fdir, &derr)) {
      logLine("WARN 本地管理入口目录不可用（人工核验释放入口不可用）：" + derr);
    } else {
      logLine("admin dir=" + admin_dir_);
    }
  }

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
  // T-211：本地管理入口低频轮询（≥ 1 s；**不每 pump 做 I/O**）。
  //   在 `advanceStartup()` 之前处理 ⇒ 同一步内即可完成「台账闭环 → READY 迁移」。
  if (last_admin_poll_ms_ == 0 || now - last_admin_poll_ms_ >= kAdminPollPeriodMs) {
    last_admin_poll_ms_ = now;
    pollAdminRelease(now);
  }
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

// T-208：`AppState` → §4.2.8.7 `ServerState`（**4 值 + UNSET，不新增枚举值**）。
//   `kBlocked` / `kFailed` ⇒ `NOT_READY`（协议侧仍以 0x0401 反映「未就绪」，不得伪装 READY）。
namespace {

std::int32_t toServerState(AppState s) {
  switch (s) {
    case AppState::kNotReady: return pbv1::SERVER_STATE_NOT_READY;
    case AppState::kReplaying: return pbv1::SERVER_STATE_REPLAYING;
    case AppState::kReady: return pbv1::SERVER_STATE_READY;
    case AppState::kStopping: return pbv1::SERVER_STATE_STOPPING;
    case AppState::kBlocked:
    case AppState::kFailed: return pbv1::SERVER_STATE_NOT_READY;
    default: return pbv1::SERVER_STATE_NOT_READY;
  }
}

}  // namespace

void TradingApp::componentState(void* user, std::int32_t* server_state,
                                std::uint64_t* param_version) {
  TradingApp* app = static_cast<TradingApp*>(user);
  if (app == 0) {
    if (server_state != 0) *server_state = pbv1::SERVER_STATE_NOT_READY;
    if (param_version != 0) *param_version = 0;
    return;
  }
  // 状态源在 `pump()` 调用方线程内同步求值（无锁；本类无内部线程）——
  //   `MSG_HELLO_ACK` 编码时读取的即当前权威状态。
  if (server_state != 0) *server_state = toServerState(app->state_);
  if (param_version != 0) *param_version = app->risk_state_.param_version();
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
//   T-210：`started_ts_ms` 取**重放会话起点**（与本文件成功路径同源、单一口径）。
//   本函数仅由 `advanceStartup()` 的失败分支调用，而该分支恒在 `replay_started_ms_`
//   记录之后（`replay_.run` 之前）⇒ 起点必已就绪。
void TradingApp::emitReplayTerminal(const std::string& detail) {
  pbv1::EvtReplayProgress e;
  e.set_state(pbv1::SERVER_STATE_NOT_READY);
  e.set_replayed_records(0);
  e.set_total_records(0);
  e.set_started_ts_ms(replay_started_ms_);
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

// 启动自动重放的进度回调（§4.5.3 ⑤；§4.2.8.5 条件必填）。
//
// 字段口径（T-210 统一，与 `CMD_TRIGGER_REPLAY` 路径同口径）：
//   · `started_ts_ms` = **重放会话起点** `replay_started_ms_`（进入 REPLAYING 时记一次）
//     —— 同一份进度流内**多帧同值**；**不得**逐帧取 `now_ms()`。
//   · `finished_ts_ms` = **条件必填**：**仅终态帧**（`st == kReady`，即重放完成）填；
//     中间进度帧（`kReplaying`）**不得**填（缺省语义 = 0/未设，接收侧不得据此拒绝）。
//   · `mismatch_detail` = 条件必填（`result = MISMATCH`）：本回调恒产出
//     `REPLAY_RESULT_OK`（失败终态帧由 `emitReplayTerminal` 承载）⇒ 不填，符契约。
void TradingApp::replayProgress(void* user, hg::flow_store::FlowState st, std::uint64_t done,
                                std::uint64_t total) {
  TradingApp* app = static_cast<TradingApp*>(user);
  if (app == 0) return;
  pbv1::EvtReplayProgress e;
  const bool finished = (st == hg::flow_store::FlowState::kReady);
  switch (st) {
    case hg::flow_store::FlowState::kReady: e.set_state(pbv1::SERVER_STATE_READY); break;
    case hg::flow_store::FlowState::kReplaying: e.set_state(pbv1::SERVER_STATE_REPLAYING); break;
    default: e.set_state(pbv1::SERVER_STATE_NOT_READY); break;
  }
  e.set_replayed_records(static_cast<std::int64_t>(done));
  e.set_total_records(static_cast<std::int64_t>(total));
  e.set_started_ts_ms(app->replay_started_ms_);
  e.set_result(pbv1::REPLAY_RESULT_OK);
  if (finished) e.set_finished_ts_ms(app->now_ms());
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
    // T-210：重放会话起点（同一份进度流内多帧同值）——与 `CMD_TRIGGER_REPLAY` 路径
    //   的 `ctx.started_ms` 同口径；`replayProgress` 从本成员取 `started_ts_ms`。
    replay_started_ms_ = now;
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

// =============================================================================
// T-211：本地管理入口（「未知在途」人工核验释放）
//   形态 (b) 进程内管理入口 = 本地管理文件轮询（编排者 2026-10-29 裁定，见 trading_app.h 头注）。
//   本节的 I/O **全部在 `trading_app.cc`**（不在 E4 扫描根），且经低频节流
//   （`kAdminPollPeriodMs ≥ 1 s`）—— 守「交易判定路径零同步 I/O」精神。
// =============================================================================

bool TradingApp::parseReleaseRequest(const std::string& text,
                                     hg::flow_store::ManualReleaseRequest* req,
                                     std::string* why) {
  if (req == 0) {
    if (why != 0) *why = "内部错误：req 为空";
    return false;
  }
  std::vector<std::pair<std::string, std::string> > kv;
  std::string perr;
  if (!parseFlatObject(text, &kv, &perr)) {
    if (why != 0) *why = perr;
    return false;
  }
  // 逐个取值（键存在性以 found_* 记；未知键忽略）。
  bool has_order = false, has_ref = false, has_a = false, has_b = false, has_reason = false;
  std::string v_order, v_ref, v_a, v_b, v_reason;
  for (std::size_t i = 0; i < kv.size(); ++i) {
    const std::string& k = kv[i].first;
    if (k == "order_id") { has_order = true; v_order = kv[i].second; }
    else if (k == "client_order_ref") { has_ref = true; v_ref = kv[i].second; }
    else if (k == "operator_a_id") { has_a = true; v_a = kv[i].second; }
    else if (k == "operator_b_id") { has_b = true; v_b = kv[i].second; }
    else if (k == "reason") { has_reason = true; v_reason = kv[i].second; }
    // 未知键 ⇒ 忽略（前向兼容；不因未知键拒绝，避免部署前滚失败）。
  }
  // 必需键缺失 ⇒ 解析失败（拒因精确到键名；缺 operator_b 亦在此拦下）。
  if (!has_order) { if (why != 0) *why = "缺必需键 order_id"; return false; }
  if (!has_ref) { if (why != 0) *why = "缺必需键 client_order_ref"; return false; }
  if (!has_a) { if (why != 0) *why = "缺必需键 operator_a_id"; return false; }
  if (!has_b) { if (why != 0) *why = "缺必需键 operator_b_id"; return false; }
  if (!has_reason) { if (why != 0) *why = "缺必需键 reason"; return false; }
  if (!parseUint64Strict(v_order, &req->order_id)) {
    if (why != 0) *why = "order_id 非无符号整数：" + v_order;
    return false;
  }
  if (!parseUint64Strict(v_a, &req->operator_a_id)) {
    if (why != 0) *why = "operator_a_id 非无符号整数：" + v_a;
    return false;
  }
  if (!parseUint64Strict(v_b, &req->operator_b_id)) {
    if (why != 0) *why = "operator_b_id 非无符号整数：" + v_b;
    return false;
  }
  req->client_order_ref = v_ref;
  req->reason = v_reason;
  return true;
}

void TradingApp::rejectAdminRelease(const std::string& path, const std::string& reason,
                                    std::uint32_t alert_code) {
  ++stats_.admin_requests;
  ++stats_.admin_rejected;
  const std::string archived = admin_dir_ + "/" + kAdminFailedSub;
  std::string moved;
  const bool ok = archiveAdminFile(path, archived, &moved);
  logLine("ALERT 人工核验释放请求被拒（状态与台账零变更）：reason=" + reason +
          (ok ? ("；已归档 " + moved) : std::string("；归档失败（请求文件保留在原处）")));
  // 告警留痕（EVT_TRADING_ALERT；无连接时 emit 返回 kNoLoop，日志行仍是留痕）。
  //   alert_code 由调用点显式给定：解析/权限类 = 0x0104（前置非法）；
  //   五守卫拒绝 = 0x0401（系统仍未就绪，语义同 BLOCKED 告警）。
  emitAlert(alert_code, pbv1::ALERT_LEVEL_WARNING, "手动核验释放被拒：" + reason);
}

bool TradingApp::handleAdminReleaseFile(const std::string& path, std::int64_t now) {
  // ① 权限守卫（**必须恰 0600**；`lstat` 拒符号链接，防经链接读取域外文件）。
  std::string why;
  if (!checkAdminFileMode(path, &why)) {
    rejectAdminRelease(path, "权限/类型不符：" + why,
                       static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kErrPayloadInvalid));
    return false;
  }
  // ② 读全文 + 解析（解析失败 ⇒ 零变更）。
  std::string text;
  if (!readWholeFile(path, &text)) {
    rejectAdminRelease(path,
                       std::string("读取请求文件失败（errno=") + std::to_string(errno) + "）",
                       static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kErrPayloadInvalid));
    return false;
  }
  hg::flow_store::ManualReleaseRequest req;
  if (!parseReleaseRequest(text, &req, &why)) {
    rejectAdminRelease(path, "解析失败：" + why,
                       static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kErrPayloadInvalid));
    return false;
  }
  // ③ 复用既有五守卫（`RecoveryHook::manualRelease` → `RecoveryCoordinator::
  //    manualVerifyRelease`；**不另写一套守卫**）。拒绝 ⇒ 台账/引擎零变更。
  hg::flow_store::ManualReleaseError err = hg::flow_store::ManualReleaseError::kNone;
  hg::order_engine::EngineEvent ev;
  if (!recovery_.manualRelease(req, now, &err, &ev)) {
    rejectAdminRelease(path,
                       std::string("守卫拒绝：") + hg::flow_store::manualReleaseErrorName(err),
                       static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kErrSystemNotReady));
    return false;
  }
  // ④ 成功：台账已闭环（open_count 已 -1）⇒ 归档 processed/（幂等：文件被移走，
  //    同一文件不会被二次处理）。引擎终态化的外部通知经 **既有** `emitEngineEvent`
  //    通道补发 EVT_ORDER_EXPIRED（与 kReleaseExpired 决策路径同形，不新开通道）。
  ++stats_.admin_requests;
  ++stats_.admin_released;
  logLine("人工核验释放成功：order_id=" +
          std::to_string(static_cast<unsigned long long>(req.order_id)) +
          " A=" + std::to_string(static_cast<unsigned long long>(req.operator_a_id)) +
          " B=" + std::to_string(static_cast<unsigned long long>(req.operator_b_id)) +
          "；台账未闭环=" +
          std::to_string(static_cast<unsigned long long>(recovery_open_count())));
  if (ev.kind != hg::order_engine::EngineEvent::Kind::kNone) {
    emitEngineEvent(ev);
  }
  const std::string archived = admin_dir_ + "/" + kAdminProcessedSub;
  std::string moved;
  if (!archiveAdminFile(path, archived, &moved)) {
    logLine("WARN 释放成功但归档失败（请求文件保留在原处，须人工清理以防重复处理）：" + path);
  }
  // ⑤ 就绪推进**不在此处**另开通道 —— 由 `advanceStartup()` 既有路径在台账清零后
  //    自动迁移 BLOCKED → READY 并补发 `EVT_SYSTEM_STATE(READY)`（恰 1 帧）。
  return true;
}

std::size_t TradingApp::pollAdminRelease(std::int64_t now) {
  if (!started_ || state_ == AppState::kStopping) return 0;
  ++stats_.admin_polls;
  const std::string req_path = admin_dir_ + "/" + kAdminRequestName;
  struct stat st;
  if (::lstat(req_path.c_str(), &st) != 0) return 0;  // 无请求文件 ⇒ 零行为
  (void)handleAdminReleaseFile(req_path, now);
  return 1;
}

}  // namespace app
}  // namespace hg
