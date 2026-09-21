// =============================================================================
// hg-trading —— C++ 交易组件可执行入口（工程骨架）
//
// 依据：docs/ARCHITECTURE.md §2.4.1（进程模型：hg-trading 先启动，长驻）、
//       §3.1–§3.6（六模块）；PROJECT.md §5.0（双组件架构）。
//
// 骨架期职责：装配六模块、打印模块清单；`--self-test` 供 ctest 断言六模块
// 已实际链接且可调用。真实启动编排（配置装载 → 重放 → 等待 Java 下发 → READY）
// 由后续 M2 任务（T-033 起）实现。
// =============================================================================

#include <cstdio>
#include <cstring>

#include "hg/ctp_adapter/module.h"
#include "hg/flow_store/module.h"
#include "hg/hgcp/module.h"
#include "hg/order_engine/module.h"
#include "hg/risk_engine/module.h"
#include "hg/risk_state/module.h"

namespace {

// 比对模块名；不符则打印并累计失败数。
int check_module(const char* expected, const char* actual) {
  if (std::strcmp(expected, actual) == 0) {
    std::printf("[ok]   %s\n", actual);
    return 0;
  }
  std::printf("[FAIL] 期望 %s，实得 %s\n", expected, actual);
  return 1;
}

}  // namespace

int main(int argc, char** argv) {
  const bool self_test = (argc > 1 && std::strcmp(argv[1], "--self-test") == 0);

  std::printf("hg-trading（骨架）\n");
  std::printf("  ctp_adapter : %s（backend=%s）\n",
              hg::ctp_adapter::module_name(), hg::ctp_adapter::backend());
  std::printf("  order_engine: %s\n", hg::order_engine::module_name());
  std::printf("  risk_engine : %s\n", hg::risk_engine::module_name());
  std::printf("  risk_state  : %s\n", hg::risk_state::module_name());
  std::printf("  flow_store  : %s\n", hg::flow_store::module_name());
  std::printf("  hgcp        : %s\n", hg::hgcp::module_name());

  if (!self_test) {
    return 0;
  }

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
