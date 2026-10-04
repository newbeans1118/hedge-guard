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
 * {@code T-045} 建表脚本与 {@code DATA_MODEL.md} 的逐列一致性校验（FUND 模块 9 张）。
 *
 * <p>本环境无 DM8 实例，无法「在 DM8 上建表成功」；本用例是替代证据：
 * 直接解析 {@code docs/DATA_MODEL.md} §2.11（FUND，TB-FUND-1~9）的字段表与索引表，与
 * {@code docs/DEPLOY/schema/m2_fund.sql} 的 {@code CREATE TABLE} 定义逐列比对：
 * <ul>
 *   <li>表数与表名一一对应（9 张）；</li>
 *   <li>每张表的列集合完全一致（含 §0.3 通用审计字段）；</li>
 *   <li>每列的类型（含精度）与非空约束一致（文档以 "—" 表「未规定类型」者跳过类型比较）；</li>
 *   <li>文档「索引」清单中的唯一/普通索引在脚本中均存在；</li>
 *   <li>外键数量与文档 FK 标注数量一致（含文件末尾 ALTER TABLE 后置外键）；</li>
 *   <li>子账户贯穿（§4.6.3）：① NOT NULL = 6 / ② 可空 = 3 / ③ 无该列 = 0；</li>
 *   <li>验收 2/3/4 落点：台账/流水子账户 NOT NULL 可加总、流水有凭证编号唯一、出入金幂等键唯一；</li>
 *   <li>验收 5 落点：本模块 9 个 DAO 接口均有对应 Dm* 实现类；</li>
 *   <li>脚本纪律：无存储过程/触发器/自定义函数、无级联删除、DM 特性有标注。</li>
 * </ul>
 *
 * <p>注意：本用例比对的是制品内部一致性，不替代 DM8 实机建表验证（部署方在可达实例复跑）。
 */
class FundSchemaTest {

    /** Markdown 反引号（避免在源码中书写反引号字符）。 */
    private static final String BT = String.valueOf((char) 96);

    private static final String DDL_REL = "docs/DEPLOY/schema/m2_fund.sql";

    /** 9 张表名。 */
    private static final List<String> TABLES = Arrays.asList(
            "t_fund_ledger", "t_fund_flow", "t_deposit_withdraw_apply", "t_transfer_apply",
            "t_capital_receipt", "t_fund_review_record", "t_fund_quota",
            "t_fund_quota_alert", "t_fund_reconcile");

    /** 实体名（= DAO 接口名去掉 Dao 后缀；验收 5 用）。 */
    private static final List<String> ENTITIES = Arrays.asList(
            "FundLedger", "FundFlow", "DepositWithdrawApply", "TransferApply",
            "CapitalReceipt", "FundReviewRecord", "FundQuota", "FundQuotaAlert", "FundReconcile");

    /** §0.3 通用审计字段。 */
    private static final Map<String, String> AUDIT_COLUMNS = auditColumns();

    private static final Set<String> AUDIT_NOT_NULL = new LinkedHashSet<>(Arrays.asList(
            "id", "created_by", "created_time", "row_version"));

    /** 文档/§4.3 点名、必须在脚本中以具名 CHECK 出现的业务不变量。 */
    private static final List<String> REQUIRED_BUSINESS_CHECKS = Arrays.asList(
            "ck_transfer_apply_diff_sub", "ck_fund_quota_alert_blocked");

    /** 幂等/唯一约束（验收 3/4）。 */
    private static final List<String> REQUIRED_UNIQUE = Arrays.asList(
            "uk_fund_flow_voucher_no", "uk_dw_apply_idem", "uk_dw_apply_no",
            "uk_capital_receipt_idem", "uk_fund_ledger_date_sub", "uk_fund_reconcile_key");

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
    @DisplayName("DDL 与 DATA_MODEL.md §2.11 逐表逐列一致（9 张、含类型/非空/索引/外键）")
    void ddlMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        Path modelFile = repo.resolve("docs/DATA_MODEL.md");
        Path ddlFile = repo.resolve(DDL_REL);
        assertTrue(Files.isRegularFile(modelFile), "缺少上游数据模型：" + modelFile);
        assertTrue(Files.isRegularFile(ddlFile), "缺少建表脚本：" + ddlFile);

        Map<String, TableSpec> specs = parseDataModel(Files.readAllLines(modelFile, StandardCharsets.UTF_8));
        assertEquals(9, specs.size(), "核对范围应为 FUND 9 张，实际解析到 " + specs.size());
        assertEquals(new TreeSet<>(TABLES), new TreeSet<>(specs.keySet()),
                "解析到的表名与 TB-FUND-1~9 不一致：" + specs.keySet());

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
    @DisplayName("子账户贯穿（§4.6.3）：FUND ① NOT NULL=6 / ② 可空=3 / ③ 无该列=0")
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
        assertEquals(6, notNull, "FUND NOT NULL 行级携带应为 6");
        assertEquals(3, nullable, "FUND 可空应为 3（t_fund_quota/t_fund_quota_alert/t_fund_reconcile，账户级）");
        assertEquals(0, absent, "FUND 无该列应为 0");
    }

    @Test
    @DisplayName("验收 2：资金台账/流水子账户 NOT NULL；台账含期初/期末与全部发生额列（可加总）")
    void acceptanceTwoAdditivity() throws IOException {
        Map<String, Map<String, ColumnSpec>> ddl = parseDdl(Files.readAllLines(
                repoRoot().resolve(DDL_REL), StandardCharsets.UTF_8));
        assertTrue(ddl.get("t_fund_ledger").get("sub_account_id").notNull,
                "t_fund_ledger.sub_account_id 须 NOT NULL（独立台账可加总的关键维度）");
        assertTrue(ddl.get("t_fund_flow").get("sub_account_id").notNull,
                "t_fund_flow.sub_account_id 须 NOT NULL（每条流水自带）");
        for (String col : Arrays.asList("begin_balance", "end_balance", "amount_in", "amount_out",
                "transfer_in", "transfer_out", "pnl_carryover", "commission", "margin_change",
                "difference_adjust")) {
            assertTrue(ddl.get("t_fund_ledger").containsKey(col),
                    "t_fund_ledger 须含可加总列 " + col + "（期初 + 发生额 = 期末）");
        }
        assertTrue(ddl.get("t_fund_flow").containsKey("balance_after"),
                "t_fund_flow 须含 balance_after（逐笔验证期初 + 发生额 = 期末）");
    }

    @Test
    @DisplayName("验收 3：资金流水每笔凭证编号（voucher_no NOT NULL + UNIQUE）；flow_type 覆盖 8 类发生额")
    void acceptanceThreeVoucher() throws IOException {
        String ddlText = new String(Files.readAllBytes(repoRoot().resolve(DDL_REL)), StandardCharsets.UTF_8);
        Map<String, ColumnSpec> flow = parseDdl(Files.readAllLines(
                repoRoot().resolve(DDL_REL), StandardCharsets.UTF_8)).get("t_fund_flow");
        assertTrue(flow.get("voucher_no").notNull, "voucher_no 须 NOT NULL（每笔有凭证编号）");
        assertTrue(containsUniqueConstraint(ddlText, "uk_fund_flow_voucher_no"),
                "voucher_no 须唯一（全系统唯一凭证编号）");
        assertTrue(ddlText.contains("CONSTRAINT ck_fund_flow_type "), "flow_type 须有具名 CHECK");
        for (String v : Arrays.asList("DEPOSIT", "WITHDRAW", "TRANSFER_OUT", "TRANSFER_IN",
                "PNL_CARRYOVER", "COMMISSION", "MARGIN_CHANGE", "DIFFERENCE_ADJUST")) {
            assertTrue(ddlText.contains("'" + v + "'"), "flow_type CHECK 应含取值 " + v);
        }
        assertTrue(ddlText.contains("CONSTRAINT ck_fund_quota_alert_blocked CHECK (blocked_operation = 'NONE')"),
                "ck_fund_quota_alert_blocked 须落到「告警不阻断」（blocked_operation 恒 NONE）");
    }

    @Test
    @DisplayName("验收 4：出入金幂等键 idempotency_key 唯一约束就位（uk_dw_apply_idem）")
    void acceptanceFourIdempotency() throws IOException {
        String ddlText = new String(Files.readAllBytes(repoRoot().resolve(DDL_REL)), StandardCharsets.UTF_8);
        assertTrue(containsUniqueConstraint(ddlText, "uk_dw_apply_idem"),
                "t_deposit_withdraw_apply.idempotency_key 须有唯一约束 uk_dw_apply_idem");
        Map<String, ColumnSpec> dw = parseDdl(Files.readAllLines(
                repoRoot().resolve(DDL_REL), StandardCharsets.UTF_8)).get("t_deposit_withdraw_apply");
        assertTrue(dw.get("idempotency_key").notNull, "idempotency_key 须 NOT NULL");
        assertTrue(containsUniqueConstraint(ddlText, "uk_capital_receipt_idem"),
                "t_capital_receipt 回执幂等键唯一约束应就位（重复回执幂等）");
    }

    @Test
    @DisplayName("验收 5：本模块 9 个 DAO 接口均有对应 Dm* 实现类（无沉默略过）")
    void acceptanceFiveDaoImplementations() throws IOException {
        Path repo = repoRoot();
        Path daoDir = repo.resolve("java/src/main/java/com/hedgeguard/dao");
        Path implDir = daoDir.resolve("impl");
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String e : ENTITIES) {
            Path iface = daoDir.resolve(e + "Dao.java");
            Path impl = implDir.resolve("Dm" + e + "Dao.java");
            if (!Files.isRegularFile(iface)) {
                problems.add("缺少 DAO 接口：" + e + "Dao.java");
            }
            if (!Files.isRegularFile(impl)) {
                problems.add("缺少 Dm* 实现：" + "Dm" + e + "Dao.java");
            }
            checked++;
        }
        assertEquals(9, checked, "应核对 9 个（模块表数）");
        assertTrue(problems.isEmpty(), "FUND 模块 DAO 接口/实现缺口：\n  - "
                + String.join("\n  - ", problems)
                + "\n（若某接口本期不实现，须在回执/TASKS 显式登记「延后实现 + 原因 + 承接任务」）");
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
            "^####\\s+(TB-FUND-(\\d+))\\s+" + BT + "([a-z0-9_]+)" + BT + ".*$");
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
