# =============================================================================
# 事前风控引擎（risk_engine）（M3）—— ARCHITECTURE.md §3.3
#
# 骨架期说明：本文件仅建立库目标与 C++11 编译纪律；接口契约见 ARCHITECTURE.md §3.3。
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_risk_engine STATIC src/module.cc)
add_library(hg::risk_engine ALIAS hg_risk_engine)
target_include_directories(hg_risk_engine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_risk_engine PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)
