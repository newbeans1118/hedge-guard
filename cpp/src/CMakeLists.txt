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
# hg-trading：交易组件可执行（骨架；进程模型见 ARCHITECTURE.md §2.4.1）
# -----------------------------------------------------------------------------
add_executable(hg-trading main.cc)
target_link_libraries(hg-trading PRIVATE
  hg::ctp_adapter
  hg::order_engine
  hg::risk_engine
  hg::risk_state
  hg::flow_store
  hg::hgcp)

# 骨架自检用例：断言六模块已链接且可调用。
# 以架构合规门禁（arch_check，见 cpp/CMakeLists.txt）为前置 fixture。
add_test(NAME hg.skeleton_modules COMMAND hg-trading --self-test)
set_tests_properties(hg.skeleton_modules PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 60)
