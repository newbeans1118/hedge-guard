#!/usr/bin/env bash
# =============================================================================
# T-128 ★可核判据 —— 「同一组 SDK 根/风格配置下，主工程与 ctp_smoke 的 CTP_FOUND 必须一致」
#
# 背景：主工程（`cpp/cmake/HgDependencies.cmake`）与探路件（`cpp/ctp_smoke/CMakeLists.txt`）
#   都必须由**同一来源**决定交易/行情头名。T-128 前态二者不同源（主工程
#   `HG_CTP_TRADER_HEADER` ↔ 探路件 `HG_CTP_HEADER_STYLE`），实测可让同一配置下
#   两端得出**互相矛盾**的 `CTP_FOUND`（探路件「发现 CTP SDK」/ 主工程「不可得」）。
#
# 做法：一次 top-level 配置（`cmake -S cpp`）中，两处各自打印一行标准化判定：
#   [HG-CTP-FOUND] role=main|smoke found=ON|OFF style=... trader_header=... md_header=...
#   本脚本抓取该两行并**断言 `found=` 相等**。
#
# 用法：
#   bash cpp/ctp_smoke/ci/check_ctp_found_parity.sh \
#        [--sdk-root DIR] [--style CLASSIC|MERGED] [--expect on|off|auto] [--build-dir DIR]
#   （--sdk-root 省略 ⇒ 不传 CTP_SDK_ROOT，预期两端一致 OFF）
#
# 仅做 **configure**（不编译）。
# 临时目录：--build-dir 缺省 = `${TMPDIR:-/tmp}/hg-ctp-parity-XXXXXX`；
#   CMake 自身临时文件用 `PARITY_TMPDIR`（缺省 `${TMPDIR:-/tmp}`）。
#   ⚠️ 本机（hedge-guard 开发机）`/tmp` 是 ~800MB tmpfs ⇒ 应显式
#      `TMPDIR=/ PARITY_TMPDIR=/` 并给 `--build-dir /<大分区>/...`。
#
# 退出码：0 = 一致（PASS）；1 = 不一致（FAIL，分歧）；3 = 不可判定（配置未成功，无并列判定行）
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_DIR="$(cd "${HERE}/../.." && pwd)"
SDK_ROOT=""
STYLE="CLASSIC"
EXPECT="auto"
BUILD_DIR=""
PARITY_TMPDIR="${PARITY_TMPDIR:-${TMPDIR:-/tmp}}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sdk-root)  SDK_ROOT="$2"; shift 2 ;;
    --style)     STYLE="$2"; shift 2 ;;
    --expect)    EXPECT="$2"; shift 2 ;;
    --build-dir) BUILD_DIR="$2"; shift 2 ;;
    -h|--help)   sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

if [[ -z "${BUILD_DIR}" ]]; then
  BUILD_DIR="$(mktemp -d "${PARITY_TMPDIR}/hg-ctp-parity-XXXXXX")"
  CLEANUP=1
else
  rm -rf "${BUILD_DIR}"; mkdir -p "${BUILD_DIR}"; CLEANUP=0
fi
LOG="${BUILD_DIR}/configure.log"

args=(-S "${CPP_DIR}" -B "${BUILD_DIR}" -DHG_CTP_HEADER_STYLE="${STYLE}" -DCMAKE_BUILD_TYPE=RelWithDebInfo)
[[ -n "${SDK_ROOT}" ]] && args+=(-DCTP_SDK_ROOT="${SDK_ROOT}")

echo "== 配置：cmake ${args[*]}"
TMPDIR="${PARITY_TMPDIR}" cmake "${args[@]}" > "${LOG}" 2>&1
RC=$?

echo "== 两处判定并列输出（role=smoke 先于 role=main 求值）"
grep -h '^-- \[HG-CTP-FOUND\]' "${LOG}" | sed 's/^/   /' || true
FOUND_LINES="$(grep -h '^-- \[HG-CTP-FOUND\]' "${LOG}" || true)"

if [[ -z "${FOUND_LINES}" ]]; then
  echo "== [不可判定] 配置未产出判定行（rc=${RC}）；日志：${LOG}"
  grep -nE 'CMake Error|FATAL|\[CTP\]|\[RSK-02\]' "${LOG}" | head -20 | sed 's/^/   /'
  [[ "${CLEANUP}" == 1 ]] && rm -rf "${BUILD_DIR}"
  exit 3
fi

smoke_line="$(grep -m1 'role=smoke' <<<"${FOUND_LINES}")"
main_line="$(grep -m1 'role=main' <<<"${FOUND_LINES}")"
smoke="$(sed -n 's/.*found=\([A-Z]*\).*/\1/p' <<<"${smoke_line}")"
main="$(sed -n 's/.*found=\([A-Z]*\).*/\1/p' <<<"${main_line}")"

if [[ -z "${smoke}" || -z "${main}" ]]; then
  echo "== [不可判定] 判定行不完整（smoke='${smoke}' main='${main}'）—— 一侧在打印前即中止；日志：${LOG}"
  grep -nE 'CMake Error|FATAL' "${LOG}" | head -10 | sed 's/^/   /'
  [[ "${CLEANUP}" == 1 ]] && rm -rf "${BUILD_DIR}"
  exit 3
fi

# 全字段对等：found 之外，style / trader_header / md_header 亦须逐字相同
# （只有唯一来源时才会逐字相同 —— 任一处另起来源都会在此暴露）。
smoke_fields="$(sed 's/^.*role=smoke //' <<<"${smoke_line}")"
main_fields="$(sed 's/^.*role=main //' <<<"${main_line}")"

STATUS=0
if [[ "${smoke}" != "${main}" ]]; then
  echo "== [FAIL] 判定分歧：smoke=${smoke} main=${main}（同配置下两端结论矛盾）"
  STATUS=1
elif [[ "${smoke_fields}" != "${main_fields}" ]]; then
  echo "== [FAIL] 判定字段不一致（found 相同但入口字段不同 ⇒ 仍有第二来源）："
  echo "   smoke: ${smoke_fields}"
  echo "   main : ${main_fields}"
  STATUS=1
else
  echo "== [PASS] 判定一致：smoke=main=${smoke}（全字段逐字相同）"
fi
if [[ "${EXPECT}" != "auto" ]]; then
  want="$(tr '[:lower:]' '[:upper:]' <<<"${EXPECT}")"
  if [[ "${smoke}" != "${want}" ]]; then
    echo "== [FAIL] 期望 found=${want}，实测 ${smoke}"
    STATUS=1
  fi
fi

[[ "${CLEANUP}" == 1 ]] && rm -rf "${BUILD_DIR}"
exit "${STATUS}"
