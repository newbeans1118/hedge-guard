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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表脚本与 {@code DATA_MODEL.md} 的<b>逐列一致性</b>校验（{@code T-004} 验收标准 1 的证据）。
 *
 * <p>本环境无 DM8 实例，无法"在 DM8 上建表成功"；本用例是<b>可机器复现的替代证据</b>：
 * 直接解析 {@code docs/DATA_MODEL.md} §2.1（CONFIG 7 张）与 §2.2（AUTH 13 张）的字段表，
 * 与 {@code docs/DEPLOY/schema/m1_config_auth.sql} 的 {@code CREATE TABLE} 定义逐列比对：
 * <ul>
 *   <li>表数与表名一一对应（20 张）；</li>
 *   <li>每张表的列集合完全一致（含 {@code §0.3} 通用审计字段的加入规则）；</li>
 *   <li>每列的类型（含精度）一致；</li>
 *   <li>每列的非空约束一致；</li>
 *   <li>文档"索引"清单中的<b>唯一</b>与<b>普通</b>索引在脚本中均存在；</li>
 *   <li>外键数量与文档 {@code FK →} 标注数量一致。</li>
 * </ul>
 *
 * <p>注意：本用例比对的是<b>制品内部一致性</b>，<b>不</b>替代 DM8 实机建表验证。
 */
class ConfigAuthSchemaTest {

    /** CONFIG + AUTH 期望表数（{@code DATA_MODEL.md} §0.6）。 */
    private static final int EXPECTED_TABLE_COUNT = 20;

    /** §0.3 通用审计字段（每张表都有）。 */
    private static final Map<String, String> AUDIT_COLUMNS = auditColumns();

    /** §0.3 中声明为 {@code NOT NULL} 的审计字段（{@code updated_*} 两列可空）。 */
    private static final Set<String> AUDIT_NOT_NULL = new LinkedHashSet<>(
            Arrays.asList("id", "created_by", "created_time", "row_version"));

    /**
     * 只增不改、且表定义显式排除 {@code updated_by/updated_time/row_version} 的表
     * （{@code DATA_MODEL.md} §2.1 TB-CONFIG-3 备注）。
     */
    private static final Set<String> AUDIT_PARTIAL_TABLES =
            Collections.singleton("t_config_change_log");

    /** 文档"约束"列中点名、必须在脚本中以具名 {@code CHECK} 出现的常量（{@code §4.3} 业务不变量）。 */
    private static final List<String> REQUIRED_BUSINESS_CHECKS = Arrays.asList(
            "ck_config_item_sensitive",
            "ck_config_chg_sensitive_values",
            "ck_report_period_mandatory_enabled",
            "ck_authorization_quota_positive",
            "ck_position_group_conflict_enforced",
            "ck_role_sysadmin_no_trading");

    private static Map<String, String> auditColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "BIGINT");
        m.put("created_by", "BIGINT");
        m.put("created_time", "TIMESTAMP");
        m.put("updated_by", "BIGINT");
        m.put("updated_time", "TIMESTAMP");
        m.put("row_version", "INT");
        return Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------ 模型侧

    /** 文档中一张表的字段定义。 */
    private static final class TableSpec {
        final String table;
        final Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        final List<String> uniqueIndexes = new ArrayList<>();
        final List<String> normalIndexes = new ArrayList<>();

        TableSpec(String table) {
            this.table = table;
        }
    }

    /** 文档中一列的定义。 */
    private static final class ColumnSpec {
        final String type;
        final boolean notNull;
        /** 文档是否就"非空"给出可比较的声明（约束列为 PK 等不含 NULL 字样时不参与比较）。 */
        final boolean notNullDefined;
        /** 文档标注的外键引用表（无外键为 {@code null}）。 */
        final String fkTarget;

        ColumnSpec(String type, boolean notNull) {
            this(type, notNull, true, null);
        }

        ColumnSpec(String type, boolean notNull, boolean notNullDefined) {
            this(type, notNull, notNullDefined, null);
        }

        ColumnSpec(String type, boolean notNull, boolean notNullDefined, String fkTarget) {
            this.type = type;
            this.notNull = notNull;
            this.notNullDefined = notNullDefined;
            this.fkTarget = fkTarget;
        }
    }

    @Test
    @DisplayName("DDL 与 DATA_MODEL.md §2.1/§2.2 逐表逐列一致（20 张、含类型/非空/索引/外键）")
    void ddlMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        Path modelFile = repo.resolve("docs/DATA_MODEL.md");
        Path ddlFile = repo.resolve("docs/DEPLOY/schema/m1_config_auth.sql");
        assertTrue(Files.isRegularFile(modelFile), "缺少上游数据模型：" + modelFile);
        assertTrue(Files.isRegularFile(ddlFile), "缺少建表脚本：" + ddlFile);

        Map<String, TableSpec> specs = parseDataModel(Files.readAllLines(modelFile, StandardCharsets.UTF_8));
        Map<String, Map<String, ColumnSpec>> ddl = parseDdl(Files.readAllLines(ddlFile, StandardCharsets.UTF_8));
        String ddlText = new String(Files.readAllBytes(ddlFile), StandardCharsets.UTF_8);

        assertEquals(EXPECTED_TABLE_COUNT, specs.size(),
                "核对范围应为 CONFIG(7) + AUTH(13) = 20 张，实际解析到 " + specs.size() + "：" + specs.keySet());
        assertEquals(new TreeSet<>(specs.keySet()), new TreeSet<>(ddl.keySet()),
                "表集合不一致：文档=" + specs.keySet() + " 脚本=" + ddl.keySet());

        List<String> problems = new ArrayList<>();
        int checkedColumns = 0;

        for (Map.Entry<String, TableSpec> e : specs.entrySet()) {
            String table = e.getKey();
            TableSpec spec = e.getValue();
            Map<String, ColumnSpec> actual = ddl.get(table);

            Map<String, ColumnSpec> expected = new LinkedHashMap<>(spec.columns);
            Set<String> audit = new LinkedHashSet<>(AUDIT_COLUMNS.keySet());
            if (AUDIT_PARTIAL_TABLES.contains(table)) {
                audit.removeAll(Arrays.asList("updated_by", "updated_time", "row_version"));
            }
            for (String col : audit) {
                ColumnSpec specCol = spec.columns.get(col);
                expected.putIfAbsent(col, specCol != null ? specCol
                        : new ColumnSpec(AUDIT_COLUMNS.get(col), AUDIT_NOT_NULL.contains(col)));
            }

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
                if (!exp.type.equalsIgnoreCase(act.type)) {
                    problems.add(table + "." + ce.getKey() + " 类型不一致：文档=" + exp.type
                            + " 脚本=" + act.type);
                }
                if (exp.notNullDefined && exp.notNull != act.notNull) {
                    problems.add(table + "." + ce.getKey() + " 非空不一致：文档 NOT NULL="
                            + exp.notNull + " 脚本 NOT NULL=" + act.notNull);
                }
            }

            for (String idx : spec.uniqueIndexes) {
                if (!containsConstraint(ddlText, idx)) {
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
            if (!containsConstraint(ddlText, check)) {
                problems.add("缺少具名业务不变量 CHECK：" + check);
            }
        }

        Set<String> expectedFk = expectedForeignKeys(specs);
        Set<String> actualFk = parseDdlForeignKeys(Files.readAllLines(ddlFile, StandardCharsets.UTF_8));
        if (!expectedFk.equals(actualFk)) {
            Set<String> missingFk = new TreeSet<>(expectedFk);
            missingFk.removeAll(actualFk);
            Set<String> extraFk = new TreeSet<>(actualFk);
            extraFk.removeAll(expectedFk);
            problems.add("外键不一致：脚本缺=" + missingFk + "，脚本多=" + extraFk);
        }

        assertTrue(problems.isEmpty(), "建表脚本与 DATA_MODEL.md 不一致（" + problems.size() + " 项）：\n  - "
                + String.join("\n  - ", problems));
        assertTrue(checkedColumns >= 290,
                "逐列核对覆盖过少（实际 " + checkedColumns + " 列，期望 ≈295），疑似解析失效");
    }

    @Test
    @DisplayName("审计字段规则：19 张表含 6 项，t_config_change_log 仅含 3 项（文档显式排除）")
    void auditFieldsFollowSpec() throws IOException {
        Path repo = repoRoot();
        Map<String, Map<String, ColumnSpec>> ddl = parseDdl(Files.readAllLines(
                repo.resolve("docs/DEPLOY/schema/m1_config_auth.sql"), StandardCharsets.UTF_8));

        for (Map.Entry<String, Map<String, ColumnSpec>> e : ddl.entrySet()) {
            Set<String> cols = e.getValue().keySet();
            if (AUDIT_PARTIAL_TABLES.contains(e.getKey())) {
                assertTrue(cols.contains("id") && cols.contains("created_by") && cols.contains("created_time"),
                        e.getKey() + " 应含 id/created_by/created_time");
                assertFalse(cols.contains("updated_by"), e.getKey() + " 文档显式排除 updated_by");
                assertFalse(cols.contains("updated_time"), e.getKey() + " 文档显式排除 updated_time");
                assertFalse(cols.contains("row_version"), e.getKey() + " 文档显式排除 row_version");
            } else {
                for (String audit : AUDIT_COLUMNS.keySet()) {
                    assertTrue(cols.contains(audit), e.getKey() + " 缺少通用审计字段 " + audit);
                }
            }
        }
    }

    @Test
    @DisplayName("脚本纪律：无存储过程 / 触发器 / 自定义函数；DM 特性均有 -- DM-SPECIFIC: 注释")
    void ddlRespectsScriptDiscipline() throws IOException {
        Path repo = repoRoot();
        String raw = new String(Files.readAllBytes(
                repo.resolve("docs/DEPLOY/schema/m1_config_auth.sql")), StandardCharsets.UTF_8);
        // 剔除注释后再做关键字判定：注释里为换库说明提到他库写法（如 AUTO_INCREMENT）不算违规
        String sql = stripComments(raw);
        String upper = sql.toUpperCase(Locale.ROOT);

        assertFalse(upper.contains("CREATE PROCEDURE"), "禁止存储过程（PROJECT.md §6）");
        assertFalse(upper.contains("CREATE TRIGGER"), "禁止触发器（PROJECT.md §6）");
        assertFalse(upper.contains("CREATE FUNCTION"), "禁止自定义函数（PROJECT.md §6）");
        assertFalse(upper.contains("ON DELETE CASCADE"), "禁止级联删除（DATA_MODEL.md §0.4）");

        int dmSpecific = countOccurrences(raw, "DM-SPECIFIC:");
        assertTrue(dmSpecific >= 6, "DM 特性标注过少：" + dmSpecific);

        assertTrue(upper.contains("IDENTITY(1,1)"), "DM 自增主键写法（§0.2 代理主键）");
        assertFalse(upper.contains("AUTO_INCREMENT"), "不得出现他库自增写法");
        assertTrue(sql.contains("COMMENT ON TABLE"), "缺少表注释（§0.5：COMMENT ON TABLE 语法）");
    }

    /** 去掉 {@code --} 行注释与行尾注释。 */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            sb.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("保留系统行 t_sub_account(id=0) 由 data-init.sql 插入（哨兵值载体，DC-020）")
    void dataInitInsertsReservedSystemRow() throws IOException {
        Path repo = repoRoot();
        String sql = new String(Files.readAllBytes(
                repo.resolve("docs/DEPLOY/schema/data-init.sql")), StandardCharsets.UTF_8);
        assertTrue(sql.contains("SET IDENTITY_INSERT t_sub_account ON"));
        assertTrue(sql.contains("SET IDENTITY_INSERT t_sub_account OFF"));
        assertTrue(sql.contains("__ALL_ACCOUNT__"));
        assertTrue(sql.contains("'DISABLED'"));
        assertTrue(sql.contains("系统保留行"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("CREATE PROCEDURE"));

        // ---- 第二条保留系统行：t_product(id=0)（v1.3.3 / DC-041）----
        // 依据：DATA_MODEL.md §2.3 TB-PRODUCT-1 备注（逐字）+ §7.1 v1.3.3；
        // 缺失该行 ⇒ t_exposure.product_id 的未映射哨兵值 0 无法落库（外键失败），"未映射清单"无行可承载
        assertTrue(sql.contains("SET IDENTITY_INSERT t_product ON"),
                "缺 t_product 保留系统行的显式 id 插入（DC-041）");
        assertTrue(sql.contains("SET IDENTITY_INSERT t_product OFF"));
        assertTrue(sql.contains("'__UNMAPPED__'"), "product_code 须为 '__UNMAPPED__'（文档逐字）");
        assertTrue(sql.contains("'未映射（系统保留）'"), "product_name 须为 '未映射（系统保留）'（文档逐字）");
        assertTrue(sql.contains("'COMMODITY'"), "category 占位值须为 'COMMODITY'（不参与红线计算）");
        assertTrue(sql.contains("'ENABLED'") || sql.contains("'DISABLED'"));
    }

    @Test
    @DisplayName("data-init 的 t_product 保留行覆盖「NOT NULL 且无 DEFAULT」的全部列（防漏列，实机会报错）")
    void dataInitProductRowCoversMandatoryColumns() throws IOException {
        Path repo = repoRoot();
        // ① 从建表脚本中取 t_product 的「NOT NULL 且无 DEFAULT」列（INSERT 必须全部显式给出）
        String ddl = new String(Files.readAllBytes(
                repo.resolve("docs/DEPLOY/schema/m1_product.sql")), StandardCharsets.UTF_8);
        int blockStart = ddl.indexOf("CREATE TABLE t_product ");
        assertTrue(blockStart >= 0, "m1_product.sql 应含 CREATE TABLE t_product");
        int blockEnd = ddl.indexOf("\n);", blockStart);
        String block = ddl.substring(blockStart, blockEnd);
        Set<String> mandatory = new TreeSet<>();
        for (String rawLine : block.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("CONSTRAINT")
                    || line.startsWith("PRIMARY") || line.startsWith("FOREIGN")
                    || line.startsWith("UNIQUE") || line.startsWith("CHECK")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space <= 0) {
                continue;
            }
            String col = line.substring(0, space);
            if (col.startsWith("id") || col.matches("[a-z_][a-z0-9_]*")) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.contains("NOT NULL") && !upper.contains("DEFAULT")) {
                    mandatory.add(col);
                }
            }
        }
        assertTrue(mandatory.size() >= 3,
                "自检：t_product 应有多列「NOT NULL 且无 DEFAULT」，实际=" + mandatory);
        assertTrue(mandatory.contains("product_code") && mandatory.contains("created_by"),
                "自检：应含 product_code / created_by，实际=" + mandatory);

        // ② data-init 的 INSERT 列清单须覆盖它们
        String init = new String(Files.readAllBytes(
                repo.resolve("docs/DEPLOY/schema/data-init.sql")), StandardCharsets.UTF_8);
        int insertAt = init.indexOf("INSERT INTO t_product");
        assertTrue(insertAt >= 0, "data-init.sql 应含 t_product 的 INSERT");
        int colsStart = init.indexOf('(', insertAt);
        int colsEnd = init.indexOf(')', colsStart);
        String cols = init.substring(colsStart, colsEnd);
        for (String col : mandatory) {
            assertTrue(cols.contains(col),
                    "t_product 保留行的 INSERT 漏列（NOT NULL 且无 DEFAULT）：" + col
                            + "；实际列清单=" + cols.replaceAll("\\s+", " "));
        }
    }

    // ------------------------------------------------------------------ 解析实现

    /**
     * 期望外键集合（{@code "表.列->引用表"}）。
     *
     * <p>两条来源：
     * <ol>
     *   <li>文档"约束"列显式标注 {@code FK → `t_x(id)`} 的列；</li>
     *   <li>{@code DATA_MODEL.md} §0.4「引用子账户：<b>所有业务表</b> {@code sub_account_id} →
     *       {@code t_sub_account(id)}，RESTRICT」——故 {@code sub_account_id} 列即使约束列未逐字标注
     *       {@code FK}，也一律计入（本脚本据此为其声明外键；文档字段行未标注属文档侧遗漏，
     *       已登记为 T-004 工单 O-1）。</li>
     * </ol>
     */
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

    /** 解析脚本中的外键（含 {@code CREATE TABLE} 内的表级约束与末尾 {@code ALTER TABLE} 声明的跨模块外键）。 */
    private static Set<String> parseDdlForeignKeys(List<String> lines) {
        Set<String> out = new TreeSet<>();
        String text = String.join("\n", lines);
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

    /** 从文档 {@code FK → `t_x(id)`} 注记中取引用表名。 */
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

    private static boolean containsConstraint(String ddlText, String name) {
        return ddlText.contains("CONSTRAINT " + name + " ");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    private static final Pattern TABLE_HEADING =
            Pattern.compile("^####\\s+(TB-(CONFIG|AUTH)-(\\d+))\\s+`([a-z0-9_]+)`.*$");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** 解析 §2.1/§2.2 各表的字段表与索引表。 */
    private static Map<String, TableSpec> parseDataModel(List<String> lines) {
        Map<String, TableSpec> out = new LinkedHashMap<>();
        TableSpec current = null;
        boolean inIndexSection = false;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher h = TABLE_HEADING.matcher(line);
            if (h.matches()) {
                current = new TableSpec(h.group(4));
                out.put(current.table, current);
                inIndexSection = false;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("### ") || line.startsWith("## ")) {
                out.remove(null);
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
            String type = baseType(firstBacktick(cells.get(1)));
            String constraint = cells.size() > 2 ? cells.get(2) : "";
            boolean definesNotNull = constraint.toUpperCase(Locale.ROOT).contains("NULL");
            String fkTarget = fkTargetOf(constraint);
            for (String name : names) {
                current.columns.put(name, new ColumnSpec(type, constraint.contains("NOT NULL"),
                        definesNotNull, fkTarget));
            }
        }
        out.remove(null);
        return out;
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?s)CREATE TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\n\\);");
    private static final Pattern COLUMN_LINE =
            Pattern.compile("^([a-z][a-z0-9_]*)\\s+([A-Za-z]+(\\s*\\([0-9,\\s]*\\))?).*$");

    /** 解析建表脚本中的每张表列定义。 */
    private static Map<String, Map<String, ColumnSpec>> parseDdl(List<String> lines) {
        Map<String, Map<String, ColumnSpec>> out = new LinkedHashMap<>();
        for (String line : lines) {
            if (!line.startsWith("CREATE TABLE")) {
                continue;
            }
            String table = line.replace("CREATE TABLE", "").trim().replace("(", "").trim();
            Map<String, ColumnSpec> columns = new TreeMap<>();
            out.put(table, columns);
        }
        String text = String.join("\n", lines);
        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            String table = m.group(1);
            Map<String, ColumnSpec> columns = new TreeMap<>();
            for (String rawLine : m.group(2).split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("--")) {
                    continue;
                }
                String head = line.split("\\s+")[0].toUpperCase(Locale.ROOT);
                if (head.equals("CONSTRAINT") || head.equals("PRIMARY") || head.equals("FOREIGN")
                        || head.equals("UNIQUE") || head.equals("CHECK")) {
                    continue;
                }
                Matcher c = COLUMN_LINE.matcher(line);
                if (!c.matches()) {
                    continue;
                }
                String name = c.group(1);
                String type = baseType(c.group(2));
                boolean notNull = line.toUpperCase(Locale.ROOT).contains("NOT NULL");
                columns.put(name, new ColumnSpec(type, notNull));
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

    /** 从测试工作目录向上寻找仓库根（含 {@code docs/DATA_MODEL.md}）。 */
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
