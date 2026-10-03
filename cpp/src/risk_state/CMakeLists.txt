# =============================================================================
# 风控内存状态（risk_state）（M4）—— ARCHITECTURE.md §3.4 / §4.4
#
# 本任务（T-034）落地「常驻内存数据 + 不可变快照 + 原子指针替换（RCU 风格）」：
#   · 常驻内存数据集（§4.4.1）：品种 / 合约 / 白名单 / 三层额度 / 持仓 / 持仓上限 /
#     敞口（三分量 + snapshot_time）/ 年度规模基数 / 分类 / 权限 / 阈值 / 总账户限额 / 计划
#   · 并发策略（§4.4.3）：`snapshot()` 原子读；`rebuild()` 构建新快照后原子替换；
#     旧快照以「原子读者计数 + 纪元宽限期」回收（自实现，C++11 无 shared_ptr 原子操作）
#   · 交易路径零 DB I/O（E1/E2）、零锁竞争 / 零同步 I/O（E4）
#
# ⚠️ 本目录属 `E4` 扫描根（`cpp/src/{order_engine,risk_engine,risk_state}` 并集）：
#    **不得**出现锁原语或同步 I/O 的调用形态；实现仅用 std::atomic。
#
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_risk_state STATIC
  src/module.cc
  src/risk_snapshot.cc
  src/load_batch.cc
  src/snapshot_manager.cc
  src/risk_state.cc
  src/rebuild.cc)
add_library(hg::risk_state ALIAS hg_risk_state)
target_include_directories(hg_risk_state PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_risk_state PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# -----------------------------------------------------------------------------
# 单测（ctest）：数据面齐备 / RCU 语义（原子读 + 原子替换 + 安全回收）/ 重建与校验 /
#   RSK-04 保守方向。以架构合规门禁（arch_check，见 cpp/CMakeLists.txt）为前置 fixture。
# -----------------------------------------------------------------------------
set(RISK_STATE_UNIT_TESTS
  data_model_test
  snapshot_manager_test
  risk_state_test
  rsk04_conservative_test
  diagnostic_accessor_test
  rebuild_test)

foreach(_t ${RISK_STATE_UNIT_TESTS})
  add_executable(risk_state_${_t} "tests/${_t}.cc")
  target_link_libraries(risk_state_${_t} PRIVATE hg::risk_state Threads::Threads)
  target_include_directories(risk_state_${_t} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/tests")
  set_target_properties(risk_state_${_t} PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF)
  add_test(NAME "risk_state.${_t}" COMMAND "risk_state_${_t}")
  set_tests_properties("risk_state.${_t}" PROPERTIES
    FIXTURES_REQUIRED hg_arch_check
    TIMEOUT 120)
endforeach()
