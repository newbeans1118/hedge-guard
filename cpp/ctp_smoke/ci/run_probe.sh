#!/usr/bin/env bash
# =============================================================================
# T-027 / RSK-02 探路复现脚本 —— CTP 头文件在 C++11 边界下的兼容验证
#
# 用法：
#   CTP_SDK_ROOT=/path/to/ctp_sdk bash cpp/ctp_smoke/ci/run_probe.sh
#   HG_SKIP_SDK=1 bash cpp/ctp_smoke/ci/run_probe.sh     # 仅做"SDK 不可得"取证
#
# 断言清单（与 T-027 验收标准一一对应）：
#   B1  CTP SDK 可得，且记录其路径 / 头文件 / 校验和
#   B2  -std=c++11 -Wall -Wextra -Werror 下 #include CTP 交易头文件编译通过
#   B3  __cplusplus == 201103（C++11 是编译期事实）
#   B4  链接真实 SDK 动态库并取得 SDK 自报版本（非桩、非等价头文件）
#   B5  反证①：CTP 头文件本身**不是** -Wextra -Werror 干净的（客观记录冲突）
#   B6  反证②：T-027 原文的 <ThostFtdcTraderApi.h> 在本 SDK 上不可得（命名口径差异）
#   B7  产物无任何 DB 客户端库（ARCHITECTURE.md 附录 B 红线 1）
#   B8  C++11 边界未被放宽（编译参数中不含 c++14/c++17/gnu++14/gnu++17）
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
_info "头文件校验和："
md5sum "${CTP_SDK_ROOT}"/ThostFtdc*.h 2>/dev/null | sed 's/^/    /'
_ok "B1 CTP SDK 可得（路径 ${CTP_SDK_ROOT}）"

_rule "1. 主路径：-std=c++11 -Wall -Wextra -Werror 下编译 + 链接真实 SDK"
rm -rf "${BUILD_DIR}"
if cmake -S "${PROJ_DIR}" -B "${BUILD_DIR}" \
     -DCTP_SDK_ROOT="${CTP_SDK_ROOT}" \
     -DHG_CTP_HEADER_STYLE=MERGED \
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

if grep -q 'sdk.linked *: YES' <<<"${PROBE_OUT}" && grep -q 'sdk.api.version.trader *: V' <<<"${PROBE_OUT}"; then
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

_rule "3. 反证②：<ThostFtdcTraderApi.h>（T-027 验收标准原文头名）在本 SDK 上是否可得"
TMP_TU="$(mktemp /tmp/hg_ctp_trader_hdr_XXXX.cxx)"
printf '#include <ThostFtdcTraderApi.h>\nint main(){return 0;}\n' > "${TMP_TU}"
if g++ -std=c++11 -Wall -Wextra -Werror -I"${CTP_SDK_ROOT}" -c "${TMP_TU}" -o /dev/null 2>"${TMP_TU}.err"; then
  _ok "B6 <ThostFtdcTraderApi.h> 可得（本 SDK 为独立 TraderApi 风格）"
else
  if grep -qi 'No such file' "${TMP_TU}.err"; then
    _ok "B6 <ThostFtdcTraderApi.h> 在本 SDK 上不可得（本 SDK 为合并版，交易头名为 ThostFtdcUserApi.h）—— 属命名口径差异，非 C++11 冲突"
    _info "$(grep -m1 -i 'fatal error' "${TMP_TU}.err")"
  else
    _bad "B6 <ThostFtdcTraderApi.h> 失败原因不是文件缺失：$(head -2 "${TMP_TU}.err")"
  fi
fi
rm -f "${TMP_TU}" "${TMP_TU}.err"

_rule "汇总"
printf '%s\n' "${RESULTS[@]}"
printf '\nPASS=%d  FAIL=%d\n' "${PASS}" "${FAIL}"
if [[ "${FAIL}" -gt 0 ]]; then echo "RESULT: FAIL"; exit 1; fi
echo "RESULT: PASS"
