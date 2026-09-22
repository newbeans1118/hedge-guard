# =============================================================================
# HgDependencies.cmake —— C++ 交易组件的依赖供给（双轨构建）
#
# 依据：PROJECT.md §5.1（CMake ≥ 3.16 + FetchContent；`CTP_FOUND` /
#       `CTP_USE_FETCH_DEPS` 条件宏控制双轨构建）；docs/ARCHITECTURE.md §1.2、
#       §3.1（ctp_adapter 依赖 CTP SDK）、§3.6 / §7.2.1（依赖清单 = CTP SDK +
#       protobuf，**无 websocketpp / Boost**，CH-006）、§4.2.5（两侧 protobuf 同大版本）。
#
# 本文件对外提供两个事实：
#   · `CTP_FOUND`          —— CTP SDK（官方分离式 `ThostFtdcTraderApi.h` + `ThostFtdcMdApi.h`
#                             + `thosttraderapi_se.so` / `thostmduserapi_se.so`）是否可得
#   · `hg_deps::protobuf`  —— protobuf 供给目标（系统包 或 FetchContent 源码构建）
#
# ⚠️ **头名/风格单一入口**：具体头文件名由 `HG_CTP_HEADER_STYLE`（CLASSIC|MERGED）在
#    `cmake/HgCtpStyle.cmake` 里**唯一派生**，本文件与探路件 `cpp/ctp_smoke/CMakeLists.txt`
#    **共用同一实现** —— 二者须给出**一致**的 `CTP_FOUND`（T-128）。
#
# 双轨口径（两轨均须**真的能编**，不是只有开关）：
#   CTP_FOUND=ON           → ctp_adapter 以真实 CTP SDK 头编译（SYSTEM 头，抑制第三方告警）
#   CTP_FOUND=OFF          → ctp_adapter 走空实现，组件仍可完整构建（无 SDK 环境 / CI）
#   ⚠️ **显式给了 SDK 根却解析失败 ⇒ FATAL_ERROR**（不得静默回落 stub，见 §1 守卫）
#   CTP_USE_FETCH_DEPS=ON  → protobuf 经 FetchContent 拉取并源码构建
#   CTP_USE_FETCH_DEPS=OFF → protobuf 用系统包（find_package(Protobuf)）
#
# ⚠️ **CTP SDK 不入 FetchContent**（厂商 SDK 无法从公共源拉取，ARCHITECTURE.md §7.2.1）。
# =============================================================================

include_guard(GLOBAL)

# -----------------------------------------------------------------------------
# 0. 开关（可 -D 覆盖）
# -----------------------------------------------------------------------------
option(CTP_USE_FETCH_DEPS
  "protobuf 依赖经 FetchContent 拉取（双轨：OFF=系统包）" OFF)

# CTP SDK 根（三种布局皆可，见 cmake/HgCtpLocate.cmake）：
#   · 官方解包父目录（内部自动探测 *_traderapi_se_linux64 / *_mduserapi_se_linux64）；
#   · 老式单目录（同时含两套头与两个库）；
#   · 亦可用 CTP_TRADER_ROOT / CTP_MD_ROOT 分别显式覆盖。
set(CTP_SDK_ROOT "" CACHE PATH
  "CTP SDK 根目录（官方解包父目录 或 老式单目录）；留空则按环境变量 CTP_SDK_ROOT 查找")
set(CTP_TRADER_ROOT "" CACHE PATH
  "交易 SDK 目录（显式覆盖 CTP_SDK_ROOT 的探测结果；须含交易头）")
set(CTP_MD_ROOT "" CACHE PATH
  "行情 SDK 目录（显式覆盖 CTP_SDK_ROOT 的探测结果；须含行情头）")
set(HG_CTP_REQUIRE_SDK OFF CACHE BOOL
  "ON 时找不到 CTP SDK 即 FATAL_ERROR（CI 严格模式）；OFF 时 CTP_FOUND=OFF 走空实现")

# -----------------------------------------------------------------------------
# 0b. CTP 头文件风格 → 头名/类名（**唯一入口** = HG_CTP_HEADER_STYLE）
#     ⚠️ 不要再在本文件里另设「按头名覆盖」的入口 —— 主工程与探路件**必须**同源
#     （T-128：双源会使同一组配置下两端得出矛盾的 `CTP_FOUND` = 诊断误导）。
#     派生实现唯一 = cmake/HgCtpStyle.cmake；本文件与 cpp/ctp_smoke/CMakeLists.txt 共用。
# -----------------------------------------------------------------------------
include("${CMAKE_CURRENT_LIST_DIR}/HgCtpStyle.cmake")
hg_ctp_resolve_style()

set(HG_PROTOBUF_MAJOR_REQUIRED "3" CACHE STRING
  "C++/Java 两侧一致的 protobuf 大版本（§4.2.5）")
set(HG_PROTOBUF_PREFERRED_VERSION "3.21.12" CACHE STRING
  "首选版本（§7.1.5；须支持 C++11）")

# -----------------------------------------------------------------------------
# 1. CTP SDK 定位（双轨之一：CTP_FOUND）—— 判定逻辑**唯一实现** = cmake/HgCtpLocate.cmake
#    支持官方分离式（父目录 + 两子目录）/ 老式单目录 / CTP_TRADER_ROOT+CTP_MD_ROOT 覆盖。
# -----------------------------------------------------------------------------
include("${CMAKE_CURRENT_LIST_DIR}/HgCtpLocate.cmake")

set(_hg_ctp_sdk_source "cache-variable CTP_SDK_ROOT")
if(NOT CTP_SDK_ROOT AND DEFINED ENV{CTP_SDK_ROOT})
  set(CTP_SDK_ROOT "$ENV{CTP_SDK_ROOT}")
  set(_hg_ctp_sdk_source "environment CTP_SDK_ROOT")
endif()

# 「用户是否显式给了根」——决定缺件时的处置（显式 ⇒ FATAL，不得静默回落 stub）。
set(_hg_ctp_explicit 0)
if(CTP_SDK_ROOT OR CTP_TRADER_ROOT OR CTP_MD_ROOT OR DEFINED ENV{CTP_SDK_ROOT})
  set(_hg_ctp_explicit 1)
endif()

set(HG_CL_TRADER_HEADER    "${HG_CTP_TRADER_HEADER}")
set(HG_CL_MD_HEADER        "${HG_CTP_MD_HEADER}")
set(HG_CL_TRADER_LIB_NAMES "thosttraderapi_se.so;libthosttraderapi_se.so;libthosttraderapi.so;libthostuserapi.so")
set(HG_CL_MD_LIB_NAMES     "thostmduserapi_se.so;libthostmduserapi_se.so;libthostmduserapi.so")
set(HG_CL_SDK_ROOT         "${CTP_SDK_ROOT}")
set(HG_CL_TRADER_ROOT      "${CTP_TRADER_ROOT}")
set(HG_CL_MD_ROOT          "${CTP_MD_ROOT}")
set(HG_CL_EXPLICIT         "${_hg_ctp_explicit}")
hg_locate_ctp(HG_CTP)

set(CTP_FOUND OFF)
if(HG_CTP_FOUND)
  set(CTP_FOUND ON)
  message(STATUS "[CTP] 发现 CTP SDK（${_hg_ctp_sdk_source}）："
                 "trader-include=${HG_CTP_TRADER_INCLUDE_DIR} ; md-include=${HG_CTP_MD_INCLUDE_DIR} ; "
                 "trader-lib=${HG_CTP_TRADER_LIB} ; md-lib=${HG_CTP_MD_LIB}")
else()
  message(STATUS "[CTP] CTP SDK 不可得（CTP_SDK_ROOT='${CTP_SDK_ROOT}' "
                 "CTP_TRADER_ROOT='${CTP_TRADER_ROOT}' CTP_MD_ROOT='${CTP_MD_ROOT}'；"
                 "缺：${HG_CTP_MISSING}）")
endif()

# -----------------------------------------------------------------------------
# 1b. 判定结果**标准化披露**（T-128 可核判据）
#     与 `cpp/ctp_smoke/CMakeLists.txt` 打印同形行 ⇒ 同一组 SDK 根/风格配置下，
#     两处的 `found=` 必须一致（判据脚本 cpp/ctp_smoke/ci/check_ctp_found_parity.sh）。
#     ⚠️ 披露**先于**下面的处置（FATAL）—— 否则分歧时只能看到一方的判定，
#        判据无法比较（本件与探路件同此口径）。
# -----------------------------------------------------------------------------
message(STATUS "[HG-CTP-FOUND] role=main found=${CTP_FOUND} style=${HG_CTP_HEADER_STYLE} "
               "trader_header=${HG_CL_TRADER_HEADER} md_header=${HG_CL_MD_HEADER}")

# -----------------------------------------------------------------------------
# 1c. 处置（判定与处置分离）：不可得时的守卫与降级说明
# -----------------------------------------------------------------------------
if(NOT HG_CTP_FOUND)
  # ★ 静默降级守卫：用户**显式**给了根却解析不出必需头/库 ⇒ 配置期失败。
  #   不得回落 stub —— 否则「控制看起来有、实际没有」（本项目「假绿」纪律）。
  if(_hg_ctp_explicit)
    message(FATAL_ERROR
      "[CTP] 已显式指定 CTP SDK 根，但解析必需头/库失败：${HG_CTP_MISSING}\n"
      "      → 拒绝静默回落 stub（否则 ctp_adapter 变成空实现且构建/测试照样『全绿』）。\n"
      "      请核对 CTP_SDK_ROOT / CTP_TRADER_ROOT / CTP_MD_ROOT 指向；"
      "若确实想走 stub 轨，请勿显式设置 SDK 根。")
  endif()
  if(HG_CTP_REQUIRE_SDK)
    message(FATAL_ERROR
      "[CTP] HG_CTP_REQUIRE_SDK=ON 但 CTP SDK 不可得；不得伪造 SDK 源码，请提供 CTP_SDK_ROOT")
  endif()
  message(STATUS "[CTP] → CTP_FOUND=OFF（ctp_adapter 走空实现）")
endif()

# -----------------------------------------------------------------------------
# 2. protobuf 供给（双轨之二：CTP_USE_FETCH_DEPS）
# -----------------------------------------------------------------------------
if(CTP_USE_FETCH_DEPS)
  include(FetchContent)
  message(STATUS "[protobuf] CTP_USE_FETCH_DEPS=ON → FetchContent 拉取 "
                 "protobuf v${HG_PROTOBUF_PREFERRED_VERSION} 并源码构建")

  # protobuf 3.x 的 CMake 选项（3.19/3.21 同名）
  set(protobuf_BUILD_TESTS OFF CACHE BOOL "" FORCE)
  set(protobuf_BUILD_CONFORMANCE OFF CACHE BOOL "" FORCE)
  set(protobuf_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(protobuf_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
  set(protobuf_BUILD_PROTOC_BINARIES ON CACHE BOOL "" FORCE)
  set(protobuf_MSVC_STATIC_RUNTIME OFF CACHE BOOL "" FORCE)
  # libprotobuf 自身即按 C++11 构建（3.x 的 target_compile_features 为 cxx_std_11）；
  # 显式对齐，避免子项目抬高标准反过来污染本工程的 C++11 边界。
  set(CMAKE_CXX_STANDARD 11 CACHE STRING "protobuf 3.x 亦为 C++11；显式对齐")

  # CMake ≥ 4 已移除对 < 3.5 的兼容；protobuf 3.19/3.21 的 cmake_minimum_required
  # 低于该下限 ⇒ 须显式抬到 3.5，否则 `Compatibility with CMake < 3.5 has been removed`。
  if(CMAKE_VERSION VERSION_GREATER_EQUAL "4.0"
     AND HG_PROTOBUF_PREFERRED_VERSION VERSION_LESS "3.22")
    set(CMAKE_POLICY_VERSION_MINIMUM "3.5" CACHE STRING
      "protobuf 3.19/3.21 的 cmake_minimum_required 低于 CMake 4 的下限" FORCE)
  endif()

  # 拉取方式：**URL + 强制 SHA256 校验**（与 `cpp/proto_smoke` 同一纪律：缓存只省下载、
  # 不省校验）。v3.21.12 源码树**有顶层 `CMakeLists.txt`** ⇒ 无需 `SOURCE_SUBDIR`。
  # `HG_PROTOBUF_TARBALL` 可指向已缓存的本地 tarball（离线/弱网 CI），校验和仍强制。
  set(HG_PROTOBUF_TARBALL "" CACHE FILEPATH
    "本地已缓存的 protobuf 源码 tarball（可选，优先于在线下载）")
  set(HG_PROTOBUF_TARBALL_SHA256 "" CACHE STRING
    "覆盖内置的 tarball SHA256（留空则用内置值）")

  set(_hg_pb_sha256 "${HG_PROTOBUF_TARBALL_SHA256}")
  if(NOT _hg_pb_sha256)
    if(HG_PROTOBUF_PREFERRED_VERSION STREQUAL "3.21.12")
      # codeload git-archive 源码 tarball（= v3.21.12 标签完整源码树）。
      # 实测 2026-09-21 下载 5,141,502 字节，SHA256 如下。
      set(_hg_pb_sha256 "930c2c3b5ecc6c9c12615cf5ad93f1cd6e12d0aba862b572e076259970ac3a53")
    endif()
  endif()
  if(NOT _hg_pb_sha256)
    message(FATAL_ERROR
      "[protobuf] v${HG_PROTOBUF_PREFERRED_VERSION} 无内置 SHA256。\n"
      "         请显式 -DHG_PROTOBUF_TARBALL_SHA256=<sha256>（**不得跳过校验**）。")
  endif()

  set(_hg_pb_fc_args
    URL_HASH "SHA256=${_hg_pb_sha256}"
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
  if(HG_PROTOBUF_TARBALL)
    if(NOT EXISTS "${HG_PROTOBUF_TARBALL}")
      message(FATAL_ERROR "[protobuf] HG_PROTOBUF_TARBALL 不存在：${HG_PROTOBUF_TARBALL}")
    endif()
    message(STATUS "[protobuf] 使用本地缓存 tarball：${HG_PROTOBUF_TARBALL}（仍强制 SHA256 校验）")
    FetchContent_Declare(protobuf URL "file://${HG_PROTOBUF_TARBALL}" ${_hg_pb_fc_args})
  else()
    set(_hg_pb_url
      "https://codeload.github.com/protocolbuffers/protobuf/tar.gz/refs/tags/v${HG_PROTOBUF_PREFERRED_VERSION}")
    message(STATUS "[protobuf] 在线下载源码 tarball：${_hg_pb_url}（SHA256 校验开启）")
    FetchContent_Declare(protobuf URL "${_hg_pb_url}" ${_hg_pb_fc_args})
  endif()

  # ---------------------------------------------------------------------------
  # 第三方源码豁免严格告警：
  #   本工程要求自家代码 `-Wall -Wextra -Werror`（ARCHITECTURE.md §1.2 / E3），
  #   但 protobuf 源码树内的 `port.h` 等含**未使用参数**（`-Wunused-parameter`），
  #   若把本工程的 `-Werror` 施加到它，会直接**编译失败**（实测 2026-09-21）。
  #   故在 `add_subdirectory(protobuf)` 生效的瞬间清空目录级 `COMPILE_OPTIONS`，
  #   事后恢复——**本工程自己的目标仍受严格告警约束**，第三方源码则豁免。
  #   （与 `ctp_adapter` 把 CTP SDK 头作为 **SYSTEM 头**引入是同一纪律：
  #     严格告警只约束自家代码。）
  # ---------------------------------------------------------------------------
  get_directory_property(_hg_saved_compile_options COMPILE_OPTIONS)
  set_directory_properties(PROPERTIES COMPILE_OPTIONS "")
  FetchContent_MakeAvailable(protobuf)
  set_directory_properties(PROPERTIES COMPILE_OPTIONS "${_hg_saved_compile_options}")

  # protobuf 3.x 的 project() 不带 VERSION（3.19/3.21 皆然）⇒ 版本即我们指定的版本。
  if(NOT protobuf_VERSION)
    set(protobuf_VERSION "${HG_PROTOBUF_PREFERRED_VERSION}")
  endif()
  set(Protobuf_VERSION "${protobuf_VERSION}")

  add_library(hg_deps_protobuf INTERFACE)
  target_link_libraries(hg_deps_protobuf INTERFACE libprotobuf)

  # ---------------------------------------------------------------------------
  # 第三方**头目录** SYSTEM 化（T-129）：
  #   上述「清空目录级 COMPILE_OPTIONS」只豁免 protobuf **自身源码**的编译；它**不**
  #   影响**消费方**包含 protobuf 头时的告警归属 —— `libprotobuf` 把源码树
  #   `_deps/protobuf-src/src` 作为**普通**（非 SYSTEM）include 目录传播（`-I`），
  #   于是本工程的 `-Werror` 会被施加到第三方 protobuf 头，`port.h:46/53`
  #   （`SizedDelete`/`SizedArrayDelete` 的具名未用参）在 `-Wall -Wextra` 下报警
  #   ⇒ 直接**构建失败**（实测 2026-09-22：`hgcp/src/payload_codec.cc` 经
  #   `hgcp.pb.h` → `google/protobuf/io/coded_stream.h` → `port.h`）。
  #
  #   处置：把 protobuf 头目录标记为 **SYSTEM**（编译器以 `-isystem` 引入 ⇒ 第三方头
  #   不参与 `-Wall -Wextra -Werror`）。口径与 `ctp_adapter` 把官方 CTP SDK 头作
  #   **SYSTEM 头**引入**同族**（ARCHITECTURE.md §1.2 / E3：严格告警只约束自家代码）。
  #   系统包轨（`find_package`）的 `protobuf::libprotobuf` 是 **IMPORTED** 目标，
  #   CMake 对 imported 目标的 `SYSTEM` 属性**默认为真** ⇒ 无需另配（本段仅关 FetchContent 轨）。
  # ---------------------------------------------------------------------------
  if(CMAKE_VERSION VERSION_GREATER_EQUAL "3.25")
    # CMake ≥ 3.25 原生 `SYSTEM` 目标属性：其 `INTERFACE_INCLUDE_DIRECTORIES`
    # 对消费方一律按系统目录对待（不影响 libprotobuf 自身的编译）。
    set_target_properties(libprotobuf PROPERTIES SYSTEM TRUE)
  else()
    # 更早版本无 `SYSTEM` 目标属性 ⇒ 在本工程供给目标上显式补充
    # `INTERFACE_SYSTEM_INCLUDE_DIRECTORIES` 标记（同目录亦在非 SYSTEM 列表中时，
    # 该标记使其被当作系统目录 —— 见 CMake 属性文档）。
    get_target_property(_hg_pb_inc_dirs libprotobuf INTERFACE_INCLUDE_DIRECTORIES)
    if(_hg_pb_inc_dirs)
      target_include_directories(hg_deps_protobuf SYSTEM INTERFACE ${_hg_pb_inc_dirs})
    endif()
  endif()
else()
  find_package(Protobuf REQUIRED)
  message(STATUS "[protobuf] CTP_USE_FETCH_DEPS=OFF → 系统包 protobuf ${Protobuf_VERSION}")
  add_library(hg_deps_protobuf INTERFACE)
  target_link_libraries(hg_deps_protobuf INTERFACE protobuf::libprotobuf)
endif()
add_library(hg_deps::protobuf ALIAS hg_deps_protobuf)

# -----------------------------------------------------------------------------
# 3. 大版本校验（§4.2.5：C++ 与 Java 两侧 protobuf 必须同一大版本 3.x）
# -----------------------------------------------------------------------------
if(NOT Protobuf_VERSION)
  message(FATAL_ERROR "[protobuf] 无法确定 protobuf 版本 → 须提 CH-xxx（L3），不得静默改技术栈")
endif()
string(REGEX MATCH "^([0-9]+)\\." _hg_pb_major "${Protobuf_VERSION}")
if(NOT _hg_pb_major)
  message(FATAL_ERROR "[protobuf] 版本串 '${Protobuf_VERSION}' 不可解析")
endif()
if(NOT CMAKE_MATCH_1 STREQUAL "${HG_PROTOBUF_MAJOR_REQUIRED}")
  message(FATAL_ERROR
    "[protobuf] 大版本不符：实测 ${Protobuf_VERSION}，要求 ${HG_PROTOBUF_MAJOR_REQUIRED}.x\n"
    "          v22+ 要求 C++17，与本项目 C++11 硬约束冲突（ARCHITECTURE.md §7.1.5 选项 B）。\n"
    "          处理：提 CH-xxx（L3）；不得静默改技术栈。")
endif()
message(STATUS "[protobuf] 大版本校验通过：${Protobuf_VERSION}（major=${CMAKE_MATCH_1}）")
