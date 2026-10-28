#ifndef HG_APP_CONFIG_H
#define HG_APP_CONFIG_H
// =============================================================================
// hg-trading 启动配置装载（T-207 / ARCHITECTURE.md §2.4.2 T1、§4.5.3 ①、§11.2）
//
// 职责：把「CLI / 环境变量 / 属性文件」三来源的**非敏感**配置收敛为一个
//   `AppConfig`，并做**启动前校验**（`FR-CONFIG-9`：非回环 bind / 非法端口 ⇒ 拒绝启动）。
//
// 优先级（高 → 低）：**CLI > env > 属性文件 > 内置缺省**。
//   与工单「CLI > env > 默认」一致；属性文件作为「默认」的载体插入到缺省之前
//   （TASKS 原文「非敏感项从 CLI/env/属性文件读取」）。
//
// 键集（ARCH §11.2 逐字对齐，**不自创协议字段**）：
//   CFG-20  hg.tcp.bind / hg.tcp.port                         （缺省 127.0.0.1 / 27400）
//   CFG-28  hg.tcp.heartbeat_ms / hg.cmd_timeout_ms /
//           hg.trade.confirm_timeout_ms                        （1000 / 3000 / 60000）
//   CFG-29  hg.flow.dir / hg.flow.fsync_mode / hg.flow.retain_days
//                                                              （./data/flow / BATCH / 90）
//   CFG-22  hg.ctp.env_type                                    （SIMULATION）
//   CTP 连接（非敏感）：hg.ctp.trade_front / hg.ctp.md_front /
//           hg.ctp.broker_id / hg.ctp.user_id / hg.ctp.flow_path
//           —— 架构无 `hg.ctp.*` 连接项的独立 CFG 编号；取 CFG-22 同族最小键集
//              （TASKS T-207 ⑩「按 CFG-22 + T-052 部署配置先例取最小键集」授权），
//              回执已列明，**未新增协议字段**（仅为本地配置键）。
//   重放     hg.replay.query_timeout_ms（§4.5.3 性能注 `HG.REPLAY.QUERY_TIMEOUT_MS`）
//
// **口令类仅 env**（`NFR-SEC-1` / `R-CONFIG-5` / 附录 B 红线 8）：
//   `HG_CTP_PASSWORD` / `HG_CTP_AUTH_CODE` / `HG_CTP_APP_ID` / `HG_HGCP_AUTH_TOKEN`
//   · CLI 传入敏感键 ⇒ **拒绝启动**（不得落进 shell history / ps）；
//   · 属性文件出现敏感键（键名含 password/token/secret/auth_code/app_id）⇒ **拒绝装载**；
//   · `redactedSummary()` 与全部日志**永不**输出其值（含长度/片段）。
//
// 本文件只做「装载 + 校验 + 脱敏呈现」，不建 socket、不碰模块状态 ⇒ 可独立单测。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "hg/ctp_adapter/ct_types.h"

namespace hg {
namespace app {

// 启动配置（全部字段均有保守缺省；装载后被 CLI/env/文件覆盖）。
struct AppConfig {
  // ---- CFG-20 HGCP 组件通道 ----
  std::string tcp_bind;        // 白名单仅 "127.0.0.1"
  std::uint16_t tcp_port;      // 1..65535（0 在配置口径非法，见 tcp.h「校验层级」）

  // ---- CFG-28 会话/超时 ----
  std::int64_t heartbeat_ms;         // 心跳间隔（§4.2.6 = 1000）
  std::int64_t cmd_timeout_ms;       // 命令超时
  std::int64_t confirm_timeout_ms;   // 人工确认超时（OrderEngineConfig）

  // ---- CFG-29 指令流水 ----
  std::string flow_dir;        // ./data/flow
  std::string flow_fsync_mode; // EVERY_RECORD / BATCH / ASYNC（原值承载）
  int flow_retain_days;        // 90

  // ---- 重放（§4.5.3 性能注）----
  std::int64_t replay_query_timeout_ms;  // 20000
  std::size_t replay_progress_every;     // 1000

  // ---- CFG-22 + CTP 连接（非敏感）----
  std::string ctp_env_type;    // SIMULATION / PRODUCTION
  std::string ctp_trade_front; // tcp://ip:port
  std::string ctp_md_front;
  std::string ctp_broker_id;
  std::string ctp_user_id;
  std::string ctp_flow_path;   // CTP 流文件目录（与 hg.flow.dir 不同）

  // ---- 敏感（**仅 env**）----
  std::string ctp_password;
  std::string ctp_auth_code;
  std::string ctp_app_id;
  std::string hgcp_auth_token;  // HGCP 鉴权令牌（可空 = 不启用鉴权）

  // ---- 运行参数（CLI 专用，非 CFG 项）----
  std::string config_file;      // 实际装载的属性文件路径（可空 = 未装载）
  int pump_timeout_ms;          // 主循环单步等待（缺省 50）
  bool verbose;                 // 详细日志（不含敏感值）

  AppConfig();

  // 转 ctp_adapter 登录配置（口令仅内存持有；供 `CtConfig::redacted()` 安全呈现）。
  hg::ctp_adapter::CtConfig toCtConfig() const;
};

// 装载/校验结果（`ok==false` ⇒ 调用方必须拒绝启动并以非 0 退出）。
struct ConfigOutcome {
  bool ok;
  std::string error;              // 人读原因（**不含敏感值**）
  std::vector<std::string> notes;  // 装载提示（非敏感：未知键、文件缺省等）

  ConfigOutcome() : ok(true) {}
};

// CLI/env/属性文件 → AppConfig。
//   · 仅识别 `--key value` / `--key=value`，以及 `--help` / `--verbose` /
//     `--config <path>` / `--dump-config` / `--self-test`（后两者由 main 处理）。
//   · 未知 `hg.*` 键 ⇒ notes 提示并忽略（不因未知键拒绝启动，避免部署配置前滚失败）。
//   · 敏感键经 CLI 或属性文件出现 ⇒ ok=false。
ConfigOutcome loadAppConfig(int argc, char** argv, AppConfig* out);

// 启动前校验（bind 白名单 / 端口 / 心跳 / 超时 / fsync 模式 / env_type / 目录）。
ConfigOutcome validateAppConfig(const AppConfig& cfg);

// 脱敏摘要（零口令；`--dump-config` 与启动日志共用）。
std::string redactedSummary(const AppConfig& cfg);

}  // namespace app
}  // namespace hg

#endif  // HG_APP_CONFIG_H
