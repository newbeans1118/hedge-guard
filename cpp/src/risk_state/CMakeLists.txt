# =============================================================================
# 风控内存状态（risk_state）（M4）—— ARCHITECTURE.md §3.4
#
# 骨架期说明：本文件仅建立库目标与 C++11 编译纪律；接口契约见 ARCHITECTURE.md §3.4。
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_risk_state STATIC src/module.cc)
add_library(hg::risk_state ALIAS hg_risk_state)
target_include_directories(hg_risk_state PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_risk_state PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
