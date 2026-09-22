# =============================================================================
# T-027 / RSK-02 —— CTP SDK 头文件在 C++11 边界下的编译兼容验证
#
# ⚠️ 2026-09-22 **重做并重冻结**（CTP 基线变更，`CH-015`）：交付基线 = **官方 CTP v6.7.13**
#    （分离式 `ThostFtdcTraderApi.h` + `CThostFtdcMdApi.h`）。上一版探路件建立在
#    `CTP Mini`（**合并式** `ThostFtdcUserApi.h` / `CThostFtdcUserSpi`）之上，
#    其 `PASS=11` 结论**对交付基线不成立、已失效**；本件在官方 SDK 上重做。
#    判据 `B6` 因此**反向**：官方基线下 `<ThostFtdcTraderApi.h>` **可得**、
#    `<ThostFtdcUserApi.h>` **不可得**（详见 ci/run_probe.sh）。
#
# 目标（可客观判定）：
#   ① 以最小程序 `#include` CTP 交易头文件，在 `-std=c++11 -Wall -Wextra -Werror`
#      下编译通过（并链接真实 SDK 动态库读取 SDK 自报版本）；
#   ② 记录 CTP SDK 版本与编译参数；
#   ③ 若冲突 → 提 CH-xxx（L1/L2）上报用户，**不得自行放宽 C++11 边界**。
#
# 上游依据：docs/ARCHITECTURE.md §3.1（ctp_adapter）、§1.2（编译标准约束）、
#           §7.2.1（依赖清单）、§9 RSK-02；PROJECT.md §5.1 / §6。
#
# 头文件风格：
#   CLASSIC = 官方**分离式** `ThostFtdcTraderApi.h`（`CThostFtdcTraderApi` /
#             `CThostFtdcTraderSpi`）—— **交付基线口径（默认）**。
#   MERGED  = `CTP Mini` **合并版** `ThostFtdcUserApi.h`（`CThostFtdcUserApi` /
#             `CThostFtdcUserSpi`）—— 历史口径，仅作对照保留（官方 SDK 上不可得）。
#   两者由同一份探路源码 + 生成的 include 头覆盖，无需改源码即可切换。
#
# SDK 定位：与主工程**共用同一判定实现**（`cpp/cmake/HgCtpLocate.cmake`），支持
#   `CTP_SDK_ROOT` 父目录自动探测 / `CTP_TRADER_ROOT`+`CTP_MD_ROOT` 覆盖 / 老式单目录。
# =============================================================================

cmake_minimum_required(VERSION 3.16)

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
  project(hgcp_ctp_smoke LANGUAGES CXX)
endif()
if(NOT CMAKE_CXX_STANDARD)
  set(CMAKE_CXX_STANDARD 11 CACHE STRING "CTP 探路工程即 C++11")
endif()
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE)
  # 注意：project() 会预置一个**空**的 CMAKE_BUILD_TYPE 缓存项，
  #       不带 FORCE 的 set(... CACHE ...) 无法覆盖 → 必须 FORCE（有 if 兜底，不覆盖用户显式选择）。
  set(CMAKE_BUILD_TYPE "RelWithDebInfo" CACHE STRING "构建类型" FORCE)
endif()
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

#------------------------------------------------------------------------------
# 0. CTP SDK 定位（与主工程同一宏：cpp/cmake/HgCtpLocate.cmake）
#------------------------------------------------------------------------------
set(CTP_SDK_ROOT "" CACHE PATH "CTP SDK 根目录（官方解包父目录 或 老式单目录）；留空则按环境变量 CTP_SDK_ROOT 查找")
set(CTP_TRADER_ROOT "" CACHE PATH "交易 SDK 目录（显式覆盖 CTP_SDK_ROOT 的探测结果；须含交易头）")
set(CTP_MD_ROOT "" CACHE PATH "行情 SDK 目录（显式覆盖 CTP_SDK_ROOT 的探测结果；须含行情头）")
set(HG_CTP_HEADER_STYLE "CLASSIC" CACHE STRING "CLASSIC（官方分离式 TraderApi，交付基线）| MERGED（CTP Mini 合并版 UserApi，历史）")
set(HG_CTP_REQUIRE_SDK OFF CACHE BOOL "ON 时找不到 SDK 即 FATAL_ERROR（CI 严格模式）")
option(HG_BUILD_SMOKE_TESTS "构建并注册 ctest 用例" ON)

set(HGCP_CTP_SDK_ROOT_SOURCE "cache-variable CTP_SDK_ROOT")
if(NOT CTP_SDK_ROOT AND DEFINED ENV{CTP_SDK_ROOT})
  set(CTP_SDK_ROOT "$ENV{CTP_SDK_ROOT}")
  set(HGCP_CTP_SDK_ROOT_SOURCE "environment CTP_SDK_ROOT")
endif()

#------------------------------------------------------------------------------
# 1. 头文件风格 → 生成 include 头
#------------------------------------------------------------------------------
if(HG_CTP_HEADER_STYLE STREQUAL "CLASSIC")
  set(HGCP_CTP_TRADER_HEADER "ThostFtdcTraderApi.h")
  set(HGCP_CTP_TRADER_API_CLASS "CThostFtdcTraderApi")
  set(HGCP_CTP_TRADER_SPI_CLASS "CThostFtdcTraderSpi")
  set(HGCP_CTP_STYLE_MERGED_DEFINE "#define HGCP_CTP_STYLE_MERGED 0")
  # 官方分离式 `SubscribePrivateTopic(THOST_TE_RESUME_TYPE, int nSeqNo=1)` ⇒ 2 参。
  set(HGCP_CTP_PRIVATE_TOPIC_ARITY 2)
elseif(HG_CTP_HEADER_STYLE STREQUAL "MERGED")
  set(HGCP_CTP_TRADER_HEADER "ThostFtdcUserApi.h")
  set(HGCP_CTP_TRADER_API_CLASS "CThostFtdcUserApi")
  set(HGCP_CTP_TRADER_SPI_CLASS "CThostFtdcUserSpi")
  set(HGCP_CTP_STYLE_MERGED_DEFINE "#define HGCP_CTP_STYLE_MERGED 1")
  set(HGCP_CTP_PRIVATE_TOPIC_ARITY 1)
else()
  message(FATAL_ERROR "[RSK-02] HG_CTP_HEADER_STYLE 只能是 CLASSIC 或 MERGED，实为 '${HG_CTP_HEADER_STYLE}'")
endif()

set(HGCP_CTP_MD_HEADER "ThostFtdcMdApi.h")
set(HGCP_CTP_TRADER_INCLUDE "#include <${HGCP_CTP_TRADER_HEADER}>")
set(HGCP_CTP_MD_INCLUDE "#include <${HGCP_CTP_MD_HEADER}>")

configure_file("${CMAKE_CURRENT_SOURCE_DIR}/src/hgcp_ctp_headers.h.in"
               "${CMAKE_CURRENT_BINARY_DIR}/hgcp_ctp_headers.h" @ONLY)

#------------------------------------------------------------------------------
# 2. SDK 可得性判定 → CTP_FOUND 双轨构建（共享宏：与主工程同源）
#------------------------------------------------------------------------------
include("${CMAKE_CURRENT_SOURCE_DIR}/../cmake/HgCtpLocate.cmake")

set(_hgcp_ctp_explicit 0)
if(CTP_SDK_ROOT OR CTP_TRADER_ROOT OR CTP_MD_ROOT OR DEFINED ENV{CTP_SDK_ROOT})
  set(_hgcp_ctp_explicit 1)
endif()

set(HG_CL_TRADER_HEADER    "${HGCP_CTP_TRADER_HEADER}")
set(HG_CL_MD_HEADER        "${HGCP_CTP_MD_HEADER}")
set(HG_CL_TRADER_LIB_NAMES "thosttraderapi_se.so;libthosttraderapi_se.so;libthosttraderapi.so;libthostuserapi.so")
set(HG_CL_MD_LIB_NAMES     "thostmduserapi_se.so;libthostmduserapi_se.so;libthostmduserapi.so")
set(HG_CL_SDK_ROOT         "${CTP_SDK_ROOT}")
set(HG_CL_TRADER_ROOT      "${CTP_TRADER_ROOT}")
set(HG_CL_MD_ROOT          "${CTP_MD_ROOT}")
set(HG_CL_EXPLICIT         "${_hgcp_ctp_explicit}")
hg_locate_ctp(HGCP_CTP)

set(CTP_FOUND OFF)
set(HGCP_CTP_PROBE_LINKED "NO")
set(HGCP_CTP_PROBE_LINKED_NUM 0)
set(HGCP_CTP_LIBRARIES "")
set(_ctp_include_dirs "")

if(HGCP_CTP_FOUND)
  set(CTP_FOUND ON)
  set(HGCP_CTP_PROBE_LINKED "YES")
  set(HGCP_CTP_PROBE_LINKED_NUM 1)
  set(HGCP_CTP_LIBRARIES "${HGCP_CTP_TRADER_LIB};${HGCP_CTP_MD_LIB}")
  set(_ctp_include_dirs "${HGCP_CTP_TRADER_INCLUDE_DIR};${HGCP_CTP_MD_INCLUDE_DIR}")
  message(STATUS "[RSK-02] 发现 CTP SDK：${CTP_SDK_ROOT}，头文件风格 ${HG_CTP_HEADER_STYLE}"
                 "（交易头 ${HGCP_CTP_TRADER_INCLUDE_DIR}/${HGCP_CTP_TRADER_HEADER}）")
  message(STATUS "[RSK-02] 链接 SDK 动态库：${HGCP_CTP_LIBRARIES}")
else()
  message(STATUS "[RSK-02] CTP SDK 不可得（CTP_SDK_ROOT='${CTP_SDK_ROOT}' "
                 "CTP_TRADER_ROOT='${CTP_TRADER_ROOT}' CTP_MD_ROOT='${CTP_MD_ROOT}'；"
                 "缺：${HGCP_CTP_MISSING}）→ CTP_FOUND=OFF，跳过探路目标")
  # ★ 与主工程同一纪律：显式给了根却解析失败 ⇒ 配置期失败（不得静默跳过）。
  if(_hgcp_ctp_explicit)
    message(FATAL_ERROR
      "[RSK-02] 已显式指定 CTP SDK 根，但解析必需头/库失败：${HGCP_CTP_MISSING}\n"
      "          → 拒绝静默跳过探路（否则『探路通过』会变成对不存在 SDK 的空判）。")
  endif()
  if(HG_CTP_REQUIRE_SDK)
    message(FATAL_ERROR "[RSK-02] HG_CTP_REQUIRE_SDK=ON 但 CTP SDK 不可得；不得伪造 SDK 源码，请提供 CTP_SDK_ROOT")
  endif()
endif()

#------------------------------------------------------------------------------
# 3. 构建信息头
#------------------------------------------------------------------------------
set(HGCP_CTP_PROBE_CXX_FLAGS "-std=c++${CMAKE_CXX_STANDARD} -Wall -Wextra -Werror ${CMAKE_CXX_FLAGS}")
string(STRIP "${HGCP_CTP_PROBE_CXX_FLAGS}" HGCP_CTP_PROBE_CXX_FLAGS)
if(NOT HGCP_CTP_LIBRARIES)
  set(HGCP_CTP_LIBRARIES "(none)")
endif()
configure_file("${CMAKE_CURRENT_SOURCE_DIR}/src/hgcp_ctp_probe_build_info.h.in"
               "${CMAKE_CURRENT_BINARY_DIR}/hgcp_ctp_probe_build_info.h" @ONLY)

#------------------------------------------------------------------------------
# 4. 目标与测试
#------------------------------------------------------------------------------
if(CTP_FOUND)
  add_executable(hgcp_ctp_probe "${CMAKE_CURRENT_SOURCE_DIR}/src/ctp_include_probe.cpp")
  target_include_directories(hgcp_ctp_probe PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")

  # ---------------------------------------------------------------------------
  # CTP SDK 头文件必须作为 **SYSTEM 头** 引入
  #
  # 实测结论（T-027 重做，官方 v6.7.13）：
  #   `ThostFtdcTraderApi.h` / `ThostFtdcMdApi.h` 的 SPI 回调基类用
  #   `virtual void OnXxx(...) {};` 形式内联空实现，**参数具名但未使用**，
  #   在 `-Wall -Wextra` 下产生大量 `-Wunused-parameter`（官方实测 602 条）；
  #   配合 `-Werror` 直接编译失败。
  #   该问题**与 C++ 语言标准无关**（头文件内无任何 C++14/17 语法），
  #   属"第三方头文件的告警洁癖"问题，故**不需要也不得**放宽 C++11 边界。
  #
  # 处置：把 CTP SDK 目录标记为 SYSTEM 目录 → GCC 抑制其内部告警，
  #       而**本工程自己的源码仍受完整 `-Wall -Wextra -Werror` 约束**。
  #       设 `-DHG_CTP_INCLUDE_AS_SYSTEM=OFF` 可复现冲突（CI 反证用）。
  # 官方分离式布局下交易头/行情头分处两个目录 ⇒ 两目录都按 SYSTEM 引入。
  # ---------------------------------------------------------------------------
  set(HG_CTP_INCLUDE_AS_SYSTEM ON CACHE BOOL "把 CTP SDK 目录作为 SYSTEM 头引入（抑制第三方告警）")
  if(HG_CTP_INCLUDE_AS_SYSTEM)
    target_include_directories(hgcp_ctp_probe SYSTEM PRIVATE ${_ctp_include_dirs})
    message(STATUS "[RSK-02] CTP SDK 目录以 SYSTEM 头引入：${_ctp_include_dirs}")
  else()
    target_include_directories(hgcp_ctp_probe PRIVATE ${_ctp_include_dirs})
    message(STATUS "[RSK-02] CTP SDK 目录以普通头引入（预期触发 -Werror=unused-parameter）")
  endif()

  set_target_properties(hgcp_ctp_probe PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF)
  target_compile_options(hgcp_ctp_probe PRIVATE -Wall -Wextra -Werror)

  if(HGCP_CTP_PROBE_LINKED_NUM EQUAL 1)
    # 经 IMPORTED 目标链接（官方 `.so` 无 `lib` 前缀；直接传路径会被 CMake 归一化为
    # `-l<name>` 而找不到）。IMPORTED 目标亦自动为该消费方追加 rpath。
    target_link_libraries(hgcp_ctp_probe PRIVATE HGCP_CTP_traderapi HGCP_CTP_mdapi)
  endif()

  if(HG_BUILD_SMOKE_TESTS)
    enable_testing()
    add_test(NAME rsk02.ctp_header_cxx11_compat COMMAND hgcp_ctp_probe)
    set_tests_properties(rsk02.ctp_header_cxx11_compat PROPERTIES TIMEOUT 30)
  endif()
endif()
