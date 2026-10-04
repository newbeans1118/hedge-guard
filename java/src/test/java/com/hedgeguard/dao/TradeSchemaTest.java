package com.hedgeguard.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-043} 建表脚本与 {@code DATA_MODEL.md} 的逐列一致性校验（TRADE 模块 9 张）。
 *
 * <p>本环境无 DM8 实例，无法「在 DM8 上建表成功」；本用例是替代证据：
 * 直接解析 {@code docs/DATA_MODEL.md} §2.6（TRADE，TB-TRADE-1~9）的字段表与索引表，与
 * {@code docs/DEPLOY/schema/m2_trade.sql} 的 {@code CREATE TABLE} 定义逐列比对：
 * <ul>
 *   <li>表数与表名一一对应（9 张）；</li>
 *   <li>每张表的列集合完全一致（含 §0.3 通用审计字段）；</li>
 *   <li>每列的类型（含精度）与非空约束一致（文档以 "—" 表「未规定类型」者跳过类型比较）；</li>
 *   <li>文档「索引」清单中的唯一/普通索引在脚本中均存在；</li>
 *   <li>外键数量与文档 FK 标注数量一致（含文件末尾 ALTER TABLE 后置外键）；</li>
 *   <li>子账户贯穿（§4.6.3）：① NOT NULL = 8 / ② 可空 = 0 / ③ 无该列 = 1（t_replay_log，例外 E3）；</li>
 *   <li>验收 2/4 落点：t_trade_fill.trade_id 唯一、t_pre_trade_reject 含 limit_layer 与敞口快照列、
 *       成交/委托/流水表均含事件幂等唯一约束；</li>
 *   <li>脚本纪律：无存储过程/触发器/自定义函数、无级联删除、DM 特性有标注。</li>
 * </ul>
 *
 * <p>注意：本用例比对的是制品内部一致性，不替代 DM8 实机建表验证（部署方在可达实例复跑）。
 */
class TradeSchemaTest {

    /** Markdown 反引号（避免在源码中书写反引号字符）。 */
    private static final String BT = String.valueOf((char) 96);

    private static final String DDL_REL = "docs/DEPLOY/schema/m2_trade.sql";

    /** 9 张表名。 */
    private static final List<String> TABLES = Arrays.asList(
            "t_trade_order", "t_trade_order_report", "t_trade_fill", "t_trade_reason",
            "t_position", "t_pre_trade_reject", "t_command_flow", "t_option_strategy", "t_replay_log");

    /** §0.3 通用审计字段。 */
    private static final Map<String, String> AUDIT_COLUMNS = auditColumns();

    private static final Set<String> AUDIT_NOT_NULL = new LinkedHashSet<>(Arrays.asList(
            "id", "created_by", "created_time", "row_version"));

    /** 文档/§4.3 点名、必须在脚本中以具名 CHECK 出现的业务不变量。 */
    private static final List<String> REQUIRED_BUSINESS_CHECKS = Arrays.asList(
            "ck_trade_reason_related_or_missing", "ck_position_qty_consistency");

    /** 幂等唯一约束（验收 2/4）。 */
    private static final List<String> REQUIRED_UNIQUE = Arrays.asList(
            "uk_trade_fill_id", "uk_order_report_idem", "uk_command_flow_idem",
            "uk_pre_trade_reject_idem", "uk_trade_order_client_ref", "uk_replay_log_seq");

    private static Map<String, String> auditColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "BIGINT");
        m.put("created_by", "BIGINT");
        m.put("created_time", "TIMESTAMP");
        m.put("updated_by", "BIGINT");
        m.put("updated_time", "TIMESTAMP");
        m.put("row_version", "INT");
        return m;
    }

    private static final class TableSpec {
        final String table;
        final Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        final List<String> uniqueIndexes = new ArrayList<>();
        final List<String> normalIndexes = new ArrayList<>();

        TableSpec(String table) {
            this.table = table;
        }
    }

    private static final class ColumnSpec {
        final String type;
        final boolean notNull;
        final boolean notNullDefined;
        final String fkTarget;

        ColumnSpec(String type, boolean notNull, boolean notNullDefined, String fkTarget) {
            this.type = type;
            this.notNull = notNull;
            this.notNullDefined = notNullDefined;
            this.fkTarget = fkTarget;
        }
    }

    @Test
    @DisplayName("DDL 与 DATA_MODEL.md §2.6 逐表逐列一致（9 张、含类型/非空/索引/外键）")
    void ddlMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        Path modelFile = repo.resolve("docs/DATA_MODEL.md");
        Path ddlFile = repo.resolve(DDL_REL);
        assertTrue(Files.isRegularFile(modelFile), "缺少上游数据模型：" + modelFile);
        assertTrue(Files.isRegularFile(ddlFile), "缺少建表脚本：" + ddlFile);

        Map<String, TableSpec> specs = parseDataModel(Files.readAllLines(modelFile, StandardCharsets.UTF_8));
        assertEquals(9, specs.size(), "核对范围应为 TRADE 9 张，实际解析到 " + specs.size());
        assertEquals(new TreeSet<>(TABLES), new TreeSet<>(specs.keySet()),
                "解析到的表名与 TB-TRADE-1~9 不一致：" + specs.keySet());

        String ddlText = new String(Files.readAllBytes(ddlFile), StandardCharsets.UTF_8);
        Map<String, Map<String, ColumnSpec>> ddl = parseDdl(Files.readAllLines(ddlFile, StandardCharsets.UTF_8));
        assertEquals(9, ddl.size(), "建表脚本应含 9 张表，实际 " + ddl.keySet());

        List<String> problems = new ArrayList<>();
        int checkedColumns = 0;
        for (String table : TABLES) {
            TableSpec spec = specs.get(table);
            Map<String, ColumnSpec> actual = ddl.get(table);
            if (actual == null) {
                problems.add("脚本缺表：" + table);
                continue;
            }
            Map<String, ColumnSpec> expected = expectedColumns(spec);
            if (!new TreeSet<>(expected.keySet()).equals(new TreeSet<>(actual.keySet()))) {
                Set<String> missing = new TreeSet<>(expected.keySet());
                missing.removeAll(actual.keySet());
                Set<String> extra = new TreeSet<>(actual.keySet());
                extra.removeAll(expected.keySet());
                problems.add(table + " 列集合不一致：脚本缺列=" + missing + "，脚本多列=" + extra);
                continue;
            }
            for (Map.Entry<String, ColumnSpec> ce : expected.entrySet()) {
                checkedColumns++;
                ColumnSpec exp = ce.getValue();
                ColumnSpec act = actual.get(ce.getKey());
                if (!isUnspecified(exp.type) && !exp.type.equalsIgnoreCase(act.type)) {
                    problems.add(table + "." + ce.getKey() + " 类型不一致：文档=" + exp.type
                            + " 脚本=" + act.type);
                }
                if (exp.notNullDefined && exp.notNull != act.notNull) {
                    problems.add(table + "." + ce.getKey() + " 非空不一致：文档 NOT NULL="
                            + exp.notNull + " 脚本 NOT NULL=" + act.notNull);
                }
            }
            for (String idx : spec.uniqueIndexes) {
                if (!containsUniqueConstraint(ddlText, idx)) {
                    problems.add(table + " 缺少文档声明的唯一索引/约束：" + idx);
                }
            }
            for (String idx : spec.normalIndexes) {
                if (!ddlText.contains("CREATE INDEX " + idx + " ")) {
                    problems.add(table + " 缺少文档声明的普通索引：" + idx);
                }
            }
        }

        for (String check : REQUIRED_BUSINESS_CHECKS) {
            if (!ddlText.contains("CONSTRAINT " + check + " ")) {
                problems.add("缺少具名业务不变量 CHECK：" + check);
            }
        }
        for (String uk : REQUIRED_UNIQUE) {
            if (!containsUniqueConstraint(ddlText, uk)) {
                problems.add("缺少幂等唯一约束：" + uk);
            }
        }

        Set<String> expectedFk = expectedForeignKeys(specs);
        Set<String> actualFk = parseDdlForeignKeys(ddlText);
        if (!expectedFk.equals(actualFk)) {
            Set<String> missingFk = new TreeSet<>(expectedFk);
            missingFk.removeAll(actualFk);
            Set<String> extraFk = new TreeSet<>(actualFk);
            extraFk.removeAll(expectedFk);
            problems.add("外键不一致：脚本缺=" + missingFk + "，脚本多=" + extraFk);
        }

        assertTrue(problems.isEmpty(), "建表脚本与 DATA_MODEL.md 不一致（" + problems.size() + " 项）：\n  - "
                + String.join("\n  - ", problems));
        assertTrue(checkedColumns >= 150,
                "逐列核对覆盖过少（实际 " + checkedColumns + " 列），疑似解析失效");
    }

    @Test
    @DisplayName("子账户贯穿（§4.6.3）：TRADE ① NOT NULL=8 / ② 可空=0 / ③ 无该列=1（t_replay_log，例外 E3）")
    void subAccountCarriageMatchesSpec() throws IOException {
        Map<String, Map<String, ColumnSpec>> ddl = parseDdl(Files.readAllLines(
                repoRoot().resolve(DDL_REL), StandardCharsets.UTF_8));
        int notNull = 0;
        int nullable = 0;
        int absent = 0;
        for (Map<String, ColumnSpec> cols : ddl.values()) {
            ColumnSpec c = cols.get("sub_account_id");
            if (c == null) {
                absent++;
            } else if (c.notNull) {
                notNull++;
            } else {
                nullable++;
            }
        }
        assertEquals(8, notNull, "TRADE NOT NULL 行级携带应为 8");
        assertEquals(0, nullable, "TRADE 可空应为 0");
        assertEquals(1, absent, "TRADE 无该列应为 1（t_replay_log，E3）");
        assertFalse(ddl.get("t_replay_log").containsKey("sub_account_id"),
                "t_replay_log 不应有 sub_account_id 列（例外 E3）");
    }

    @Test
    @DisplayName("验收 2：t_trade_fill.trade_id 唯一；t_pre_trade_reject 含 limit_layer 与敞口快照列")
    void acceptanceTwoAnchors() throws IOException {
        String ddlText = new String(Files.readAllBytes(repoRoot().resolve(DDL_REL)), StandardCharsets.UTF_8);
        assertTrue(containsUniqueConstraint(ddlText, "uk_trade_fill_id"),
                "t_trade_fill.trade_id 须有唯一约束 uk_trade_fill_id（幂等键）");
        Map<String, ColumnSpec> reject = parseDdl(Files.readAllLines(
                repoRoot().resolve(DDL_REL), StandardCharsets.UTF_8)).get("t_pre_trade_reject");
        assertTrue(reject.containsKey("limit_layer"), "t_pre_trade_reject 须含 limit_layer");
        assertTrue(reject.containsKey("exposure_snapshot_id"), "t_pre_trade_reject 须含 exposure_snapshot_id");
        assertTrue(reject.containsKey("exposure_snapshot_time"), "t_pre_trade_reject 须含 exposure_snapshot_time");
        assertTrue(ddlText.contains("CONSTRAINT ck_reject_limit_layer "),
                "limit_layer 须有具名 CHECK（5 值域）");
    }

    @Test
    @DisplayName("审计字段规则：9 张表均含 §0.3 的 6 项审计字段")
    void auditFieldsFollowSpec() throws IOException {
        Map<String, Map<String, ColumnSpec>> ddl = parseDdl(Files.readAllLines(
                repoRoot().resolve(DDL_REL), StandardCharsets.UTF_8));
        for (Map.Entry<String, Map<String, ColumnSpec>> e : ddl.entrySet()) {
            for (String audit : AUDIT_COLUMNS.keySet()) {
                assertTrue(e.getValue().containsKey(audit), e.getKey() + " 缺少通用审计字段 " + audit);
            }
        }
    }

    @Test
    @DisplayName("脚本纪律：无存储过程/触发器/自定义函数/级联删除；DM 特性均有 -- DM-SPECIFIC: 注释")
    void ddlRespectsScriptDiscipline() throws IOException {
        String raw = new String(Files.readAllBytes(repoRoot().resolve(DDL_REL)), StandardCharsets.UTF_8);
        String sql = stripComments(raw);
        String upper = sql.toUpperCase(Locale.ROOT);
        assertFalse(upper.contains("CREATE PROCEDURE"), "禁止存储过程");
        assertFalse(upper.contains("CREATE TRIGGER"), "禁止触发器");
        assertFalse(upper.contains("CREATE FUNCTION"), "禁止自定义函数");
        assertFalse(upper.contains("ON DELETE CASCADE"), "禁止级联删除");
        assertFalse(upper.contains("AUTO_INCREMENT"), "不得出现他库自增写法");
        assertTrue(upper.contains("IDENTITY(1,1)"), "须用 DM 自增主键写法");
        assertTrue(sql.contains("COMMENT ON TABLE"), "缺少表注释");
        assertTrue(raw.contains("DM-SPECIFIC:"), "缺少 DM 特性标注");
    }

    // ------------------------------------------------------------------ 解析实现

    private static boolean isUnspecified(String type) {
        return type == null || type.isEmpty() || "—".equals(type) || "-".equals(type);
    }

    private static Map<String, ColumnSpec> expectedColumns(TableSpec spec) {
        Map<String, ColumnSpec> expected = new LinkedHashMap<>(spec.columns);
        for (String col : AUDIT_COLUMNS.keySet()) {
            ColumnSpec specCol = spec.columns.get(col);
            expected.putIfAbsent(col, specCol != null ? specCol
                    : new ColumnSpec(AUDIT_COLUMNS.get(col), AUDIT_NOT_NULL.contains(col), true, null));
        }
        return expected;
    }

    private static Set<String> expectedForeignKeys(Map<String, TableSpec> specs) {
        Set<String> out = new TreeSet<>();
        for (TableSpec spec : specs.values()) {
            for (Map.Entry<String, ColumnSpec> e : spec.columns.entrySet()) {
                if (e.getValue().fkTarget != null) {
                    out.add(spec.table + "." + e.getKey() + "->" + e.getValue().fkTarget);
                } else if ("sub_account_id".equals(e.getKey())) {
                    out.add(spec.table + ".sub_account_id->t_sub_account");
                }
            }
        }
        return out;
    }

    private static final Pattern DDL_FK = Pattern.compile(
            "CONSTRAINT\\s+\\w+\\s+FOREIGN\\s+KEY\\s*\\(([a-z0-9_]+)\\)\\s*REFERENCES\\s+([a-z0-9_]+)");

    private static Set<String> parseDdlForeignKeys(String text) {
        Set<String> out = new TreeSet<>();
        Matcher table = CREATE_TABLE.matcher(text);
        while (table.find()) {
            String tableName = table.group(1);
            Matcher fk = DDL_FK.matcher(table.group(2));
            while (fk.find()) {
                out.add(tableName + "." + fk.group(1) + "->" + fk.group(2));
            }
        }
        Matcher alter = Pattern.compile(
                "(?s)ALTER TABLE\\s+([a-z0-9_]+)\\s+ADD CONSTRAINT\\s+\\w+\\s+FOREIGN KEY\\s*\\(([a-z0-9_]+)\\)"
                        + "\\s*REFERENCES\\s+([a-z0-9_]+)").matcher(text);
        while (alter.find()) {
            out.add(alter.group(1) + "." + alter.group(2) + "->" + alter.group(3));
        }
        return out;
    }

    /** 从文档 FK → 注记中取引用表名（首个反引号 token，去掉 (id)）。 */
    private static String fkTargetOf(String cell) {
        int idx = cell.indexOf("FK");
        if (idx < 0) {
            return null;
        }
        List<String> tokens = backtickedAll(cell.substring(idx));
        if (tokens.isEmpty()) {
            return null;
        }
        String target = tokens.get(0);
        int paren = target.indexOf('(');
        return paren < 0 ? target : target.substring(0, paren);
    }

    private static boolean containsUniqueConstraint(String ddlText, String name) {
        return ddlText.contains("CONSTRAINT " + name + " ")
                || ddlText.contains("CREATE UNIQUE INDEX " + name + " ");
    }

    private static final Pattern TABLE_HEADING = Pattern.compile(
            "^####\\s+(TB-TRADE-(\\d+))\\s+" + BT + "([a-z0-9_]+)" + BT + ".*$");
    private static final Pattern BACKTICKED = Pattern.compile(BT + "([^" + BT + "]+)" + BT);
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static Map<String, TableSpec> parseDataModel(List<String> lines) {
        Map<String, TableSpec> out = new LinkedHashMap<>();
        TableSpec current = null;
        boolean inIndexSection = false;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher h = TABLE_HEADING.matcher(line);
            if (h.matches()) {
                current = new TableSpec(h.group(3));
                out.put(current.table, current);
                inIndexSection = false;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("### ") || line.startsWith("## ")) {
                current = null;
                continue;
            }
            if (line.equals("**索引**：")) {
                inIndexSection = true;
                continue;
            }
            if (!line.startsWith("|")) {
                if (line.startsWith("**备注**")) {
                    current = null;
                    inIndexSection = false;
                }
                continue;
            }
            List<String> cells = splitRow(line);
            if (cells.size() < 3 || cells.get(0).startsWith("字段") || cells.get(0).startsWith("---")
                    || cells.get(0).startsWith(":--")) {
                continue;
            }
            if (inIndexSection) {
                String indexName = firstBacktick(cells.get(0));
                String kind = cells.get(2);
                if (indexName == null) {
                    continue;
                }
                if (kind.contains("唯一")) {
                    current.uniqueIndexes.add(indexName);
                } else if (kind.contains("普通")) {
                    current.normalIndexes.add(indexName);
                }
                continue;
            }
            List<String> names = new ArrayList<>();
            for (String token : backtickedAll(cells.get(0))) {
                if (IDENTIFIER.matcher(token).matches()) {
                    names.add(token);
                }
            }
            if (names.isEmpty()) {
                continue;
            }
            List<String> types = new ArrayList<>();
            for (String token : backtickedAll(cells.get(1))) {
                types.add(baseType(token));
            }
            String constraint = cells.size() > 2 ? cells.get(2) : "";
            boolean definesNotNull = constraint.toUpperCase(Locale.ROOT).contains("NULL");
            boolean notNull = constraint.contains("NOT NULL");
            String fkTarget = fkTargetOf(constraint);
            for (int i = 0; i < names.size(); i++) {
                String type;
                if (types.size() == names.size()) {
                    type = types.get(i);
                } else if (types.size() == 1) {
                    type = types.get(0);
                } else {
                    type = "—";
                }
                current.columns.put(names.get(i), new ColumnSpec(type, notNull, definesNotNull, fkTarget));
            }
        }
        return out;
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?s)CREATE TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\n\\s*\\);");
    private static final Pattern COLUMN_LINE =
            Pattern.compile("^([a-z][a-z0-9_]*)\\s+([A-Za-z]+(\\s*\\([0-9,\\s]*\\))?).*$");

    private static Map<String, Map<String, ColumnSpec>> parseDdl(List<String> lines) {
        Map<String, Map<String, ColumnSpec>> out = new LinkedHashMap<>();
        String text = String.join("\n", lines);
        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            String table = m.group(1);
            Map<String, ColumnSpec> columns = new java.util.TreeMap<>();
            for (String rawLine : m.group(2).split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("--")) {
                    continue;
                }
                String head = line.split("\\s+")[0].toUpperCase(Locale.ROOT);
                if (head.equals("CONSTRAINT") || head.equals("PRIMARY") || head.equals("FOREIGN")
                        || head.equals("UNIQUE") || head.equals("CHECK") || head.equals("ON")) {
                    continue;
                }
                Matcher c = COLUMN_LINE.matcher(line);
                if (!c.matches()) {
                    continue;
                }
                columns.put(c.group(1), new ColumnSpec(baseType(c.group(2)),
                        line.toUpperCase(Locale.ROOT).contains("NOT NULL"), true, null));
            }
            out.put(table, columns);
        }
        return out;
    }

    private static List<String> splitRow(String line) {
        String body = line.startsWith("|") ? line.substring(1) : line;
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        return Arrays.asList(body.split("\\|", -1));
    }

    private static String firstBacktick(String cell) {
        List<String> all = backtickedAll(cell);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<String> backtickedAll(String cell) {
        List<String> out = new ArrayList<>();
        Matcher m = BACKTICKED.matcher(cell);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    private static String baseType(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        int space = t.indexOf(' ');
        if (space > 0) {
            t = t.substring(0, space);
        }
        return t.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            sb.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
        }
        return sb.toString();
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根（应含 docs/DATA_MODEL.md），当前目录="
                + Paths.get("").toAbsolutePath() + "；复现=在 java/ 目录下运行 mvn test");
    }
}
