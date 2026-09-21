#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# scan_java_sql.sh — E5 判据：Java「dao 之外不得出现 SQL」
#
# 承载：docs/ARCHITECTURE.md §3.0.2（E5 手段）与 附录 B 红线 2。
#
# 用法：
#   tools/arch-check/scan_java_sql.sh [--root <dir>]
#     --root <dir>   待扫描根目录（默认 java）
#     -h, --help     显示帮助
#   退出码语义（六脚本统一，`T-095`）：0 = 通过（无命中）/**默认路径**不存在（SKIP）；
#     1 = 命中（架构偏离）；2 = 用法/前置错误：**显式传入**的 `--root` 不存在，或参数错误
#     （**不得静默放行**，防「路径拼错」被当 SKIP）。
#
# 扫描范围：<root>/**/*.java
# 排除项  ：**/dao/**（含 **/dao/sql/**）；main 与 test 同规则。
#
# 判据（v1.5.6 修订：预处理剔注释 + ② 按"可执行形态 / 配置值形态"二分且按目录分域）：
#
#   预处理：先剔 Java 注释（strip_java_comments.awk，字符串字面量内不视为注释）
#           —— 判据判的是**可执行代码**；注释/javadoc 里的 SQL 叙述不构成执行路径。
#
#   ① SQL 语句形态（大小写不敏感；**适用全仓**，dao 之外）
#        SELECT  后跟 标识符 / 星号 / 引号
#        INSERT  INTO
#        UPDATE  后跟 标识符 再跟 SET
#        DELETE  FROM
#        CREATE  TABLE | INDEX | VIEW
#        DROP    TABLE | INDEX
#        ALTER   TABLE
#   ②a 可执行连接创建形态（**适用全仓**）
#        DriverManager.getConnection(                ← 创建物理连接的唯一调用形态
#        Class.forName( 且字面量内含 jdbc           ← 加载 JDBC 驱动类（如
#                                                      Class.forName("dm.jdbc.driver.DmDriver")）
#   ②a2 驱动注册形态（**仅适用非 src/test/** 的生产代码**）
#        DriverManager.registerDriver(
#   ②b 内嵌连接串形态（**仅适用非 src/test/** 的生产代码**）
#        URL scheme  : jdbc: | odbc:
#        常量/字段名 : jdbcUrl | jdbc_url
#   ②c 其它驱动/连接串关键字（**适用全仓**；实测不出现于散文）
#        dm_jdbc | sqldriver | connectstring
#   ③ 显式豁免 DAO 访问器调用形态
#        .(insert|updateById|deleteById|findById|findPage|count|exists)(
#
#   命中 ① / ② 且不属于 ③ → 失败。
#
# 为何 ① 先剔注释、但**不**剔字符串字面量：
#   `String sql = "SELECT id FROM t_user";` 是**真外泄**（可执行路径），必须命中；
#   而 javadoc 里描述契约的 SQL 片段（`ParamDispatchServiceImpl.java:63`）不执行，
#   命中它属**误报**，且会让判据"按字面不可满足"而诱导改写表述。
#
# 为何 ① 仍适用全仓、也**不**按肉眼"大小写"放行：
#   `BaseDao` 的冻结契约方法名即 insert / updateById / deleteById（见 java/CONTRACTS.md）。
#   裸词判据会让任何一次 DAO 写入调用变红（判据按字面不可满足），
#   故按**语句形态**（SELECT 后跟标识符等）判定，并以 ③ 显式豁免访问器调用形态。
#
# 为何 ② 拆成 ②a / ②b / ②c 且 ②b **仅适用生产代码**：
#   `hg.db.url`（`CFG-31`）的**合法取值形状**就是 `jdbc:dm://…`。装配类测试**必须**
#   构造"已配置库"分支的夹具（本轮 4 处：BootstrapAssemblyTest:103、
#   SensitiveLoggingAndConfigTest:160/167/174），故 ② 若能命中配置值形状，
#   **只要有人测装配判据必红**——与"不得以改写测试迎合扫描"直接冲突。
#   故按**目录**分域：生产代码内嵌连接串才是偏离（配置须经 hg.properties / 环境变量，
#   §11 `CFG-31` / `NFR-SEC-1`）；测试构造配置值不构成"绕过 DAO 直连"。
#   而 ②a（`DriverManager.getConnection(` / 驱动类加载）是**可执行连接创建**的唯一调用形态，
#   出现在任何文件（含 test）都说明代码在自建物理连接 ⇒ **不放宽、适用全仓**；
#   实测全仓 `DriverManager.getConnection(` 仅 `dao/pool/JdbcConnectionFactory.java` 命中
#   （该目录已被排除）⇒ 零误报。**判据锚定"调用形态"而非类名/导入**：`import
#   java.sql.DriverManager;` 与 `DriverManager.registerDriver(` 均不匹配 ②a ——
#   后者（test 目录的假驱动夹具，见 `FakeJdbcDriver`，NFR-TEST-2 内存假件）
#   在**生产**代码里才是直连前置，故单列为 ②a2 并**仅适用非 src/test/**。
#   ⚠️ 该收紧的直接动因（实测）：v1.5.6 判据草案曾以裸 `drivermanager` 判 ②a，
#      `FakeJdbcDriver.java`（test 夹具，仅 `registerDriver`）落地后即产生 2 处误报
#      —— 与"不得迫使合法测试夹具改写"冲突，故锚点由"类名"改为"调用形态"。
#
# 为何 ②c 保持裸关键字：
#   dm_jdbc / sqldriver / connectstring 本就不出现在散文里（实测 java/** 全仓 **0 命中**）。
#
# 自测（本脚本 + strip_java_comments.awk 实测）：
#   T1  真 SQL 字面量     String sql = "SELECT id FROM t_user";          → 命中（①）
#   T2  DAO 访问器调用    configItemDao.insert(item, operatorId)         → 不命中（③）
#   T3  枚举字面量        "UPDATE"（变更类型取值）                        → 不命中
#   T4  JDBC 连接创建     DriverManager.getConnection(url, props)        → 命中（②a）
#   T5  驱动类加载        Class.forName("dm.jdbc.driver.DmDriver")       → 命中（②a）
#   T6  生产内嵌连接串    String u = "jdbc:dm://10.0.0.1:5236/HG";       → 命中（②b，生产）
#   T7  测试配置值夹具    System.setProperty(KEY_DB_URL, "jdbc:dm://…")  → 不命中（②b 仅生产）
#   T8  注释里的 SQL       /** … SELECT MAX(x) FROM t … */                → 不命中（预处理剔注释）
#   T9  注释里的连接串     // 例如 jdbc:dm://127.0.0.1:5236/HG            → 不命中（预处理剔注释）
#   T10 测试内真外泄       test 目录里 DriverManager.getConnection(…)     → **命中**（②a 全仓）
#   T11 生产驱动注册       DriverManager.registerDriver(new X())          → 命中（②a2，生产）
#   T12 测试假驱动夹具     test 目录里仅 registerDriver(…)（FakeJdbcDriver）→ 不命中（②a2 仅生产）
# =============================================================================

set -u

ROOT="java"
ROOT_EXPLICIT=0
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STRIP="$SCRIPT_DIR/strip_java_comments.awk"

usage() {
  cat <<'USAGE'
用法：scan_java_sql.sh [--root <dir>]
  --root <dir>   待扫描根目录，默认 java
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
  echo "SKIP: 默认路径 $ROOT 不存在（Java 组件未铺开）—— 退出码 0"
  exit 0
fi

if [ ! -f "$STRIP" ]; then
  echo "错误：注释剔除器缺失：$STRIP" >&2
  exit 2
fi

# ---- ① 语句形态（大小写不敏感；适用全仓）-----------------------------------
STMT="\\bselect[[:space:]]+[a-z_*\"'\`]"
STMT="$STMT|\\binsert[[:space:]]+into\\b"
STMT="$STMT|\\bupdate[[:space:]]+[a-z_][a-z0-9_.]*[[:space:]]+set\\b"
STMT="$STMT|\\bdelete[[:space:]]+from\\b"
STMT="$STMT|\\bcreate[[:space:]]+(table|index|view)\\b"
STMT="$STMT|\\bdrop[[:space:]]+(table|index)\\b"
STMT="$STMT|\\balter[[:space:]]+table\\b"

# ---- ②a 可执行连接创建形态（适用全仓）-------------------------------------
#   锚定「**调用形态**」而非类名/导入：`DriverManager.getConnection(` 是**创建物理连接**的
#   唯一调用形态；`Class.forName(…jdbc…)` 是**加载 JDBC 驱动**。不匹配 `import ...DriverManager;`
#   与 `DriverManager.registerDriver(`（后者见 ②a2）。
DRV_EXEC='driverManager\.getConnection\(|class\.forName\([^)]*jdbc'

# ---- ②a2 驱动注册形态（仅适用非 src/test/**）------------------------------
#   生产代码 `DriverManager.registerDriver(` = 自行注册驱动，属直连前置；
#   而 **test 目录的假驱动夹具**（`FakeJdbcDriver`，NFR-TEST-2 内存假件）
#   必然包含该调用 —— 判据不得迫使合法夹具改写（见头部"为何 ②b 按目录分域"同理）。
DRV_EXEC_PROD='driverManager\.registerDriver\('

# ---- ②b 内嵌连接串形态（仅适用非 src/test/**）-----------------------------
DRV_EMBED='jdbc:|odbc:|jdbcurl|jdbc_url'

# ---- ②c 驱动/连接串关键字（适用全仓；实测不出现于散文）---------------------
DRV_KW='dm_jdbc|sqldriver|connectstring'

# ---- ③ 豁免：先屏蔽 DAO 访问器调用形态，再匹配 ①/② ------------------------
DAO_CALL='s/\.(insert|updateById|deleteById|findById|findPage|count|exists)[[:space:]]*\(/./g'

mapfile -t FILES < <(find "$ROOT" -type f -name '*.java' ! -path '*/dao/*' | LC_ALL=C sort)

echo "== E5 Java SQL 外泄扫描（dao 之外不得出现 SQL）=="
echo "扫描范围  : $ROOT/**/*.java"
echo "排除项    : **/dao/**（含 **/dao/sql/**）"
echo "待扫描文件: ${#FILES[@]} 个"
echo "预处理    : 剔除 Java 注释（strip_java_comments.awk；字符串字面量内不视为注释）"
echo "判据 ①    : SQL 语句形态（SELECT/INSERT INTO/UPDATE…SET/DELETE FROM/CREATE|DROP|ALTER TABLE）—— 适用全仓"
echo "判据 ②a   : 可执行连接创建形态（DriverManager.getConnection( / Class.forName(…jdbc…)）—— 适用全仓"
echo "判据 ②a2  : 驱动注册形态（DriverManager.registerDriver(）—— 仅适用非 src/test/** 的生产代码"
echo "判据 ②b   : 内嵌连接串形态（jdbc: / odbc: / jdbcUrl / jdbc_url）—— 仅适用非 src/test/** 的生产代码"
echo "判据 ②c   : 其它驱动/连接串关键字（dm_jdbc / sqldriver / connectstring）—— 适用全仓"
echo "判据 ③    : 豁免 DAO 访问器调用（.(insert|updateById|deleteById|findById|findPage|count|exists)( ）"
echo "-------------------------------------------------------------------"

hits=0
for f in "${FILES[@]}"; do
  case "$f" in
    */src/test/*) RE="$STMT|$DRV_EXEC|$DRV_KW" ;;                                  # ②a2 / ②b 对 test 目录豁免
    *)            RE="$STMT|$DRV_EXEC|$DRV_EXEC_PROD|$DRV_EMBED|$DRV_KW" ;;
  esac
  # 预处理：剔注释 → ③ 屏蔽 DAO 访问器调用 → 按 ①/② 匹配；grep -n 保留原行号
  nums=$(awk -f "$STRIP" "$f" | sed -E "$DAO_CALL" | grep -nEi "$RE" | cut -d: -f1)
  [ -n "$nums" ] || continue
  for n in $nums; do
    # 命中内容取「原文行」（已去首尾空白），便于直接定位
    printf '%s:%s: %s\n' "$f" "$n" "$(sed -n "${n}p" "$f" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
    hits=$((hits + 1))
  done
done

echo "-------------------------------------------------------------------"
if [ "$hits" -eq 0 ]; then
  echo "E5 通过：dao 之外未发现 SQL（退出码 0）"
  exit 0
fi
echo "E5 失败：dao 之外发现 $hits 处 SQL/驱动痕迹（退出码 1）"
exit 1
