#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# check_no_db_links.sh — E1 判据：C++ 交易组件不得链接 DB 客户端库
#
# 承载：docs/ARCHITECTURE.md §3.0.2（E1 手段）、§3.0.1（C++ 组件「绝不拥有
#       数据库驱动与 SQL」）、§4.4.3 / §8.1.3（判定路径「零数据库 I/O」）。
#
# 用法：
#   tools/arch-check/check_no_db_links.sh [--binary <path>] [--link-root <dir>]
#     --binary <path>    待 ldd 的 C++ 交易组件可执行文件（默认 build/hg-trading；
#                        未显式给出时另在仓库内自动发现名为 hg-trading 的文件）
#     --link-root <dir>  检索 CMake link.txt 的根目录（默认 .，即仓库根）
#     -h, --help         显示本帮助
#   退出码语义（六脚本统一，`T-095`）：0 = 通过 / 无产物（SKIP）；1 = 命中（架构偏离）；
#     2 = 用法/前置错误：**显式传入**的 `--link-root` / `--binary` 不存在，或参数错误
#         （**不得静默放行**）；默认 `--link-root .`（仓库根）不存在 ⇒ SKIP + 退出 0。
#
# 检查对象（两类；任一命中 DB 库名即失败）：
#   ① 可执行文件依赖：对 <binary> 执行 ldd，检索 DB 客户端库；
#   ② 链接行        ：在 <link-root>/**/link.txt（CMake 产物的链接命令行）中检索 DB 库名。
#
# DB 库名判据（大小写不敏感）：
#   达梦（DM）  ：dmjdbc | dmdpi                 ← 架构明文
#   通用（子串）：mysql | sqlite | odbc           ← 架构明文；子串形态覆盖
#                 libmysqlclient / libsqlite3 / libodbc / -lmysqlclient / -lsqlite3 …
#   pq / oci    ：-l(pq|oci) / lib(pq|oci) 前缀形态，以及独立词 \bpq\b / \boci\b
#                 ← 架构明文。**为何 pq / oci 加形态约束**：二者极短，裸子串会与
#                 路径、哈希、版本号中的偶然字样重合（本项目纪律：判据不得与被判
#                 对象「合法取值形状」重合）；故只在「链接器形态（-lNAME / libNAME）」
#                 或「独立词」下判定，避免误报。
#
# 无产物时的行为（**关键**）：
#   M2 之前 C++ 交易组件尚未构建 —— <binary> 与交易组件的 link.txt 均不存在。
#   此时**不得**因缺文件报红（否则 CI 恒红 —— 本项目已多次踩「判据形态与被判对象
#   合法形态重合 ⇒ 恒红」的坑）。脚本在「既无 binary、又无任何 link.txt」时打印
#   明确 SKIP 说明并**退出 0**。
#   ⚠️ 实测：本仓库当前 `cpp/**/build*/` 下**已存在**探路工程（proto_smoke /
#      ctp_smoke）的 link.txt（非交易组件）。本脚本按 `--link-root` 一并检索
#      （更严、更早生效），故**真实仓库默认运行会实际扫描这些 link.txt**（实测 0 命中、
#      退出 0），而**不是**走 SKIP 分支；SKIP 分支由隔离夹具演示（见回执）。
#
# 已知未覆盖项（须与 §3.0.2 落点标注一致，不得沉默略过）：
#   · 仅覆盖**直接链接**（ldd 一层 + link.txt 文本）。静态库内**间接**引入的 DB 代码
#     （如某 .a 内部符号）不在覆盖范围，须以符号级 / 运行时检查兜底。
#   · 非 ELF 产物（脚本伪产物 / 静态链接）无法 ldd —— 此时**跳过该项并提示**，不报红。
#   · 不解析 CMake 源（CMakeLists.txt）中的 `target_link_libraries`；只看**已生成**的
#     link.txt 与**已构建**的 binary。二者皆无即 SKIP。
# =============================================================================

set -u

BINARY_OPT=""
LINK_ROOT="."
LINK_ROOT_EXPLICIT=0

usage() {
  cat <<'USAGE'
用法：check_no_db_links.sh [--binary <path>] [--link-root <dir>]
  --binary <path>    待 ldd 的 C++ 交易组件可执行文件（默认 build/hg-trading，并自动发现 hg-trading）
  --link-root <dir>  检索 CMake link.txt 的根目录（默认 .）
  -h, --help         显示本帮助
退出码：0 通过 / 无产物（SKIP）；1 命中；2 用法或前置错误（含显式路径不存在）
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --binary)
      if [ "$#" -lt 2 ]; then echo "错误：--binary 缺少参数" >&2; exit 2; fi
      BINARY_OPT="$2"; shift 2 ;;
    --binary=*) BINARY_OPT="${1#--binary=}"; shift ;;
    --link-root)
      if [ "$#" -lt 2 ]; then echo "错误：--link-root 缺少参数" >&2; exit 2; fi
      LINK_ROOT="$2"; LINK_ROOT_EXPLICIT=1; shift 2 ;;
    --link-root=*) LINK_ROOT="${1#--link-root=}"; LINK_ROOT_EXPLICIT=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "错误：未知参数 $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [ ! -d "$LINK_ROOT" ]; then
  if [ "$LINK_ROOT_EXPLICIT" -eq 1 ]; then
    echo "错误：--link-root 目录不存在（显式路径）：$LINK_ROOT" >&2
    exit 2
  fi
  echo "SKIP: 默认 --link-root $LINK_ROOT 不存在（不成立于正常仓库）—— 退出码 0"
  exit 0
fi

if [ -n "$BINARY_OPT" ] && [ ! -f "$BINARY_OPT" ]; then
  echo "错误：--binary 文件不存在（显式路径）：$BINARY_OPT" >&2
  exit 2
fi

# ---- DB 客户端库判据（大小写不敏感）----------------------------------------
DB_RE='dmjdbc|dmdpi|mysql|sqlite|odbc|-l(pq|oci)|lib(pq|oci)|\bpq\b|\boci\b'

# ---- 发现待检 binary（交易组件可执行文件）----------------------------------
BINARIES=()
if [ -n "$BINARY_OPT" ]; then
  [ -f "$BINARY_OPT" ] && BINARIES+=("$BINARY_OPT")
else
  [ -f "build/hg-trading" ]     && BINARIES+=("build/hg-trading")
  [ -f "cpp/build/hg-trading" ] && BINARIES+=("cpp/build/hg-trading")
  while IFS= read -r p; do
    [ -n "$p" ] && BINARIES+=("$p")
  done < <(find . -type f -name 'hg-trading' 2>/dev/null | LC_ALL=C sort)
fi

# ---- 发现 link.txt（CMake 产物的链接命令行）--------------------------------
mapfile -t LINKS < <(find "$LINK_ROOT" -type f -name 'link.txt' 2>/dev/null | LC_ALL=C sort)

echo "== E1 C++ 链接检查（不得链接 DB 客户端库）=="
echo "检查对象  : ldd(<binary>) + <link-root>/**/link.txt"
echo "link-root : $LINK_ROOT"
echo "待检 binary: ${#BINARIES[@]} 个"
for b in ${BINARIES[@]+"${BINARIES[@]}"}; do echo "              - $b"; done
echo "待检 link  : ${#LINKS[@]} 个"
echo "DB 库判据 : dmjdbc | dmdpi | mysql | sqlite | odbc | -l(pq|oci) | lib(pq|oci) | \\bpq\\b | \\boci\\b"
echo "-------------------------------------------------------------------"

hits=0
note_hit() { printf '%s\n' "$1"; hits=$((hits + 1)); }

# ---- ① ldd 检索 ------------------------------------------------------------
for b in ${BINARIES[@]+"${BINARIES[@]}"}; do
  out="$(ldd "$b" 2>&1)" || true
  if printf '%s\n' "$out" | grep -qiE 'not a dynamic executable|not a valid dynamic program'; then
    echo "NOTE: $b 非动态可执行文件（静态链接 / 脚本伪产物）—— 跳过 ldd，不报红"
    continue
  fi
  while IFS= read -r ln; do
    [ -n "$ln" ] || continue
    note_hit "$b (ldd): $ln"
  done < <(printf '%s\n' "$out" | grep -iE "$DB_RE")
done

# ---- ② link.txt 检索 -------------------------------------------------------
for lf in ${LINKS[@]+"${LINKS[@]}"}; do
  while IFS=: read -r n ln; do
    [ -n "${n:-}" ] || continue
    note_hit "$lf:$n: $(printf '%s' "$ln" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
  done < <(grep -nEi "$DB_RE" "$lf")
done

echo "-------------------------------------------------------------------"
if [ "$hits" -eq 0 ]; then
  if [ "${#BINARIES[@]}" -eq 0 ] && [ "${#LINKS[@]}" -eq 0 ]; then
    echo "SKIP: 未发现 C++ 交易组件产物（binary 与 link.txt 均不存在，C++ 组件未构建）—— 退出码 0"
    exit 0
  fi
  echo "E1 通过：C++ 产物未链接 DB 客户端库（退出码 0）"
  exit 0
fi
echo "E1 失败：C++ 产物链接了 DB 客户端库，共 $hits 处（退出码 1）"
exit 1
