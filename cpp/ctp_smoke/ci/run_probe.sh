#!/usr/bin/env bash
# =============================================================================
# T-027 / RSK-02 探路复现脚本 —— CTP 头文件在 C++11 边界下的兼容验证
#
# 用法：
#   CTP_SDK_ROOT=/path/to/ctp_sdk_parent bash cpp/ctp_smoke/ci/run_probe.sh
#     （CTP_SDK_ROOT 可指**官方解包父目录**，亦可指老式单目录；后者含两套头与两个库）
#   HG_SKIP_SDK=1 bash cpp/ctp_smoke/ci/run_probe.sh     # 仅做"SDK 不可得"取证
#
# 断言清单（与 T-027 验收标准一一对应）：
#   B1  CTP SDK 可得，且记录其路径 / 头文件 / 校验和
#   B2  -std=c++11 -Wall -Wextra -Werror 下 #include CTP 交易头文件编译通过
#   B3  __cplusplus == 201103（C++11 是编译期事实）
#   B4  链接真实 SDK 动态库并取得 SDK 自报版本（非桩、非等价头文件）
#   B5  反证①：CTP 头文件本身**不是** -Wextra -Werror 干净的（客观记录冲突）
#   B6  官方基线头名：<ThostFtdcTraderApi.h>（分离式）**可得** —— 交付基线口径
#   B6b 合并版 <ThostFtdcUserApi.h> 在官方 SDK 上**不可得**（基线口径差异，非 C++11 冲突）
#   B7  产物无任何 DB 客户端库（ARCHITECTURE.md 附录 B 红线 1）
#   B8  C++11 边界未被放宽（编译参数中不含 c++14/c++17/gnu++14/gnu++17）
#
# ⚠️ 2026-09-22 判据反转（`CH-015`）：上一版在 `CTP Mini`（合并式 `ThostFtdcUserApi.h`）
#    基线上把「<ThostFtdcTraderApi.h> 不可得」记为 B6 PASS；交付基线改为**官方 CTP
#    v6.7.13**（分离式）后该结论**失效**，B6/B6b 随之反转。
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_DIR="$(cd "${HERE}/.." && pwd)"
BUILD_DIR="${PROJ_DIR}/build"
NONSYS_BUILD_DIR="${PROJ_DIR}/build-nonsystem"
LOGDIR="${PROJ_DIR}/build-logs"
mkdir -p "${LOGDIR}"

CTP_SDK_ROOT="${CTP_SDK_ROOT:-${1:-}}"

PASS=0; FAIL=0; declare -a RESULTS=()
_ok()   { PASS=$((PASS+1)); RESULTS+=("  [PASS] $1"); printf '\033[32m[PASS]\033[0m %s\n' "$1"; }
_bad()  { FAIL=$((FAIL+1)); RESULTS+=("  [FAIL] $1"); printf '\033[31m[FAIL]\033[0m %s\n' "$1"; }
_info() { printf '\033[36m[INFO]\033[0m %s\n' "$1"; }
_rule() { printf '\n==================== %s ====================\n' "$1"; }

_rule "0. 工具链与 SDK 事实"
_info "$(g++ --version | head -1)"
_info "$(cmake --version | head -1)"

if [[ -z "${CTP_SDK_ROOT}" || ! -d "${CTP_SDK_ROOT}" ]]; then
  _rule "SDK 不可得：按 T-027 要求如实记录，不伪造 SDK 源码"
  if [[ "${HG_SKIP_SDK:-0}" == "1" ]]; then
    _ok "B1 已按 HG_SKIP_SDK=1 主动跳过 SDK 探路（结论：未执行，不可据此判定通过）"
    printf '\nPASS=%d FAIL=%d\nRESULT: PASS (SKIPPED)\n' "${PASS}" "${FAIL}"
    exit 0
  fi
  _bad "B1 CTP SDK 不可得：请以 CTP_SDK_ROOT=<dir> 指定 SDK 目录"
  printf '%s\n' "${RESULTS[@]}"
  printf '\nPASS=%d FAIL=%d\nRESULT: FAIL\n' "${PASS}" "${FAIL}"
  exit 1
fi

_info "CTP_SDK_ROOT = ${CTP_SDK_ROOT}"
ls -la "${CTP_SDK_ROOT}" | sed 's/^/    /'
_info "头文件校验和（递归，官方解包为「父目录 + 两子目录」）："
find "${CTP_SDK_ROOT}" -maxdepth 2 -type f -name 'ThostFtdc*.h' -exec md5sum {} \; 2>/dev/null | sed 's/^/    /'
_ok "B1 CTP SDK 可得（路径 ${CTP_SDK_ROOT}）"

# 头文件所在目录解析（供 B6 探针用 —— 官方分离式下交易头**不在**根目录）。
_hdr_dir() {  # $1 = 头文件名；打印其所在目录（找不到则空）
  local f
  f="$(find "${CTP_SDK_ROOT}" -maxdepth 2 -type f -name "$1" 2>/dev/null | head -1)"
  if [[ -n "${f}" ]]; then dirname "${f}"; fi
}

_rule "1. 主路径：-std=c++11 -Wall -Wextra -Werror 下编译 + 链接真实 SDK"
rm -rf "${BUILD_DIR}"
if cmake -S "${PROJ_DIR}" -B "${BUILD_DIR}" \
     -DCTP_SDK_ROOT="${CTP_SDK_ROOT}" \
     -DHG_CTP_HEADER_STYLE=CLASSIC \
     -DHG_CTP_INCLUDE_AS_SYSTEM=ON \
     -DCMAKE_BUILD_TYPE=RelWithDebInfo > "${LOGDIR}/configure.log" 2>&1 \
   && cmake --build "${BUILD_DIR}" -j"$(nproc)" > "${LOGDIR}/build.log" 2>&1; then
  _ok "B2 编译通过（-std=c++11 -Wall -Wextra -Werror，CTP 头作为 SYSTEM 引入）"
else
  _bad "B2 编译失败；日志尾部："
  tail -20 "${LOGDIR}/build.log"
fi

PROBE_OUT="$("${BUILD_DIR}/hgcp_ctp_probe" 2>&1)"
echo "${PROBE_OUT}"
grep -q 'cxx.standard.__cplusplus *: 201103$' <<<"${PROBE_OUT}" \
  && _ok "B3 __cplusplus == 201103（C++11 是编译期事实）" \
  || _bad "B3 __cplusplus 不是 201103"

if grep -q 'sdk.linked *: YES' <<<"${PROBE_OUT}" \
   && grep -Eq 'sdk\.api\.version\.trader *: [vV]' <<<"${PROBE_OUT}"; then
  _ok "B4 已链接真实 SDK 动态库并取到自报版本：$(grep 'sdk.api.version.trader' <<<"${PROBE_OUT}" | sed 's/.*: //')"
else
  _bad "B4 未链接真实 SDK 或未取到版本（须在结论中注明『未取得真实 SDK 版本』）"
fi

ctest --test-dir "${BUILD_DIR}" --output-on-failure > "${LOGDIR}/ctest.log" 2>&1 \
  && _ok "B2b ctest 全绿" || { _bad "B2b ctest 未全绿"; tail -10 "${LOGDIR}/ctest.log"; }

if ldd "${BUILD_DIR}/hgcp_ctp_probe" | grep -Eiq 'dmjdbc|dmdpi|mysql|sqlite|libpq|odbc|oci'; then
  _bad "B7 产物链接了 DB 客户端库（违反附录 B 红线 1）"
else
  _ok "B7 ldd 无任何 DB 客户端库（附录 B 红线 1）"
fi

# ---------------------------------------------------------------------------
# B7b：E6（前端零构建链）自检 —— 必须自证"扫描范围 + 排除项"
#   ⚠️ 实测陷阱（来自 #java）：CMake FetchContent 拉下的 protobuf 源码树自带
#      `js/package.json`（如 `cpp/proto_smoke/build-fallback/_deps/protobuf-src/js/package.json`），
#      朴素的 E6 扫描会**误报**为"引入构建链"。故扫描**必须**排除 `build*/` 与 `_deps/`。
#      本项目纪律：凡"0 命中"类结论，**必须**同时声明扫描范围与排除项，否则结论不可复核。
# ---------------------------------------------------------------------------
E6_SCOPE="${PROJ_DIR}"
E6_EXCLUDES=(-path '*/build*' -o -path '*/_deps/*' -o -path '*/third_party/*')
_info "B7b E6 扫描范围：${E6_SCOPE}（排除：*/build*、*/_deps/*、*/third_party/*）"
E6_HITS="$(find "${E6_SCOPE}" \( "${E6_EXCLUDES[@]}" \) -prune -o \
           -type f \( -name 'package.json' -o -name '*.bundle.js' -o -name '*.min.js' \) -print 2>/dev/null)"
E6_DIRS="$(find "${E6_SCOPE}" \( "${E6_EXCLUDES[@]}" \) -prune -o -type d \( -name node_modules -o -name dist \) -print 2>/dev/null)"
if [[ -z "${E6_HITS}" && -z "${E6_DIRS}" ]]; then
  _ok "B7b cpp/ 源码树无前端构建链产物（0 命中，范围与排除项已自证）"
else
  _bad "B7b cpp/ 源码树出现前端构建链产物：${E6_HITS} ${E6_DIRS}"
fi

# B7c：C++ 侧依赖清单纪律（CH-006）——不得出现 websocketpp / Boost 的 find_package 或 FetchContent
if grep -rEq 'websocketpp|find_package\(Boost|Boost::system' \
     "${PROJ_DIR}/CMakeLists.txt" "${PROJ_DIR}"/*/CMakeLists.txt 2>/dev/null; then
  _bad "B7c CMake 中出现 websocketpp / Boost（违反 CH-006：C++ 侧仅 CTP SDK + protobuf）"
else
  _ok "B7c CMake 无 websocketpp / Boost 的 find_package 或 FetchContent（CH-006）"
fi

if grep -Eq -- '-std=(gnu\+\+)?c\+\+(14|17|20|23)|-std=(gnu\+\+)(14|17|20|23)' "${BUILD_DIR}/compile_commands.json" 2>/dev/null; then
  _bad "B8 编译参数中出现 C++14/17/20/23（C++11 边界被放宽！）"
else
  _ok "B8 C++11 边界未被放宽（编译参数仅 -std=c++11）"
fi

_rule "2. 反证①：CTP 头文件本身不是 -Wextra -Werror 干净的"
rm -rf "${NONSYS_BUILD_DIR}"
cmake -S "${PROJ_DIR}" -B "${NONSYS_BUILD_DIR}" \
     -DCTP_SDK_ROOT="${CTP_SDK_ROOT}" \
     -DHG_CTP_INCLUDE_AS_SYSTEM=OFF \
     -DCMAKE_BUILD_TYPE=RelWithDebInfo > "${LOGDIR}/configure-nonsystem.log" 2>&1
if cmake --build "${NONSYS_BUILD_DIR}" -j"$(nproc)" > "${LOGDIR}/build-nonsystem.log" 2>&1; then
  _bad "B5 反证失败：CTP 头作为普通头引入竟编译通过（预期应因 -Werror=unused-parameter 失败）"
else
  if grep -q 'Werror=unused-parameter' "${LOGDIR}/build-nonsystem.log"; then
    _ok "B5 反证成立：CTP 头在 -Wextra -Werror 下产生 -Wunused-parameter（与 C++ 标准无关，属第三方头告警洁癖）"
    _info "冲突样例：$(grep -m1 'Werror=unused-parameter' "${LOGDIR}/build-nonsystem.log")"
  else
    _bad "B5 反证失败原因不是 unused-parameter，请核对日志"
    grep -m3 -E 'error:' "${LOGDIR}/build-nonsystem.log" || true
  fi
fi

_rule "3. 官方基线头名：<ThostFtdcTraderApi.h> 可得 / 合并版 <ThostFtdcUserApi.h> 不可得（判据反转，CH-015）"
TRADER_HDR_DIR="$(_hdr_dir ThostFtdcTraderApi.h)"
MERGED_HDR_DIR="$(_hdr_dir ThostFtdcUserApi.h)"
TMP_TU="${LOGDIR}/hg_ctp_trader_hdr.cxx"
printf '#include <ThostFtdcTraderApi.h>\nint main(){return 0;}\n' > "${TMP_TU}"
# 用 **-isystem**（= 本工程实际口径；官方头在普通 -I 下会因 -Wunused-parameter 撞 -Werror，
# 见 B5）。此处判的是「官方头名可得」，不是「官方头 -Werror 干净」。
if [[ -n "${TRADER_HDR_DIR}" ]] && g++ -std=c++11 -Wall -Wextra -Werror -isystem "${TRADER_HDR_DIR}" -c "${TMP_TU}" -o /dev/null 2>"${TMP_TU}.err"; then
  _ok "B6 <ThostFtdcTraderApi.h> 可得且在本工程口径下编译通过（-isystem；官方分离式 —— 交付基线；目录 ${TRADER_HDR_DIR}）"
else
  _bad "B6 <ThostFtdcTraderApi.h> 不可得或编译失败（官方基线应可得）：$(head -2 "${TMP_TU}.err" 2>/dev/null)"
fi
if [[ -z "${MERGED_HDR_DIR}" ]]; then
  _ok "B6b 合并版 <ThostFtdcUserApi.h> 在官方 SDK 上不可得 —— 属基线口径差异（CH-015），非 C++11 冲突"
else
  _bad "B6b 官方 SDK 中竟存在 <ThostFtdcUserApi.h>（${MERGED_HDR_DIR}）；请核对交付基线（预期不可得）"
fi
rm -f "${TMP_TU}" "${TMP_TU}.err"

_rule "汇总"
printf '%s\n' "${RESULTS[@]}"
printf '\nPASS=%d  FAIL=%d\n' "${PASS}" "${FAIL}"
if [[ "${FAIL}" -gt 0 ]]; then echo "RESULT: FAIL"; exit 1; fi
echo "RESULT: PASS"
