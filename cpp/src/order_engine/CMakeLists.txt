# =============================================================================
# 指令生命周期管理（order_engine）（M2）—— ARCHITECTURE.md §3.2
#
# T-036 落地：受理 → 事前风控 → 冻结 → 待确认 → 人工确认（再校验 + token）→
#   落流水（flow_store append，先于发单）→ 发 CTP → 回报处理 → 终态；
#   超时失效 / 撤单 / 部分成交。
# 依赖（§3.2）：risk_engine（事前风控）、flow_store（流水）、ctp_adapter（发送面）。
# 本目录属 E4 扫描根：零锁 / 零同步 I/O 形态 / 无 SQL。
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_order_engine STATIC
  src/module.cc
  src/order_engine.cc
  src/trade_gateway.cc
  src/recovery_hook.cc)                  # T-041：恢复决策执行主体（还原/终态化/核验）
add_library(hg::order_engine ALIAS hg_order_engine)
target_include_directories(hg_order_engine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_link_libraries(hg_order_engine PUBLIC
  hg::risk_engine
  hg::flow_store
  hg::ctp_adapter)
set_target_properties(hg_order_engine PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# -----------------------------------------------------------------------------
# 单测（ctest）：状态机主链 + 门卫（0x0305/0x0307/0x2001/0x2002/0x2003/0x2004/0x2007）
#   + 落流水先于发单 + 超时失效 + 部分成交 + 幂等回报 + 冻结/释放账本。
#   以架构合规门禁（arch_check）为前置 fixture。
# -----------------------------------------------------------------------------
add_executable(order_engine_order_engine_test "tests/order_engine_test.cc")
target_link_libraries(order_engine_order_engine_test PRIVATE hg::order_engine)
target_include_directories(order_engine_order_engine_test PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/tests"
  "${CMAKE_CURRENT_SOURCE_DIR}/../risk_engine/tests")  # 复用 testutil（harness/构造器）
set_target_properties(order_engine_order_engine_test PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
add_test(NAME "order_engine.order_engine" COMMAND order_engine_order_engine_test)
set_tests_properties("order_engine.order_engine" PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 60)

# -----------------------------------------------------------------------------
# 单测（ctest）：T-041 崩溃恢复集成——还原/决策执行/人工核验/强杀重启模拟（验收 1–4）
#   进程内模拟承载（工单门禁 4：非真跨进程 E2E，诚实披露于用例注释）。
# -----------------------------------------------------------------------------
add_executable(order_engine_recovery_hook_test "tests/order_engine_recovery_hook_test.cc")
target_link_libraries(order_engine_recovery_hook_test PRIVATE hg::order_engine)
target_include_directories(order_engine_recovery_hook_test PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/tests"
  "${CMAKE_CURRENT_SOURCE_DIR}/../risk_engine/tests")  # 复用 testutil（harness/构造器）
set_target_properties(order_engine_recovery_hook_test PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
add_test(NAME "order_engine.recovery_hook" COMMAND order_engine_recovery_hook_test)
set_tests_properties("order_engine.recovery_hook" PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 60)
