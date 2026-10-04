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
 * SETTLE 模块建表脚本（docs/DEPLOY/schema/m2_settle.sql）与 DATA_MODEL.md 第 2.7 节的
 * 逐列一致性校验（T-044 验收 1/2/3 的机器可复现证据）。
 *
 * <p>本环境无 DM8 实例，无法在 DM8 上建表；本用例是替代证据：解析文档 §2.7（TB-SETTLE-1~16）
 * 的字段表 / 索引表，与脚本 CREATE TABLE 逐列比对（表名、列集合、类型含精度、非空、
 * 唯一/普通索引、外键、文档点名的具名 CHECK）。
 */
class M2SettleSchemaTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m2_settle.sql";
    private static final int EXPECTED_TABLES = 16;

    private static final Map<String, String> AUDIT_COLUMNS = auditColumns();
    private static final Set<String> AUDIT_NOT_NULL = new LinkedHashSet<>(
            Arrays.asList("id", "created_by", "created_time", "row_version"));

    private static final List<String> REQUIRED_BUSINESS_CHECKS = Arrays.asList(
            "ck_check_task_scope_sentinel",
            "ck_check_signature_scope_sentinel");

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
        return Collections.unmodifiableMap(m);
    }

    @Test
    @DisplayName("DDL 与 DATA_MODEL.md 第 2.7 节逐表逐列一致（16 张，含类型/非空/索引/外键/具名 CHECK）")
    void ddlMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        Map<String, Spec> specs = parseDataModel(Files.readAllLines(repo.resolve("docs/DATA_MODEL.md"), StandardCharsets.UTF_8));
        Map<String, Map<String, Col>> ddl = parseDdl(Files.readAllLines(repo.resolve(SCRIPT), StandardCharsets.UTF_8));
        String ddlText = new String(Files.readAllBytes(repo.resolve(SCRIPT)), StandardCharsets.UTF_8);

        assertEquals(EXPECTED_TABLES, specs.size(), "文档 §2.7 应解析到 16 张表，实际 " + specs.size());
        assertEquals(EXPECTED_TABLES, ddl.size(), "脚本应建 16 张表，实际 " + ddl.keySet());
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
        for (String ck : REQUIRED_BUSINESS_CHECKS) {
            if (!ddlText.contains("CONSTRAINT " + ck + " ")) problems.add("缺少具名 CHECK：" + ck);
        }
        Set<String> expectedFk = expectedForeignKeys(specs);
        Set<String> actualFk = parseForeignKeys(ddlText);
        if (!expectedFk.equals(actualFk)) {
            Set<String> miss = new TreeSet<>(expectedFk); miss.removeAll(actualFk);
            Set<String> more = new TreeSet<>(actualFk); more.removeAll(expectedFk);
            problems.add("外键不一致：脚本缺=" + miss + "，脚本多=" + more);
        }
        assertTrue(problems.isEmpty(), "建表脚本与 DATA_MODEL.md 不一致（" + problems.size() + " 项）：\n  - "
                + String.join("\n  - ", problems));
        assertTrue(checkedColumns >= 180, "逐列核对覆盖过少（实际 " + checkedColumns + " 列），疑似解析失效");
    }

    @Test
    @DisplayName("验收2：结算持仓/资金/盈亏表子账户标识 NOT NULL（可加总）；uk_check_signature_task_role 就位")
    void settleAddUpAndNoProxySign() throws IOException {
        Path repo = repoRoot();
        Map<String, Map<String, Col>> ddl = parseDdl(Files.readAllLines(repo.resolve(SCRIPT), StandardCharsets.UTF_8));
        for (String t : Arrays.asList("t_settle_position", "t_settle_fund", "t_settle_pnl")) {
            assertTrue(ddl.containsKey(t), "缺少表：" + t);
            Col c = ddl.get(t).get("sub_account_id");
            assertTrue(c != null && c.notNull, t + ".sub_account_id 应 NOT NULL（子账户可加总的物理前提）");
        }
        String text = new String(Files.readAllBytes(repo.resolve(SCRIPT)), StandardCharsets.UTF_8);
        List<String> cols = uniqueColumns(text, "uk_check_signature_task_role");
        assertEquals(Arrays.asList("check_task_id", "sign_role"), cols,
                "uk_check_signature_task_role 应为 (check_task_id, sign_role) —— 下沉不可代签/不可重复签");
        assertTrue(text.contains("CONSTRAINT ck_check_signature_scope_sentinel CHECK"),
                "t_check_signature 应有范围标注型作用域 CHECK");
    }

    @Test
    @DisplayName("验收3：t_agg_diff_candidate（候选态）与 t_reconcile_difference.sub_account_id（定位态）字段齐备")
    void aggregationDiffMachineJudgementFields() throws IOException {
        Path repo = repoRoot();
        Map<String, Map<String, Col>> ddl = parseDdl(Files.readAllLines(repo.resolve(SCRIPT), StandardCharsets.UTF_8));
        Map<String, Col> diff = ddl.get("t_reconcile_difference");
        assertTrue(diff != null && diff.containsKey("sub_account_id"), "t_reconcile_difference.sub_account_id 应就位（定位态）");
        assertFalse(diff.get("sub_account_id").notNull, "定位态可空（可定位时非空，未定位为 NULL）");
        Map<String, Col> cand = ddl.get("t_agg_diff_candidate");
        assertTrue(cand != null, "缺少 t_agg_diff_candidate");
        for (String c : Arrays.asList("sub_account_id", "aggregation_check_id", "candidate_rank",
                "diff_amount", "resolution_status", "reconcile_difference_id", "evidence_desc")) {
            assertTrue(cand.containsKey(c), "t_agg_diff_candidate 缺字段：" + c);
        }
        assertTrue(cand.get("sub_account_id").notNull, "候选归属子账户应 NOT NULL");
        String text = new String(Files.readAllBytes(repo.resolve(SCRIPT)), StandardCharsets.UTF_8);
        assertTrue(text.contains("CHECK (resolution_status IN ('PENDING', 'CONFIRMED', 'EXCLUDED'))"),
                "resolution_status 取值域应含 PENDING/CONFIRMED/EXCLUDED（阻断生效集合 = {PENDING,CONFIRMED}）");
    }

    @Test
    @DisplayName("审计字段：16 张表均含 §0.3 的 6 项通用审计字段")
    void auditFieldsFollowSpec() throws IOException {
        Path repo = repoRoot();
        Map<String, Map<String, Col>> ddl = parseDdl(Files.readAllLines(repo.resolve(SCRIPT), StandardCharsets.UTF_8));
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
            Pattern.compile("^####\\s+TB-SETTLE-(\\d+)\\s+\\x60([a-z0-9_]+)\\x60.*$");
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
            for (String tok : backtickedAll(cells.get(0))) if (IDENT.matcher(tok).matches()) names.add(tok);
            if (names.isEmpty()) continue;
            String type = baseType(firstBacktick(cells.get(1)));
            String con = cells.size() > 2 ? cells.get(2) : "";
            boolean defines = con.toUpperCase(Locale.ROOT).contains("NULL");
            String fk = fkTargetOf(con);
            for (String n : names) cur.cols.put(n, new Col(type, con.contains("NOT NULL"), defines, fk));
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

    private static List<String> uniqueColumns(String text, String name) {
        Matcher m = Pattern.compile("CONSTRAINT\\s+" + name + "\\s+UNIQUE\\s*\\(([^)]*)\\)").matcher(text);
        if (m.find()) return splitCols(m.group(1));
        Matcher i = Pattern.compile("CREATE\\s+UNIQUE\\s+INDEX\\s+" + name + "\\s+ON\\s+\\w+\\s*\\(([^)]*)\\)").matcher(text);
        return i.find() ? splitCols(i.group(1)) : Collections.emptyList();
    }

    private static List<String> splitCols(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) out.add(p.trim());
        return out;
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

