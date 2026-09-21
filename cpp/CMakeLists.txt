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
# 探路工程（**冻结，不得改动** —— T-026 / T-027 已验证证据）：
#   proto_smoke —— T-026 / RSK-01：protobuf 在 C++11 下的空壳构通验证
#   ctp_smoke   —— T-027 / RSK-02：CTP 头文件 C++11 兼容验证
#
# 双轨构建（PROJECT.md §5.1 / §9 NFR-DEPLOY-3）：
#   -DCTP_SDK_ROOT=<dir>         → CTP_FOUND=ON（ctp_adapter 以真实 CTP SDK 编译）
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
# 探路工程（M2 首周）—— **冻结产物，不得改动**（T-026 / T-027 已验证证据）
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
#   tools/arch-check/arch-check.sh 聚合 E1–E6 六判据脚本，任一非 0 ⇒ 整体非 0。
#   注册为 ctest fixture 的 SETUP：业务测试经 FIXTURES_REQUIRED 依赖它，
#   即「ctest 前置步骤」；`mvn verify` 侧由 java/pom.xml 的 exec 前置同样调用。
# -----------------------------------------------------------------------------
get_filename_component(HG_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
add_test(NAME arch_check
         COMMAND bash "${HG_REPO_ROOT}/tools/arch-check/arch-check.sh")
set_tests_properties(arch_check PROPERTIES
  FIXTURES_SETUP hg_arch_check
  TIMEOUT 900)
