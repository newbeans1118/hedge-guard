# =============================================================================
# CTP 适配层（ctp_adapter）（M1）—— ARCHITECTURE.md §3.1
#
# 本任务（T-033）落地：
#   · 封装 CTP 合并版 `ThostFtdc UserApi`（交易 + 行情 + 资金划转），登录/登出/连接状态；
#   · `sendOrder` / `cancelOrder` / `subscribeMarket` / `reqFundTransfer`（异步受理语义）；
#   · `CtSpiImpl : CThostFtdcUserSpi` / `CtMdSpiImpl : CThostFtdcMdSpi`
#     —— 回调**只做**「序列化并投递队列（orderEventQueue / quoteQueue）」；
#   · 期货与期权（`PROJECT.md` §10-10 / `NFR-COMPAT-6`）；
#   · CTP 未连接 ⇒ `sendOrder` 回 `0x2004`，指令**退回保留、不静默丢弃**（FR-TRADE-2 验收 4）。
#
# 双轨（`CTP_FOUND`，见 cpp/cmake/HgDependencies.cmake）：
#   ON  → 真实 SDK 头（**SYSTEM**，抑制第三方告警撞 `-Werror`）+ 链接 libthost*.so；
#   OFF → stub 降级轨（连接恒失败，组件仍可完整构建）。
#
# 全局 -Wall -Wextra -Werror / CMAKE_CXX_STANDARD 11 由 cpp/CMakeLists.txt 施加。
# =============================================================================

add_library(hg_ctp_adapter STATIC
  src/module.cc
  src/ct_adapter.cc)                       # SDK 无关层（两轨共用）
add_library(hg::ctp_adapter ALIAS hg_ctp_adapter)
target_include_directories(hg_ctp_adapter PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
set_target_properties(hg_ctp_adapter PROPERTIES
  CXX_STANDARD 11
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS OFF)

# 队列使用 std::this_thread::yield（有界自旋）⇒ 需要线程库。
target_link_libraries(hg_ctp_adapter PUBLIC Threads::Threads)

# 双轨开关（PUBLIC：公共头 ct_mapping.h / ct_spi_impl.h 以 `#if HG_CTP_FOUND` 分域，
# 消费方（单测 / 其它模块）须看到同一取值）。
target_compile_definitions(hg_ctp_adapter PUBLIC HG_CTP_FOUND=$<BOOL:${CTP_FOUND}>)

if(CTP_FOUND)
  # CTP include 作 **SYSTEM** 头：其头文件在 -Wall -Wextra 下有大量第三方告警，
  # 若按普通头引入会被 -Werror 放大为构建失败（口径与 cpp/ctp_smoke 探路结论一致：
  # 上游 CTP 头 SYSTEM 引入，作用域窄于 -Wno-unused-parameter）。
  target_include_directories(hg_ctp_adapter SYSTEM PUBLIC "${CTP_SDK_ROOT}")

  target_sources(hg_ctp_adapter PRIVATE
    src/ct_gateway_ctp.cc
    src/ct_mapping.cc
    src/ct_spi_impl.cc)

  # 真实 SDK 动态库（R-FUND-11：资金划转复用同一 CTP 通道，不引入新依赖）。
  target_link_libraries(hg_ctp_adapter PUBLIC
    "${CTP_SDK_ROOT}/libthostuserapi.so"
    "${CTP_SDK_ROOT}/libthostmduserapi.so")
else()
  target_sources(hg_ctp_adapter PRIVATE src/ct_gateway_stub.cc)
endif()

# -----------------------------------------------------------------------------
# 单测（ctest）：队列语义 / 适配逻辑（未连接 0x2004 与退回保留 / 回调仅入队 /
#   期货与期权）/（CTP 轨）字段映射 + 真实 SPI 回调入队。
#   以架构合规门禁（arch_check，见 cpp/CMakeLists.txt）为前置 fixture。
# -----------------------------------------------------------------------------
set(CTP_ADAPTER_UNIT_TESTS
  ct_queues_test
  ct_adapter_test)

if(CTP_FOUND)
  # 字段映射 + 真实 SPI 依赖真实 CTP 头/库 ⇒ 仅 SDK 轨注册。
  list(APPEND CTP_ADAPTER_UNIT_TESTS
    ct_mapping_test
    ct_spi_test)
endif()

foreach(_t ${CTP_ADAPTER_UNIT_TESTS})
  add_executable(ctp_adapter_${_t} "tests/${_t}.cc")
  target_link_libraries(ctp_adapter_${_t} PRIVATE hg::ctp_adapter)
  target_include_directories(ctp_adapter_${_t} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/tests")
  set_target_properties(ctp_adapter_${_t} PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF)
  add_test(NAME "ctp_adapter.${_t}" COMMAND "ctp_adapter_${_t}")
  set_tests_properties("ctp_adapter.${_t}" PROPERTIES
    FIXTURES_REQUIRED hg_arch_check
    TIMEOUT 180)
endforeach()
