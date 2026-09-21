# =============================================================================
# 指令生命周期管理（order_engine）（M2）—— ARCHITECTURE.md §3.2
#
# 骨架期说明：本文件仅建立库目标与 C++11 编译纪律；接口契约见 ARCHITECTURE.md §3.2。
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_order_engine STATIC src/module.cc)
add_library(hg::order_engine ALIAS hg_order_engine)
target_include_directories(hg_order_engine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_order_engine PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
