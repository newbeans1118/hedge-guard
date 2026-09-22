# =============================================================================
# HgCtpLocate.cmake —— CTP SDK（官方分离式 / 老式单目录）定位宏（**单一实现**）
#
# 依据：PROJECT.md §5.1（CTP SDK 外部提供）；docs/ARCHITECTURE.md §3.1 / §7.2.1
#       （ctp_adapter 依赖 CTP SDK）；§9 RSK-02。
#
# 为何抽成共享宏：`cpp/cmake/HgDependencies.cmake`（主工程）与
#   `cpp/ctp_smoke/CMakeLists.txt`（T-027 探路件）**必须**用同一套「SDK 是否可得」的
#   判定逻辑 —— 否则两处会静默漂移（本项目已实测过：官方 SDK 在场却被判为不可得，
#   两条轨各写一份判定 = 各错一次）。故本文件是**唯一**判定实现。
#
# 支持的布局（三种，向后兼容）：
#   ① 显式覆盖：`CTP_TRADER_ROOT` / `CTP_MD_ROOT` 各自指向含对应头的目录；
#   ② 官方父目录：`CTP_SDK_ROOT` 指向官方解包父目录，其下含
#      `*traderapi_se_linux64/`（交易）与 `*mduserapi_se_linux64/`（行情）两个子目录；
#   ③ 老式单目录：`CTP_SDK_ROOT` 直接含**两套头与两个库**。
#
# ⚠️ 官方 `.so` **无 `lib` 前缀**（`thosttraderapi_se.so` / `thostmduserapi_se.so`）
#    ⇒ `-lthosttraderapi_se` 失败；本宏返回**库文件绝对路径**供调用方直接链接。
#
# 用法（宏，展开于调用方作用域）：
#   set(HG_CL_TRADER_HEADER  "ThostFtdcTraderApi.h")
#   set(HG_CL_MD_HEADER      "ThostFtdcMdApi.h")
#   set(HG_CL_TRADER_LIB_NAMES "thosttraderapi_se.so;libthosttraderapi.so;libthostuserapi.so")
#   set(HG_CL_MD_LIB_NAMES     "thostmduserapi_se.so;libthostmduserapi.so")
#   set(HG_CL_SDK_ROOT    "${CTP_SDK_ROOT}")
#   set(HG_CL_TRADER_ROOT "${CTP_TRADER_ROOT}")
#   set(HG_CL_MD_ROOT     "${CTP_MD_ROOT}")
#   set(HG_CL_EXPLICIT    0|1)          # 用户是否**显式**给了根（决定缺件是否 FATAL）
#   include(HgCtpLocate.cmake)          # 或先 include 再多次调用
#   hg_locate_ctp(HG_CTP)               # 输出前缀
#
# 输出（`<prefix>_*`）：
#   _FOUND              ON/OFF —— 头与库**齐备**
#   _TRADER_INCLUDE_DIR / _MD_INCLUDE_DIR     头文件目录（可能相同）
#   _TRADER_LIB / _MD_LIB                     库文件绝对路径（空 = 未找到）
#   _LIB_DIRS                                 库所在目录（供 rpath）
#   _MISSING                                  缺件描述（人类可读，用于报错）
#   _TRADER_DIR / _MD_DIR                     解析出的头目录（诊断用）
#   `<prefix>_traderapi` / `<prefix>_mdapi`    **IMPORTED 共享库目标**（FOUND 时创建；
#                                             链接请用目标名，勿直接传 `.so` 路径 —— 见下）
#
# ⚠️ 为何用 IMPORTED 目标而非直接传 `.so` 绝对路径：现代 CMake 会把
#   `target_link_libraries(<t> <abs>/x.so)` 归一化为 `-L<dir> -lx`；官方 `.so` 名为
#   `thosttraderapi_se.so`（无 `lib` 前缀）⇒ 会变成 `-lthosttraderapi_se` 而**找不到**。
#   IMPORTED 目标逐字使用 `IMPORTED_LOCATION`，且 CMake 自动为消费方追加 rpath。
#
# 退出语义：**本宏不自行 FATAL**（判定与处置分离）—— 由调用方按
#   `EXPLICIT && !FOUND ⇒ FATAL_ERROR`（防静默回落 stub）/ 非显式 ⇒ 走 stub。
# =============================================================================

include_guard(GLOBAL)

macro(hg_locate_ctp OUT_PREFIX)
  # ---- 头目录解析 -----------------------------------------------------------
  set(_hgcl_trader_dir "")
  if(HG_CL_TRADER_ROOT)
    set(_hgcl_trader_dir "${HG_CL_TRADER_ROOT}")
  elseif(HG_CL_SDK_ROOT)
    if(EXISTS "${HG_CL_SDK_ROOT}/${HG_CL_TRADER_HEADER}")
      set(_hgcl_trader_dir "${HG_CL_SDK_ROOT}")
    else()
      file(GLOB _hgcl_tcand "${HG_CL_SDK_ROOT}/*traderapi*")
      foreach(_c ${_hgcl_tcand})
        if(IS_DIRECTORY "${_c}" AND EXISTS "${_c}/${HG_CL_TRADER_HEADER}")
          set(_hgcl_trader_dir "${_c}")
          break()
        endif()
      endforeach()
    endif()
  endif()

  set(_hgcl_md_dir "")
  if(HG_CL_MD_ROOT)
    set(_hgcl_md_dir "${HG_CL_MD_ROOT}")
  elseif(HG_CL_SDK_ROOT)
    if(EXISTS "${HG_CL_SDK_ROOT}/${HG_CL_MD_HEADER}")
      set(_hgcl_md_dir "${HG_CL_SDK_ROOT}")
    else()
      file(GLOB _hgcl_mcand "${HG_CL_SDK_ROOT}/*mduserapi*")
      foreach(_c ${_hgcl_mcand})
        if(IS_DIRECTORY "${_c}" AND EXISTS "${_c}/${HG_CL_MD_HEADER}")
          set(_hgcl_md_dir "${_c}")
          break()
        endif()
      endforeach()
    endif()
  endif()

  # ---- 库文件解析（候选名逐个 EXISTS；不用 find_library 以免缓存污染负例）----
  set(_hgcl_lib_dirs "")
  foreach(_d "${_hgcl_trader_dir}" "${_hgcl_md_dir}" "${HG_CL_SDK_ROOT}")
    if(_d AND IS_DIRECTORY "${_d}")
      list(APPEND _hgcl_lib_dirs "${_d}")
    endif()
  endforeach()
  if(_hgcl_lib_dirs)
    list(REMOVE_DUPLICATES _hgcl_lib_dirs)
  endif()

  set(_hgcl_trader_lib "")
  foreach(_n ${HG_CL_TRADER_LIB_NAMES})
    foreach(_d ${_hgcl_lib_dirs})
      if(NOT _hgcl_trader_lib AND EXISTS "${_d}/${_n}")
        set(_hgcl_trader_lib "${_d}/${_n}")
      endif()
    endforeach()
  endforeach()

  set(_hgcl_md_lib "")
  foreach(_n ${HG_CL_MD_LIB_NAMES})
    foreach(_d ${_hgcl_lib_dirs})
      if(NOT _hgcl_md_lib AND EXISTS "${_d}/${_n}")
        set(_hgcl_md_lib "${_d}/${_n}")
      endif()
    endforeach()
  endforeach()

  # ---- 汇总 ---------------------------------------------------------------
  set(${OUT_PREFIX}_TRADER_DIR "${_hgcl_trader_dir}")
  set(${OUT_PREFIX}_MD_DIR "${_hgcl_md_dir}")
  set(${OUT_PREFIX}_TRADER_INCLUDE_DIR "${_hgcl_trader_dir}")
  set(${OUT_PREFIX}_MD_INCLUDE_DIR "${_hgcl_md_dir}")
  set(${OUT_PREFIX}_TRADER_LIB "${_hgcl_trader_lib}")
  set(${OUT_PREFIX}_MD_LIB "${_hgcl_md_lib}")
  set(${OUT_PREFIX}_LIB_DIRS "")
  set(${OUT_PREFIX}_MISSING "")

  if(NOT _hgcl_trader_dir)
    list(APPEND ${OUT_PREFIX}_MISSING "交易头目录（${HG_CL_TRADER_HEADER}）")
  elseif(NOT EXISTS "${_hgcl_trader_dir}/${HG_CL_TRADER_HEADER}")
    list(APPEND ${OUT_PREFIX}_MISSING "交易头（${_hgcl_trader_dir}/${HG_CL_TRADER_HEADER}）")
  endif()
  if(NOT _hgcl_md_dir)
    list(APPEND ${OUT_PREFIX}_MISSING "行情头目录（${HG_CL_MD_HEADER}）")
  elseif(NOT EXISTS "${_hgcl_md_dir}/${HG_CL_MD_HEADER}")
    list(APPEND ${OUT_PREFIX}_MISSING "行情头（${_hgcl_md_dir}/${HG_CL_MD_HEADER}）")
  endif()
  if(NOT _hgcl_trader_lib)
    list(APPEND ${OUT_PREFIX}_MISSING "交易库（候选名：${HG_CL_TRADER_LIB_NAMES}）")
  endif()
  if(NOT _hgcl_md_lib)
    list(APPEND ${OUT_PREFIX}_MISSING "行情库（候选名：${HG_CL_MD_LIB_NAMES}）")
  endif()

  if(_hgcl_trader_dir AND _hgcl_md_dir AND _hgcl_trader_lib AND _hgcl_md_lib)
    set(${OUT_PREFIX}_FOUND ON)
    set(${OUT_PREFIX}_LIB_DIRS "${_hgcl_trader_dir};${_hgcl_md_dir}")
    if(${OUT_PREFIX}_LIB_DIRS)
      list(REMOVE_DUPLICATES ${OUT_PREFIX}_LIB_DIRS)
    endif()

    # ⚠️ 必须用 **IMPORTED 目标** 承载绝对路径：现代 CMake 会把
    #    `target_link_libraries(<t> <abs>/x.so)` 归一化为 `-L<dir> -lx`
    #    （实测：官方 `thosttraderapi_se.so` 被转成 `-lthosttraderapi_se`，
    #    链接器报 "cannot find -lthosttraderapi_se" —— 见 `T-033` 回执）。
    #    IMPORTED 目标则**逐字使用** `IMPORTED_LOCATION`（并自动为消费方加 rpath）。
    if(NOT TARGET ${OUT_PREFIX}_traderapi)
      add_library(${OUT_PREFIX}_traderapi SHARED IMPORTED GLOBAL)
      set_target_properties(${OUT_PREFIX}_traderapi PROPERTIES
        IMPORTED_LOCATION "${_hgcl_trader_lib}")
    endif()
    if(NOT TARGET ${OUT_PREFIX}_mdapi)
      add_library(${OUT_PREFIX}_mdapi SHARED IMPORTED GLOBAL)
      set_target_properties(${OUT_PREFIX}_mdapi PROPERTIES
        IMPORTED_LOCATION "${_hgcl_md_lib}")
    endif()
  else()
    set(${OUT_PREFIX}_FOUND OFF)
  endif()
endmacro()
