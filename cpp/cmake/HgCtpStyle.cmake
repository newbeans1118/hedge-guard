# =============================================================================
# HgCtpStyle.cmake —— CTP 头文件风格 → 具体头名/类名的**唯一**派生实现
#
# 依据：PROJECT.md §5.1（CTP SDK 外部提供）；docs/ARCHITECTURE.md §3.1 / §7.2.1
#       （ctp_adapter 依赖 CTP SDK）、§9 RSK-02；NFR-MAINT-3（单一事实来源）。
#
# 为何独立成文件：`cpp/cmake/HgDependencies.cmake`（**主工程**）与
#   `cpp/ctp_smoke/CMakeLists.txt`（**T-027 探路件**）**都必须**由**同一个**变量
#   决定「交易/行情头文件名」。若各写一份（T-128 前态：主工程用 `HG_CTP_TRADER_HEADER`、
#   探路件用 `HG_CTP_HEADER_STYLE` 自行派生），则**同一组 SDK 根/风格配置**下两端会
#   得出**互相矛盾**的 `CTP_FOUND` —— 而探路件的职责恰恰是校验产品侧的 CTP 集成 ⇒
#   会给出与产品不一致的结论（**诊断误导**）。实测前态：官方 SDK + 旧头名 knob 下，
#   探路件打印「发现 CTP SDK」并注册 `rsk02`，主工程却判「不可得」并 FATAL。
#
# **单一入口（唯一可 -D 覆盖的变量）**：
#   HG_CTP_HEADER_STYLE —— CLASSIC（默认，交付基线）| MERGED（历史对照）
#
# **派生值**（由 `hg_ctp_resolve_style()` 在**调用方作用域**设置；**非缓存**、
#   不作为独立入口，仅供下游消费）：
#   HG_CTP_TRADER_HEADER      交易头文件名
#   HG_CTP_MD_HEADER          行情头文件名
#   HG_CTP_TRADER_API_CLASS   交易 API 类名
#   HG_CTP_TRADER_SPI_CLASS   交易 SPI 类名
#   HG_CTP_MD_API_CLASS       行情 API 类名
#   HG_CTP_MD_SPI_CLASS       行情 SPI 类名
#   HG_CTP_PRIVATE_TOPIC_ARITY `SubscribePrivateTopic` 参数元数
#   HG_CTP_STYLE_MERGED        1 = 合并版；0 = 官方分离式
#
# 风格口径：
#   CLASSIC（官方**分离式**，交付基线）= `ThostFtdcTraderApi.h` + `ThostFtdcMdApi.h`
#           （`CThostFtdcTraderApi` / `CThostFtdcTraderSpi`）。
#   MERGED（历史对照）= `CTP Mini` **合并版** `ThostFtdcUserApi.h`（`CThostFtdcUserApi` /
#           `CThostFtdcUserSpi`）；官方 SDK 上不可得。
#
# ⚠️ **产品侧仅支持 CLASSIC**：`cpp/src/ctp_adapter/**`（`module.cc` / `ct_gateway_ctp.cc` /
#    `ct_spi_impl.h` / `ct_mapping.h` / 两测试）**直接 `#include <ThostFtdcTraderApi.h>`**
#    （交付基线口径，CH-015）⇒ `MERGED` **仅供探路件做历史对照**，主工程在合并版 SDK 上
#    会在**编译期**如实失败（非静默）。本开关只统一「头名变量源」，不改变该事实。
# =============================================================================

include_guard(GLOBAL)

# 单一入口：头文件风格。**不要**再新增「按头名分别覆盖」的入口 —— 见 T-128。
set(HG_CTP_HEADER_STYLE "CLASSIC" CACHE STRING
  "CTP 头文件风格（**唯一入口**，头名由其派生）：CLASSIC（官方分离式 TraderApi，交付基线）| MERGED（CTP Mini 合并版 UserApi，历史）")

# -----------------------------------------------------------------------------
# 宏：按 HG_CTP_HEADER_STYLE 在**调用方作用域**设置全部派生变量。
# 用宏（非缓存 set）⇒ 主工程与探路件各自在自己的作用域取到**同一套**派生值。
# -----------------------------------------------------------------------------
macro(hg_ctp_resolve_style)
  if(HG_CTP_HEADER_STYLE STREQUAL "CLASSIC")
    set(HG_CTP_TRADER_HEADER     "ThostFtdcTraderApi.h")
    set(HG_CTP_TRADER_API_CLASS  "CThostFtdcTraderApi")
    set(HG_CTP_TRADER_SPI_CLASS  "CThostFtdcTraderSpi")
    # 官方分离式 `SubscribePrivateTopic(THOST_TE_RESUME_TYPE, int nSeqNo = 1)` ⇒ 2 参。
    set(HG_CTP_PRIVATE_TOPIC_ARITY 2)
    set(HG_CTP_STYLE_MERGED      0)
  elseif(HG_CTP_HEADER_STYLE STREQUAL "MERGED")
    set(HG_CTP_TRADER_HEADER     "ThostFtdcUserApi.h")
    set(HG_CTP_TRADER_API_CLASS  "CThostFtdcUserApi")
    set(HG_CTP_TRADER_SPI_CLASS  "CThostFtdcUserSpi")
    set(HG_CTP_PRIVATE_TOPIC_ARITY 1)
    set(HG_CTP_STYLE_MERGED      1)
  else()
    message(FATAL_ERROR
      "[CTP] HG_CTP_HEADER_STYLE 只能是 CLASSIC 或 MERGED，实为 '${HG_CTP_HEADER_STYLE}'"
      "（唯一入口见 cpp/cmake/HgCtpStyle.cmake）")
  endif()
  # 行情侧两种风格共用同一头/类名。
  set(HG_CTP_MD_HEADER    "ThostFtdcMdApi.h")
  set(HG_CTP_MD_API_CLASS "CThostFtdcMdApi")
  set(HG_CTP_MD_SPI_CLASS "CThostFtdcMdSpi")

  # ---------------------------------------------------------------------------
  # 旧入口防退化（T-128 收口）：
  #   `HG_CTP_TRADER_HEADER` / `HG_CTP_MD_HEADER` 在 T-128 前是**可 -D 覆盖**的缓存变量
  #   （主工程专用），与探路件的 `HG_CTP_HEADER_STYLE` 构成**双源**。现统一为派生值。
  #   若缓存里仍留有与之**冲突**的旧值 ⇒ 显式失败，避免「设了却没生效」的静默不一致
  #   （本项目「假绿」纪律：宁可配置期报错，不可静默走另一套头名）。
  #   与风格**一致**的陈旧缓存项无害，放行（避免无谓打断既有构建目录）。
  # ---------------------------------------------------------------------------
  if(DEFINED CACHE{HG_CTP_TRADER_HEADER}
     AND NOT "${HG_CTP_TRADER_HEADER}" STREQUAL "$CACHE{HG_CTP_TRADER_HEADER}")
    message(FATAL_ERROR
      "[CTP] 检测到过时的旧入口缓存项 HG_CTP_TRADER_HEADER='$CACHE{HG_CTP_TRADER_HEADER}'，"
      "与当前风格（${HG_CTP_HEADER_STYLE}）派生的 '${HG_CTP_TRADER_HEADER}' 冲突。\n"
      "      头名已统一为由 HG_CTP_HEADER_STYLE 派生（唯一入口，T-128）\n"
      "      ⇒ 请改用 -DHG_CTP_HEADER_STYLE=CLASSIC|MERGED，并清除旧缓存项\n"
      "        （如 -U HG_CTP_TRADER_HEADER，或删除构建目录后重新配置）。")
  endif()
  if(DEFINED CACHE{HG_CTP_MD_HEADER}
     AND NOT "${HG_CTP_MD_HEADER}" STREQUAL "$CACHE{HG_CTP_MD_HEADER}")
    message(FATAL_ERROR
      "[CTP] 检测到过时的旧入口缓存项 HG_CTP_MD_HEADER='$CACHE{HG_CTP_MD_HEADER}'，"
      "与当前风格（${HG_CTP_HEADER_STYLE}）派生的 '${HG_CTP_MD_HEADER}' 冲突。\n"
      "      头名已统一为由 HG_CTP_HEADER_STYLE 派生（唯一入口，T-128）\n"
      "      ⇒ 请改用 -DHG_CTP_HEADER_STYLE=CLASSIC|MERGED，并清除旧缓存项\n"
      "        （如 -U HG_CTP_MD_HEADER，或删除构建目录后重新配置）。")
  endif()
endmacro()
