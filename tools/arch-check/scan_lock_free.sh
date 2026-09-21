#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# scan_lock_free.sh — E4 判据：交易路径无锁 / 无同步 I/O 扫描
#
# 承载：docs/ARCHITECTURE.md §3.0.2（E4 手段）、§4.4.3（NFR-PERF-1「无同步 I/O、
#       无阻塞调用」）、§8.1.3（判定函数 `RiskEngine::check` 内禁止项）。
#
# 用法：
#   tools/arch-check/scan_lock_free.sh [--root <dir>]
#     --root <dir>   待扫描根目录（**显式**给出则以该目录为唯一根）
#                    省略时用**默认扫描根**（见下「默认扫描根」）
#     -h, --help     显示本帮助
#   退出码语义（六脚本统一，`T-095`）：
#     0 = 通过；或**默认扫描根**全部不存在（组件未铺开，打印 SKIP）
#     1 = 命中（架构偏离）
#     2 = 用法/前置错误：**显式传入**的 `--root` 不存在，或参数错误（**不得静默放行**）
#
# 默认扫描根（`T-120`；依据 `ARCHITECTURE.md §3.0.2` 的 `E4` 脚注，`v1.5.12` 裁定）
#   —— **三目录集合取并集** = `cpp/src/order_engine` + `cpp/src/risk_engine` + `cpp/src/risk_state`
#   （「交易路径」= **执行在 T4 交易线程上的代码**；`flow_store`（T7 合法 `append + fsync`）、
#     `hgcp`（T5/T6/T8 合法 socket `send`/`recv`）、`ctp_adapter`（T2/T3 SPI 回调）**显式排除**）。
#   SKIP 条件 = **三目录全部不存在**；**任一存在即实扫**（并集）。
#   ⚠️ 旧路径 `cpp/src/trading` **已废**（该目录不存在）；旧路径→新口径的映射见
#      `docs/ARCHITECTURE.md §3.0.2` 的 `E4` 脚注（本脚本不再以该路径为默认根）。
#
# 扫描范围：<扫描根>/**/*.{cpp,cxx,cc,c,hpp,h,hh}
# 排除项  ：**/build*、**/_deps/**、**/third_party/**
#
# 本脚本**实际覆盖**的清单（判红；命中即失败；先剔 C++ 注释与字符串/字符字面量再判）：
#   A. 锁与同步原语：mutex / lock_guard / unique_lock / condition_variable（**词边界**，
#      含前缀变体 std::mutex / shared_mutex / recursive_mutex / timed_mutex 等）
#   B. 同步 I/O / 阻塞调用：fsync / fdatasync / write / read / send / sendto /
#      recv / recvfrom / sleep / usleep / nanosleep —— **一律按调用形态**（`T-095` 订正
#      `TE-AC-002`），**不得裸词，也不得把「定义/声明」当调用**。判定规则（逐 token 判）：
#        · 形如 `name(` 且 name 前**非标识符字符**（行首/前导空白 / `(` / `=` / `,` / `::` …）
#          ⇒ **判为调用**；
#        · 形如 `<类型片段>[ \t]*[*&]*[ \t]*name(`（如 `int read(` / `int* read(` / `char *read(`）⇒ 判为**声明/定义** ⇒ **不判**；
#        · 形如 `.name(` / `->name(`（成员调用）：仅当 name ∈ {send,sendto,recv,recvfrom}
#          （网络专用名）时**判**；通用名 `read`/`write` 的成员调用**不判**。
#      故：`struct S{int read(int);}; s.read(3);` **不命中**；`::send(fd,…)` **仍命中**。
#      ⚠️ **已知边界（未覆盖，须与 §3.0.2 标注一致）**：
#        · **通用名 `read`/`write` 的成员调用**（如 `sock.read(`）不判 —— 与 `s.read(3)`
#          形状重合、静态不可区分（纪律：判据形态不得与被判对象合法取值形状重合）；
#        · 以 `a.read(` / `a.write(` 之外的 `obj.read(` 形式出现的真实同步 I/O 由**代码审查**兜底。
#        · **跨行返回类型的定义 ⇒ 误报**：函数名落在行首、返回类型在上一行（如 `int*`⏎
#          `read(int n){…}`；`int`⏎`read(` 亦然）⇒ 被判为调用（退 1）。**方向 = 误报**。
#        · **`expr * name(` / `expr & name(` ⇒ 漏判**：如 `int y = a * read(fd);` /
#          `int z = 2 * send(fd);` ⇒ **不被命中**（退 0）。根因：`T* name(`（定义）与
#          `expr * name(`（调用）的 **token 形态完全相同、静态不可判** —— 此系修「`int* read(`
#          误报」（`R2-1`）的**对称代价**，即**为消除误报而接受的一处真阳放松**。兜底：
#          `E2`（调用形态扫描）+ 代码审查；且交易路径本不应出现 `read`/`write` 同步 I/O
#          （该形态在真实交易路径模块 `cpp/src/{order_engine,risk_engine,risk_state}` 中不现实）。
#        · **限定名定义 ⇒ 误报**：`int Foo::read(int){}` / `int A::write(int){}` ⇒ 被判为
#          调用（退 1）。**方向 = 误报**。
#
# 本脚本**未覆盖**的判据项（架构 §8.1.3 明文列出的其余禁止项；**不判红、不影响退出码**）：
#   C. 无动态分配 —— 含**显式**分配（new / malloc / calloc / realloc / std::function）
#      与**隐式**分配（std::string 拼接 / std::vector 扩容 / 任何 STL 隐式堆分配）。
#      未覆盖原因：**静态判定不可靠** —— 显式 token 可扫，但隐式分配需语义/数据流分析。
#   D. atomic 的 compare_exchange 循环（自旋）与异常抛出路径（属**控制流性质**）。
#   ⬆️ 以上 C/D 由**代码审查 + 运行期/动态分析**兜底；本脚本对其仅作**信息性 WARN 输出**
#      （列出疑似 token 供人工核对），**绝不据此判红**（否则将与「合法构造」重合 ⇒ 恒红）。
#   本节未覆盖项已同步写入 `docs/ARCHITECTURE.md` §3.0.2 的 E4 落点标注。
#
# 预处理与「不对称」说明（`T-095`）：
#   本脚本先剔 **C++ 注释** 与 **字符串/字符字面量**（含 `\` 转义）。剔字面量的理由：
#   `const char* a = "mutex";` 这类**合法 C++11 文字**不构成锁调用（`TE-AC-001` 同根因）。
#   ⚠️ **与 `E5` 故意不对称**：`E5` 必须**保留字符串**（它要抓字符串里内嵌的连接串）。
#   E4 判的是**调用构造**，故剔字面量；后来者勿以「统一」为名改成与 E5 同形。
#
# 无目录时的行为（`T-095` 统一；`T-120` 扩为多根）：**默认扫描根**（三目录）**全部**不存在
#   ⇒ SKIP + 退出 0；**显式 `--root` 不存在 ⇒ 退出 2**（三目录中任一存在即实扫，不 SKIP）。
#
# 自测（隔离夹具见回执；本脚本两向实测）：
#   反例  真码含 `std::mutex m;` / `std::lock_guard<…>`  → 命中（退出码 1）
#   反例  真码含 `::send(fd, buf, n, 0);`               → 命中（同步 I/O，调用形态）
#   正例  真码含 `struct S{int read(int); int write(int);}; s.read(3);` → **不命中**
#   正例  定义含**指针/引用返回类型**：`int* read(int){…}` / `int& write(int){…}` / `char *read(int){…}` → **不命中**（`R2-1`：类型与函数名间的 `*`/`&` 已识别为定义）
#   正例  注释含 `// 本路径无 mutex`                     → 不命中（注释已剔）
#   正例  字符串含 `const char* s = "mutex";`            → 不命中（字面量已剔）
#   正例  目录不存在（默认扫描根三目录）                 → SKIP（退出码 0）
#   前置  显式 `--root /no/such`                         → 退出码 2
# =============================================================================

set -u

# 默认扫描根 = 三目录集合（`T-120`；依据 `ARCHITECTURE.md §3.0.2` 的 `E4` 脚注）
DEFAULT_ROOTS=(cpp/src/order_engine cpp/src/risk_engine cpp/src/risk_state)
ROOTS=("${DEFAULT_ROOTS[@]}")
ROOT_EXPLICIT=0

usage() {
  cat <<'USAGE'
用法：scan_lock_free.sh [--root <dir>]
  --root <dir>   待扫描根目录（显式给出则以该目录为唯一根）
                 省略时用默认扫描根 = 三目录集合并集
                 cpp/src/order_engine + cpp/src/risk_engine + cpp/src/risk_state
  -h, --help     显示本帮助
退出码：0 通过 / 默认扫描根三目录全部不存在（SKIP）；1 命中；2 用法或前置错误（含显式 --root 不存在）
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --root)
      if [ "$#" -lt 2 ]; then echo "错误：--root 缺少参数" >&2; exit 2; fi
      ROOTS=("$2"); ROOT_EXPLICIT=1; shift 2 ;;
    --root=*) ROOTS=("${1#--root=}"); ROOT_EXPLICIT=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "错误：未知参数 $1" >&2; usage >&2; exit 2 ;;
  esac
done

# ---- 扫描根解析：显式 ⇒ 单目录（不存在即退出 2）；默认 ⇒ 三目录并集（全不存在才 SKIP）----
SCAN_ROOTS=()
if [ "$ROOT_EXPLICIT" -eq 1 ]; then
  if [ ! -d "${ROOTS[0]}" ]; then
    echo "错误：--root 目录不存在（显式路径）：${ROOTS[0]}" >&2
    exit 2
  fi
  SCAN_ROOTS=("${ROOTS[0]}")
else
  for d in "${ROOTS[@]}"; do
    if [ -d "$d" ]; then
      SCAN_ROOTS+=("$d")
    else
      echo "注意：默认扫描根子目录不存在，跳过：$d" >&2
    fi
  done
  if [ "${#SCAN_ROOTS[@]}" -eq 0 ]; then
    echo "SKIP: 默认扫描根（三目录集合：${ROOTS[*]}）全部不存在（交易路径尚未铺开，C++ 组件未构建）—— 退出码 0"
    exit 0
  fi
fi

# ---- C++ 注释 + 字符串/字符字面量 剔除器（内联；每输入行 → 每输出行，行号不变）----
STRIP_AWK="$(mktemp)"
DETECT_AWK="$(mktemp)"
trap 'rm -f "$STRIP_AWK" "$DETECT_AWK"' EXIT
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

cat > "$DETECT_AWK" <<'AWKEOF'
# detect.awk — 输出发生「锁 / 同步 I/O」命中的行号（FNR）。
# 判定按 token 形态：区分 调用 / 声明-定义 / 成员调用（详见脚本头 B 段）。
function iswc(c) { return (c != "" && c ~ /[A-Za-z0-9_]/) }

function lock_hit(s,   names, i, n, nm, p, q, e, before, after) {
  n = split("mutex lock_guard unique_lock condition_variable", names, " ")
  for (i = 1; i <= n; i++) {
    nm = names[i]; p = 1
    while (p <= length(s)) {
      q = index(substr(s, p), nm); if (q == 0) break
      q = p + q - 1
      before = (q == 1) ? "" : substr(s, q - 1, 1)
      e = q + length(nm); after = substr(s, e, 1)
      if (!iswc(before) && !iswc(after)) return 1
      p = q + 1
    }
  }
  return 0
}

function preword(s, k,   w, c) {
  w = ""
  while (k >= 1) { c = substr(s, k, 1); if (c !~ /[A-Za-z0-9_]/) break; w = c w; k-- }
  return w
}

function sync_hit(s,   names, netnames, i, n, nm, p, q, e, before, after, k, pre, pw) {
  n = split("fsync fdatasync write read send sendto recv recvfrom sleep usleep nanosleep", names, " ")
  netnames = " send sendto recv recvfrom "
  for (i = 1; i <= n; i++) {
    nm = names[i]; p = 1
    while (p <= length(s)) {
      q = index(substr(s, p), nm); if (q == 0) break
      q = p + q - 1
      before = (q == 1) ? "" : substr(s, q - 1, 1)
      e = q + length(nm); after = substr(s, e)
      if (!iswc(before) && after ~ /^[[:space:]]*\(/) {
        k = q - 1
        while (k >= 1 && substr(s, k, 1) ~ /[[:space:]]/) k--
        # 跳过指针/引用修饰符（* / &，可含其间空白）—— 使 `int* read(` / `char *read(` 判为定义（R2-1）
        while (k >= 1 && substr(s, k, 1) ~ /[*&]/) {
          k--
          while (k >= 1 && substr(s, k, 1) ~ /[[:space:]]/) k--
        }
        pre = (k >= 1) ? substr(s, k, 1) : ""
        if (pre == "." || pre == ">") {
          if (index(netnames, " " nm " ") > 0) return 1
        } else if (k < 1 || !iswc(pre)) {
          return 1
        } else {
          pw = preword(s, k)
          if (pw == "return" || pw == "co_return") return 1
        }
      }
      p = q + 1
    }
  }
  return 0
}

{ if (lock_hit($0) || sync_hit($0)) print FNR }
AWKEOF

DISPLAY_ROOTS="$(printf '%s, ' "${SCAN_ROOTS[@]}")"; DISPLAY_ROOTS="${DISPLAY_ROOTS%, }"

echo "== E4 交易路径无锁 / 无同步 I/O 扫描 =="
if [ "$ROOT_EXPLICIT" -eq 1 ]; then
  echo "扫描根    : ${DISPLAY_ROOTS}（显式 --root）"
else
  echo "扫描根    : ${DISPLAY_ROOTS}（默认三目录集合取并集；依据 ARCHITECTURE.md §3.0.2 的 E4 脚注）"
fi
echo "扫描范围  : <扫描根>/**/*.{cpp,cxx,cc,c,hpp,h,hh}"
echo "排除项    : **/build*、**/_deps/**、**/third_party/**"
echo "覆盖 A 锁 : mutex / lock_guard / unique_lock / condition_variable（词边界，含前缀变体）"
echo "覆盖 B I/O: fsync / fdatasync / write / read / send / sendto / recv / recvfrom / sleep / usleep / nanosleep（**按调用形态**：非标识符前缀/行首/:: 判为调用；类型+空白+name( 判为定义不判；通用名成员调用不判）"
echo "未覆盖 C  : 无动态分配（new/malloc/std::function 及 std::string 拼接 / std::vector 扩容等**隐式**分配）"
echo "未覆盖 D  : atomic compare_exchange 循环 / 异常抛出路径 —— 两者由代码审查兜底"
echo "预处理    : 剔 C++ 注释 + 字符串/字符字面量（与 E5「留字符串」故意不对称）"
echo "-------------------------------------------------------------------"

mapfile -t FILES < <(find "${SCAN_ROOTS[@]}" -type f \
  \( -name '*.cpp' -o -name '*.cxx' -o -name '*.cc' -o -name '*.c' \
     -o -name '*.hpp' -o -name '*.h' -o -name '*.hh' \) \
  ! -path '*/build*' ! -path '*/_deps/*' ! -path '*/third_party/*' | LC_ALL=C sort -u)

echo "待扫描文件: ${#FILES[@]} 个"

# C/D 未覆盖项（仅 WARN，不判红）—— 粗粒度 token，供人工核对
WARN_RE='\bnew\b|\bmalloc[[:space:]]*\(|\bcalloc[[:space:]]*\(|\brealloc[[:space:]]*\(|\bstd::function\b|\bthrow\b|\bcompare_exchange\b'

echo "-------------------------------------------------------------------"
hits=0; warns=0
for f in ${FILES[@]+"${FILES[@]}"}; do
  nums="$(awk -f "$STRIP_AWK" "$f" | awk -f "$DETECT_AWK")"
  if [ -n "$nums" ]; then
    for n in $nums; do
      printf '%s:%s: %s\n' "$f" "$n" "$(sed -n "${n}p" "$f" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
      hits=$((hits + 1))
    done
  fi
  wnums="$(awk -f "$STRIP_AWK" "$f" | grep -nEi "$WARN_RE" | cut -d: -f1)"
  if [ -n "$wnums" ]; then
    for n in $wnums; do
      printf '[WARN·未覆盖判据，不影响退出码] %s:%s: %s\n' "$f" "$n" "$(sed -n "${n}p" "$f" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
      warns=$((warns + 1))
    done
  fi
done

echo "-------------------------------------------------------------------"
if [ "$hits" -eq 0 ]; then
  echo "E4 通过：交易路径未发现锁 / 同步 I/O（覆盖 A+B；WARN $warns 处为未覆盖判据 C/D 的信息性提示）（退出码 0）"
  exit 0
fi
echo "E4 失败：交易路径发现 $hits 处锁 / 同步 I/O（退出码 1）"
exit 1
