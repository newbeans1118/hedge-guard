# =============================================================================
# HGCP 通信（hgcp）（M6）—— ARCHITECTURE.md §3.6
#
# 骨架期说明：本文件仅建立库目标与 C++11 编译纪律；接口契约见 ARCHITECTURE.md §3.6。
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_hgcp STATIC src/module.cc)
add_library(hg::hgcp ALIAS hg_hgcp)
target_include_directories(hg_hgcp PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_hgcp PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# HGCP 帧编解码依赖 protobuf（ARCHITECTURE.md §3.6 / §7.2.1）；
# 供给由双轨决定：CTP_USE_FETCH_DEPS=ON → FetchContent；OFF → 系统包。
target_link_libraries(hg_hgcp PUBLIC hg_deps::protobuf)
