# =============================================================================
# 指令流水与重放（flow_store）（M5）—— ARCHITECTURE.md §3.5
#
# T-036：append + fsync 原语（flow_store.cc；行格式契约冻结）。
# T-037：启动重放 / 状态机 / CTP 对账接缝 / 分片保留（replay.cc，本目录不在
#        E4 扫描根 v1.5.12）。本目录不在 E4 扫描根（v1.5.12）。
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_flow_store STATIC
  src/module.cc
  src/flow_store.cc                      # T-036：append + fsync 原语
  src/replay.cc                          # T-037：重放 + 状态机 + 对账 + 保留清理
  src/recovery.cc)                       # T-041：恢复台账 + 人工核验入口 + 门禁读面
add_library(hg::flow_store ALIAS hg_flow_store)
target_include_directories(hg_flow_store PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_flow_store PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# -----------------------------------------------------------------------------
# 单测（ctest）：T-037 重放/状态机/对账/保留（验收 1–4）
# -----------------------------------------------------------------------------
add_executable(flow_store_replay_test "tests/flow_store_replay_test.cc")
target_link_libraries(flow_store_replay_test PRIVATE hg::flow_store)
set_target_properties(flow_store_replay_test PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
add_test(NAME "flow_store.replay" COMMAND flow_store_replay_test)
set_tests_properties("flow_store.replay" PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 60)

# -----------------------------------------------------------------------------
# 单测（ctest）：T-041 恢复台账 / 人工核验（双人复核五守卫）/ 门禁读面（验收 2/3/4）
# -----------------------------------------------------------------------------
add_executable(flow_store_recovery_test "tests/flow_store_recovery_test.cc")
target_link_libraries(flow_store_recovery_test PRIVATE hg::flow_store)
set_target_properties(flow_store_recovery_test PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
add_test(NAME "flow_store.recovery" COMMAND flow_store_recovery_test)
set_tests_properties("flow_store.recovery" PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 60)
