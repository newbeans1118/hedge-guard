# =============================================================================
# CTP 适配层（ctp_adapter）（M1）—— ARCHITECTURE.md §3.1
#
# 骨架期说明：本文件仅建立库目标与 C++11 编译纪律；接口契约见 ARCHITECTURE.md §3.1。
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_ctp_adapter STATIC src/module.cc)
add_library(hg::ctp_adapter ALIAS hg_ctp_adapter)
target_include_directories(hg_ctp_adapter PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_ctp_adapter PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# CTP SDK 双轨（CTP_FOUND）：ON 时以真实 SDK 头编译（SYSTEM 头抑制第三方告警），
# OFF 时本模块走空实现，组件仍可完整构建（无 SDK 环境 / CI）。
target_compile_definitions(hg_ctp_adapter PRIVATE HG_CTP_FOUND=$<BOOL:${CTP_FOUND}>)
if(CTP_FOUND)
  target_include_directories(hg_ctp_adapter SYSTEM PRIVATE "${CTP_SDK_ROOT}")
endif()
