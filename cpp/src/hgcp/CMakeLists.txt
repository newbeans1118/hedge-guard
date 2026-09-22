# =============================================================================
# HGCP 通信（hgcp）（M6）—— ARCHITECTURE.md §3.6 / §4.2
#
# 本任务（T-032）落地「HGCP 帧编解码与元协议」中**不依赖消息字段 schema** 的部分：
#   · 帧编解码（32B 头 + CRC32 + 大端 + 1 MiB 上限）—— §4.2.2
#   · 协议常量表（消息类型 40 / 组件错误码 62 / FLAGS 位）—— §4.2.3 / §4.3.2
#   · 鉴权（SHA-256 摘要 + 常量时间比较 + 审计留痕，禁令牌落日志）—— §4.2.6
#   · 心跳 / 重连退避 / 补数缓冲 —— §4.2.6
#   · 回环绑定白名单 + 单连接约束 —— §4.2.1
#
# ⚠️ 消息 payload 的**字段级 schema**（字段类型 / 字段号）在 `ARCHITECTURE.md §4.2`
#    中**未定义**（§4.2.3 只给字段名、§4.2.4 只覆盖 3 条消息且无字段号）⇒
#    `proto/hgcp.proto` 与元协议 payload 编解码**待 `CH-013` 裁定**后落地（见 T-032 回执）。
#
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_hgcp STATIC
  src/module.cc
  src/protocol.cc
  src/frame.cc
  src/crypto.cc
  src/auth.cc
  src/timing.cc
  src/resume_buffer.cc
  src/endpoint.cc
  src/payload_codec.cc
  src/tcp.cc
  src/session.cc
  src/server_loop.cc)
# 承载层 = `tcp`（回环 socket 原语）+ `session`（schema-free 连接状态机）
#        + `server_loop`（两者的**生产侧装配**：accept/读/握手门禁/心跳/退避/补数/单连接）。
add_library(hg::hgcp ALIAS hg_hgcp)
target_include_directories(hg_hgcp PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_hgcp PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# HGCP 帧载荷后续依赖 protobuf（§3.6 / §7.2.1）；
# 供给由双轨决定：CTP_USE_FETCH_DEPS=ON → FetchContent；OFF → 系统包。
target_link_libraries(hg_hgcp PUBLIC hg_deps::protobuf)

# -----------------------------------------------------------------------------
# 单测（ctest）：帧 / 鉴权 / 心跳退避 / 补数缓冲 / 回环绑定
#   以架构合规门禁（arch_check，见 cpp/CMakeLists.txt）为前置 fixture。
# -----------------------------------------------------------------------------
set(HGCP_UNIT_TESTS
  frame_test
  envelope_test
  crypto_auth_test
  timing_test
  resume_buffer_test
  endpoint_test
  session_test
  transport_test)

foreach(_t ${HGCP_UNIT_TESTS})
  add_executable(hgcp_${_t} "tests/${_t}.cc")
  target_link_libraries(hgcp_${_t} PRIVATE hg::hgcp)
  target_include_directories(hgcp_${_t} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/tests")
  set_target_properties(hgcp_${_t} PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF)
  add_test(NAME "hgcp.${_t}" COMMAND "hgcp_${_t}")
  set_tests_properties("hgcp.${_t}" PROPERTIES
    FIXTURES_REQUIRED hg_arch_check
    TIMEOUT 60)
endforeach()
