# =============================================================================
# 指令流水与重放（flow_store）（M5）—— ARCHITECTURE.md §3.5
#
# 骨架期说明：本文件仅建立库目标与 C++11 编译纪律；接口契约见 ARCHITECTURE.md §3.5。
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_flow_store STATIC src/module.cc)
add_library(hg::flow_store ALIAS hg_flow_store)
target_include_directories(hg_flow_store PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_flow_store PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
