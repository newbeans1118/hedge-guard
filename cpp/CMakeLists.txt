# =============================================================================
# HedgeGuard — C++ 交易组件（顶层构建入口）
#
# 依据：PROJECT.md §5.1（CMake ≥ 3.16 + FetchContent；ctest）；
#       docs/ARCHITECTURE.md §1.2（CMAKE_CXX_STANDARD 11 + -Wall -Wextra -Werror）、
#       §3.1–§3.6（C++ 模块划分 ctp_adapter / order_engine / risk_engine /
#                   risk_state / flow_store / hgcp）、附录 B（红线清单）。
#
# 当前状态：**M2 首周探路阶段**。
#   本文件目前只聚合两个探路工程；M2 业务模块目录
#   （`cpp/src/<模块>/**`、`cpp/tests/**`）在 `T-030` 汇签下达铺开许可后新增。
#
# 探路工程：
#   proto_smoke —— T-026 / RSK-01：protobuf 在 C++11 下的空壳构通验证
#   ctp_smoke   —— T-027 / RSK-02：CTP 头文件 C++11 兼容验证
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
# 探路工程（M2 首周）
# -----------------------------------------------------------------------------
add_subdirectory(proto_smoke)
add_subdirectory(ctp_smoke)
