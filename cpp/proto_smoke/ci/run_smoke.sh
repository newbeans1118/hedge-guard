#!/usr/bin/env bash
# =============================================================================
# T-026 / RSK-01 探路复现脚本 —— protobuf 在 C++11 下的空壳构通验证
#
# 用法：
#   bash cpp/proto_smoke/ci/run_smoke.sh              # 全流程（系统包 + 回退链路）
#   HG_SKIP_FALLBACK=1 bash .../run_smoke.sh          # 跳过回退链路（离线环境）
#   HG_STAGE=system|fallback  .../run_smoke.sh        # 只跑其中一段
#
# 退出码：0 = 全绿；非 0 = 失败（任一断言不成立）
#
# 断言清单（与 T-026 验收标准一一对应）：
#   A1  -std=c++11 -Wall -Wextra -Werror 实际生效（读 compile_commands.json）
#   A2  HGCP 空消息编译 + 收发成功；含字段消息收发作加严补充
#   A3  实际可用 protobuf 版本与编译参数被记录
#   A4  大版本 = 3（构建期 FATAL_ERROR 校验）
#   A5  -Werror 真的生效（反证：使用 3.21 已弃用 API 必须编译失败）
#   A6  回退链路可复现：① FetchContent 拉取 3.19.6；② `protobuf-lite` 降级（RSK-01 应对③）
#   A7  产物未链接任何 DB 客户端库（ARCHITECTURE.md 附录 B 红线 1）
#   A8  源码树无前端构建链产物（E6；**范围与排除项自证** —— 必须排除 `build*/` 与 `_deps/`）
#   A9  CMake 无 websocketpp / Boost（CH-006：C++ 侧仅 CTP SDK + protobuf）
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_DIR="$(cd "${HERE}/.." && pwd)"
BUILD_DIR="${PROJ_DIR}/build"
FALLBACK_BUILD_DIR="${PROJ_DIR}/build-fallback"
FALLBACK_VERSION="${HG_PROTOBUF_FALLBACK_VERSION:-3.19.6}"
STAGE="${HG_STAGE:-all}"

PASS=0
FAIL=0
declare -a RESULTS=()

_ok()   { PASS=$((PASS+1)); RESULTS+=("  [PASS] $1"); printf '\033[32m[PASS]\033[0m %s\n' "$1"; }
_bad()  { FAIL=$((FAIL+1)); RESULTS+=("  [FAIL] $1"); printf '\033[31m[FAIL]\033[0m %s\n' "$1"; }
_info() { printf '\033[36m[INFO]\033[0m %s\n' "$1"; }
_rule() { printf '\n==================== %s ====================\n' "$1"; }

# ---------------------------------------------------------------------------
_rule "0. 工具链事实"
# ---------------------------------------------------------------------------
_info "$(g++ --version | head -1)"
_info "$(cmake --version | head -1)"
_info "protoc: $(protoc --version 2>/dev/null || echo '未找到')"
_info "protobuf 系统包: $(dpkg-query -W -f='${Version}' libprotobuf-dev 2>/dev/null || echo '未安装')"

# ---------------------------------------------------------------------------
if [[ "${STAGE}" == "all" || "${STAGE}" == "system" ]]; then
_rule "1. 主路径：系统 protobuf + C++11 构通"
# ---------------------------------------------------------------------------
rm -rf "${BUILD_DIR}"
cmake -S "${PROJ_DIR}" -B "${BUILD_DIR}" \
      -DCMAKE_BUILD_TYPE=RelWithDebInfo \
      -DHG_PROTOBUF_USE_SYSTEM=ON \
      -DHG_PROTOBUF_MAJOR_REQUIRED=3 2>&1 | tee "${BUILD_DIR}.configure.log" | tail -20

cmake --build "${BUILD_DIR}" -j"$(nproc)" 2>&1 | tee "${BUILD_DIR}.build.log" | tail -20
if [[ ${PIPESTATUS[0]} -ne 0 ]]; then _bad "A2 主路径构建失败"; else _ok "A2 主路径构建成功（-std=c++11 -Wall -Wextra -Werror）"; fi

# --- A1：编译参数实际生效 ---------------------------------------------------
CCJ="${BUILD_DIR}/compile_commands.json"
if [[ -f "${CCJ}" ]] \
   && grep -q -- '-std=c++11' "${CCJ}" \
   && grep -q -- '-Wall' "${CCJ}" \
   && grep -q -- '-Wextra' "${CCJ}" \
   && grep -q -- '-Werror' "${CCJ}"; then
  _ok "A1 compile_commands.json 中 -std=c++11 -Wall -Wextra -Werror 均实际生效"
else
  _bad "A1 编译参数未在 compile_commands.json 中完整出现"
fi

# --- A3 / A4：版本记录 + 大版本校验 ----------------------------------------
CFG_OUT="$("${BUILD_DIR}/hgcp_proto_smoke" --print-config)"
echo "${CFG_OUT}"
if grep -q 'protobuf.cmake.found *: 3\.21\.12' <<<"${CFG_OUT}"; then
  _ok "A3 实际可用 protobuf = 3.21.12（首选版本，来自系统包）"
else
  _bad "A3 实际可用 protobuf 非 3.21.12，请核对输出"
fi
if grep -q 'protobuf.cmake.major.required: 3' <<<"${CFG_OUT}"; then
  _ok "A4 构建期大版本=3 校验通过"
else
  _bad "A4 大版本校验未生效"
fi
if grep -q 'cxx.standard.__cplusplus *: 201103$' <<<"${CFG_OUT}" \
   && grep -q 'cxx.standard.is_cxx11 *: YES' <<<"${CFG_OUT}"; then
  _ok "A1b __cplusplus == 201103（C++11 是编译期事实，而非仅构建请求）"
else
  _bad "A1b __cplusplus 不是 201103"
fi

# --- A2：空消息 + 加严消息收发 ---------------------------------------------
ctest --test-dir "${BUILD_DIR}" --output-on-failure 2>&1 | tee "${BUILD_DIR}.ctest.log" | tail -15
if ctest --test-dir "${BUILD_DIR}" --output-on-failure >/dev/null 2>&1; then
  _ok "A2 ctest 3/3 通过（空消息收发 + 加严消息收发 + 编译标准事实）"
else
  _bad "A2 ctest 未全绿"
fi

# --- A5：-Werror 反证 ------------------------------------------------------
TMP_TU="$(mktemp /tmp/hg_werror_probe_XXXX.cxx)"
cat > "${TMP_TU}" <<'EOF'
#include "hgcp_smoke.pb.h"
int main() {
  hgcp::v1::HgcpEmpty m;
  return m.ByteSize();  // 3.21 已弃用 → -Werror=deprecated-declarations 必须拦下
}
EOF
if g++ -std=c++11 -Wall -Wextra -Werror -c "${TMP_TU}" \
      -I"${BUILD_DIR}/generated" -I"${BUILD_DIR}" \
      -o /dev/null 2>"${TMP_TU}.err"; then
  _bad "A5 反证失败：使用已弃用 ByteSize() 竟编译通过 → -Werror 未真正生效"
else
  if grep -q 'deprecated' "${TMP_TU}.err"; then
    _ok "A5 -Werror 真实生效（反证命中 -Werror=deprecated-declarations）"
  else
    _bad "A5 反证失败原因不是 deprecated 告警，请核对：$(head -3 "${TMP_TU}.err")"
  fi
fi
rm -f "${TMP_TU}" "${TMP_TU}.err"

# --- A7：无 DB 依赖 --------------------------------------------------------
if ldd "${BUILD_DIR}/hgcp_proto_smoke" | grep -Eiq 'dmjdbc|dmdpi|mysql|sqlite|libpq|odbc|oci'; then
  _bad "A7 产物链接了 DB 客户端库（违反附录 B 红线 1）"
else
  _ok "A7 ldd 无任何 DB 客户端库（附录 B 红线 1）"
fi

# --- A8：E6 前端零构建链（自证扫描范围与排除项）-----------------------------
# ⚠️ 实测陷阱（来自 #java）：CMake FetchContent 拉下的 protobuf 源码树自带
#    `_deps/protobuf-src/js/package.json`，朴素 E6 扫描会**误报**成"引入构建链"。
#    故扫描必须排除 `build*/`、`_deps/`、`third_party/`；
#    本项目纪律：凡"0 命中"结论，**必须**同时声明范围与排除项，否则不可复核。
_info "A8 E6 扫描范围：${PROJ_DIR}（排除：*/build*、*/_deps/*、*/third_party/*）"
E6_HITS="$(find "${PROJ_DIR}" \( -path '*/build*' -o -path '*/_deps/*' -o -path '*/third_party/*' \) -prune -o \
           -type f \( -name 'package.json' -o -name '*.bundle.js' -o -name '*.min.js' \) -print 2>/dev/null)"
E6_DIRS="$(find "${PROJ_DIR}" \( -path '*/build*' -o -path '*/_deps/*' -o -path '*/third_party/*' \) -prune -o \
           -type d \( -name node_modules -o -name dist \) -print 2>/dev/null)"
if [[ -z "${E6_HITS}" && -z "${E6_DIRS}" ]]; then
  _ok "A8 cpp/proto_smoke 源码树无前端构建链产物（0 命中，范围与排除项已自证）"
else
  _bad "A8 出现前端构建链产物：${E6_HITS} ${E6_DIRS}"
fi

# --- A9：CH-006 依赖纪律 -----------------------------------------------------
if grep -rEq 'websocketpp|find_package\(Boost|Boost::system' \
     "${PROJ_DIR}/CMakeLists.txt" 2>/dev/null; then
  _bad "A9 CMake 中出现 websocketpp / Boost（违反 CH-006）"
else
  _ok "A9 CMake 无 websocketpp / Boost（CH-006：C++ 侧仅 CTP SDK + protobuf）"
fi
fi  # STAGE system

# ---------------------------------------------------------------------------
if [[ "${STAGE}" == "all" || "${STAGE}" == "fallback" ]] && [[ "${HG_SKIP_FALLBACK:-0}" != "1" ]]; then
_rule "2. 回退链路：FetchContent 拉取 protobuf v${FALLBACK_VERSION}"
# ---------------------------------------------------------------------------
rm -rf "${FALLBACK_BUILD_DIR}"
# 拉取方式：默认 GIT（无需内置哈希）；若提供已缓存的 tarball 则走 URL + 强制 SHA256 校验
#（弱网/离线 CI 用：先离线缓存 tarball，再设 HG_PROTOBUF_TARBALL 与本变量）。
#
# 注意：内置哈希表当前为空 —— 首次在联网 CI 上用 URL 方式拉取成功后，
# 应把 `protobuf-all-<ver>.tar.gz` 的实测 SHA256 钉进
# `cpp/proto_smoke/CMakeLists.txt` 的 `_hg_pb_sha256`；此后 URL 方式即可零参数使用。
# **不得**为了省事而关闭 `URL_HASH`。
# 另：`protobuf-cpp-<ver>.tar.gz` **不可**用于 CMake FetchContent（无顶层
#     CMakeLists.txt），CMakeLists 内有守卫会明确报错（本次实测结论）。
FETCH_VIA="${HG_PROTOBUF_FETCH_VIA:-GIT}"
FETCH_ARGS=(-DHG_PROTOBUF_FETCH_VIA="${FETCH_VIA}")
if [[ -n "${HG_PROTOBUF_TARBALL:-}" ]]; then
  # 刻意**不**传 -DHG_PROTOBUF_TARBALL_SHA256：让 CMake 内置的钉死哈希真正参与校验。
  FETCH_ARGS+=(-DHG_PROTOBUF_TARBALL="${HG_PROTOBUF_TARBALL}")
  _info "回退链路使用本地缓存 tarball：${HG_PROTOBUF_TARBALL}（校验用 CMake 内置钉死 SHA256）"
fi

if cmake -S "${PROJ_DIR}" -B "${FALLBACK_BUILD_DIR}" \
      -DCMAKE_BUILD_TYPE=RelWithDebInfo \
      -DHG_PROTOBUF_USE_SYSTEM=OFF \
      -DHG_PROTOBUF_FORCE_FETCH=ON \
      -DHG_PROTOBUF_FALLBACK_VERSION="${FALLBACK_VERSION}" \
      "${FETCH_ARGS[@]}" \
      > "${FALLBACK_BUILD_DIR}.configure.log" 2>&1 \
   && cmake --build "${FALLBACK_BUILD_DIR}" -j"$(nproc)" \
      > "${FALLBACK_BUILD_DIR}.build.log" 2>&1; then
  "${FALLBACK_BUILD_DIR}/hgcp_proto_smoke" --self-test | tee "${FALLBACK_BUILD_DIR}.self-test.log"
  if grep -q "RESULT: PASS" "${FALLBACK_BUILD_DIR}.self-test.log"; then
    _ok "A6 回退链路 v${FALLBACK_VERSION} 构通并收发成功"
  else
    _bad "A6 回退链路自检未通过"
  fi
  if ctest --test-dir "${FALLBACK_BUILD_DIR}" >/dev/null 2>&1; then
    _ok "A6b 回退链路 ctest 全绿"
  else
    _bad "A6b 回退链路 ctest 未全绿"
  fi
else
  _bad "A6 回退链路构建失败（多半是网络不可达）；configure 日志尾部："
  tail -15 "${FALLBACK_BUILD_DIR}.configure.log" || true
fi
fi  # STAGE fallback

# ---------------------------------------------------------------------------
if [[ "${STAGE}" == "all" || "${STAGE}" == "lite" ]]; then
_rule "3. 降级链路：protobuf-lite（RSK-01 应对③）"
# ---------------------------------------------------------------------------
LITE_BUILD_DIR="${PROJ_DIR}/build-lite"
rm -rf "${LITE_BUILD_DIR}"
if cmake -S "${PROJ_DIR}" -B "${LITE_BUILD_DIR}" \
      -DCMAKE_BUILD_TYPE=RelWithDebInfo \
      -DHG_PROTOBUF_LITE=ON > "${LITE_BUILD_DIR}.configure.log" 2>&1 \
   && cmake --build "${LITE_BUILD_DIR}" -j"$(nproc)" > "${LITE_BUILD_DIR}.build.log" 2>&1 \
   && ctest --test-dir "${LITE_BUILD_DIR}" > "${LITE_BUILD_DIR}.ctest.log" 2>&1; then
  _ok "A6c protobuf-lite 降级链路构通且 ctest 全绿（$(grep -o 'libprotobuf-lite[^ ]*' <<<"$(ldd "${LITE_BUILD_DIR}/hgcp_proto_smoke")" | head -1)）"
else
  _bad "A6c protobuf-lite 降级链路失败；日志尾部："
  tail -10 "${LITE_BUILD_DIR}.build.log" 2>/dev/null || tail -10 "${LITE_BUILD_DIR}.configure.log"
fi
fi  # STAGE lite

# ---------------------------------------------------------------------------
_rule "汇总"
printf '%s\n' "${RESULTS[@]}"
echo
echo "PASS=${PASS}  FAIL=${FAIL}"
if [[ "${FAIL}" -gt 0 ]]; then
  echo "RESULT: FAIL"
  exit 1
fi
echo "RESULT: PASS"
