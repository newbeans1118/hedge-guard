# =============================================================================
# HGCP 通信（hgcp）（M6）—— ARCHITECTURE.md §3.6 / §4.2
#
# 本任务（T-032）落地「HGCP 帧编解码与元协议」，含：
#   · 帧编解码（32B 头 + CRC32 + 大端 + 1 MiB 上限）—— §4.2.2
#   · 协议常量表（消息类型 40 / 组件错误码 62 / FLAGS 位）—— §4.2.3 / §4.3.2
#   · 鉴权（SHA-256 摘要 + 常量时间比较 + 审计留痕，禁令牌落日志）—— §4.2.6
#   · 心跳 / 重连退避 / 补数缓冲 —— §4.2.6
#   · 回环绑定白名单 + 单连接约束 —— §4.2.1
#   · **payload 编解码（`proto/hgcp.proto` 生成代码）**—— `CH-013` / §4.2.8
#     （`PbPayloadCodec` + `PbTransportPolicy`；帧层/承载层零改动）
#
# schema：**已由 `CH-013` 定版**（2026-09-22，用户批准）⇒ 唯一定义 = `proto/hgcp.proto`
#   （由 `ARCHITECTURE.md §4.2.8` 逐条转录；C++(`T-032`) / Java(`T-042`) 同源生成）。
#   本模块在构建期以 `protoc` 从该 schema 生成 C++ 代码并编译进 `hg_hgcp`。
#
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

# -----------------------------------------------------------------------------
# 真实冻结 schema（`CH-013` / §4.2.8）→ protoc 生成 C++ 代码
#   protoc 供给与 `cmake/HgDependencies.cmake` 双轨一致：
#     CTP_USE_FETCH_DEPS=ON  → 目标 `protoc`（源码构建）
#     CTP_USE_FETCH_DEPS=OFF → 系统包 `Protobuf_PROTOC_EXECUTABLE`
# -----------------------------------------------------------------------------
get_filename_component(HG_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
set(HGCP_PROTO_DIR "${HG_REPO_ROOT}/proto")
set(HGCP_PROTO_FILE "${HGCP_PROTO_DIR}/hgcp.proto")
if(NOT EXISTS "${HGCP_PROTO_FILE}")
  message(FATAL_ERROR "[hgcp] 找不到真实 schema：${HGCP_PROTO_FILE}（CH-013 / §4.2.8）")
endif()

set(_hgcp_proto_deps "${HGCP_PROTO_FILE}")
if(TARGET protoc)
  set(HGCP_PROTOC_CMD "$<TARGET_FILE:protoc>")
  list(APPEND _hgcp_proto_deps protoc)
elseif(Protobuf_PROTOC_EXECUTABLE)
  set(HGCP_PROTOC_CMD "${Protobuf_PROTOC_EXECUTABLE}")
else()
  message(FATAL_ERROR "[hgcp] 无 protoc 可用（系统包 / FetchContent 均未提供）；不得静默降级")
endif()

set(HGCP_PB_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${HGCP_PB_GEN_DIR}")

add_custom_command(
  OUTPUT "${HGCP_PB_GEN_DIR}/hgcp.pb.h" "${HGCP_PB_GEN_DIR}/hgcp.pb.cc"
  COMMAND "${HGCP_PROTOC_CMD}"
          "--proto_path=${HGCP_PROTO_DIR}"
          "--cpp_out=${HGCP_PB_GEN_DIR}"
          "${HGCP_PROTO_FILE}"
  DEPENDS ${_hgcp_proto_deps}
  COMMENT "[hgcp] protoc --cpp_out 生成真实 schema hgcp.pb.{h,cc}（CH-013 / §4.2.8）"
  VERBATIM)

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
  src/pb_transport_policy.cc
  src/tcp.cc
  src/session.cc
  src/server_loop.cc
  src/command_handler.cc
  src/event_emitter.cc
  "${HGCP_PB_GEN_DIR}/hgcp.pb.cc")
# 承载层 = `tcp`（回环 socket 原语）+ `session`（schema-free 连接状态机）
#        + `server_loop`（两者的**生产侧装配**：accept/读/握手门禁/心跳/退避/补数/单连接）。
add_library(hg::hgcp ALIAS hg_hgcp)
target_include_directories(hg_hgcp PUBLIC
  "${CMAKE_CURRENT_SOURCE_DIR}/include"
  "${HGCP_PB_GEN_DIR}")   # 生成头 `hgcp.pb.h` 供 payload 层 / 注入策略使用
set_target_properties(hg_hgcp PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# HGCP 帧载荷依赖 protobuf（§3.6 / §7.2.1）；
# 供给由双轨决定：CTP_USE_FETCH_DEPS=ON → FetchContent；OFF → 系统包。
target_link_libraries(hg_hgcp PUBLIC hg_deps::protobuf hg::risk_state hg::order_engine hg::flow_store)

# -----------------------------------------------------------------------------
# 单测（ctest）：帧 / 信封 + payload 编解码 / 鉴权 / 心跳退避 / 补数缓冲 / 回环绑定
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
  transport_test
  command_handler_test
  order_path_test
  event_emitter_test
  protocol_special_test
  seq_out_of_order_test)

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

# -----------------------------------------------------------------------------
# T-038：command_handler_test 需要引擎联动（把装填后的快照喂给 RiskEngine 判定）。
# T-036：order_path_test 同形（交易路径接缝集成，复用 risk_engine testutil）。
foreach(_t command_handler_test order_path_test)
  target_link_libraries(hgcp_${_t} PRIVATE hg::risk_engine)
  target_include_directories(hgcp_${_t} PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../risk_engine/tests")
endforeach()

# -----------------------------------------------------------------------------
# T-037：重放门卫与触发接线单测（0x0401 单一门卫扩展 + CMD_TRIGGER_REPLAY +
#        EVT_REPLAY_PROGRESS 编码）。复用 risk_engine testutil（就绪夹具同形）。
# -----------------------------------------------------------------------------
add_executable(hgcp_replay_gate_test "tests/replay_gate_test.cc")
target_link_libraries(hgcp_replay_gate_test PRIVATE hg::hgcp hg::risk_engine)
target_include_directories(hgcp_replay_gate_test PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/tests"
  "${CMAKE_CURRENT_SOURCE_DIR}/../risk_engine/tests")
set_target_properties(hgcp_replay_gate_test PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
add_test(NAME "hgcp.replay_gate" COMMAND hgcp_replay_gate_test)
set_tests_properties("hgcp.replay_gate" PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 60)

# -----------------------------------------------------------------------------
# T-041：恢复未闭环门禁单测（0x0401 单一门卫第三合取项 + 可空持有负控 +
#        人工闭环先后序）。复用 risk_engine testutil（就绪夹具同形）。
# -----------------------------------------------------------------------------
add_executable(hgcp_recovery_gate_test "tests/recovery_gate_test.cc")
target_link_libraries(hgcp_recovery_gate_test PRIVATE hg::hgcp hg::risk_engine)
target_include_directories(hgcp_recovery_gate_test PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/tests"
  "${CMAKE_CURRENT_SOURCE_DIR}/../risk_engine/tests")
set_target_properties(hgcp_recovery_gate_test PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
add_test(NAME "hgcp.recovery_gate" COMMAND hgcp_recovery_gate_test)
set_tests_properties("hgcp.recovery_gate" PROPERTIES
  FIXTURES_REQUIRED hg_arch_check
  TIMEOUT 60)
