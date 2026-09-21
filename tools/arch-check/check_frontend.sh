#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# check_frontend.sh — E6 判据：前端零构建链 + 无直连扫描
#
# 承载：docs/ARCHITECTURE.md §3.0.2（E6 手段）、§3.0.1（前端「绝不拥有：直连数据库；
#       直连 C++ 组件；业务判定逻辑」）、§7.1（前端零构建链）。
#
# 用法：
#   tools/arch-check/check_frontend.sh [--root <dir>]
#     --root <dir>   前端源码根目录（默认 web）
#     -h, --help     显示本帮助
#   退出码语义（六脚本统一，`T-095`）：0 = 通过 / **默认路径**不存在（SKIP）；
#     1 = 命中（架构偏离）；2 = 用法/前置错误：**显式传入**的 `--root` 不存在，或参数错误
#     （**不得静默放行**，防「路径拼错」被当 SKIP）。
#
# 扫描根  ：<root>（默认 web；本仓库实际前端目录）
# 排除项  ：**/node_modules/**、**/dist/**（其**存在本身**即判红项①，无需再入源码遍历）
# ⚠️ **扫描范围严格限定在前端源码目录内，不做全仓裸扫**：本项目 `docs/**` 与测试夹具中
#   存在 `jdbc:dm://…` 这类**合法配置值**（曾致 `E5` 误报 4 处）。前端判据若全仓裸扫，
#   会把后端/文档侧的合法连接串误判为"前端直连"。
#
# 判据（两类；任一命中即失败）：
#   ① 零构建链：在 <root> 内存在 `package.json` / `node_modules` / `dist` /
#      `*.bundle.js` / `*.min.js` → 失败（前端须为**免构建**的原生 HTML/JS/CSS）。
#   ② 无直连  ：在 <root> 源码内出现 → 失败：
#        jdbc                （任何提及 JDBC 的字样）
#        127.0.0.1:<端口>    （直连本机服务的 IP:PORT 字面量）
#        dsn                 （独立词，数据源名）
#
# 已知未覆盖 / 取舍（**不得沉默略过**）：
#   · 判 ② 按**原文**扫描，**未做注释/字符串剔除** —— 即前端**注释**中提及 `jdbc`/`dsn`
#     亦判红。取舍理由：前端为免构建的原生 JS，注释形态多样（行注释、块注释、模板串），
#     内联预处理器收益低；且此三词在前端**不存在合法用途**，纯提及亦属应清理项。
#     如后续出现误报，可仿 `E5` 的 `strip_java_comments.awk` 同族预处理放宽。
#   · 不检测 `localhost:<port>` / `ws://` 绝对地址（架构只点名 `127.0.0.1:端口`）；
#     前端经 `window.location.host` 取同源地址即合规，故不据此判红。
#   · 不检测**间接**直连（如经常量拼接出的 IP）；属语义级，须代码审查兜底。
#
# 自测（隔离夹具见回执；本脚本两向实测）：
#   反例  <root>/package.json 存在                 → 命中（①，退出码 1）
#   反例  <root>/app.min.js 存在                    → 命中（①）
#   反例  <root>/x.js 含 `jdbc:dm://…`             → 命中（②）
#   反例  <root>/x.js 含 `127.0.0.1:27402`         → 命中（②）
#   正例  目录不存在                                → SKIP（退出码 0）
# =============================================================================

set -u

ROOT="web"
ROOT_EXPLICIT=0

usage() {
  cat <<'USAGE'
用法：check_frontend.sh [--root <dir>]
  --root <dir>   前端源码根目录，默认 web
  -h, --help     显示本帮助
退出码：0 通过 / 默认路径不存在（SKIP）；1 命中；2 用法或前置错误（含显式 --root 不存在）
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --root)
      if [ "$#" -lt 2 ]; then echo "错误：--root 缺少参数" >&2; exit 2; fi
      ROOT="$2"; ROOT_EXPLICIT=1; shift 2 ;;
    --root=*) ROOT="${1#--root=}"; ROOT_EXPLICIT=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "错误：未知参数 $1" >&2; usage >&2; exit 2 ;;
  esac
done

echo "== E6 前端零构建链 + 无直连扫描 =="
echo "扫描根    : $ROOT（仅前端源码目录；**不做全仓裸扫**）"
echo "排除项    : **/node_modules/**、**/dist/**"
echo "判据 ①    : <root> 内存在 package.json / node_modules / dist / *.bundle.js / *.min.js → 失败"
echo "判据 ②    : <root> 源码出现 jdbc / 127.0.0.1:<端口> / dsn → 失败（按原文，未剔注释）"
echo "-------------------------------------------------------------------"

if [ ! -d "$ROOT" ]; then
  if [ "$ROOT_EXPLICIT" -eq 1 ]; then
    echo "错误：--root 目录不存在（显式路径）：$ROOT" >&2
    exit 2
  fi
  echo "SKIP: 默认前端目录 $ROOT 不存在（组件未铺开）—— 退出码 0"
  exit 0
fi

hits=0; note() { printf '%s\n' "$1"; hits=$((hits + 1)); }

# ---- ① 零构建链 ------------------------------------------------------------
echo "判据 ① 实测（构建链残留）："
bc=0
while IFS= read -r p; do
  [ -n "$p" ] || continue
  note "BUILD-CHAIN: $p"; bc=$((bc + 1))
done < <(find "$ROOT" \( -name 'package.json' -o -name 'node_modules' -o -name 'dist' \
  -o -name '*.bundle.js' -o -name '*.min.js' \) 2>/dev/null | LC_ALL=C sort)
[ "$bc" -eq 0 ] && echo "  （无）"

# ---- ② 无直连 --------------------------------------------------------------
echo "判据 ② 实测（直连字样）："
DC_RE='jdbc|127\.0\.0\.1:[0-9]+|\bdsn\b'
dc=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  while IFS=: read -r n ln; do
    [ -n "${n:-}" ] || continue
    note "$f:$n: $(printf '%s' "$ln" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"; dc=$((dc + 1))
  done < <(grep -nEi "$DC_RE" "$f")
done < <(find "$ROOT" -type f ! -path '*/node_modules/*' ! -path '*/dist/*' 2>/dev/null | LC_ALL=C sort)
[ "$dc" -eq 0 ] && echo "  （无）"

echo "-------------------------------------------------------------------"
if [ "$hits" -eq 0 ]; then
  echo "E6 通过：前端零构建链、无直连字样（退出码 0）"
  exit 0
fi
echo "E6 失败：前端存在构建链残留或直连字样，共 $hits 处（退出码 1）"
exit 1
