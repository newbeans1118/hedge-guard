# =============================================================================
# 事前风控引擎（risk_engine）（M3）—— ARCHITECTURE.md §3.3 / §4.4
#
# 本任务（T-035）落地「8 条规则全内存判定 + 三层限额全序 + 子账户/总账户双层校验」：
#   · `RiskEngine::check(const OrderRequest&)`（**纯函数式**：const 快照 + 入参，无副作用）
#   · `RiskEngine::applySnapshot(const RiskSnapshot&)`（原子替换判定快照）
#   · 规则 R-RISK-1 ~ R-RISK-8（白名单 / 三层限额 / 持仓上限 / 规模红线 / 时点净持仓 /
#     期限红线 / 权限与授权 / 价格偏离）
#   · 交易路径纪律（§4.4.3 / §8.1.3）：零 DB I/O（E1/E2）、零锁 / 零同步 I/O（E4）、
#     **零动态分配**（entries[] 定长数组）
#
# ⚠️ 本目录属 `E4` 扫描根（`cpp/src/{order_engine,risk_engine,risk_state}` 并集）：
#    **不得**出现锁原语或同步 I/O 的调用形态。
#
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_risk_engine STATIC
  src/module.cc
  src/order_request.cc
  src/risk_result.cc
  src/clock.cc
  src/quota_selection.cc
  src/risk_engine.cc)
add_library(hg::risk_engine ALIAS hg_risk_engine)
target_include_directories(hg_risk_engine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_link_libraries(hg_risk_engine PUBLIC hg::risk_state)
set_target_properties(hg_risk_engine PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# -----------------------------------------------------------------------------
# 单测（ctest）：8 条规则逐条 / 三层限额全序与并列取一 / 双层校验 / 纯函数式 /
#   入参非法拒绝 / 平仓豁免。以架构合规门禁（arch_check，见 cpp/CMakeLists.txt）为前置 fixture。
# -----------------------------------------------------------------------------
set(RISK_ENGINE_UNIT_TESTS
  risk_engine_whitelist_test
  risk_engine_quota_layers_test
  risk_engine_selector_test
  risk_engine_position_limit_test
  risk_engine_scale_redline_test
  risk_engine_net_position_test
  risk_engine_term_test
  risk_engine_permission_test
  risk_engine_price_deviation_test
  risk_engine_double_layer_test
  risk_engine_purity_test
  risk_engine_close_exemption_test
  risk_engine_related_object_test
  risk_engine_trading_suspend_test)

foreach(_t ${RISK_ENGINE_UNIT_TESTS})
  add_executable(risk_engine_${_t} "tests/${_t}.cc")
  target_link_libraries(risk_engine_${_t} PRIVATE hg::risk_engine Threads::Threads)
  target_include_directories(risk_engine_${_t} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/tests")
  set_target_properties(risk_engine_${_t} PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF)
  add_test(NAME "risk_engine.${_t}" COMMAND "risk_engine_${_t}")
  set_tests_properties("risk_engine.${_t}" PROPERTIES
    FIXTURES_REQUIRED hg_arch_check
    TIMEOUT 120)
endforeach()

# -----------------------------------------------------------------------------
# 基准：`bench_risk_check`（§8.1.2「事前风控基准测试 … ≥ 10 万次调用统计 P99/P99.9」）
#   以「P99 ≤ 5 ms」为**验收断言**（NFR-PERF-1 / §4.4.4 预算分解）。
# -----------------------------------------------------------------------------
add_executable(bench_risk_check tests/bench_risk_check.cc)
target_link_libraries(bench_risk_check PRIVATE hg::risk_engine Threads::Threads)
target_include_directories(bench_risk_check PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/tests")
set_target_properties(bench_risk_check PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
add_test(NAME "risk_engine.bench_risk_check" COMMAND bench_risk_check)
set_tests_properties("risk_engine.bench_risk_check" PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 300)
