# =============================================================================
# C++ 交易组件源码根 —— 六模块（docs/ARCHITECTURE.md §3.1–§3.6）
#
#   M1 ctp_adapter   CTP 适配层（封装 CTP SDK，异步 SPI → 内部事件）
#   M2 order_engine  指令生命周期管理
#   M3 risk_engine   事前风控引擎（全内存判定）
#   M4 risk_state    风控内存状态（不可变快照）
#   M5 flow_store    指令流水与重放（append-only 本地文件）
#   M6 hgcp          HGCP 通信（本地 TCP 回环，不含 HTTP / WebSocket）
#
# 依赖方向（ARCHITECTURE.md §2.3）由后续任务在实现层保证；骨架期仅建立库目标。
# =============================================================================

add_subdirectory(ctp_adapter)
add_subdirectory(order_engine)
add_subdirectory(risk_engine)
add_subdirectory(risk_state)
add_subdirectory(flow_store)
add_subdirectory(hgcp)

# -----------------------------------------------------------------------------
# hg-trading：交易组件可执行（进程模型见 ARCHITECTURE.md §2.4.1）
#
# T-207：启动编排实化 —— `main.cc`（进程入口：开关/信号/主循环）
#        + `app_config.*`（配置装载：CLI > env > 属性文件 > 缺省；敏感项仅 env）
#        + `trading_app.*`（八类对象装配 + §4.5.3 启动时序 + 单线程泵）
#        三者**同属入口层**（不新增第七模块，落 `cpp/src/` 根部）。
# -----------------------------------------------------------------------------
add_executable(hg-trading main.cc app_config.cc trading_app.cc)
target_link_libraries(hg-trading PRIVATE
  hg::ctp_adapter
  hg::order_engine
  hg::risk_engine
  hg::risk_state
  hg::flow_store
  hg::hgcp)

# 骨架自检用例：断言六模块已链接且可调用（T-207 保留，判据语义不变）。
# 以架构合规门禁（arch_check，见 cpp/CMakeLists.txt）为前置 fixture。
add_test(NAME hg.skeleton_modules COMMAND hg-trading --self-test)
set_tests_properties(hg.skeleton_modules PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 60)

# -----------------------------------------------------------------------------
# T-207：启动编排单测（进程内驱动真实 TradingApp + 真实回环 socket + 真实 protobuf）
#   一例一进程（用例选择经 argv），失败定位精确。
#   判据映射：TASKS T-207 ⑥「新增 ctest 至少 5 例」。
# -----------------------------------------------------------------------------
add_executable(hg_app_test "tests/trading_app_test.cc" app_config.cc trading_app.cc)
target_link_libraries(hg_app_test PRIVATE
  hg::ctp_adapter
  hg::order_engine
  hg::risk_engine
  hg::risk_state
  hg::flow_store
  hg::hgcp)
target_include_directories(hg_app_test PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}"
  "${CMAKE_CURRENT_SOURCE_DIR}/hgcp/tests")
set_target_properties(hg_app_test PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

#   progress  —— T-210：启动自动重放 `EVT_REPLAY_PROGRESS` 条件必填字段
#                （终态帧 finished_ts_ms != 0；中间进度帧 == 0；started_ts_ms 会话内恒定）
set(HG_APP_CASES config secret listen ready progress corrupt)
foreach(_c ${HG_APP_CASES})
  add_test(NAME "app.${_c}" COMMAND hg_app_test "${_c}")
  set_tests_properties("app.${_c}" PROPERTIES
    FIXTURES_REQUIRED hg_arch_check
    TIMEOUT 120)
endforeach()

# 进程级用例（①②③：SIGTERM 退 0 / 非回环拒启 / 口令零日志）
add_test(NAME app.startup_signal
  COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/startup_signal_test.sh"
          $<TARGET_FILE:hg-trading>)
set_tests_properties(app.startup_signal PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 180)
