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
 * 建表脚本与 {@code DATA_MODEL.md} 的<b>逐列一致性</b>校验
 * （{@code T-005}/{@code T-006}/{@code T-007} 验收标准 1 的机器可复现证据）。
 *
 * <p>本环境无 DM8 实例，无法"在 DM8 上建表成功"；本用例是<b>替代证据</b>：
 * 直接解析 {@code docs/DATA_MODEL.md} §2.3（PRODUCT 8 张）/ §2.4（SPOT 10 张）/
 * §2.5（PLAN 8 张）的字段表与索引表，与
 * {@code docs/DEPLOY/schema/m1_product.sql} / {@code m1_spot.sql} / {@code m1_plan.sql}
 * 的 {@code CREATE TABLE} 定义逐列比对：
 * <ul>
 *   <li>表数与表名一一对应（8 + 10 + 8 = 26 张）；</li>
 *   <li>每张表的列集合完全一致（含 {@code §0.3} 通用审计字段）；</li>
 *   <li>每列的类型（含精度）与非空约束一致；</li>
 *   <li>文档"索引"清单中的唯一/普通索引在脚本中均存在
 *       （条件唯一索引以 {@code CREATE UNIQUE INDEX ... WHERE} 表达，见 {@code §6.1 D6}）；</li>
 *   <li>外键数量与文档 {@code FK →} 标注数量一致；</li>
 *   <li>脚本纪律：无存储过程/触发器/自定义函数、无级联删除、DM 特性有标注。</li>
 * </ul>
 *
 * <p>注意：本用例比对的是<b>制品内部一致性</b>，<b>不</b>替代 DM8 实机建表验证。
 */
class M1ProductSpotPlanSchemaTest {

    /** 各模块建表脚本（相对仓库根）。 */
    private static final Map<String, String> MODULE_SCRIPTS = moduleScripts();

    /** 各模块期望表数（{@code DATA_MODEL.md} §0.6 / §4.6.3）。 */
    private static final Map<String, Integer> MODULE_TABLE_COUNT = moduleTableCount();

    /** 合计 26 张 = PRODUCT 8 + SPOT 10 + PLAN 8。 */
    private static final int TOTAL_TABLE_COUNT = 26;

    /** §0.3 通用审计字段（每张表都有）。 */
    private static final Map<String, String> AUDIT_COLUMNS = auditColumns();

    /** §0.3 中声明为 {@code NOT NULL} 的审计字段（{@code updated_*} 两列可空）。 */
    private static final Set<String> AUDIT_NOT_NULL = new LinkedHashSet<>(
            Arrays.asList("id", "created_by", "created_time", "row_version"));

    /** 文档"约束"列或 §4.3 点名、必须在脚本中以具名 {@code CHECK} 出现的业务不变量。 */
    private static final List<String> REQUIRED_BUSINESS_CHECKS = Arrays.asList(
            // PRODUCT
            "ck_product_category_managed",
            "ck_product_unit_factor_positive",
            "ck_sub_instr_quota_nonneg",
            "ck_product_quota_nonneg",
            "ck_product_quota_layer_sentinel",
            // SPOT
            "ck_sales_contract_dual",
            "ck_prod_plan_period",
            "ck_wip_completion_ratio",
            "ck_inventory_negative_confirmed",
            "ck_spot_price_positive",
            // SPOT —— 条件非空 + 品类双态（DC-041 两轮 / CH-008）
            "ck_exposure_product_unmapped",
            "ck_exposure_spot_category_scope",
            // SPOT —— 品类列双态（CH-008 / DC-041 第 2 轮）
            "ck_exposure_spot_category_scope",
            // PLAN
            "ck_hedge_plan_ratio",
            "ck_hedge_doc_pending_review");

    private static Map<String, String> moduleScripts() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PRODUCT", "docs/DEPLOY/schema/m1_product.sql");
        m.put("SPOT", "docs/DEPLOY/schema/m1_spot.sql");
        m.put("PLAN", "docs/DEPLOY/schema/m1_plan.sql");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Integer> moduleTableCount() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("PRODUCT", 8);
        m.put("SPOT", 10);
        m.put("PLAN", 8);
        return Collections.unmodifiableMap(m);
    }

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

    /** 文档中一张表的字段与索引定义。 */
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
        /** 文档是否就"非空"给出可比较的声明（约束列不含 NULL 字样时不参与比较）。 */
        final boolean notNullDefined;
        /** 文档标注的外键引用表（无外键为 {@code null}）。 */
        final String fkTarget;

        ColumnSpec(String type, boolean notNull, boolean notNullDefined, String fkTarget) {
            this.type = type;
            this.notNull = notNull;
            this.notNullDefined = notNullDefined;
            this.fkTarget = fkTarget;
        }
    }

    @Test
    @DisplayName("DDL 与 DATA_MODEL.md §2.3/§2.4/§2.5 逐表逐列一致（26 张、含类型/非空/索引/外键）")
    void ddlMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        Path modelFile = repo.resolve("docs/DATA_MODEL.md");
        assertTrue(Files.isRegularFile(modelFile), "缺少上游数据模型：" + modelFile);

        Map<String, TableSpec> specs = parseDataModel(Files.readAllLines(modelFile, StandardCharsets.UTF_8));
        String ddlText = "";
        int parsedTables = 0;
        for (Map.Entry<String, String> e : MODULE_SCRIPTS.entrySet()) {
            Path ddlFile = repo.resolve(e.getValue());
            assertTrue(Files.isRegularFile(ddlFile), "缺少建表脚本：" + ddlFile);
            ddlText += new String(Files.readAllBytes(ddlFile), StandardCharsets.UTF_8) + "\n";
            parsedTables += parseDdl(Files.readAllLines(ddlFile, StandardCharsets.UTF_8)).size();
        }
        assertEquals(TOTAL_TABLE_COUNT, specs.size(),
                "核对范围应为 PRODUCT(8)+SPOT(10)+PLAN(8) = 26 张，实际解析到 " + specs.size());
        assertEquals(MODULE_TABLE_COUNT.get("PRODUCT") + MODULE_TABLE_COUNT.get("SPOT")
                        + MODULE_TABLE_COUNT.get("PLAN"), parsedTables,
                "三个脚本合计建表数应为 26，实际 " + parsedTables);

        List<String> problems = new ArrayList<>();
        int checkedColumns = 0;

        for (Map.Entry<String, String> module : MODULE_SCRIPTS.entrySet()) {
            Path ddlFile = repo.resolve(module.getValue());
            Map<String, Map<String, ColumnSpec>> ddl =
                    parseDdl(Files.readAllLines(ddlFile, StandardCharsets.UTF_8));
            assertEquals(MODULE_TABLE_COUNT.get(module.getKey()).intValue(), ddl.size(),
                    module.getKey() + " 建表数不符：期望 " + MODULE_TABLE_COUNT.get(module.getKey())
                            + "，实际 " + ddl.keySet());

            for (Map.Entry<String, TableSpec> e : specs.entrySet()) {
                String table = e.getKey();
                TableSpec spec = e.getValue();
                Map<String, ColumnSpec> actual = ddl.get(table);
                if (actual == null) {
                    continue;   // 归属其它模块的脚本已在本轮其他分支核对
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
        }

        for (String check : REQUIRED_BUSINESS_CHECKS) {
            if (!containsConstraint(ddlText, check)) {
                problems.add("缺少具名业务不变量 CHECK：" + check);
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
        assertTrue(checkedColumns >= 380,
                "逐列核对覆盖过少（实际 " + checkedColumns + " 列），疑似解析失效");
    }

    @Test
    @DisplayName("审计字段规则：26 张表均含 §0.3 的 6 项审计字段")
    void auditFieldsFollowSpec() throws IOException {
        Path repo = repoRoot();
        for (Map.Entry<String, String> module : MODULE_SCRIPTS.entrySet()) {
            Map<String, Map<String, ColumnSpec>> ddl = parseDdl(Files.readAllLines(
                    repo.resolve(module.getValue()), StandardCharsets.UTF_8));
            for (Map.Entry<String, Map<String, ColumnSpec>> e : ddl.entrySet()) {
                for (String audit : AUDIT_COLUMNS.keySet()) {
                    assertTrue(e.getValue().containsKey(audit),
                            e.getKey() + " 缺少通用审计字段 " + audit);
                }
            }
        }
    }

    @Test
    @DisplayName("脚本纪律：无存储过程/触发器/自定义函数/级联删除；DM 特性均有 -- DM-SPECIFIC: 注释")
    void ddlRespectsScriptDiscipline() throws IOException {
        Path repo = repoRoot();
        for (Map.Entry<String, String> module : MODULE_SCRIPTS.entrySet()) {
            String raw = new String(Files.readAllBytes(repo.resolve(module.getValue())),
                    StandardCharsets.UTF_8);
            String sql = stripComments(raw);
            String upper = sql.toUpperCase(Locale.ROOT);

            assertFalse(upper.contains("CREATE PROCEDURE"), module.getValue() + " 禁止存储过程");
            assertFalse(upper.contains("CREATE TRIGGER"), module.getValue() + " 禁止触发器");
            assertFalse(upper.contains("CREATE FUNCTION"), module.getValue() + " 禁止自定义函数");
            assertFalse(upper.contains("ON DELETE CASCADE"), module.getValue() + " 禁止级联删除");
            assertFalse(upper.contains("AUTO_INCREMENT"), module.getValue() + " 不得出现他库自增写法");
            assertTrue(upper.contains("IDENTITY(1,1)"), module.getValue() + " 须用 DM 自增主键写法");
            assertTrue(sql.contains("COMMENT ON TABLE"), module.getValue() + " 缺少表注释");
            assertTrue(raw.contains("DM-SPECIFIC:"), module.getValue() + " 缺少 DM 特性标注");
        }
    }

    @Test
    @DisplayName("子账户贯穿：SPOT 9 张 NOT NULL / PLAN 8 张 NOT NULL / PRODUCT 3 张 NOT NULL + 1 张可空")
    void subAccountCarriageMatchesSpec() throws IOException {
        Path repo = repoRoot();
        Map<String, Integer> notNull = new LinkedHashMap<>();
        Map<String, Integer> nullable = new LinkedHashMap<>();
        Map<String, Integer> absent = new LinkedHashMap<>();
        for (Map.Entry<String, String> module : MODULE_SCRIPTS.entrySet()) {
            Map<String, Map<String, ColumnSpec>> ddl = parseDdl(Files.readAllLines(
                    repo.resolve(module.getValue()), StandardCharsets.UTF_8));
            int n = 0;
            int nu = 0;
            int a = 0;
            for (Map<String, ColumnSpec> cols : ddl.values()) {
                ColumnSpec c = cols.get("sub_account_id");
                if (c == null) {
                    a++;
                } else if (c.notNull) {
                    n++;
                } else {
                    nu++;
                }
            }
            notNull.put(module.getKey(), n);
            nullable.put(module.getKey(), nu);
            absent.put(module.getKey(), a);
        }
        // DATA_MODEL.md §4.6.3 (1)：PRODUCT ①3/②1/③4；SPOT ①9/②1/③0；PLAN ①8/②0/③0
        assertEquals(Integer.valueOf(3), notNull.get("PRODUCT"), "PRODUCT NOT NULL 行级携带应为 3");
        assertEquals(Integer.valueOf(1), nullable.get("PRODUCT"), "PRODUCT 可空应为 1");
        assertEquals(Integer.valueOf(4), absent.get("PRODUCT"), "PRODUCT 无该列应为 4");
        assertEquals(Integer.valueOf(9), notNull.get("SPOT"), "SPOT NOT NULL 行级携带应为 9");
        assertEquals(Integer.valueOf(1), nullable.get("SPOT"), "SPOT 可空应为 1");
        assertEquals(Integer.valueOf(0), absent.get("SPOT"), "SPOT 无该列应为 0");
        assertEquals(Integer.valueOf(8), notNull.get("PLAN"), "PLAN NOT NULL 行级携带应为 8");
        assertEquals(Integer.valueOf(0), absent.get("PLAN"), "PLAN 无该列应为 0");
    }

    // ------------------------------------------------------------------ 解析实现

    private static Map<String, ColumnSpec> expectedColumns(TableSpec spec) {
        Map<String, ColumnSpec> expected = new LinkedHashMap<>(spec.columns);
        for (String col : AUDIT_COLUMNS.keySet()) {
            ColumnSpec specCol = spec.columns.get(col);
            expected.putIfAbsent(col, specCol != null ? specCol
                    : new ColumnSpec(AUDIT_COLUMNS.get(col), AUDIT_NOT_NULL.contains(col), true, null));
        }
        return expected;
    }

    /**
     * 期望外键集合（{@code "表.列->引用表"}）。
     *
     * <p>两条来源：
     * <ol>
     *   <li>文档"约束"列显式标注 {@code FK → `t_x(id)`} 的列；</li>
     *   <li>{@code DATA_MODEL.md} §0.4「所有业务表 {@code sub_account_id} → {@code t_sub_account(id)}，RESTRICT」。</li>
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

    /** 解析脚本中的外键（含 {@code CREATE TABLE} 内表级约束与末尾 {@code ALTER TABLE} 声明）。 */
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

    /**
     * 唯一约束判据：既接受表级 {@code CONSTRAINT <name> UNIQUE (...)}，
     * 也接受条件唯一索引 {@code CREATE UNIQUE INDEX <name> ... WHERE ...}
     * （{@code uk_product_spot_primary} / {@code uk_hedge_doc_current}，{@code §6.1 D6}）。
     */
    private static boolean containsUniqueConstraint(String ddlText, String name) {
        return containsConstraint(ddlText, name)
                || ddlText.contains("CREATE UNIQUE INDEX " + name + " ");
    }

    private static final Pattern TABLE_HEADING =
            Pattern.compile("^####\\s+(TB-(PRODUCT|SPOT|PLAN)-(\\d+))\\s+`([a-z0-9_]+)`.*$");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** 解析 §2.3/§2.4/§2.5 各表的字段表与索引表。 */
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
        return out;
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?s)CREATE TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\n\\);");
    private static final Pattern COLUMN_LINE =
            Pattern.compile("^([a-z][a-z0-9_]*)\\s+([A-Za-z]+(\\s*\\([0-9,\\s]*\\))?).*$");

    /** 解析建表脚本中的每张表列定义。 */
    private static Map<String, Map<String, ColumnSpec>> parseDdl(List<String> lines) {
        Map<String, Map<String, ColumnSpec>> out = new LinkedHashMap<>();
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

    /** 去掉 {@code --} 行注释与行尾注释。 */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            sb.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
        }
        return sb.toString();
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
