#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# scan_cpp11.sh — E3 判据：C++ 侧 C++11 合规（编译参数固定 + 禁用特性扫描）
#
# 承载：docs/ARCHITECTURE.md §3.0.2（E3 手段）、§1.2（C++11 硬边界：CMAKE_CXX_STANDARD 11
#       + -Wall -Wextra -Werror）、§9 RSK-01/RSK-02。
#
# 用法：
#   tools/arch-check/scan_cpp11.sh [--root <dir>]
#     --root <dir>   待扫描根目录（默认 cpp）
#     -h, --help     显示本帮助
#   退出码语义（六脚本统一，`T-095`）：
#     0 = 通过；或**默认路径**不存在（组件未铺开，打印 SKIP）
#     1 = 命中（架构偏离）
#     2 = 用法/前置错误：**显式传入**的 `--root` 不存在，或参数错误
#         —— 显式路径不存在 ⇒ 2（**不得静默放行**，防「路径拼错」被当 SKIP）。
#
# 扫描范围：<root>/**/*.{cpp,cxx,cc,c,hpp,h,hh}
# 排除项  ：**/build*（含 build / build-lite / build-nonsystem）、**/_deps/**、**/third_party/**
#
# 判据（两段；任一段失败 → 失败）：
#   ① 编译参数（从 <root> 下 CMakeLists.txt 取，先剔 CMake `#` 注释）：
#        · 标准：须声明 `-std=c++11` **或** `CMAKE_CXX_STANDARD 11` / `CXX_STANDARD 11`；
#        · 且**不得**出现冲突版本（`-std=c++1{4,7}` / `CMAKE_CXX_STANDARD 1{4,7,8,9}` 等）；
#        · 警告：须同时含 `-Wall`、`-Wextra`、`-Werror`。
#        （缺失任一项 → 报红。若 <root> 下无任何 CMakeLists.txt，视为 C++ 组件未铺开 → 该段记未覆盖。）
#   ② 禁用特性关键字（**词边界**；**先剔 C++ 注释与字符串/字符字面量**后判定；命中即失败）：
#        make_unique | std::optional（含 `optional<` 模板形态）| string_view
#        | if constexpr | std::variant（含 `variant<` 模板形态）| 结构化绑定（auto[a,b]）
#
# 为何必须剔「注释 + 字符串/字符字面量」（本项目血泪纪律）：
#   (a) 注释：本仓库注释里**大量**出现禁用特性的叙述字样（如「禁用 std::make_unique」）。
#       若对原文判定，注释本身即命中 ⇒ 判据**按字面不可满足** ⇒ 诱导「改写注释迎合扫描」
#       （与 E5 的 `ParamDispatchServiceImpl.java:63` javadoc 误报**同源**）。
#   (b) 字符串/字符字面量：`const char* a = "std::optional";` 是**合法 C++11 代码**
#       （它只是一段文字，不构成 C++17 特性使用）。若判据命中它，则「判据形态与被判对象
#       合法取值形状重合」⇒ 恒红。故 `T-095`（缺陷 `TE-AC-001` 同根因）把判定面扩为
#       **剔注释 + 剔字符串/字符字面量**。
#   ⚠️ **与 `E5` 的不对称（不得「统一」）**：`E5` **必须扫字符串字面量** —— 它要抓的正是
#      字符串里内嵌的 SQL / 连接串（`E5` 的 `②b`：`String u = "jdbc:dm://…"` 是**真外泄**）。
#       而 `E3`/`E4` 判的是**语言特性/锁调用**这类「代码构造」，字符串里出现同名**文字**
#       不构成使用。二者判定面**故意不同**，后来者勿以「一致性」为名统一。
#
# 预处理实现（**内联 awk**，因写入域限定不得新增第 5 个文件）：
#   剔除 `//` 行注释 与 `/* … */` 块注释；**并剔除字符串 `"…"` 与字符 `'…'` 字面量的内容**
#   （含 `\` 转义）；跨行块注释状态保持；每输入行 → 每输出行（**行号不变**，命中可精确定位）。
#   **如实声明的漏判面**（`T-095` 订正 `TE-AC-004`；**不得**再作「零漏报」表述）：
#     · **行尾 `\` 续行**未做拼接 —— `std::make_` + 换行 + `unique<int>()`、`if ` + 换行 +
#       `constexpr` 这类型态**会被漏判**（本脚本对「续行拼接形态」**未覆盖**）；
#     · **多行原始字符串 `R"…(…)"`** 未特殊处理 —— 简单同行的 `R"(std::optional)"` 因
#       其形如 `"(…)"` 会被本预处理器当普通字符串剔除（⇒ 退 0，符合预期），但**多行 / 自定义
#       定界符的原始字符串未覆盖**。
#     （`TE-AC-004` 订正：如实声明上述漏判面，**不得再作「零漏报」表述**。）
#
# 自测（隔离夹具见回执；本脚本两向实测）：
#   反例  真码含 `std::make_unique<T>()`            → 命中（退出码 1）
#   反例  真码含 `std::string_view` / `if constexpr` / `auto [a,b]` → 命中
#   反例  CMakeLists 缺 `-Werror` 或含冲突标准       → 命中（①）
#   正例  **注释**含 `// 禁用 std::make_unique`      → 不命中（注释已剔）
#   正例  **字符串**含 `const char* a="std::optional";` → 不命中（字面量已剔）
#   正例  空目录（无源码 / 无 CMakeLists）           → SKIP（退出码 0）
#   前置  显式 `--root /no/such`                     → 退出码 2（非 SKIP）
# =============================================================================

set -u

ROOT="cpp"
ROOT_EXPLICIT=0
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<'USAGE'
用法：scan_cpp11.sh [--root <dir>]
  --root <dir>   待扫描根目录，默认 cpp
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

if [ ! -d "$ROOT" ]; then
  if [ "$ROOT_EXPLICIT" -eq 1 ]; then
    echo "错误：--root 目录不存在（显式路径）：$ROOT" >&2
    exit 2
  fi
  echo "SKIP: 默认路径 $ROOT 不存在（C++ 组件未铺开）—— 退出码 0"
  exit 0
fi

# ---- C++ 注释 + 字符串/字符字面量 剔除器（内联；每输入行 → 每输出行，行号不变）----
STRIP_AWK="$(mktemp)"
trap 'rm -f "$STRIP_AWK"' EXIT
cat > "$STRIP_AWK" <<'AWKEOF'
# strip C++ comments AND string/char literal contents, keep line numbers
BEGIN { inblk = 0 }
{
  line = $0; out = ""; i = 1; n = length(line); instr = 0; inchr = 0
  while (i <= n) {
    c = substr(line, i, 1); two = substr(line, i, 2)
    if (instr) { if (c == "\\") { i += 2; continue } if (c == "\"") { instr = 0 } i++; continue }
    if (inchr) { if (c == "\\") { i += 2; continue } if (c == "'") { inchr = 0 } i++; continue }
    if (inblk) { if (two == "*/") { inblk = 0; i += 2 } else { i++ }; continue }
    if (two == "/*") { inblk = 1; i += 2; continue }
    if (two == "//") { break }
    if (c == "\"") { instr = 1; i++; continue }
    if (c == "'") { inchr = 1; i++; continue }
    out = out c; i++
  }
  print out
}
AWKEOF

# ---- ② 禁用特性关键字（词边界；模板形态一并覆盖）---------------------------
BANNED='\bmake_unique\b|\bstd::optional\b|\boptional[[:space:]]*<|\bstring_view\b|\bif[[:space:]]+constexpr\b|\bstd::variant\b|\bvariant[[:space:]]*<|\bauto[[:space:]]*(\&\&?[[:space:]]*)?\['

mapfile -t FILES < <(find "$ROOT" -type f \
  \( -name '*.cpp' -o -name '*.cxx' -o -name '*.cc' -o -name '*.c' \
     -o -name '*.hpp' -o -name '*.h' -o -name '*.hh' \) \
  ! -path '*/build*' ! -path '*/_deps/*' ! -path '*/third_party/*' | LC_ALL=C sort)

mapfile -t CMAKES < <(find "$ROOT" -type f -name 'CMakeLists.txt' \
  ! -path '*/build*' ! -path '*/_deps/*' ! -path '*/third_party/*' | LC_ALL=C sort)

echo "== E3 C++11 合规扫描（编译参数固定 + 禁用特性）=="
echo "扫描范围  : $ROOT/**/*.{cpp,cxx,cc,c,hpp,h,hh}"
echo "排除项    : **/build*、**/_deps/**、**/third_party/**"
echo "待扫描源文件: ${#FILES[@]} 个"
echo "CMakeLists  : ${#CMAKES[@]} 个"
echo "判据 ①    : 编译参数固定 -std=c++11（或 CMAKE_CXX_STANDARD 11）+ -Wall -Wextra -Werror，无冲突标准"
echo "判据 ②    : 禁用特性（词边界 + 剔注释与字符串/字符字面量）：make_unique / std::optional / string_view / if constexpr / std::variant / 结构化绑定"
echo "预处理    : 剔 C++ 注释 + 字符串/字符字面量（与 E5「留字符串」故意不对称：E3 判代码构造，非字符串文字）"
echo "-------------------------------------------------------------------"

if [ "${#FILES[@]}" -eq 0 ] && [ "${#CMAKES[@]}" -eq 0 ]; then
  echo "SKIP: $ROOT 下无 C++ 源码与 CMakeLists（C++ 组件未铺开）—— 退出码 0"
  exit 0
fi

fail=0

# ---- ① 编译参数 ------------------------------------------------------------
std_ok=0; conf=0; w_wall=0; w_extra=0; w_error=0
for cm in ${CMAKES[@]+"${CMAKES[@]}"}; do
  body="$(sed -E 's/#.*$//' "$cm")"
  printf '%s\n' "$body" | grep -qE -- '-std=c\+\+11|CMAKE_CXX_STANDARD[[:space:]]+11|CXX_STANDARD[[:space:]]+11' && std_ok=1
  printf '%s\n' "$body" | grep -qE -- '-std=c\+\+(1[4789]|2[03])|CMAKE_CXX_STANDARD[[:space:]]+(1[4789]|2[03])|CXX_STANDARD[[:space:]]+(1[4789]|2[03])' && conf=1
  printf '%s\n' "$body" | grep -q -- '-Wall'   && w_wall=1
  printf '%s\n' "$body" | grep -q -- '-Wextra' && w_extra=1
  printf '%s\n' "$body" | grep -q -- '-Werror' && w_error=1
done

echo "判据 ① 实测："
echo "  标准声明   -std=c++11 / CMAKE_CXX_STANDARD 11 : $([ "$std_ok" -eq 1 ] && echo 存在 || echo 缺失)"
echo "  冲突标准   C++14/17/20/23                     : $([ "$conf"   -eq 1 ] && echo 存在 || echo 无)"
echo "  警告参数   -Wall=$w_wall  -Wextra=$w_extra  -Werror=$w_error"
if [ "${#CMAKES[@]}" -eq 0 ]; then
  echo "  ⚠️ 无 CMakeLists.txt 可查 —— 编译参数段记为未覆盖（不据此判红；源码段照常判）"
elif [ "$std_ok" -eq 0 ] || [ "$conf" -eq 1 ] || [ "$w_wall" -eq 0 ] || [ "$w_extra" -eq 0 ] || [ "$w_error" -eq 0 ]; then
  echo "判据 ① 失败：编译参数不符（须固定 C++11 + -Wall -Wextra -Werror 且无冲突标准）"
  fail=1
else
  echo "判据 ① 通过：编译参数符合 C++11 硬边界"
fi

# ---- ② 禁用特性 ------------------------------------------------------------
echo "判据 ② 实测（剔注释与字面量后的命中）："
hits=0
for f in ${FILES[@]+"${FILES[@]}"}; do
  nums="$(awk -f "$STRIP_AWK" "$f" | grep -nEi "$BANNED" | cut -d: -f1)"
  [ -n "$nums" ] || continue
  for n in $nums; do
    printf '%s:%s: %s\n' "$f" "$n" "$(sed -n "${n}p" "$f" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
    hits=$((hits + 1))
  done
done
if [ "$hits" -gt 0 ]; then
  echo "判据 ② 失败：发现 $hits 处 C++11 之外的禁用特性"
  fail=1
else
  echo "判据 ② 通过：未发现禁用特性（$(( ${#FILES[@]} )) 个源文件）"
fi

echo "-------------------------------------------------------------------"
if [ "$fail" -eq 0 ]; then
  echo "E3 通过：编译参数与源码均符合 C++11（退出码 0）"
  exit 0
fi
echo "E3 失败：C++11 合规存在偏离（退出码 1）"
exit 1
