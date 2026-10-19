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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-076}：ACCT 模块 12 表建表脚本（docs/DEPLOY/schema/m4_acct.sql）与
 * {@code DATA_MODEL.md} §2.10 的逐列一致性校验（验收 1/3 的机器可复现证据）。
 *
 * <p>本环境无 DM8 实例（T-052 披露；CH-016），本用例为<b>替代证据</b>：解析文档 §2.10
 * （TB-ACCT-1~12）的字段表 / 索引表，与脚本 CREATE TABLE 逐列比对（表名、列集合、类型含精度、
 * 非空、唯一/普通索引、外键、文档声明的具名 CHECK）。DM8 实机建表归生产就绪阶段，不冒充（CH-016）。
 *
 * <p>另核验收 2（§4.6.3 ① 层 NOT NULL 子账户标识 7 表）与验收 3（{@code voucher_type}
 * 含行权/作废，v1.2 DC-031）。
 */
class M4AcctSchemaTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m4_acct.sql";
    private static final int EXPECTED_TABLES = 12;

    private static final Map<String, String> AUDIT_COLUMNS = auditColumns();
    private static final Set<String> AUDIT_NOT_NULL = new LinkedHashSet<>(
            Arrays.asList("id", "created_by", "created_time", "row_version"));

    /** §4.6.3 ① 层：ACCT 模块 NOT NULL 子账户标识 7 表（§4.6.1 #43~48、50）。 */
    private static final List<String> LAYER1_NOT_NULL_TABLES = Arrays.asList(
            "t_hedge_relation", "t_effectiveness_assess", "t_rebalance_record",
            "t_voucher", "t_voucher_line", "t_hedge_effect", "t_voucher_review");

    /** §4.6.3 ② 层：可空但已说明 2 表（§4.6.1 #49/#51）。 */
    private static final List<String> LAYER2_NULLABLE_TABLES = Arrays.asList(
            "t_dual_pnl_report", "t_cas24_selfcheck");

    /** §4.6.3 ③ 层：无该列 3 表（例外 E2 ×2 + 条目级）。 */
    private static final List<String> LAYER3_NO_COLUMN_TABLES = Arrays.asList(
            "t_accounting_period", "t_period_close_log", "t_cas24_selfcheck_item");

    private static final class Col {
        final String type;
        final boolean notNull;
        final boolean notNullDefined;
        final String fk;
        Col(String type, boolean notNull, boolean notNullDefined, String fk) {
            this.type = type; this.notNull = notNull; this.notNullDefined = notNullDefined; this.fk = fk;
        }
    }

    private static final class Spec {
        final String table;
        final Map<String, Col> cols = new LinkedHashMap<>();
        final List<String> uniq = new ArrayList<>();
        final List<String> idx = new ArrayList<>();
        Spec(String t) { this.table = t; }
    }

    private static Map<String, String> auditColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "BIGINT");
        m.put("created_by", "BIGINT");
        m.put("created_time", "TIMESTAMP");
        m.put("updated_by", "BIGINT");
        m.put("updated_time", "TIMESTAMP");
        m.put("row_version", "INT");
        return java.util.Collections.unmodifiableMap(m);
    }

    @Test
    @DisplayName("验收1：DDL 与 DATA_MODEL.md §2.10 逐表逐列一致（12 张，含类型/非空/索引/外键）")
    void ddlMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        Map<String, Spec> specs = parseDataModel(Files.readAllLines(repo.resolve("docs/DATA_MODEL.md"), StandardCharsets.UTF_8));
        Map<String, Map<String, Col>> ddl = parseDdl(Files.readAllLines(repo.resolve(SCRIPT), StandardCharsets.UTF_8));
        String ddlText = new String(Files.readAllBytes(repo.resolve(SCRIPT)), StandardCharsets.UTF_8);

        assertEquals(EXPECTED_TABLES, specs.size(), "文档 §2.10 应解析到 12 张表，实际 " + specs.size());
        assertEquals(EXPECTED_TABLES, ddl.size(), "脚本应建 12 张表，实际 " + ddl.keySet());
        assertEquals(new TreeSet<>(specs.keySet()), new TreeSet<>(ddl.keySet()), "表名集合不一致");

        List<String> problems = new ArrayList<>();
        int checkedColumns = 0;
        for (Map.Entry<String, Spec> e : specs.entrySet()) {
            Spec spec = e.getValue();
            Map<String, Col> actual = ddl.get(spec.table);
            Map<String, Col> expected = expectedColumns(spec);
            if (!new TreeSet<>(expected.keySet()).equals(new TreeSet<>(actual.keySet()))) {
                Set<String> missing = new TreeSet<>(expected.keySet()); missing.removeAll(actual.keySet());
                Set<String> extra = new TreeSet<>(actual.keySet()); extra.removeAll(expected.keySet());
                problems.add(spec.table + " 列集合不一致：脚本缺列=" + missing + "，脚本多列=" + extra);
                continue;
            }
            for (Map.Entry<String, Col> ce : expected.entrySet()) {
                checkedColumns++;
                Col exp = ce.getValue();
                Col act = actual.get(ce.getKey());
                if (!exp.type.equalsIgnoreCase(act.type)) {
                    problems.add(spec.table + "." + ce.getKey() + " 类型不一致：文档=" + exp.type + " 脚本=" + act.type);
                }
                if (exp.notNullDefined && exp.notNull != act.notNull) {
                    problems.add(spec.table + "." + ce.getKey() + " 非空不一致：文档 NOT NULL=" + exp.notNull + " 脚本=" + act.notNull);
                }
            }
            for (String u : spec.uniq) {
                if (!containsUnique(ddlText, u)) problems.add(spec.table + " 缺少文档声明的唯一约束：" + u);
            }
            for (String i : spec.idx) {
                if (!ddlText.contains("CREATE INDEX " + i + " ")) problems.add(spec.table + " 缺少文档声明的普通索引：" + i);
            }
        }
        Set<String> expectedFk = expectedForeignKeys(specs);
        Set<String> actualFk = parseForeignKeys(ddlText);
        if (!expectedFk.equals(actualFk)) {
            Set<String> miss = new TreeSet<>(expectedFk); miss.removeAll(actualFk);
            Set<String> more = new TreeSet<>(actualFk); more.removeAll(expectedFk);
            problems.add("外键不一致：脚本缺=" + miss + "，脚本多=" + more);
        }
        assertTrue(problems.isEmpty(), "建表脚本与 DATA_MODEL.md §2.10 不一致（" + problems.size() + " 项）：\n  - "
                + String.join("\n  - ", problems));
        assertTrue(checkedColumns >= 120, "逐列核对覆盖过少（实际 " + checkedColumns + " 列），疑似解析失效");
    }

    @Test
    @DisplayName("验收2：§4.6.3 ① 层 7 表 sub_account_id NOT NULL；② 层 2 表可空；③ 层 3 表无该列")
    void subAccountLayerAssignment() throws IOException {
        Map<String, Map<String, Col>> ddl = parseDdl(
                Files.readAllLines(repoRoot().resolve(SCRIPT), StandardCharsets.UTF_8));
        assertEquals(EXPECTED_TABLES, ddl.size(), "应解析到 12 张表");
        List<String> problems = new ArrayList<>();
        for (String t : LAYER1_NOT_NULL_TABLES) {
            Col c = ddl.get(t).get("sub_account_id");
            if (c == null) problems.add(t + " 缺 sub_account_id 列（§4.6.3 ① 层要求）");
            else if (!c.notNull) problems.add(t + ".sub_account_id 应 NOT NULL（§4.6.3 ① 层）");
        }
        for (String t : LAYER2_NULLABLE_TABLES) {
            Col c = ddl.get(t).get("sub_account_id");
            if (c == null) problems.add(t + " 缺 sub_account_id 列（② 层要求存在该列）");
            else if (c.notNull) problems.add(t + ".sub_account_id 应可空（② 层可空已说明，§4.6.1 #49/#51）");
        }
        for (String t : LAYER3_NO_COLUMN_TABLES) {
            if (ddl.get(t).containsKey("sub_account_id")) problems.add(t + " 不应有 sub_account_id 列（③ 层例外 E2/条目级）");
        }
        assertTrue(problems.isEmpty(), "三层划分与 §4.6.3 不一致：" + problems);
    }

    @Test
    @DisplayName("验收3：voucher_type 含 EXERCISE/EXPIRED_WORTHLESS（v1.2 DC-031）；凭证/自检待外部复核恒 Y")
    void voucherTypeExerciseAndExpiredWorthless() throws IOException {
        String sql = new String(Files.readAllBytes(repoRoot().resolve(SCRIPT)),
                StandardCharsets.UTF_8);
        assertTrue(sql.contains("'EXERCISE'") && sql.contains("'EXPIRED_WORTHLESS'"),
                "voucher_type CHECK 应含 EXERCISE / EXPIRED_WORTHLESS（v1.2 DC-031，FR-ACCT-11 落点）");
        // R-ACCT-10：待外部复核标注不得移除——t_voucher 与 t_cas24_selfcheck 恒 'Y'
        assertTrue(sql.contains("CONSTRAINT ck_t_voucher_pending_review CHECK (pending_external_review = 'Y')"),
                "t_voucher.pending_external_review 应恒 'Y'（R-ACCT-10）");
        assertTrue(sql.contains("CONSTRAINT ck_t_cas24_selfcheck_pending_review CHECK (pending_external_review = 'Y')"),
                "t_cas24_selfcheck.pending_external_review 应恒 'Y'（R-ACCT-10）");
        // v1.3.8/BUG-005/T-201：评估记录与再平衡记录亦带标注（四类套期会计文档承载齐备）
        assertTrue(sql.contains("CONSTRAINT ck_t_effectiveness_assess_pending_review CHECK (pending_external_review = 'Y')"),
                "t_effectiveness_assess.pending_external_review 应恒 'Y'（R-ACCT-10，v1.3.8 增列）");
        assertTrue(sql.contains("CONSTRAINT ck_t_rebalance_record_pending_review CHECK (pending_external_review = 'Y')"),
                "t_rebalance_record.pending_external_review 应恒 'Y'（R-ACCT-10，v1.3.8 增列）");
        assertTrue(sql.contains("CONSTRAINT ck_t_dual_pnl_report_spot_column CHECK (spot_pnl_column_present = 'Y')"),
                "t_dual_pnl_report.spot_pnl_column_present 应恒 'Y'（FR-ACCT-10 验收 2 不省略栏位的结构保证）");
        // CAS24 自检五条款齐备（TB-ACCT-12 / §3.1）
        for (String code : Arrays.asList("'FORMAL_DESIGNATION'", "'WRITTEN_DOC'",
                "'EFFECTIVENESS_RECORDS'", "'REBALANCE_RECORDS'", "'DUAL_PNL_PRESENTED'")) {
            assertTrue(sql.contains(code), "t_cas24_selfcheck_item.item_code 应含 " + code);
        }
        // 分录金额 > 0（§4.3：方向由 direction 表达）
        assertTrue(sql.contains("CONSTRAINT ck_t_voucher_line_amount_positive CHECK (amount > 0)"),
                "t_voucher_line.amount 应有 > 0 CHECK（§4.3）");
    }

    @Test
    @DisplayName("审计字段：12 张表均含 §0.3 的 6 项通用审计字段")
    void auditFieldsFollowSpec() throws IOException {
        Map<String, Map<String, Col>> ddl = parseDdl(
                Files.readAllLines(repoRoot().resolve(SCRIPT), StandardCharsets.UTF_8));
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, Col>> e : ddl.entrySet()) {
            for (Map.Entry<String, String> a : AUDIT_COLUMNS.entrySet()) {
                Col c = e.getValue().get(a.getKey());
                if (c == null) { problems.add(e.getKey() + " 缺审计字段 " + a.getKey()); continue; }
                if (!a.getValue().equalsIgnoreCase(c.type)) problems.add(e.getKey() + "." + a.getKey() + " 类型应为 " + a.getValue());
                if (a.getKey().equals("created_by") || a.getKey().equals("created_time")) {
                    if (!c.notNull) problems.add(e.getKey() + "." + a.getKey() + " 应 NOT NULL");
                }
            }
        }
        assertTrue(problems.isEmpty(), "审计字段不一致：" + problems);
    }

    private static Map<String, Col> expectedColumns(Spec spec) {
        Map<String, Col> expected = new LinkedHashMap<>(spec.cols);
        for (String col : AUDIT_COLUMNS.keySet()) {
            Col sc = spec.cols.get(col);
            expected.putIfAbsent(col, sc != null ? sc
                    : new Col(AUDIT_COLUMNS.get(col), AUDIT_NOT_NULL.contains(col), true, null));
        }
        return expected;
    }

    private static Set<String> expectedForeignKeys(Map<String, Spec> specs) {
        Set<String> out = new TreeSet<>();
        for (Spec spec : specs.values()) {
            for (Map.Entry<String, Col> e : spec.cols.entrySet()) {
                if (e.getValue().fk != null) out.add(spec.table + "." + e.getKey() + "->" + e.getValue().fk);
                else if ("sub_account_id".equals(e.getKey())) out.add(spec.table + ".sub_account_id->t_sub_account");
            }
        }
        return out;
    }

    private static final Pattern TABLE_HEADING =
            Pattern.compile("^####\\s+TB-ACCT-(\\d+)\\s+\\x60([a-z0-9_]+)\\x60.*$");
    private static final Pattern BACKTICKED = Pattern.compile("\\x60([^\\x60]+)\\x60");
    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static Map<String, Spec> parseDataModel(List<String> lines) {
        Map<String, Spec> out = new LinkedHashMap<>();
        Spec cur = null;
        boolean inIdx = false;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher h = TABLE_HEADING.matcher(line);
            if (h.matches()) { cur = new Spec(h.group(2)); out.put(cur.table, cur); inIdx = false; continue; }
            if (cur == null) continue;
            if (line.startsWith("### ") || line.startsWith("## ")) { cur = null; continue; }
            if (line.equals("**索引**：")) { inIdx = true; continue; }
            if (!line.startsWith("|")) {
                if (line.startsWith("**备注**")) { cur = null; inIdx = false; }
                continue;
            }
            List<String> cells = splitRow(line);
            if (cells.size() < 3 || cells.get(0).startsWith("字段") || cells.get(0).startsWith("---")) continue;
            if (inIdx) {
                String name = firstBacktick(cells.get(0));
                if (name == null) continue;
                if (cells.get(2).contains("唯一")) cur.uniq.add(name);
                else if (cells.get(2).contains("普通")) cur.idx.add(name);
                continue;
            }
            List<String> names = new ArrayList<>();
            List<String> compoundNames = new ArrayList<>();
            String firstField = null;
            // 复合行（两列同写一格）：`a` / `b`（全名，如 reopened_by/reopened_time）、
            // `a` / `_b`（第二列省略公共前缀，如 derivative_pnl_realized/_unrealized）
            for (String p : cells.get(0).split("`")) {
                String tok = p.trim();
                if (tok.matches("[a-z][a-z0-9_]*")) {
                    compoundNames.add(tok);
                    if (firstField == null) firstField = tok;
                } else if (tok.matches("_[a-z][a-z0-9_]*") && firstField != null) {
                    // `a_x` / `_y` 形态：第二列 = 首列去掉尾部与首列末段对齐后的公共前缀 + y
                    // （先例 `derivative_pnl_realized` / `_unrealized` ⇒ derivative_pnl_unrealized）
                    String suffix = tok.substring(1);
                    int cut = firstField.lastIndexOf('_');
                    String base = cut > 0 ? firstField.substring(0, cut + 1) : firstField;
                    compoundNames.add(base + suffix);
                }
            }
            for (String tok : backtickedAll(cells.get(0))) if (IDENT.matcher(tok).matches()) names.add(tok);
            if (compoundNames.size() > names.size()) names = compoundNames;
            if (names.isEmpty()) continue;
            String type = baseType(firstBacktick(cells.get(1)));
            // 复合行的类型格可能为 `BIGINT` / `TIMESTAMP` 两型（逐列对应）
            List<String> types = backtickedAll(cells.get(1));
            String con = cells.size() > 2 ? cells.get(2) : "";
            boolean defines = con.toUpperCase(Locale.ROOT).contains("NULL");
            String fk = fkTargetOf(con);
            for (int n = 0; n < names.size(); n++) {
                String colType = n < types.size() ? baseType(types.get(n)) : type;
                cur.cols.put(names.get(n), new Col(colType, con.contains("NOT NULL"), defines, fk));
            }
        }
        return out;
    }

    private static final Pattern CREATE_TABLE = Pattern.compile("(?s)CREATE TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\n\\);");
    private static final Pattern COLUMN_LINE = Pattern.compile("^([a-z][a-z0-9_]*)\\s+([A-Za-z]+(\\s*\\([0-9,\\s]*\\))?).*$");

    private static Map<String, Map<String, Col>> parseDdl(List<String> lines) {
        Map<String, Map<String, Col>> out = new LinkedHashMap<>();
        String text = String.join("\n", lines);
        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            Map<String, Col> cols = new TreeMap<>();
            for (String raw : m.group(2).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("--")) continue;
                String head = line.split("\\s+")[0].toUpperCase(Locale.ROOT);
                if (head.equals("CONSTRAINT") || head.equals("PRIMARY") || head.equals("FOREIGN")
                        || head.equals("UNIQUE") || head.equals("CHECK")) continue;
                Matcher c = COLUMN_LINE.matcher(line);
                if (!c.matches()) continue;
                cols.put(c.group(1), new Col(baseType(c.group(2)), line.toUpperCase(Locale.ROOT).contains("NOT NULL"), true, null));
            }
            out.put(m.group(1), cols);
        }
        return out;
    }

    private static boolean containsUnique(String text, String name) {
        return text.contains("CONSTRAINT " + name + " UNIQUE (")
                || text.contains("CREATE UNIQUE INDEX " + name + " ");
    }

    private static Set<String> parseForeignKeys(String text) {
        Set<String> out = new TreeSet<>();
        Matcher t = CREATE_TABLE.matcher(text);
        while (t.find()) {
            Matcher fk = Pattern.compile("CONSTRAINT\\s+\\w+\\s+FOREIGN\\s+KEY\\s*\\(([a-z0-9_]+)\\)\\s*REFERENCES\\s+([a-z0-9_]+)")
                    .matcher(t.group(2));
            while (fk.find()) out.add(t.group(1) + "." + fk.group(1) + "->" + fk.group(2));
        }
        return out;
    }

    private static String fkTargetOf(String cell) {
        int i = cell.indexOf("FK");
        if (i < 0) return null;
        List<String> toks = backtickedAll(cell.substring(i));
        if (toks.isEmpty()) return null;
        String t = toks.get(0);
        int p = t.indexOf('(');
        return p < 0 ? t : t.substring(0, p);
    }

    private static List<String> splitRow(String line) {
        String body = line.startsWith("|") ? line.substring(1) : line;
        if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
        return Arrays.asList(body.split("\\|", -1));
    }

    private static String firstBacktick(String cell) {
        List<String> all = backtickedAll(cell);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<String> backtickedAll(String cell) {
        List<String> out = new ArrayList<>();
        Matcher m = BACKTICKED.matcher(cell);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    private static String baseType(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        int s = t.indexOf(' ');
        if (s > 0) t = t.substring(0, s);
        return t.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) return p;
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；cwd=" + Paths.get("").toAbsolutePath());
    }
}
