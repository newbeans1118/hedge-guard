# =============================================================================
# T-026 / RSK-01 —— protobuf 在 C++11 下的空壳构通验证工程
#
# 目标（可客观判定）：
#   ① `-std=c++11 -Wall -Wextra -Werror` 下用 HGCP 空消息完成编译 + 收发；
#   ② 首选 protobuf 3.21.12，失败回退 3.19.6，回退链路可复现；
#   ③ 记录实际可用版本与编译参数；两侧 protobuf 大版本一致（3.x）→ 构建期校验；
#   ④ 两版本皆不可用 → 提 CH-xxx（L3），不得静默改技术栈。
#
# 上游依据：docs/ARCHITECTURE.md §7.1.5（版本锁定）、§7.2.1（依赖清单）、
#           §9 RSK-01（应对方案）、§4.2.5（序列化规定）。
# =============================================================================

cmake_minimum_required(VERSION 3.16)

# 支持两种用法：① 作为 cpp/ 顶层工程的子目录；② 单独 `cmake -S cpp/proto_smoke`
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  project(hgcp_proto_smoke LANGUAGES CXX)
endif()
if(NOT CMAKE_CXX_STANDARD)
  set(CMAKE_CXX_STANDARD 11 CACHE STRING "探路工程即 C++11；与 protobuf 3.x 对齐")
endif()
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE)
  # project() 会预置一个**空**的 CMAKE_BUILD_TYPE 缓存项，不带 FORCE 的
  # set(... CACHE ...) 无法覆盖 → 必须 FORCE（有 if 兜底，不覆盖用户显式选择）。
  set(CMAKE_BUILD_TYPE "RelWithDebInfo" CACHE STRING "构建类型" FORCE)
endif()
# 导出编译数据库：供 CI 断言"实际生效"的编译参数（-std=c++11 / -Wall -Wextra -Werror）
# 注意：CMAKE_EXPORT_COMPILE_COMMANDS 在 CMakeCache 中已被预置为空串，
#       `set(... CACHE BOOL ...)` 不带 FORCE 不会覆盖，故用普通（非缓存）变量。
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

#------------------------------------------------------------------------------
# 0. 记录探路口径（探路工程自足，不依赖仓库根 CMakeLists）
#------------------------------------------------------------------------------
set(HG_PROTOBUF_MAJOR_REQUIRED "3" CACHE STRING "C++/Java 两侧必须一致的 protobuf 大版本（§4.2.5）")
set(HG_PROTOBUF_PREFERRED_VERSION "3.21.12" CACHE STRING "首选版本（§7.1.5 选项 A）")
set(HG_PROTOBUF_FALLBACK_VERSION "3.19.6" CACHE STRING "回退版本（§7.1.5 / RSK-01 应对②）")
option(HG_PROTOBUF_USE_SYSTEM "优先使用系统安装的 protobuf；OFF 时直接走 FetchContent" ON)
option(HG_PROTOBUF_FORCE_FETCH "强制走 FetchContent（用于回退链路取证）" OFF)
option(HG_BUILD_SMOKE_TESTS "构建并注册 ctest 用例" ON)
# RSK-01 应对③：`protobuf-lite` 降级路径（须走 CH-xxx(L3) 才能正式启用，
# 本开关**只为探路取证**而存在，用于客观判定该降级路径是否真的可行）。
option(HG_PROTOBUF_LITE "使用 protobuf-lite 运行时（RSK-01 应对③ 取证用）" OFF)

set(HGCP_SMOKE_SCHEMA_FILE "proto/hgcp_smoke.proto")
# ⚠️ 2026-09-22（T-032 验收 6）：真实冻结 schema `proto/hgcp.proto` **已由 `CH-013` 定稿并落盘**
#    （`ARCHITECTURE.md §4.2.8`）⇒ 本探路工程除原有 smoke 用例（留痕）外，**另以真实 schema 生成
#    并编译**（见下方 §3b），以客观判定「真实 schema 的生成代码能否在 C++11 -Werror 下构通」。
set(HGCP_SMOKE_SCHEMA_IS_FROZEN_CONTRACT "NO (本 schema 仅作探路留痕；真实冻结 schema = proto/hgcp.proto，已由 CH-013 / §4.2.8 定稿落盘)")

message(STATUS "[RSK-01] 首选 protobuf = ${HG_PROTOBUF_PREFERRED_VERSION}，回退 = ${HG_PROTOBUF_FALLBACK_VERSION}")

#------------------------------------------------------------------------------
# 1. protobuf 供给：系统包优先 → FetchContent 回退
#------------------------------------------------------------------------------
set(HGCP_SMOKE_PROTOBUF_PROVIDER "unknown")
set(HGCP_PROTOBUF_TARGET "")
set(HGCP_PROTOC_CMD "")
set(HGCP_PROTOBUF_FETCH_DEPENDS "")

if(HG_PROTOBUF_USE_SYSTEM AND NOT HG_PROTOBUF_FORCE_FETCH)
  find_package(Protobuf QUIET)
endif()

if(Protobuf_FOUND)
  set(HGCP_SMOKE_PROTOBUF_PROVIDER "system-package")
  if(HG_PROTOBUF_LITE)
    set(HGCP_PROTOBUF_TARGET protobuf::libprotobuf-lite)
    set(HGCP_SMOKE_PROTOBUF_VARIANT "lite")
  else()
    set(HGCP_PROTOBUF_TARGET protobuf::libprotobuf)
    set(HGCP_SMOKE_PROTOBUF_VARIANT "full")
  endif()
  set(HGCP_PROTOC_CMD "${Protobuf_PROTOC_EXECUTABLE}")
  message(STATUS "[RSK-01] 采用系统 protobuf ${Protobuf_VERSION}（${HGCP_SMOKE_PROTOBUF_VARIANT} 运行时）")
else()
  set(HGCP_SMOKE_PROTOBUF_PROVIDER "fetchcontent-${HG_PROTOBUF_FALLBACK_VERSION}")
  message(STATUS "[RSK-01] 系统 protobuf 不可用/被跳过 → FetchContent 拉取 v${HG_PROTOBUF_FALLBACK_VERSION}")

  include(FetchContent)
  # protobuf 3.x 的 CMake 选项（3.19/3.21 同名）
  set(protobuf_BUILD_TESTS OFF CACHE BOOL "" FORCE)
  set(protobuf_BUILD_CONFORMANCE OFF CACHE BOOL "" FORCE)
  set(protobuf_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(protobuf_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
  set(protobuf_BUILD_PROTOC_BINARIES ON CACHE BOOL "" FORCE)
  set(protobuf_MSVC_STATIC_RUNTIME OFF CACHE BOOL "" FORCE)

  # libprotobuf 自身即按 C++11 构建（3.x 的 target_compile_features 为 cxx_std_11）；
  # 此处显式对齐，避免子项目抬高标准反过来污染本工程的 C++11 边界。
  set(CMAKE_CXX_STANDARD 11 CACHE STRING "protobuf 3.x 亦为 C++11；显式对齐")

  # ---------------------------------------------------------------------------
  # 拉取方式：
  #   GIT（默认）—— git 仓库；但浅克隆开销受网络影响大
  #   URL        —— 源码 tarball；**强制校验 SHA256**
  #                 `HG_PROTOBUF_TARBALL` 可指向已缓存的本地 tarball（离线/弱网 CI），
  #                 校验和仍然强制 —— 缓存只省下载，不省校验。
  #
  # ⚠️ 实测结论（2026-09-20，探路时撞到并已修复）——protobuf 的 CMake 入口在 3.21 前后**不同**：
  #   · v3.19.6：**无顶层 `CMakeLists.txt`**（HTTP 404），CMake 入口是 `cmake/CMakeLists.txt`
  #              → FetchContent **必须**给 `SOURCE_SUBDIR cmake`，否则
  #                `FetchContent_MakeAvailable` 只会把源码 populate 下来而**不配置任何目标**，
  #                表现为"configure 静默成功、后续才炸"，非常难查。
  #   · v3.21.12：**有顶层 `CMakeLists.txt`**（HTTP 200）→ 无需 `SOURCE_SUBDIR`。
  #   · `protobuf-cpp-<ver>.tar.gz`（release 的 C++-only 资产）**不可**用于 CMake FetchContent
  #     （无顶层 CMakeLists.txt，且 `cmake/` 也不在预期位置）。
  # ---------------------------------------------------------------------------
  set(HG_PROTOBUF_FETCH_VIA "GIT" CACHE STRING "回退拉取方式：GIT（默认）| URL（源码 tarball，须钉死 SHA256）")
  set(HG_PROTOBUF_TARBALL "" CACHE FILEPATH "本地已缓存的 protobuf 源码 tarball（可选，优先于在线下载）")
  set(HG_PROTOBUF_TARBALL_SHA256 "" CACHE STRING "覆盖内置的 tarball SHA256（留空则用内置值）")

  if(HG_PROTOBUF_FALLBACK_VERSION VERSION_LESS "3.21.0")
    set(_hg_pb_source_subdir "cmake")   # 3.19.x：CMake 入口在 cmake/
  else()
    set(_hg_pb_source_subdir "")        # 3.21.x 起：CMake 入口在源码树根
  endif()

  set(_hg_pb_sha256 "${HG_PROTOBUF_TARBALL_SHA256}")
  if(NOT _hg_pb_sha256)
    if(HG_PROTOBUF_FALLBACK_VERSION STREQUAL "3.19.6")
      # codeload 的 git-archive 源码 tarball（= v3.19.6 标签的完整源码树）。
      # 选它而非 release 资产，是因为 release 资产命名随版本变动
      # （`-cpp-` / `-all-`），而 codeload URL 形式稳定且可钉死哈希。
      # 实测：2026-09-20 下载 5,299,501 字节，SHA256 如下。
      set(_hg_pb_sha256 "9a301cf94a8ddcb380b901e7aac852780b826595075577bb967004050c835056")
    elseif(HG_PROTOBUF_FALLBACK_VERSION STREQUAL "3.21.12")
      # 本机已有系统包 3.21.12，故未在本次探路中钉死 3.21.12 的源码 tarball 哈希。
      # 原则：**宁可不校验→拒绝构建（FATAL_ERROR），也不写入未经验证的哈希值。**
      set(_hg_pb_sha256 "")
    endif()
  endif()

  if(HG_PROTOBUF_FETCH_VIA STREQUAL "URL")
    if(NOT _hg_pb_sha256)
      message(FATAL_ERROR
        "[RSK-01] 回退版本 v${HG_PROTOBUF_FALLBACK_VERSION} 无内置 SHA256。\n"
        "         请显式提供 -DHG_PROTOBUF_TARBALL_SHA256=<sha256>（**不得跳过校验**），\n"
        "         或改用 -DHG_PROTOBUF_FETCH_VIA=GIT。")
    endif()
    set(_hg_fc_args
      URL_HASH "SHA256=${_hg_pb_sha256}"
      DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    if(_hg_pb_source_subdir)
      list(APPEND _hg_fc_args SOURCE_SUBDIR "${_hg_pb_source_subdir}")
    endif()
    if(HG_PROTOBUF_TARBALL)
      if(NOT EXISTS "${HG_PROTOBUF_TARBALL}")
        message(FATAL_ERROR "[RSK-01] HG_PROTOBUF_TARBALL 不存在：${HG_PROTOBUF_TARBALL}")
      endif()
      message(STATUS "[RSK-01] 使用本地缓存 tarball：${HG_PROTOBUF_TARBALL}")
      FetchContent_Declare(protobuf URL "file://${HG_PROTOBUF_TARBALL}" ${_hg_fc_args})
    else()
      set(_hg_pb_url "https://codeload.github.com/protocolbuffers/protobuf/tar.gz/refs/tags/v${HG_PROTOBUF_FALLBACK_VERSION}")
      message(STATUS "[RSK-01] 在线下载源码 tarball：${_hg_pb_url}（SHA256 校验开启）")
      FetchContent_Declare(protobuf URL "${_hg_pb_url}" ${_hg_fc_args})
    endif()
  elseif(HG_PROTOBUF_FETCH_VIA STREQUAL "GIT")
    set(_hg_fc_git_args
      GIT_REPOSITORY https://github.com/protocolbuffers/protobuf.git
      GIT_TAG        v${HG_PROTOBUF_FALLBACK_VERSION}
      GIT_SHALLOW    TRUE
      GIT_PROGRESS   TRUE)
    if(_hg_pb_source_subdir)
      list(APPEND _hg_fc_git_args SOURCE_SUBDIR "${_hg_pb_source_subdir}")
    endif()
    FetchContent_Declare(protobuf ${_hg_fc_git_args})
  else()
    message(FATAL_ERROR "[RSK-01] HG_PROTOBUF_FETCH_VIA 只能是 URL 或 GIT，实为 '${HG_PROTOBUF_FETCH_VIA}'")
  endif()

  # ---------------------------------------------------------------------------
  # 实测结论（2026-09-20，探路时撞到并已修复）：
  #   protobuf 3.19.x 的 `cmake/CMakeLists.txt` 首行是 `cmake_minimum_required(VERSION 3.1.3)`，
  #   而 **CMake 4.x 已移除对 < 3.5 的兼容** → 直接 `CMake Error ... Compatibility with
  #   CMake < 3.5 has been removed`。须显式设 `CMAKE_POLICY_VERSION_MINIMUM=3.5` 才能继续。
  #   这也是"回退到 3.19.6"在 CMake ≥ 4 环境下的**必需**前置条件，CI 必须带上。
  # ---------------------------------------------------------------------------
  if(CMAKE_VERSION VERSION_GREATER_EQUAL "4.0" AND HG_PROTOBUF_FALLBACK_VERSION VERSION_LESS "3.22")
    set(CMAKE_POLICY_VERSION_MINIMUM "3.5" CACHE STRING
        "protobuf 3.19/3.21 的 cmake_minimum_required 低于 CMake 4 的下限" FORCE)
    message(STATUS "[RSK-01] CMake ${CMAKE_VERSION} + protobuf ${HG_PROTOBUF_FALLBACK_VERSION} → 设 CMAKE_POLICY_VERSION_MINIMUM=3.5")
  endif()

  FetchContent_MakeAvailable(protobuf)

  # 兜底：protobuf 3.19/3.21 的 `project(protobuf C CXX)` **不带 VERSION**，
  # 故 `protobuf_VERSION` 可能为空 —— 此时版本即我们指定的回退版本，不得误判为失败。
  if(NOT protobuf_VERSION)
    set(protobuf_VERSION "${HG_PROTOBUF_FALLBACK_VERSION}")
  endif()

  # 陷阱守卫：源码树里没有 CMakeLists.txt ⇒ FetchContent 只 populate 未配置。
  set(_hg_pb_cmake_entry "${protobuf_SOURCE_DIR}")
  if(_hg_pb_source_subdir)
    set(_hg_pb_cmake_entry "${protobuf_SOURCE_DIR}/${_hg_pb_source_subdir}")
  endif()
  if(NOT EXISTS "${_hg_pb_cmake_entry}/CMakeLists.txt")
    message(FATAL_ERROR
      "[RSK-01] FetchContent 已 populate，但 CMake 入口不存在：${_hg_pb_cmake_entry}/CMakeLists.txt\n"
      "         多半是把不带顶层 CMakeLists.txt 的 protobuf 发行包当成了可 CMake 构建的源码树\n"
      "         （v3.19.x 的入口在 `cmake/`，须 SOURCE_SUBDIR cmake）。\n"
      "         **不得**跳过校验或静默改技术栈。")
  endif()

  set(HGCP_PROTOBUF_TARGET libprotobuf)
  if(HG_PROTOBUF_LITE)
    set(HGCP_PROTOBUF_TARGET libprotobuf-lite)
    set(HGCP_SMOKE_PROTOBUF_VARIANT "lite")
  else()
    set(HGCP_SMOKE_PROTOBUF_VARIANT "full")
  endif()
  set(HGCP_PROTOC_CMD "$<TARGET_FILE:protoc>")
  # 供 build_info 打印：FetchContent 下 protoc 是构建期目标，没有可直接打印的路径字面量，
  # 故此处记录其来源而非生成器表达式（避免把 `$<TARGET_FILE:...>` 原样印进报告）。
  set(Protobuf_PROTOC_EXECUTABLE "<built-from-source: protoc target (protobuf ${HG_PROTOBUF_FALLBACK_VERSION})>")
  set(HGCP_PROTOBUF_FETCH_DEPENDS protoc)
  set(Protobuf_VERSION "${protobuf_VERSION}")
endif()

#------------------------------------------------------------------------------
# 2. 构建期校验：protobuf 大版本必须 = 3（§4.2.5「C++ 与 Java 必须使用同一大版本 3.x」）
#------------------------------------------------------------------------------
if(NOT Protobuf_VERSION)
  message(FATAL_ERROR "[RSK-01] 无法确定 protobuf 版本（Protobuf_VERSION 为空）→ 须提 CH-xxx（L3），不得静默改技术栈")
endif()

string(REGEX MATCH "^([0-9]+)\\." _pb_major_match "${Protobuf_VERSION}")
if(NOT _pb_major_match)
  message(FATAL_ERROR "[RSK-01] protobuf 版本串 '${Protobuf_VERSION}' 不可解析")
endif()
if(NOT CMAKE_MATCH_1 STREQUAL "${HG_PROTOBUF_MAJOR_REQUIRED}")
  message(FATAL_ERROR
    "[RSK-01] protobuf 大版本不符：实测 ${Protobuf_VERSION}，要求 ${HG_PROTOBUF_MAJOR_REQUIRED}.x\n"
    "         v22+ 要求 C++17，与本项目 C++11 硬约束冲突（ARCHITECTURE.md §7.1.5 选项 B）。\n"
    "         处理：提 CH-xxx（L3）申请 protobuf-lite / 降级 3.17.3；不得静默改技术栈。")
endif()
message(STATUS "[RSK-01] 大版本校验通过：protobuf ${Protobuf_VERSION}（major=${CMAKE_MATCH_1}）provider=${HGCP_SMOKE_PROTOBUF_PROVIDER}")

#------------------------------------------------------------------------------
# 3. 生成 pb 代码（自实现 add_custom_command：系统包 / FetchContent 两种供给都能用）
#------------------------------------------------------------------------------
get_filename_component(HGCP_SMOKE_SCHEMA_ABS "${CMAKE_CURRENT_SOURCE_DIR}/${HGCP_SMOKE_SCHEMA_FILE}" ABSOLUTE)
if(NOT EXISTS "${HGCP_SMOKE_SCHEMA_ABS}")
  message(FATAL_ERROR "[RSK-01] 找不到 schema：${HGCP_SMOKE_SCHEMA_ABS}")
endif()

set(_gen_dir "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${_gen_dir}")
set(_pb_hdr "${_gen_dir}/hgcp_smoke.pb.h")
set(_pb_src "${_gen_dir}/hgcp_smoke.pb.cc")

# lite 运行时须用 `--cpp_out=lite:<dir>`（protoc 3.21 支持），
# 否则生成代码引用完整运行时的反射/描述符符号，链接 lite 库必失败。
if(HG_PROTOBUF_LITE)
  set(_cpp_out_arg "--cpp_out=lite:${_gen_dir}")
else()
  set(_cpp_out_arg "--cpp_out=${_gen_dir}")
endif()

add_custom_command(
  OUTPUT "${_pb_hdr}" "${_pb_src}"
  COMMAND ${HGCP_PROTOC_CMD}
          "--proto_path=${CMAKE_CURRENT_SOURCE_DIR}/proto"
          "${_cpp_out_arg}"
          "${HGCP_SMOKE_SCHEMA_ABS}"
  DEPENDS "${HGCP_SMOKE_SCHEMA_ABS}" ${HGCP_PROTOBUF_FETCH_DEPENDS}
  COMMENT "[RSK-01] protoc --cpp_out 生成 hgcp_smoke.pb.{h,cc}"
  VERBATIM)

add_custom_target(hgcp_smoke_proto_gen DEPENDS "${_pb_hdr}" "${_pb_src}")

#------------------------------------------------------------------------------
# 3b. 真实冻结 schema（`proto/hgcp.proto`，CH-013 / §4.2.8）→ 生成并编译
#     验收 6：以 `-std=c++11 -Wall -Wextra -Werror` 编译真实 schema 的生成代码。
#     说明：保留上述 smoke 用例（探路留痕）；本段**新增**真实 schema 的生成 + 编译
#           （生成的 `hgcp.pb.cc` 编入同一可执行目标 ⇒ 施加同一严格编译参数）。
#------------------------------------------------------------------------------
set(HGCP_REAL_SCHEMA_ABS
    "${CMAKE_CURRENT_SOURCE_DIR}/../../proto/hgcp.proto")
if(NOT EXISTS "${HGCP_REAL_SCHEMA_ABS}")
  message(FATAL_ERROR "[RSK-01] 找不到真实冻结 schema：${HGCP_REAL_SCHEMA_ABS}（CH-013 / §4.2.8）")
endif()
set(_real_gen_dir "${CMAKE_CURRENT_BINARY_DIR}/generated_real")
file(MAKE_DIRECTORY "${_real_gen_dir}")
set(_pb_real_hdr "${_real_gen_dir}/hgcp.pb.h")
set(_pb_real_src "${_real_gen_dir}/hgcp.pb.cc")
if(HG_PROTOBUF_LITE)
  set(_cpp_out_real_arg "--cpp_out=lite:${_real_gen_dir}")
else()
  set(_cpp_out_real_arg "--cpp_out=${_real_gen_dir}")
endif()
add_custom_command(
  OUTPUT "${_pb_real_hdr}" "${_pb_real_src}"
  COMMAND ${HGCP_PROTOC_CMD}
          "--proto_path=${CMAKE_CURRENT_SOURCE_DIR}/../../proto"
          "${_cpp_out_real_arg}"
          "${HGCP_REAL_SCHEMA_ABS}"
  DEPENDS "${HGCP_REAL_SCHEMA_ABS}" ${HGCP_PROTOBUF_FETCH_DEPENDS}
  COMMENT "[RSK-01] protoc --cpp_out 生成真实 schema（CH-013 / §4.2.8）hgcp.pb.{h,cc}"
  VERBATIM)
add_custom_target(hgcp_real_proto_gen DEPENDS "${_pb_real_hdr}" "${_pb_real_src}")

#------------------------------------------------------------------------------
# 4. 构建信息头（把 CMake 观测到的版本/参数固化进二进制）
#------------------------------------------------------------------------------
set(HGCP_SMOKE_CXX_STANDARD_VALUE "${CMAKE_CXX_STANDARD}")
set(HGCP_SMOKE_PROTOBUF_MAJOR_REQUIRED "${HG_PROTOBUF_MAJOR_REQUIRED}")
set(HGCP_SMOKE_CXX_FLAGS "-std=c++${CMAKE_CXX_STANDARD} -Wall -Wextra -Werror ${CMAKE_CXX_FLAGS}")
string(STRIP "${HGCP_SMOKE_CXX_FLAGS}" HGCP_SMOKE_CXX_FLAGS)
set(HGCP_SMOKE_BUILD_DIR "${CMAKE_CURRENT_BINARY_DIR}")
configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/src/hgcp_proto_smoke_build_info.h.in"
  "${CMAKE_CURRENT_BINARY_DIR}/hgcp_proto_smoke_build_info.h"
  @ONLY)

#------------------------------------------------------------------------------
# 5. 可执行目标
#------------------------------------------------------------------------------
add_executable(hgcp_proto_smoke
  "${CMAKE_CURRENT_SOURCE_DIR}/src/hgcp_proto_smoke_main.cpp"
  "${_pb_src}"
  "${_pb_hdr}"
  "${_pb_real_src}"
  "${_pb_real_hdr}")
add_dependencies(hgcp_proto_smoke hgcp_smoke_proto_gen)
add_dependencies(hgcp_proto_smoke hgcp_real_proto_gen)

target_include_directories(hgcp_proto_smoke PRIVATE
  "${_gen_dir}"
  "${_real_gen_dir}"
  "${CMAKE_CURRENT_BINARY_DIR}")

target_link_libraries(hgcp_proto_smoke PRIVATE ${HGCP_PROTOBUF_TARGET})

find_package(Threads REQUIRED)
target_link_libraries(hgcp_proto_smoke PRIVATE Threads::Threads)

# 严格 C++11 + 零警告（ARCHITECTURE.md §1.2 / E3 脚本口径）
set_target_properties(hgcp_proto_smoke PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
target_compile_options(hgcp_proto_smoke PRIVATE -Wall -Wextra -Werror)

#------------------------------------------------------------------------------
# 6. ctest 用例
#------------------------------------------------------------------------------
if(HG_BUILD_SMOKE_TESTS)
  enable_testing()
  add_test(NAME rsk01.cxx11_standard_in_effect
           COMMAND hgcp_proto_smoke --print-config)
  add_test(NAME rsk01.empty_message_roundtrip
           COMMAND hgcp_proto_smoke --empty-roundtrip)
  add_test(NAME rsk01.fields_message_roundtrip
           COMMAND hgcp_proto_smoke --fields-roundtrip)
  set_tests_properties(rsk01.cxx11_standard_in_effect PROPERTIES TIMEOUT 30)
  set_tests_properties(rsk01.empty_message_roundtrip PROPERTIES TIMEOUT 30)
  set_tests_properties(rsk01.fields_message_roundtrip PROPERTIES TIMEOUT 30)
endif()
