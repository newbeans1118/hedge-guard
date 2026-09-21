# strip_java_comments.awk — 剔除 Java 注释，**保留行号**（每输入行 → 每输出行）
#
# 用途：供 `tools/arch-check/scan_java_sql.sh`（`E5` 判据）预处理。
#       判据判的是**可执行代码**中的 SQL / 驱动痕迹；注释与散文不构成
#       "业务代码绕过 DAO 直连数据库"的执行路径，须先剔除，否则：
#         · javadoc 里描述契约的 SQL 片段（如 `SELECT MAX(...) FROM t_param_dispatch`）
#           会被 ① 语句形态判红 ⇒ 判据按字面不可满足 ⇒ 诱导改写表述。
#
# 语义：
#   · 剔除 `//` 行注释（到行尾）
#   · 剔除 `/* ... */` 块注释（含 javadoc；可跨行；Java 注释不嵌套）
#   · **字符串字面量内的 `/`、`/*`、`//` 序列不视为注释**
#     （关键：本项目确有字符串字面量含 `"/**` 的情形，如
#      `java/src/main/java/com/hedgeguard/web/HttpServerLauncher.java:83`
#      的 `"/**，REST 前缀 /api/v1）"`；不做字符串感知会把其后整个文件吞成注释）
#   · `inblk` 跨行保持；字符串不能跨行（Java 11 无跨行字符串字面量），故 `instr` 逐行复位
#
# 已知容差（**不造成字符丢失、不影响 SQL/驱动形态判定**）：
#   字符字面量内含双引号（如 `'"'`）会使本行余下部分被误判为"字符串内"，
#   从而不剔除该行行尾的 `//` 注释。仅影响"行尾注释是否被剔除"，不产生漏报。
BEGIN { inblk = 0 }
{
  line = $0; out = ""; i = 1; n = length(line); instr = 0
  while (i <= n) {
    c = substr(line, i, 1); two = substr(line, i, 2)
    if (instr) {
      if (c == "\\") { out = out two; i += 2; continue }   # 转义：\" 与 \\ 不结束字符串
      if (c == "\"") { instr = 0 }
      out = out c; i++; continue
    }
    if (inblk) { if (two == "*/") { inblk = 0; i += 2 } else { i++ }; continue }
    if (two == "/*") { inblk = 1; i += 2; continue }
    if (two == "//") { break }
    if (c == "\"") { instr = 1 }
    out = out c; i++
  }
  print out
}
