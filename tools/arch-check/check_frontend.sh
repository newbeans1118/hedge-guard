#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# check_frontend.sh — E6 判据：前端零构建链 + 无直连扫描 + 跨模块引用 × 实际导出
#
# 承载：docs/ARCHITECTURE.md §3.0.2（E6 手段）、§3.0.1（前端「绝不拥有：直连数据库；
#       直连 C++ 组件；业务判定逻辑」）、§7.1（前端零构建链）、§3.15（`HG.<module>.*`
#       统一命名空间）；判据 ③ 由 `T-138` 追加（来源 = `T-134` 验收 3 / `T-017` 验证盲区）。
#
# 用法：
#   tools/arch-check/check_frontend.sh [--root <dir>]
#     --root <dir>   前端源码根目录（默认 web）
#     -h, --help     显示本帮助
#   退出码语义（六脚本统一，`T-095`）：0 = 通过 / **默认路径**不存在（SKIP）；
#     1 = 命中（架构偏离）；2 = 用法/前置错误：**显式传入**的 `--root` 不存在、参数错误，
#     **或判据 ③ 所需运行时 `node` 缺失**（**不得静默放行**，防「路径拼错」被当 SKIP；
#     `node` 缺失属「判据未能真正执行」⇒ 按前置错误返 2，**不得静默 SKIP 返 0**）。
#
# 扫描根  ：<root>（默认 web；本仓库实际前端目录）
# 排除项  ：**/node_modules/**、**/dist/**（其**存在本身**即判红项①，无需再入源码遍历）
# ⚠️ **扫描范围严格限定在前端源码目录内，不做全仓裸扫**：本项目 `docs/**` 与测试夹具中
#   存在 `jdbc:dm://…` 这类**合法配置值**（曾致 `E5` 误报 4 处）。前端判据若全仓裸扫，
#   会把后端/文档侧的合法连接串误判为"前端直连"。
#
# 判据（三类；任一命中即失败）：
#   ① 零构建链：在 <root> 内存在 `package.json` / `node_modules` / `dist` /
#      `*.bundle.js` / `*.min.js` → 失败（前端须为**免构建**的原生 HTML/JS/CSS）。
#   ② 无直连  ：在 <root> 源码内出现 → 失败：
#        jdbc                （任何提及 JDBC 的字样）
#        127.0.0.1:<端口>    （直连本机服务的 IP:PORT 字面量）
#        dsn                 （独立词，数据源名）
#   ③ 跨模块引用 × 实际导出（`T-138`）：`<root>/**/*.js` 中形如 `HG.<mod>.<member>(`
#      的**调用**引用，其 `<mod>` 须为已定义命名空间、`<member>` 须在该模块的**实际
#      导出集合**内；否则 → 失败（打印 `文件:行` + 缺失符号）。判据实现在
#      `tools/arch-check/scan_hg_exports.mjs`（单文件 Node ESM、**零第三方依赖**；
#      先剔注释与字符串/模板原文，`${…}` 内代码保留）。**已知未覆盖**见该脚本头。
#      ⚠️ **覆盖边界（与 `scan_hg_exports.mjs` 头注、`tools/arch-check/README.md` 逐字一致）**：
#      · **覆盖边界（三段式完全不判、不计数）**：本判据仅覆盖两段式 `HG.<mod>.<member>(`；三段式 `HG.a.b.c(` 完全不判、且不计入统计（深层成员为对象属性 / 动态挂载，静态不可判定，强判会误红——实测 `HG.api.PAGE_SIZES`（`api` 导出的数组常量，非方法）/ `HG.pages._riskAlertConn`（动态挂载的 WebSocket 实例，非方法）的第三段 `.forEach` / `.close` 均不在模块导出集合内；真实树三段式共 17 处：`HG.api.PAGE_SIZES.forEach(` 8、`HG.routes.PLAN.*(` 7、`HG.pages._*.close(` 2，均不计入「跨模块调用 300 处」统计）
#      · 导出集合不可静态确定的模块降为 `partial` 并逐条打印 `NOTE`（**不判红**），
#        归因分三类：① **非字面量赋值**（`HG.x = factory()` / 工厂 / 函数调用 / 外部引用
#        ⇒ **整模块** `partial(0+)`）② 导出体内 **spread** ③ 导出体内**计算属性名**。
#      · **挂账未修（真实树暴露 0 例）**：getter 简写 `{ get limit() {} }` 的键被记为 `get`
#        ⇒ 引用 `HG.m.limit(` **误红**；生成器 `*gen() {}` 不被识别 ⇒ 该模块 `partial` 而**漏判**；
#        `Object.assign(HG.x, {…})` 不被认作导出定义 ⇒ 引用 `HG.x.m(` **误红**。
#      · 正则字面量未做专门处理：仓库内存在 ≥24 处正则字面量（未逐一校验）。正则体内含
#        `//` 会剔除该行余部；含 `/*` 会**吞掉其后内容**（无配对 `*/` 则吞至文件末尾），
#        本判据不校验被吞内容，仅对「块注释未闭合」向 stderr 打 `WARN`（不改退出码）。
#
# 已知未覆盖 / 取舍（**不得沉默略过**）：
#   · 判 ② 按**原文**扫描，**未做注释/字符串剔除** —— 即前端**注释**中提及 `jdbc`/`dsn`
#     亦判红。取舍理由：前端为免构建的原生 JS，注释形态多样（行注释、块注释、模板串），
#     内联预处理器收益低；且此三词在前端**不存在合法用途**，纯提及亦属应清理项。
#     如后续出现误报，可仿 `E5` 的 `strip_java_comments.awk` 同族预处理放宽。
#   · 不检测 `localhost:<port>` / `ws://` 绝对地址（架构只点名 `127.0.0.1:端口`）；
#     前端经 `window.location.host` 取同源地址即合规，故不据此判红。
#   · 不检测**间接**直连（如经常量拼接出的 IP）；属语义级，须代码审查兜底。
#   · 判 ③ 与判 ② 的口径**故意不对称**：③ **剔注释**（否则 `T-134` 的根因留痕注释
#     本身会被误判为违规）且**只判调用形态**（纯属性读取得 `undefined`、不抛错）；
#     ② 仍按原文（见上条）。该不对称**已同步落登** `docs/ARCHITECTURE.md §3.0.2` 的
#     `E6` 行（`v1.5.26`，`T-138` 补正；退出码列已注明「③ 需 `node` ⇒ 缺失退 `2`」）。
#
# 自测（隔离夹具见回执；本脚本三向实测）：
#   反例  <root>/package.json 存在                 → 命中（①，退出码 1）
#   反例  <root>/app.min.js 存在                    → 命中（①）
#   反例  <root>/x.js 含 `jdbc:dm://…`             → 命中（②）
#   反例  <root>/x.js 含 `127.0.0.1:27402`         → 命中（②）
#   反例  <root>/js/x.js 含 `HG.session.roleLabel()` → 命中（③，退出码 1）
#   反例  `node` 自 `PATH` 剥离                      → 前置错误（退出码 2）
#   正例  目录不存在                                → SKIP（退出码 0）
# =============================================================================

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ROOT="web"
ROOT_EXPLICIT=0

usage() {
  cat <<'USAGE'
用法：check_frontend.sh [--root <dir>]
  --root <dir>   前端源码根目录，默认 web
  -h, --help     显示本帮助
退出码：0 通过 / 默认路径不存在（SKIP）；1 命中；2 用法或前置错误
        （含显式 --root 不存在、判据 ③ 所需运行时 node 缺失）
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

echo "== E6 前端零构建链 + 无直连扫描 + 跨模块引用 × 实际导出 =="
echo "扫描根    : $ROOT（仅前端源码目录；**不做全仓裸扫**）"
echo "排除项    : **/node_modules/**、**/dist/**"
echo "判据 ①    : <root> 内存在 package.json / node_modules / dist / *.bundle.js / *.min.js → 失败"
echo "判据 ②    : <root> 源码出现 jdbc / 127.0.0.1:<端口> / dsn → 失败（按原文，未剔注释）"
echo "判据 ③    : <root>/**/*.js 的 HG.<mod>.<member>( 引用了未定义命名空间 / 未导出成员 → 失败（T-138）"
echo "-------------------------------------------------------------------"

if [ ! -d "$ROOT" ]; then
  if [ "$ROOT_EXPLICIT" -eq 1 ]; then
    echo "错误：--root 目录不存在（显式路径）：$ROOT" >&2
    exit 2
  fi
  echo "SKIP: 默认前端目录 $ROOT 不存在（组件未铺开）—— 退出码 0"
  exit 0
fi

# ---- 判据 ③ 前置：所需运行时与前置脚本（缺失 ⇒ 前置错误 2，**不得静默 SKIP 返 0**）----
EXPORTS_SCANNER="${SCRIPT_DIR}/scan_hg_exports.mjs"
if [ ! -f "$EXPORTS_SCANNER" ]; then
  echo "错误：判据 ③ 前置脚本不存在：$EXPORTS_SCANNER（判据无法执行）" >&2
  echo "E6 用法/前置错误（退出码 2）" >&2
  exit 2
fi
if ! command -v node >/dev/null 2>&1; then
  echo "错误：判据 ③ 所需运行时 node 不存在（PATH=${PATH}）—— 判据无法执行" >&2
  echo "     按前置错误返回 2（**不得静默 SKIP 返 0**；见 docs/ARCHITECTURE.md §3.0.2 退出码语义）" >&2
  echo "E6 用法/前置错误（退出码 2）" >&2
  exit 2
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

# ---- ③ 跨模块引用 × 实际导出（T-138）--------------------------------------
echo "判据 ③ 实测（跨模块调用引用 × 目标模块实际导出）："
c3_out="$(node "$EXPORTS_SCANNER" --root "$ROOT" 2>&1)"; c3_rc=$?
printf '%s\n' "$c3_out"
c3_hits="$(printf '%s\n' "$c3_out" | sed -n 's/^HIT-COUNT: //p' | head -1)"
case "${c3_hits:-}" in ''|*[!0-9]*) c3_hits=0 ;; esac
if [ "$c3_rc" -eq 0 ]; then
  :
elif [ "$c3_rc" -eq 1 ]; then
  # 命中数优先取扫描器自报；解析失败则至少计 1（不得把已判红的结果漏计）
  if [ "$c3_hits" -eq 0 ]; then c3_hits=1; fi
  hits=$((hits + c3_hits))
else
  echo "错误：判据 ③ 前置错误（扫描器退出码 ${c3_rc}）—— 判据未能真正执行" >&2
  echo "E6 用法/前置错误（退出码 2）" >&2
  exit 2
fi

echo "-------------------------------------------------------------------"
if [ "$hits" -eq 0 ]; then
  echo "E6 通过：前端零构建链、无直连字样、跨模块引用均命中实际导出（退出码 0）"
  exit 0
fi
echo "E6 失败：前端存在构建链残留 / 直连字样 / 未导出跨模块引用，共 $hits 处（退出码 1）"
exit 1
