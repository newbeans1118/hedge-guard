// =============================================================================
// hg-trading —— C++ 交易组件可执行入口（T-207：启动编排实化）
//
// 依据：docs/ARCHITECTURE.md §2.4.1（进程模型：hg-trading 先启动、长驻）、
//       §2.4.2 T1（初始化 / 配置装载 / 依赖装配 / 启动 T2–T9 / 等待退出信号）、
//       §4.5.3（启动重放时序与「系统未就绪」）、§4.2.6（心跳/就绪/鉴权）。
//
// 本文件的职责**只有**三件（真正的编排在 `trading_app`）：
//   ① 进程级开关：`--help` / `--self-test`（六模块断言，ctest `hg.skeleton_modules`）/
//      `--dump-config`（脱敏摘要）/ `--verbose`；
//   ② 信号：`SIGINT`/`SIGTERM` ⇒ 请求优雅退出（`STOPPING` → 释放资源 → 退出 0）；
//      **重复信号** ⇒ 强制退出（`_exit(0)`，async-signal-safe）；
//      `SIGPIPE` 忽略（对端关闭不得杀进程）；
//   ③ 主循环：`TradingApp::pump()` 单线程驱动，直到收到退出信号。
//
// 配置装载（CLI > env > 属性文件 > 缺省）与校验见 `app_config.h`；
// 装配/时序/状态机见 `trading_app.h`。本文件不含业务逻辑。
// =============================================================================

#include <signal.h>
#include <unistd.h>

#include <csignal>
#include <cstdio>
#include <cstring>
#include <string>

#include "app_config.h"
#include "hg/ctp_adapter/module.h"
#include "hg/flow_store/module.h"
#include "hg/hgcp/module.h"
#include "hg/order_engine/module.h"
#include "hg/risk_engine/module.h"
#include "hg/risk_state/module.h"
#include "trading_app.h"

namespace {

// 信号标志（异步信号处理器只写 sig_atomic_t，其余全部在主循环侧处理）。
volatile std::sig_atomic_t g_stop_requested = 0;

void onTerminateSignal(int /*sig*/) {
  if (g_stop_requested != 0) {
    // 重复信号 ⇒ 强制退出（不再等待收尾；回执/流水由既有 fsync 语义承接）。
    _exit(0);
  }
  g_stop_requested = 1;
}

void installSignalHandlers() {
  struct sigaction sa;
  std::memset(&sa, 0, sizeof(sa));
  sa.sa_handler = onTerminateSignal;
  sigemptyset(&sa.sa_mask);
  sa.sa_flags = 0;  // 不设 SA_RESTART：让 poll() 被中断返回，主循环尽快响应
  (void)sigaction(SIGINT, &sa, 0);
  (void)sigaction(SIGTERM, &sa, 0);
  // 对端关闭导致 write 触发 SIGPIPE ⇒ 默认动作是杀进程；此处忽略，由返回码处理。
  struct sigaction ign;
  std::memset(&ign, 0, sizeof(ign));
  ign.sa_handler = SIG_IGN;
  sigemptyset(&ign.sa_mask);
  (void)sigaction(SIGPIPE, &ign, 0);
}

// 比对模块名；不符则打印并累计失败数。
int check_module(const char* expected, const char* actual) {
  if (std::strcmp(expected, actual) == 0) {
    std::printf("[ok]   %s\n", actual);
    return 0;
  }
  std::printf("[FAIL] 期望 %s，实得 %s\n", expected, actual);
  return 1;
}

// `--self-test`：六模块可链接可调用（ctest `hg.skeleton_modules` 判据，语义不变）。
int selfTest() {
  std::printf("hg-trading 自检\n");
  std::printf("  ctp_adapter : %s（backend=%s）\n",
              hg::ctp_adapter::module_name(), hg::ctp_adapter::backend());
  std::printf("  order_engine: %s\n", hg::order_engine::module_name());
  std::printf("  risk_engine : %s\n", hg::risk_engine::module_name());
  std::printf("  risk_state  : %s\n", hg::risk_state::module_name());
  std::printf("  flow_store  : %s\n", hg::flow_store::module_name());
  std::printf("  hgcp        : %s\n", hg::hgcp::module_name());

  int failures = 0;
  failures += check_module("ctp_adapter", hg::ctp_adapter::module_name());
  failures += check_module("order_engine", hg::order_engine::module_name());
  failures += check_module("risk_engine", hg::risk_engine::module_name());
  failures += check_module("risk_state", hg::risk_state::module_name());
  failures += check_module("flow_store", hg::flow_store::module_name());
  failures += check_module("hgcp", hg::hgcp::module_name());

  if (failures == 0) {
    std::printf("SELFTEST: PASS（六模块可链接可调用）\n");
    return 0;
  }
  std::printf("SELFTEST: FAIL（%d 处不符）\n", failures);
  return 1;
}

bool hasFlag(int argc, char** argv, const char* flag) {
  for (int i = 0; i < argc; ++i) {
    if (argv[i] != 0 && std::strcmp(argv[i], flag) == 0) return true;
  }
  return false;
}

void usage(const char* prog) {
  std::printf(
      "用法：%s [选项]\n"
      "\n"
      "长驻交易组件：装载配置 → 打开流水 → 回环监听（127.0.0.1）→ 等 Java 下发\n"
      "风控参数 → 重放与在途对账 → READY。收到 SIGINT/SIGTERM 优雅退出（退出码 0）。\n"
      "\n"
      "选项：\n"
      "  --self-test            六模块自检（不启动进程）\n"
      "  --dump-config          打印**脱敏**配置摘要后退出（口令永不回显）\n"
      "  --verbose              详细日志（不含敏感值）\n"
      "  --config <path>        属性文件路径（缺省 ./hg.properties 或 $HG_CONFIG_FILE）\n"
      "  --hg.<key> <value>     覆盖任一配置键（如 --hg.tcp.port 27400）\n"
      "  -h, --help             本帮助\n"
      "\n"
      "配置优先级：CLI > 环境变量 > 属性文件 > 内置缺省。\n"
      "敏感项（口令/令牌）**仅经环境变量**：HG_CTP_PASSWORD / HG_CTP_AUTH_CODE /\n"
      "HG_CTP_APP_ID / HG_HGCP_AUTH_TOKEN——不得写入 CLI 或属性文件（会被拒绝启动）。\n"
      "环境变量命名：hg.tcp.port → HG_TCP_PORT（点转下划线、大写）。\n",
      prog == 0 ? "hg-trading" : prog);
}

}  // namespace

int main(int argc, char** argv) {
  if (hasFlag(argc, argv, "-h") || hasFlag(argc, argv, "--help")) {
    usage(argc > 0 ? argv[0] : 0);
    return 0;
  }
  if (hasFlag(argc, argv, "--self-test")) {
    return selfTest();
  }

  // ① 配置装载（argv[0] 跳过；load* 只解析选项与取值）
  hg::app::AppConfig cfg;
  const hg::app::ConfigOutcome loaded = hg::app::loadAppConfig(argc - 1, argv + 1, &cfg);
  if (!loaded.ok) {
    std::fprintf(stderr, "[hg-trading] ERROR 配置装载失败：%s\n", loaded.error.c_str());
    return 2;
  }
  for (std::size_t i = 0; i < loaded.notes.size(); ++i) {
    std::printf("[hg-trading] note: %s\n", loaded.notes[i].c_str());
  }
  if (hasFlag(argc, argv, "--dump-config")) {
    const hg::app::ConfigOutcome valid = hg::app::validateAppConfig(cfg);
    if (!valid.ok) {
      std::fprintf(stderr, "[hg-trading] ERROR 配置校验失败：%s\n", valid.error.c_str());
      return 2;
    }
    std::printf("config(redacted): %s\n", hg::app::redactedSummary(cfg).c_str());
    return 0;
  }
  if (cfg.verbose) {
    std::printf("[hg-trading] config(redacted): %s\n",
                hg::app::redactedSummary(cfg).c_str());
  }

  // ② 信号（先装再启动，避免启动窗口内的信号被默认动作杀掉）
  installSignalHandlers();

  // ③ 启动编排（失败 ⇒ 非 0 退出 + 告警；绝不静默回退）
  hg::app::TradingApp app(cfg);
  std::string error;
  if (!app.start(&error)) {
    std::fprintf(stderr, "[hg-trading] ERROR 启动失败：%s\n", error.c_str());
    return 1;
  }

  // ④ 主循环（单线程泵；时间由 TradingApp 统一注入）
  while (g_stop_requested == 0) {
    app.pump(cfg.pump_timeout_ms);
  }

  // ⑤ 优雅停机（STOPPING → 释放资源 → 退出 0）
  app.stop();
  std::printf("[hg-trading] exit 0\n");
  std::fflush(stdout);
  return 0;
}
