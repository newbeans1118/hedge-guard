# =============================================================================
# HedgeGuard — C++ 交易组件（顶层构建入口）
#
# 依据：PROJECT.md §5.1（CMake ≥ 3.16 + FetchContent；ctest）；
#       docs/ARCHITECTURE.md §1.2（CMAKE_CXX_STANDARD 11 + -Wall -Wextra -Werror）、
#       §3.1–§3.6（C++ 模块划分 ctp_adapter / order_engine / risk_engine /
#                   risk_state / flow_store / hgcp）、附录 B（红线清单）。
#
# 当前状态：**M2 业务开发已铺开**（T-030 汇签下达许可，T-031 建立骨架）。
#   本文件聚合：① 两个探路工程（冻结产物，仅存证）② C++ 交易组件六模块骨架
#   （`cpp/src/<模块>/**`）③ 架构合规聚合门禁（`tools/arch-check/arch-check.sh`）。
#
# 探路工程（**冻结产物** —— 仅存证；改动须走对应任务重做重冻结）：
#   proto_smoke —— T-026 / RSK-01：protobuf 在 C++11 下的空壳构通验证（冻结）
#   ctp_smoke   —— T-027 / RSK-02：CTP 头文件 C++11 兼容验证
#                  （2026-09-22 因 CTP 基线变更 → 官方 v6.7.13 **重做并重冻结**，`CH-015`）
#
# CTP SDK 定位（三种布局皆可；显式给了根却解析失败 ⇒ 配置期 FATAL_ERROR）：
#   -DCTP_SDK_ROOT=<dir>         → 官方解包父目录 或 老式单目录 → CTP_FOUND=ON
#   -DCTP_TRADER_ROOT=<dir> / -DCTP_MD_ROOT=<dir>  → 分别显式覆盖交易/行情目录
#   -DCTP_USE_FETCH_DEPS=ON      → protobuf 经 FetchContent 源码构建
# =============================================================================

cmake_minimum_required(VERSION 3.16)

project(HedgeGuardCpp LANGUAGES CXX)

# -----------------------------------------------------------------------------
# 全局编译纪律（ARCHITECTURE.md §1.2 / CI 脚本 E3 口径）
#   C++11 硬边界：禁用 std::make_unique（C++14）、std::optional / std::string_view /
#   结构化绑定 / if constexpr（C++17+）；替代 std::pair<T,bool> / 裸指针 / 传统 SFINAE。
# -----------------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
add_compile_options(-Wall -Wextra -Werror)

if(NOT MSVC)
  # 统一使用 libstdc++ 并发模型；不引入额外依赖
  find_package(Threads REQUIRED)
endif()

# 供 CI 断言"实际生效"的编译参数
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE "RelWithDebInfo" CACHE STRING "构建类型" FORCE)
endif()

enable_testing()

# -----------------------------------------------------------------------------
# 探路工程（M2 首周）—— **冻结产物**（T-026 冻结；T-027 于 2026-09-22 因 CTP 基线变更重做重冻结）
# -----------------------------------------------------------------------------
add_subdirectory(proto_smoke)
add_subdirectory(ctp_smoke)

# -----------------------------------------------------------------------------
# 依赖供给（双轨：CTP_FOUND / CTP_USE_FETCH_DEPS）——见 cmake/HgDependencies.cmake
#   置于探路工程之后：探路工程自带 protobuf 供给（系统包优先），先于本工程可能
#   发起的 FetchContent 声明完成解析，避免两处 protobuf 供给互相干扰。
# -----------------------------------------------------------------------------
include(cmake/HgDependencies.cmake)

# -----------------------------------------------------------------------------
# C++ 交易组件六模块（M2 业务开发）
# -----------------------------------------------------------------------------
add_subdirectory(src)

# -----------------------------------------------------------------------------
# 架构合规聚合门禁（ARCHITECTURE.md §3.13）
#   tools/arch-check/arch-check.sh 聚合 E1–E8 八判据脚本，任一非 0 ⇒ 整体非 0。
#   注册为 ctest fixture 的 SETUP：业务测试经 FIXTURES_REQUIRED 依赖它，
#   即「ctest 前置步骤」；`mvn verify` 侧由 java/pom.xml 的 exec 前置同样调用。
# -----------------------------------------------------------------------------
get_filename_component(HG_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
add_test(NAME arch_check
         COMMAND bash "${HG_REPO_ROOT}/tools/arch-check/arch-check.sh")
set_tests_properties(arch_check PROPERTIES
  FIXTURES_SETUP hg_arch_check
  TIMEOUT 900)

# -----------------------------------------------------------------------------
# CTP_FOUND 一致性判据接线（T-130）—— 使 `cpp/ctp_smoke/ci/check_ctp_found_parity.sh`
# 在 `ctest` 中**实扫**（此前**全仓零调用点 ⇒ 判据永不生效**，OBS-T128-1）。
#   判据：一次 top-level `cmake -S cpp` 配置，抓 `role=smoke` 与 `role=main` 两行
#         `[HG-CTP-FOUND] found=… style=… trader_header=… md_header=…` ⇒ 断言**全字段逐字一致**。
#   参数以**本构建目录实际生效**的 `CTP_SDK_ROOT` / `HG_CTP_HEADER_STYLE` 为准：
#     · 有 SDK 根（且配置期 `CTP_FOUND=ON`）⇒ `--expect on`（两端须真的 ON，非空判）；
#     · 无 SDK 根 ⇒ `--expect auto`（两端须一致 OFF）。
#   临时目录落**本 CMake 构建树内**（避免本机 `/tmp` 800MB tmpfs 常满）。
#   退出码：0=一致；1=分歧/一侧未披露；2=用法；3=判据未执行 ⇒ **非 0 即 ctest 失败**。
# -----------------------------------------------------------------------------
if(UNIX)
  set(_hg_parity_expect "auto")
  if(CTP_SDK_ROOT AND CTP_FOUND)
    set(_hg_parity_expect "on")
  endif()
  add_test(NAME ctp_found_parity
    COMMAND "${CMAKE_COMMAND}" -E env
            "TMPDIR=${CMAKE_CURRENT_BINARY_DIR}"
            "PARITY_TMPDIR=${CMAKE_CURRENT_BINARY_DIR}"
            bash "${CMAKE_CURRENT_SOURCE_DIR}/ctp_smoke/ci/check_ctp_found_parity.sh"
            --sdk-root "${CTP_SDK_ROOT}"
            --style "${HG_CTP_HEADER_STYLE}"
            --expect "${_hg_parity_expect}"
            --build-dir "${CMAKE_CURRENT_BINARY_DIR}/ctp_found_parity_build")
  set_tests_properties(ctp_found_parity PROPERTIES TIMEOUT 300)
endif()
