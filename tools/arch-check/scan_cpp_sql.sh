#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# scan_cpp_sql.sh — E2 判据：C++ 侧不得出现 SQL / 数据库驱动痕迹
#
# 承载：docs/ARCHITECTURE.md §3.0.2（E2 手段）与 附录 B 红线 1。
#
# 用法：
#   tools/arch-check/scan_cpp_sql.sh [--root <dir>]
#     --root <dir>   待扫描根目录（默认 cpp）
#     -h, --help     显示本帮助
#   退出码语义（六脚本统一，`T-095`）：
#     0 = 通过；或**默认路径**不存在（组件未铺开，打印 SKIP）
#     1 = 命中（架构偏离）
#     2 = 用法/前置错误：**显式传入**的 `--root` 不存在，或参数错误（**不得静默放行**）
#
# 扫描范围：<root>/**/*.{cpp,cxx,cc,c,hpp,h,hh}
# 排除项  ：**/build*（含 build / build-lite / build-nonsystem）、**/_deps/**、**/third_party/**
#
# 判据（`T-095` 与 `E5` 同构化：**注释预处理** + **② 按「调用形态 / 目录」分域**）：
#
#   预处理：先剔 **C++ 注释**（`//` 与 `/* … */`）；**字符串/字符字面量内不视为注释**
#           （含 `\` 转义）。判的是**可执行代码**，注释里的 SQL 叙述不构成执行路径。
#           ⚠️ **与 `E3`/`E4` 故意不对称**：本脚本（同 `E5`）**保留字符串字面量** ——
#           `const char* s = "SELECT id FROM t_user";` 与 `"jdbc:dm://…"` 是**真外泄**，
#           必须命中。勿以「统一」为名改成剔字面量（会漏掉真外泄）。
#
#   ① SQL 语句形态（大小写不敏感；**适用全仓**）
#        SELECT  后跟 标识符 / 星号 / 引号
#        INSERT  INTO
#        UPDATE  后跟 标识符 再跟 SET
#        DELETE  FROM
#        CREATE  TABLE | INDEX | VIEW
#        DROP    TABLE | INDEX | VIEW
#        ALTER   TABLE
#   ②a 可执行连接创建形态（**调用形态**；**适用全仓**）—— DB 客户端**建立连接/初始化**的
#         C-API 调用形态，及 `DriverManager.getConnection(`（JDBC 调用形态的类比锚点）：
#        SQLConnect( / SQLConnectW( / SQLDriverConnect( / SQLDriverConnectW( / SQLAllocHandle(
#        mysql_real_connect( / mysql_init( / sqlite3_open( / sqlite3_open_v2(
#        PQconnectdb( / PQconnectStart( / dm_connect( / dpi_connect( / dm_login( / dpi_login(
#        DriverManager.getConnection(
#   ②b 内嵌连接串形态（**适用全仓**）
#        URL scheme  : jdbc: | odbc:
#   ②c 其它驱动/连接串关键字（**适用全仓**；实测不出现于散文）
#        sqldriver | connectstring | dm_jdbc | drivermanager
#
#   命中 ① / ② → 失败。
#
# 为何按「SQL 语句形态 + 调用形态」而非裸词判定：
#   旧判据 `\b(select|insert|update|delete|…)\b` 是**裸词**判定，与 E5 原缺陷同构：
#   C++ 侧任何名为 `insert` / `update` 的标识符（函数名、变量名、枚举值）都会被误杀，
#   判据按字面不可满足，且会诱导开发者为过扫描而改写命名。故收紧为 **SQL 语句形态 +
#   调用形态 + 连接串形态 + 少数裸关键字**：语句形态要求 SQL 关键字后跟其语法必需成分，
#   ②a 锚定**调用形态**（而非裸露的类名/库名），裸标识符不再命中。
#
# 为何 ② 分域（对齐 E5）：
#   `E5` 的 ② 拆 `②a`/`②a2`/`②b`/`②c` 的动因是「判据形态与被判对象**合法取值形状**重合」
#   （Java 侧：测试必须构造 `jdbc:dm://…` 配置值夹具）。**C++ 侧无此情形** —— C++ 组件
#   整体**不得触 DB**（§3.0.1），任何目录下的 DB 痕迹都是偏离，故：
#     · **不做生产/测试目录分域**（无「测试配置值」这类合法形态）；
#     · **无 `②a2`（驱动注册）对应支** —— C++ 侧无 `DriverManager.registerDriver(` 类比物。
#   ②a/②b/②c 的分域目的是「按**形态**收紧、避免裸词误伤」，非「按目录放行」。
#
# 自测（隔离夹具见回执；本脚本两向实测）：
#   T1 真 SQL 字面量      const char* s = "SELECT id FROM t_user";  → 命中（①）
#   T2 纯标识符 insert    void insert(int v);  int update = 1;      → 不命中
#   T3 注释里的 SQL       // 例如 SELECT id FROM t_user              → 不命中（预处理剔注释）
#   T4 内嵌连接串         const char* u = "jdbc:dm://127.0.0.1:5236";→ 命中（②b）
#   T5 DB 连接调用形态    mysql_real_connect(...) / sqlite3_open(...) → 命中（②a）
#   T6 构建目录内命中     build/…/"DELETE FROM t";                   → 不命中（已排除）
#   T7 显式 --root 不存在                                 → 退出码 2（非 SKIP）
# =============================================================================

set -u

ROOT="cpp"
ROOT_EXPLICIT=0

usage() {
  cat <<'USAGE'
用法：scan_cpp_sql.sh [--root <dir>]
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

# ---- C++ 注释剔除器（内联；**保留**字符串/字符字面量；每输入行 → 每输出行，行号不变）----
STRIP_AWK="$(mktemp)"
trap 'rm -f "$STRIP_AWK"' EXIT
cat > "$STRIP_AWK" <<'AWKEOF'
# strip C++ comments only (KEEP string/char literals), keep line numbers
BEGIN { inblk = 0 }
{
  line = $0; out = ""; i = 1; n = length(line); instr = 0; inchr = 0
  while (i <= n) {
    c = substr(line, i, 1); two = substr(line, i, 2)
    if (instr) {
      if (c == "\\") { out = out two; i += 2; continue }
      if (c == "\"") { instr = 0 }
      out = out c; i++; continue
    }
    if (inchr) {
      if (c == "\\") { out = out two; i += 2; continue }
      if (c == "'") { inchr = 0 }
      out = out c; i++; continue
    }
    if (inblk) { if (two == "*/") { inblk = 0; i += 2 } else { i++ }; continue }
    if (two == "/*") { inblk = 1; i += 2; continue }
    if (two == "//") { break }
    if (c == "\"") { instr = 1 } else if (c == "'") { inchr = 1 }
    out = out c; i++
  }
  print out
}
AWKEOF

# ---- ① 语句形态（大小写不敏感）--------------------------------------------
STMT="\\bselect[[:space:]]+[a-z_*\"'\`]"
STMT="$STMT|\\binsert[[:space:]]+into\\b"
STMT="$STMT|\\bupdate[[:space:]]+[a-z_][a-z0-9_.]*[[:space:]]+set\\b"
STMT="$STMT|\\bdelete[[:space:]]+from\\b"
STMT="$STMT|\\bcreate[[:space:]]+(table|index|view)\\b"
STMT="$STMT|\\bdrop[[:space:]]+(table|index|view)\\b"
STMT="$STMT|\\balter[[:space:]]+table\\b"

# ---- ②a 可执行连接创建形态（调用形态；适用全仓）----------------------------
DRV_CALL='(SQLConnect|SQLConnectW|SQLDriverConnect|SQLDriverConnectW|SQLAllocHandle|mysql_real_connect|mysql_init|sqlite3_open|sqlite3_open_v2|PQconnectdb|PQconnectStart|dm_connect|dpi_connect|dm_login|dpi_login)[[:space:]]*\('
DRV_CALL2='drivermanager[[:space:]]*\.[[:space:]]*getconnection[[:space:]]*\('

# ---- ②b 内嵌连接串形态（适用全仓）-----------------------------------------
DRV_EMBED='jdbc:|odbc:'

# ---- ②c 其它驱动/连接串关键字（适用全仓；实测不出现于散文）-----------------
DRV_KW='sqldriver|connectstring|dm_jdbc|drivermanager'

E2_RE="$STMT|$DRV_CALL|$DRV_CALL2|$DRV_EMBED|$DRV_KW"

mapfile -t FILES < <(find "$ROOT" -type f \
  \( -name '*.cpp' -o -name '*.cxx' -o -name '*.cc' -o -name '*.c' \
     -o -name '*.hpp' -o -name '*.h' -o -name '*.hh' \) \
  ! -path '*/build*' ! -path '*/_deps/*' ! -path '*/third_party/*' | LC_ALL=C sort)

echo "== E2 C++ SQL 外泄扫描（C++ 侧不得出现 SQL / DB 驱动）=="
echo "扫描范围  : $ROOT/**/*.{cpp,cxx,cc,c,hpp,h,hh}"
echo "排除项    : **/build*、**/_deps/**、**/third_party/**"
echo "待扫描文件: ${#FILES[@]} 个"
echo "预处理    : 剔除 C++ 注释（保留字符串/字符字面量——与 E3/E4 故意不对称）"
echo "判据 ①    : SQL 语句形态（SELECT/INSERT INTO/UPDATE…SET/DELETE FROM/CREATE|DROP|ALTER TABLE）"
echo "判据 ②a   : DB 连接创建调用形态（SQLConnect( / mysql_real_connect( / sqlite3_open( / PQconnectdb( / dm_connect( / DriverManager.getConnection( …）"
echo "判据 ②b   : 内嵌连接串形态（jdbc: / odbc:）"
echo "判据 ②c   : 其它驱动/连接串关键字（sqldriver / connectstring / dm_jdbc / drivermanager）"
echo "-------------------------------------------------------------------"

hits=0
for f in ${FILES[@]+"${FILES[@]}"}; do
  # 预处理：剔注释 → 按 ①/② 匹配；grep -n 保留原行号
  nums=$(awk -f "$STRIP_AWK" "$f" | grep -nEi "$E2_RE" | cut -d: -f1)
  [ -n "$nums" ] || continue
  for n in $nums; do
    printf '%s:%s: %s\n' "$f" "$n" "$(sed -n "${n}p" "$f" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
    hits=$((hits + 1))
  done
done

echo "-------------------------------------------------------------------"
if [ "$hits" -eq 0 ]; then
  echo "E2 通过：C++ 侧未发现 SQL / DB 驱动痕迹（退出码 0）"
  exit 0
fi
echo "E2 失败：C++ 侧发现 $hits 处 SQL / DB 驱动痕迹（退出码 1）"
exit 1
