#ifndef HG_TRADING_APP_H
#define HG_TRADING_APP_H
// =============================================================================
// hg-trading 启动编排与主循环（T-207；ARCHITECTURE.md §2.4.1 / §2.4.2 T1 / §4.5.3）
//
// 本类把六模块装配为**长驻进程**，并把启动时序固化在代码里：
//
//   ① 配置校验（`app_config`）→ 对象按依赖序构造（构造期即装配，见成员声明顺序）
//   ② 打开流水目录 + 当日分片（`hg.flow.dir`；不可用 ⇒ 拒绝启动，非 0 退出）
//   ③ 建立回环监听（127.0.0.1:hg.tcp.port）—— **不接受业务指令**
//        · 未收齐参数 ⇒ 交易指令由 `CommandHandler::system_ready()` 回 0x0401（不入队）
//   ④ 等 Java 分批下发 `CMD_LOAD_RISK_PARAMS` 收齐（`RiskState::ready()` + version>0）
//   ⑤ state = REPLAYING：`ReplayController::run`（重放 + 在途对账）→
//      `RecoveryHook::execute`（OrderMap 还原 + 决策执行 + 未知台账登记）
//   ⑥ 对账一致且门卫通过 ⇒ state = READY + `EVT_SYSTEM_STATE(READY, param_version)`
//      损坏 / 不一致 / 存在未闭环未知在途 ⇒ **永不 READY**（FAILED / BLOCKED）+ 告警
//
// 主循环（T4/T5/T6 同泵线程，单线程驱动，无内部线程）：
//   `pump(timeout)` = HgcpServerLoop::pump（accept/读帧/心跳/补数）
//                   → 入站帧交 `CommandHandler` → 回执/事件经 `EventEmitter`
//                   → CTP 回报轮询入 `OrderEngine` → 待确认超时扫描
//                   → 启动就绪推进；时间统一取 `now_ms()`（可注入）。
//
// 线程模型说明（诚实披露）：ARCH §2.4.2 的 T5/T6/T7 为独立线程；本期装配为
//   **单泵线程**（事件与流水同线程同步落盘），无共享可变状态 ⇒ 无锁、无竞争；
//   T2/T3 回调线程由 CTP SDK 提供，仅向无锁队列投递（`CtAdapter` 既有实现）。
//   独立线程化（T4/T5/T6/T7 拆分）不在本单写域内，回执已披露。
//
// 所有权：全部模块对象为**本类成员**（值语义，按依赖序声明 ⇒ 逆序析构自动正确）；
//   模块间只持裸指针引用，不取得所有权。
//
// ---- T-211：本地管理入口（「未知在途」人工核验释放）--------------------------------
// 缺口：`RecoveryHook::manualRelease` / `RecoveryCoordinator::manualVerifyRelease`
//   均已实现五守卫，但**全仓零调用点** ⇒ 一旦重放产生「状态未知」在途单，`system_ready()`
//   第三合取项恒 false ⇒ 系统永久 `BLOCKED`（重启亦无用）。承载形态由编排者 2026-10-29
//   裁定为 **(b) 进程内管理入口 = 本地管理文件轮询**（理由见工单：`recovery.h` 头注原意
//   「管理面归 Java/运维侧，不扩协议」+ 部署脚本 `setsid` 后台启动致 stdin 不可行 +
//   `NFR-DEPLOY-4`「本地 admin 配置可持久化（文件，权限 600）」同族惯例 + 本文件不在
//   `E4` 扫描根；**不扩协议、不新增 `CMD_*`、不改线格式**）。
//
// 约定（**选型依据见交付回执**）：
//   · 请求文件路径 = `<hg.flow.dir>/admin/release-request.json`（由既有 CFG-29 `hg.flow.dir`
//     派生，**不新增配置键** —— `app_config.*` 不在本单写域；同族「本地 admin 可持久化」）；
//   · 权限 = **必须恰为 `0600`**（属主可读写、组/其他零权限），否则拒绝处理并告警
//     （`NFR-DEPLOY-4` 同族；权限模型不匹配 ⇒ 先报后改，不另择通道）；
//   · 格式 = **平面 JSON 对象**，字段逐项对齐 `flow_store::ManualReleaseRequest`：
//       `order_id` / `client_order_ref` / `operator_a_id` / `operator_b_id` / `reason`
//     （无第三方 JSON 库 ⇒ 内置极简平面对象解析；未知键忽略，缺必需键 ⇒ 解析失败）；
//   · 处理 = 解析后**原样调用 `RecoveryHook::manualRelease` ⇒ 复用既有五守卫**
//     （**不另写守卫**）；成功 ⇒ 台账闭环 ⇒ `system_ready()` 第三合取恢复 ⇒ 由
//     **`advanceStartup()` 既有补发路径**迁移 `READY` 并补发 `EVT_SYSTEM_STATE(READY)`
//     （**恰 1 帧**，不另开通道）；失败 ⇒ 台账与引擎**零变更**、状态不变、告警留痕（含拒因）；
//   · 幂等与归档 = 处理后**移出请求路径**至 `<admin>/processed/`（成功）或
//     `<admin>/failed/`（拒绝）⇒ 同一文件**不被重复处理**，归档可审计；
//   · 轮询节拍 = 主循环低频轮询（`kAdminPollPeriodMs = 1000 ms`，**≥ 1 s**）；**不在每次
//     `pump` 做文件 I/O**（`last_admin_poll_ms_` 节流），守「交易判定路径零同步 I/O」精神。
//   · 时钟统一取 `now_ms()`；`admin_*` 计数进 `AppStats`（诊断/测试）。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "app_config.h"
#include "hg/ctp_adapter/ct_adapter.h"
#include "hg/flow_store/flow_store.h"
#include "hg/flow_store/recovery.h"
#include "hg/flow_store/replay.h"
#include "hg/hgcp/auth.h"
#include "hg/hgcp/command_handler.h"
#include "hg/hgcp/event_emitter.h"
#include "hg/hgcp/server_loop.h"
#include "hg/order_engine/order_engine.h"
#include "hg/order_engine/recovery_hook.h"
#include "hg/order_engine/trade_gateway.h"
#include "hg/risk_engine/risk_engine.h"
#include "hg/risk_state/risk_state.h"

namespace hg {
namespace app {

// 进程状态（ARCH §4.5.3；`kBlocked` 为本装配的诚实扩展 = 重放已完成但存在
// 「状态未知」在途单（`RecoveryHook::has_unknown_open()`），须人工双人核验闭环
// 后方可 READY —— 协议侧仍以 0x0401 反映「未就绪」，**不新增协议状态值**）。
enum class AppState {
  kNotReady = 0,   // 已监听，等参数下发（交易指令 0x0401）
  kReplaying = 1,
  kReady = 2,
  kBlocked = 3,    // 重放 OK 但门卫未过（未知在途未闭环）⇒ 仍 0x0401
  kFailed = 4,     // 流水损坏/对账不一致/目录不可读 ⇒ 永不 READY
  kStopping = 5
};

const char* appStateName(AppState s);

// 运行期计数（诊断/测试；不含敏感信息）。
struct AppStats {
  std::uint64_t frames_in;       // 入站帧（含 HELLO/心跳等承载帧）
  std::uint64_t commands;        // 控制指令（0x0101..0x011E）消费数
  std::uint64_t replies;         // 已上线回执帧
  std::uint64_t events;          // 已上线事件帧
  std::uint64_t events_deferred;  // 就绪前被抑制的 EVT_SYSTEM_STATE(READY)
  std::uint64_t ctp_events;      // 已消费 CTP 回报
  std::uint64_t quotes_dropped;  // 已丢弃行情（行情发布未装配，见回执披露）
  std::uint64_t expire_events;   // 超时失效事件
  std::uint64_t replay_runs;     // 重放执行次数

  // T-211 本地管理入口（人工核验释放）计数。
  std::uint64_t admin_requests;  // 发现并处理的请求文件数（含被拒）
  std::uint64_t admin_released;  // 五守卫通过并完成释放
  std::uint64_t admin_rejected;  // 解析/权限/守卫任一拒绝（状态零变更）
  std::uint64_t admin_polls;     // 实际执行的轮询次数（**节流后**；证「非每 pump 做 I/O」）

  AppStats();
};

class TradingApp {
 public:
  // 时钟注入（缺省 = 系统实时 ms；测试注入确定性时刻）。
  typedef std::int64_t (*ClockFn)(void* user);

  explicit TradingApp(const AppConfig& cfg);
  ~TradingApp();

  // 启动编排（§4.5.3 ①②③）。false ⇒ 调用方须以非 0 退出；*error 载原因。
  bool start(std::string* error);

  // 单步主循环；返回本步入站帧数。timeout_ms ≤ 0 ⇒ 非阻塞。
  int pump(int timeout_ms);

  // 优雅停机：STOPPING（拒新指令）→ 停监听 → 释放资源。幂等。
  void stop();

  // 就绪推进（pump 内自动调用；测试可直接驱动）。返回是否发生状态迁移。
  bool advanceStartup();

  // ---- 观测面 ----
  AppState state() const { return state_; }
  const char* stateName() const { return appStateName(state_); }
  bool listening() const { return loop_.listening(); }
  std::uint16_t bound_port() const { return loop_.bound_port(); }
  const AppStats& stats() const { return stats_; }
  const AppConfig& config() const { return cfg_; }
  const std::string& last_error() const { return last_error_; }
  bool ctp_degraded() const { return ctp_degraded_; }
  bool ctp_login_attempted() const { return ctp_login_attempted_; }
  std::uint64_t param_version() const { return risk_state_.param_version(); }
  // 0x0401 单一门卫的只读投影（= CommandHandler::system_ready()）。
  bool system_ready() const { return handler_.system_ready(); }
  std::size_t recovery_open() const { return ledger_.entry_count(); }
  // T-211：台账**未闭环**计数（= `has_unknown_open()` 的数值投影；释放后归零）。
  //   与 `recovery_open()`（登记总数，含已释放）区分，供人工核验闭环断言。
  std::size_t recovery_open_count() const { return ledger_.view().open_count; }

  // ---- T-211 本地管理入口观测面（测试/诊断；非交易路径）----
  const std::string& admin_dir() const { return admin_dir_; }  // = <hg.flow.dir>/admin
  // 单次轮询（**测试可直接驱动**；`pump` 内按 `kAdminPollPeriodMs` 节流调用）。
  // 返回本次**实际处理**的请求数（0 ⇒ 无新请求 / 未到节拍由调用方判定）。
  // 无请求文件 ⇒ 零 I/O 开销外的行为（仅 stat 一次）；请求文件在 ⇒ 处理后必归档移走。
  std::size_t pollAdminRelease(std::int64_t now);

  void set_clock(ClockFn fn, void* user);
  std::int64_t now_ms() const;

 private:
  TradingApp(const TradingApp&);
  TradingApp& operator=(const TradingApp&);

  // 入站控制指令 → 回执 + 事件。
  void handleCommand(const hg::hgcp::Frame& f, std::int64_t now);
  // 事件统一出口（就绪前抑制 ERROR/READY 混合中的 READY；见实现）。
  void dispatchEvent(std::uint16_t type, const std::vector<std::uint8_t>& body);
  // 引擎事件 → HGCP 事件帧（EVT_ORDER_REPORT/TRADE_FILL/ORDER_EXPIRED/CANCEL_RESULT）。
  void emitEngineEvent(const hg::order_engine::EngineEvent& ev);
  // CTP 回报 / 行情轮询。
  void pollCtp(std::int64_t now);
  // CTP 登录态变化观测（异步登录成功/断连 ⇒ 更新 degraded 标记 + 发 EVT_CTP_STATE）。
  void refreshCtpState(std::int64_t now);
  // 停机有界等待：拒新指令后排空在途回报（静默窗口 / 预算双闸）。
  void drainCtpOnStop(std::int64_t budget_ms);
  // 待确认指令超时扫描（R-TRADE-6 / CFG-28）。
  void driveExpire(std::int64_t now);

  // T-211：本地管理入口 —— 解析一个请求文件的平面 JSON ⇒ `ManualReleaseRequest`。
  //   成功 ⇒ true 且 *req 填充；失败 ⇒ false 且 *why 载拒因（可空）。未知键忽略。
  //   必需键 = order_id / client_order_ref / operator_a_id / operator_b_id / reason。
  static bool parseReleaseRequest(const std::string& text,
                                  hg::flow_store::ManualReleaseRequest* req,
                                  std::string* why);
  // T-211：单请求文件的处理（权限校验 → 解析 → 五守卫 → 归档）。
  //   返回 true = 五守卫通过并完成释放；false = 拒绝（含拒因，已归档 failed/）。
  bool handleAdminReleaseFile(const std::string& path, std::int64_t now);
  // T-211：拒绝路径统一收口 —— 归档 failed/ + 告警留痕（含拒因）+ 计数。
  //   alert_code 由调用点显式给定（解析/权限类 vs 守卫类语义不同）。
  void rejectAdminRelease(const std::string& path, const std::string& reason,
                          std::uint32_t alert_code);

  void emitSystemState(int pb_server_state, const std::string& reason);
  void emitAlert(std::uint32_t alert_code, int pb_alert_level, const std::string& detail);
  void emitCtpState(std::int64_t now, const std::string& reason);
  void emitReplayTerminal(const std::string& detail);

  static void replayProgress(void* user, hg::flow_store::FlowState state,
                             std::uint64_t done, std::uint64_t total);

  // T-208：组件状态源（`HgcpServerLoop::ComponentStateFn`）——把 `AppState` 映射为
  //   §4.2.8.7 `ServerState` 并给出 `param_version`，供 `MSG_HELLO_ACK` 编码。
  //   `hgcp` 不感知本类 ⇒ 经**函数指针 + user** 反向注入（本类为回调实现方）。
  //   映射：kNotReady→NOT_READY / kReplaying→REPLAYING / kReady→READY /
  //         kStopping→STOPPING / kBlocked/kFailed→NOT_READY（**不新增枚举值**）。
  static void componentState(void* user, std::int32_t* server_state,
                             std::uint64_t* param_version);

  void setState(AppState s, const std::string& note);

  const AppConfig cfg_;

  // ---- 装配（声明序 = 依赖序；析构逆序 ⇒ 依赖后建先毁）----
  hg::ctp_adapter::CtAdapter ct_;
  hg::flow_store::FlowStore flow_;
  hg::flow_store::ReplayController replay_;
  hg::risk_state::RiskState risk_state_;
  hg::risk_engine::RiskEngine risk_;
  hg::order_engine::CtGatewayBridge bridge_;
  hg::order_engine::OrderEngine engine_;
  hg::flow_store::RecoveryCoordinator ledger_;
  hg::order_engine::RecoveryHook recovery_;
  hg::hgcp::HgcpServerLoop loop_;
  hg::hgcp::EventEmitter emitter_;
  hg::hgcp::RiskStateFullRebuilder rebuilder_;
  hg::hgcp::CommandHandler handler_;
  hg::hgcp::TokenVerifier auth_verifier_;  // 仅当配置了令牌（摘要持有，无明文）

  AppState state_;
  AppStats stats_;
  std::string last_error_;
  bool started_;
  bool ctp_degraded_;
  bool ctp_login_attempted_;
  bool ctp_logged_in_observed_;  // 上次观测到的 CTP 登录态（迁移检测）
  std::int64_t last_expire_ms_;
  // 重放会话起点（T-210）：进入 REPLAYING 时记一次 ⇒ 同一份进度流内**多帧同值**。
  //   与 `CMD_TRIGGER_REPLAY` 路径（`command_handler.cc:159` 用 `ctx.started_ms`）
  //   同口径，作为 `EvtReplayProgress.started_ts_ms` 的**唯一来源**。
  std::int64_t replay_started_ms_;
  // T-211：本地管理入口（人工核验释放）——目录派生于 `hg.flow.dir`（CFG-29 同族）。
  std::string admin_dir_;         // = <hg.flow.dir>/admin
  std::int64_t last_admin_poll_ms_;  // 低频轮询节流（≥ kAdminPollPeriodMs）

  ClockFn clock_fn_;
  void* clock_user_;
};

}  // namespace app
}  // namespace hg

#endif  // HG_TRADING_APP_H
