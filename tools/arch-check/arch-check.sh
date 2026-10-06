#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# arch-check.sh — 架构合规**聚合入口**（docs/ARCHITECTURE.md §3.13）
#
# 职责：按 §3.0.2「八大工程化手段」依次调用 **E1–E8 八个判据脚本**，汇总结果并
#       给出**单一退出码**——**任一脚本非 0 ⇒ 整体非 0**（任一失败则整体失败）。
#
# ⚠️ 本脚本**只做「调用 + 汇总退出码」**，**不实现、不改写任何判据逻辑**：
#   E1 `check_no_db_links.sh`（T-090）  E2 `scan_cpp_sql.sh`（T-095）
#   E3 `scan_cpp11.sh`（T-091）         E4 `scan_lock_free.sh`（T-092）
#   E5 `scan_java_sql.sh` + `strip_java_comments.awk`（既有）
#   E6 `check_frontend.sh`（T-093）     E7 `scan_hgcp_schema.py`（T-121）
#   E8 `scan_contracts_payload.py`（T-170）
#
# 调用口径：
#   · 先 **cd 到仓库根**（由本脚本位置推导 `../..`），再以**各脚本的默认扫描根**
#     逐一调用（**不显式传 `--root`**）。理由：各脚本的「显式路径不存在 ⇒ 退出 2」
#     语义是防「路径拼错被静默当 SKIP」的护栏；聚合入口对「组件尚未铺开」的目录
#     （如 `web`）应让脚本走其**设计好的默认 SKIP 分支**，而非把合法缺位当用法错误。
#     故此处一律使用默认根。
#   · ⚠️ `E4`（`scan_lock_free.sh`）的默认扫描根**不再是** `cpp/src/trading`（该目录不存在）——
#     自 `T-120` 起为**三目录集合** `cpp/src/{order_engine,risk_engine,risk_state}`（取并集，
#     依据 `docs/ARCHITECTURE.md §3.0.2` 的 `E4` 脚注）；**三目录全部不存在**时才 SKIP。
#     本入口**仍不传 `--root`**（上述口径不变），故 `E4` 现在**实际扫描**而非 `SKIP`。
#   · 判据脚本的**解释器按扩展名分派**（`T-121` 起）：`*.py` ⇒ `python3`，其余 ⇒ `bash`。
#     E1–E6 全部为 `*.sh` ⇒ **调用形态与参数逐字节不变**；`E7`/`E8` 为单文件 Python3
#     （`scan_hgcp_schema.py`（`T-121`）/ `scan_contracts_payload.py`（`T-170`），均无第三方依赖）。分派只影响「用哪个解释器」，
#     不改任何判据的入参、默认根或退出码语义。
#   · 退出码语义沿用各脚本统一口径（`T-095`）：
#       0 = 通过 / **默认路径**不存在（SKIP）；1 = 命中（架构偏离）；2 = 用法/前置错误
#
# 聚合退出码（**最高严重度优先**，数值即严重度序）：
#   0  全部脚本返回 0
#   1  无脚本返回 2，但至少一个返回 1（架构偏离）
#   2  至少一个脚本返回 2（用法/前置错误——判据未能真正执行，属更严重缺口）
#
# 用法：
#   tools/arch-check/arch-check.sh        # 在仓库任意位置执行均可（脚本自行定位仓库根）
#   -h, --help                            # 显示帮助
#
# 接线：作为 `ctest` 前置 fixture（见 `cpp/CMakeLists.txt` 的 `arch_check`）与
#       `mvn verify` 前置 exec（见 `java/pom.xml`）——新增 `E7` 后**两侧零改动**
#       （两者均只调用本入口，不枚举判据脚本）。
# =============================================================================

set -u

usage() {
  cat <<'USAGE'
用法：arch-check.sh
  聚合 E1–E8 八个架构判据脚本（各用默认路径，从仓库根调用），
  任一脚本非 0 ⇒ 整体非 0。
  -h, --help   显示本帮助
退出码：0 全部通过；1 存在架构偏离（无用法错误）；2 存在用法/前置错误
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    *) echo "错误：未知参数 $1" >&2; usage >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}" || { echo "错误：无法进入仓库根 ${REPO_ROOT}" >&2; exit 2; }

# 判据脚本清单（有序）：<手段>|<脚本文件>|<说明>
CHECKS=(
  "E1|check_no_db_links.sh|C++ 链接检查（不得链接 DB 客户端库）"
  "E2|scan_cpp_sql.sh|C++ SQL / 驱动痕迹静态扫描"
  "E3|scan_cpp11.sh|C++11 合规扫描（编译参数 + 禁用特性）"
  "E4|scan_lock_free.sh|交易路径无锁 / 无同步 I/O 扫描"
  "E5|scan_java_sql.sh|Java「dao 之外不得出现 SQL」扫描"
  "E6|check_frontend.sh|前端零构建链 + 无直连扫描"
  "E7|scan_hgcp_schema.py|HGCP 字段级 schema：ARCHITECTURE.md §4.2.8 ↔ proto/hgcp.proto"
  "E8|scan_contracts_payload.py|CONTRACTS §3.2.1 payload 键 ↔ proto 字段名（T-170）"
)

echo "############################################################################"
echo "# 架构合规聚合检查 arch-check（ARCHITECTURE.md §3.13；任一非 0 ⇒ 整体非 0）"
echo "# 仓库根：${REPO_ROOT}"
echo "# 判据脚本目录：${SCRIPT_DIR}"
echo "# 判据：E1–E8（E7 = HGCP §4.2.8 ↔ proto/hgcp.proto，T-121；E8 = CONTRACTS §3.2.1 payload 键 ↔ proto 字段名，T-170）"
echo "############################################################################"

declare -a CODES=()
declare -a NAMES=()
max_code=0
n_pass=0
n_hit=0
n_err=0

for entry in "${CHECKS[@]}"; do
  id="${entry%%|*}"; rest="${entry#*|}"; file="${rest%%|*}"; desc="${rest#*|}"
  path="${SCRIPT_DIR}/${file}"
  NAMES+=("${id}")
  echo
  echo "===== ${id}  ${file} —— ${desc} ====="
  if [ ! -f "${path}" ]; then
    echo "错误：判据脚本不存在：${path}" >&2
    code=2
  else
    # 解释器按扩展名分派（`T-121`）：`*.py` ⇒ `python3`；其余 ⇒ `bash`。
    # E1–E6 均为 `*.sh` ⇒ 调用形态逐字节不变（无入参、默认根）。
    case "${file}" in
      *.py) python3 "${path}" ;;
      *)    bash "${path}" ;;
    esac
    code=$?
  fi
  CODES+=("${code}")
  echo "----- ${id} 退出码：${code}（0=通过/SKIP, 1=命中, 2=用法/前置错误）-----"
  case "${code}" in
    0) n_pass=$((n_pass + 1)) ;;
    1) n_hit=$((n_hit + 1)) ;;
    *) n_err=$((n_err + 1)) ;;
  esac
  if [ "${code}" -gt "${max_code}" ]; then max_code="${code}"; fi
done

echo
echo "############################################################################"
echo "# 汇总（最高严重度优先：2 > 1 > 0）"
echo "#   通过(0)=${n_pass}  命中(1)=${n_hit}  用法/前置错误(2)=${n_err}"
i=0
while [ "${i}" -lt "${#NAMES[@]}" ]; do
  printf '#   %-3s 退出码 %s\n' "${NAMES[${i}]}" "${CODES[${i}]}"
  i=$((i + 1))
done
echo "############################################################################"

if [ "${max_code}" -eq 0 ]; then
  echo "arch-check 通过：八个判据脚本全部通过（退出码 0）"
  exit 0
fi
echo "arch-check 失败：存在非 0 判据（整体退出码 ${max_code}）" >&2
exit "${max_code}"
