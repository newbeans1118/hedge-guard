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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-094}：RISK 模块余 10 表建表脚本与 {@code DATA_MODEL.md} 的机器校验
 * （替代证据；本机无 DM8 实例，归 {@code T-052}）。
 *
 * <p>三项判据：
 * <ol>
 *   <li><b>列级</b>：10 表逐列（列集合 / 类型 / 非空）与 §2.8 一致，且文档声明的唯一 / 普通索引与
 *       外键数逐表吻合；</li>
 *   <li><b>作用域哨兵值具名 CHECK 三处一致</b>：{@code ck_annual_scale_scope_sentinel} /
 *       {@code ck_risk_threshold_scope_sentinel} 的条件在 §4.3（权威）/ 本文件头 / 表定义三处逐字一致；</li>
 *   <li><b>验收 4</b>：{@code t_risk_threshold} 具备「默认值不得低于 {@code INDEX.md} §9.1 口径」的存储表达
 *       （{@code default_value NOT NULL} + {@code is_compliance_redline} + {@code min_value}/{@code max_value}）。</li>
 * </ol>
 */
class M3RiskSchemaTest {

    /** T-094 交付的 10 张表（编号以 DATA_MODEL §2.8 实际编号为准）。 */
    private static final List<String> TABLES = Arrays.asList(
            "t_annual_scale", "t_risk_threshold", "t_mirror_watermark", "t_risk_alert",
            "t_risk_disposal", "t_abnormal_order_stat", "t_post_risk_report",
            "t_risk_violation", "t_limit_usage_stat", "t_risk_metric_snapshot");

    /** §4.3 双向联动型具名 CHECK（表名 → 约束名）。 */
    private static final Map<String, String> SENTINEL = new LinkedHashMap<>();
    static {
        SENTINEL.put("t_annual_scale", "ck_annual_scale_scope_sentinel");
        SENTINEL.put("t_risk_threshold", "ck_risk_threshold_scope_sentinel");
    }

    /**
     * RISK 模块 11 表（{@code T-094} 的 10 表 + 第 11 表 {@code t_param_dispatch}）逐表具名 CHECK 清单。
     *
     * <p>本案（{@code TE-T094-001}）盲区＝「非哨兵 CHECK 无人校验」：{@code ck_limit_usage_ratio_nonneg}
     * 由 {@code DATA_MODEL.md} §4.3「精度相关的强约束均已在各表 CHECK 中声明」点名却漏落生产 DDL。
     * 故此处以「表 → 约束名集合」数据驱动做<b>双向</b>比对（缺一必红 / 未登记的新增也必红）。
     */
    private static final Map<String, List<String>> NAMED_CHECKS = namedChecks();

    private static Map<String, List<String>> namedChecks() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("t_param_dispatch", Arrays.asList(
                "ck_param_dispatch_type", "ck_param_dispatch_status", "ck_param_dispatch_batch"));
        m.put("t_annual_scale", Arrays.asList(
                "ck_annual_scale_scope_sentinel", "ck_annual_scale_scope_type",
                "ck_annual_scale_category", "ck_annual_scale_dispatch_status"));
        m.put("t_risk_threshold", Arrays.asList(
                "ck_risk_threshold_scope_sentinel", "ck_risk_threshold_value_type",
                "ck_risk_threshold_redline", "ck_risk_threshold_scope_type",
                "ck_risk_threshold_dispatch_status"));
        m.put("t_mirror_watermark", Arrays.asList(
                "ck_mirror_watermark_stream_type", "ck_mirror_watermark_lag_alarm",
                "ck_mirror_watermark_conn_state"));
        m.put("t_risk_disposal", Arrays.asList(
                "ck_risk_disposal_is_global", "ck_risk_disposal_type",
                "ck_risk_disposal_scan_scope", "ck_risk_disposal_hint_accepted",
                "ck_risk_disposal_dispatch_status"));
        m.put("t_risk_alert", Arrays.asList(
                "ck_risk_alert_type", "ck_risk_alert_level", "ck_risk_alert_disposed"));
        m.put("t_abnormal_order_stat", Arrays.asList(
                "ck_abnormal_stat_is_abnormal", "ck_abnormal_stat_action"));
        m.put("t_post_risk_report", Arrays.asList("ck_post_risk_report_type"));
        m.put("t_risk_violation", Arrays.asList(
                "ck_risk_violation_type", "ck_risk_violation_severity", "ck_risk_violation_source"));
        m.put("t_limit_usage_stat", Arrays.asList(
                "ck_limit_usage_limit_type", "ck_limit_usage_hint_level",
                "ck_limit_usage_ratio_nonneg"));
        m.put("t_risk_metric_snapshot", Arrays.asList("ck_risk_metric_source"));
        return m;
    }

    private static final Map<String, String> AUDIT = audit();

    private static Map<String, String> audit() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "BIGINT");
        m.put("created_by", "BIGINT");
        m.put("created_time", "TIMESTAMP");
        m.put("updated_by", "BIGINT");
        m.put("updated_time", "TIMESTAMP");
        m.put("row_version", "INT");
        return m;
    }

    @Test
    @DisplayName("T-094 十表列/类型/非空 + 文档声明的索引/外键 与 §2.8 逐表一致")
    void schemaMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        List<String> dm = Files.readAllLines(repo.resolve("docs/DATA_MODEL.md"), StandardCharsets.UTF_8);
        String sql = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_risk.sql")),
                StandardCharsets.UTF_8);
        Map<String, DocTable> docs = parseDocTables(dm);

        assertEquals(new TreeSet<>(TABLES), new TreeSet<>(docs.keySet()),
                "§2.8 未解析到全部 10 张目标表；实测=" + docs.keySet());

        List<String> problems = new ArrayList<>();
        for (String table : TABLES) {
            DocTable d = docs.get(table);
            Map<String, String[]> cols = parseDdlTable(sql, table);
            if (cols.isEmpty()) {
                problems.add(table + " 在 DDL 中未解析到任何列");
                continue;
            }
            Map<String, String> expType = new LinkedHashMap<>(d.colType);
            Map<String, Boolean> expNotNull = new LinkedHashMap<>(d.colNotNull);
            for (Map.Entry<String, String> a : AUDIT.entrySet()) {
                if (!expType.containsKey(a.getKey())) {
                    expType.put(a.getKey(), a.getValue());
                    expNotNull.put(a.getKey(), !a.getKey().startsWith("updated_"));
                }
            }
            if (!new TreeSet<>(expType.keySet()).equals(new TreeSet<>(cols.keySet()))) {
                problems.add(table + " 列集合不一致：文档+审计=" + new TreeSet<>(expType.keySet())
                        + " 脚本=" + new TreeSet<>(cols.keySet()));
                continue;
            }
            for (Map.Entry<String, String> e : expType.entrySet()) {
                String[] act = cols.get(e.getKey());
                if (!e.getValue().equalsIgnoreCase(act[0])) {
                    problems.add(table + "." + e.getKey() + " 类型不一致：文档=" + e.getValue()
                            + " 脚本=" + act[0]);
                }
                boolean expNn = Boolean.TRUE.equals(expNotNull.get(e.getKey()));
                if (expNn != Boolean.parseBoolean(act[1])) {
                    problems.add(table + "." + e.getKey() + " 非空不一致：文档 NOT NULL=" + expNn
                            + " 脚本=" + act[1]);
                }
            }
            for (String u : d.unique) {
                if (!sql.contains("CONSTRAINT " + u + " ")) {
                    problems.add(table + " 缺唯一约束：" + u);
                }
            }
            for (String n : d.normal) {
                if (!sql.contains("CREATE INDEX " + n + " ")) {
                    problems.add(table + " 缺普通索引：" + n);
                }
            }
            int fk = countToken(sqlBlock(sql, table), "FOREIGN KEY");
            if (fk != d.fkTargets) {
                problems.add(table + " 外键数不一致：文档 FK 标注=" + d.fkTargets + " 脚本=" + fk);
            }
        }
        assertTrue(problems.isEmpty(), "脚本与 §2.8 不一致：\n  - " + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("作用域哨兵值具名 CHECK：§4.3(权威) 与 文件头 与 表定义 三处逐字一致")
    void sentinelChecksThreeWayConsistent() throws IOException {
        Path repo = repoRoot();
        String dm = new String(Files.readAllBytes(repo.resolve("docs/DATA_MODEL.md")), StandardCharsets.UTF_8);
        String sql = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_risk.sql")),
                StandardCharsets.UTF_8);
        String normDm = compact(dm);
        String normSql = compact(sql);

        for (Map.Entry<String, String> e : SENTINEL.entrySet()) {
            String cond = extractCheck(dm, e.getValue());
            assertNotNull(cond, "§4.3 未找到 " + e.getValue());
            String normCond = compact(cond);
            assertTrue(normCond.length() > 20,
                    e.getValue() + " 条件过短，疑似提取失败：" + normCond);
            assertTrue(normDm.contains(normCond),
                    e.getValue() + " 条件未出现在 §4.3（权威）");
            int hits = countOccurrences(normSql, normCond);
            assertTrue(hits >= 2,
                    e.getValue() + " 条件在 m3_risk.sql 中仅出现 " + hits + " 次（应 ≥2：文件头 + 表定义）");
            assertTrue(sqlBlock(sql, e.getKey()).contains("CONSTRAINT " + e.getValue() + " CHECK ("),
                    e.getKey() + " 表定义缺具名 CHECK：" + e.getValue());
        }
    }

    @Test
    @DisplayName("具名 CHECK 清单：RISK 11 表逐表比对（缺一必红；未登记新增同红）")
    void namedChecksPresent() throws IOException {
        Path repo = repoRoot();
        String sql = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_risk.sql")),
                StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : NAMED_CHECKS.entrySet()) {
            TreeSet<String> actual = namedCheckNames(sqlBlock(sql, e.getKey()));
            TreeSet<String> expected = new TreeSet<>(e.getValue());
            if (!expected.equals(actual)) {
                TreeSet<String> missing = new TreeSet<>(expected);
                missing.removeAll(actual);
                TreeSet<String> extra = new TreeSet<>(actual);
                extra.removeAll(expected);
                problems.add(e.getKey() + " 缺=" + missing + " 多=" + extra);
            }
        }
        assertTrue(problems.isEmpty(), "具名 CHECK 与清单不一致：\n  - " + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("验收 4：t_risk_threshold 支撑默认值不得低于 INDEX.md §9.1 口径的存储表达")
    void riskThresholdSupportsComplianceFloor() throws IOException {
        Path repo = repoRoot();
        String sql = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_risk.sql")),
                StandardCharsets.UTF_8);
        Map<String, String[]> cols = parseDdlTable(sql, "t_risk_threshold");
        assertTrue(cols.containsKey("default_value"), "缺 default_value 列");
        assertEquals("VARCHAR(128)", cols.get("default_value")[0].toUpperCase(Locale.ROOT),
                "default_value 类型应为 VARCHAR(128)");
        assertTrue(Boolean.parseBoolean(cols.get("default_value")[1]), "default_value 须 NOT NULL");
        assertTrue(cols.containsKey("is_compliance_redline"), "缺 is_compliance_redline 列");
        assertTrue(cols.containsKey("min_value") && cols.containsKey("max_value"),
                "缺 min_value/max_value（输入越界拒绝的可判数据）");
        assertTrue(sql.contains("INDEX.md §9.1"),
                "DDL 注释须点明默认值口径来源 INDEX.md §9.1");
    }

    @Test
    @DisplayName("脚本纪律：11 表齐全、无存储过程/触发器/函数、无级联删除、DM 特性带注释")
    void discipline() throws IOException {
        Path repo = repoRoot();
        String raw = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_risk.sql")),
                StandardCharsets.UTF_8);
        int createTables = 0;
        for (String line : raw.split("\n", -1)) {
            if (line.startsWith("CREATE TABLE ")) {
                createTables++;
            }
        }
        assertEquals(11, createTables, "m3_risk.sql 的 CREATE TABLE 应为 11（TB-RISK-1~11）");
        String upper = stripComments(raw).toUpperCase(Locale.ROOT);
        assertTrue(!upper.contains("CREATE PROCEDURE"), "禁止存储过程");
        assertTrue(!upper.contains("CREATE TRIGGER"), "禁止触发器");
        assertTrue(!upper.contains("CREATE FUNCTION"), "禁止自定义函数");
        assertTrue(!upper.contains("ON DELETE CASCADE"), "禁止级联删除（§0.4）");
        assertTrue(raw.contains("-- DM-SPECIFIC:"), "DM 特性须带注释");
        assertTrue(raw.contains("CONSTRAINT ck_annual_scale_scope_sentinel CHECK ("),
                "缺 ck_annual_scale_scope_sentinel");
        assertTrue(raw.contains("CONSTRAINT ck_risk_threshold_scope_sentinel CHECK ("),
                "缺 ck_risk_threshold_scope_sentinel");
    }

    // ------------------------------------------------------------------ 文档解析

    private static final class DocTable {
        final Map<String, String> colType = new LinkedHashMap<>();
        final Map<String, Boolean> colNotNull = new LinkedHashMap<>();
        final List<String> unique = new ArrayList<>();
        final List<String> normal = new ArrayList<>();
        int fkTargets;
    }

    private static Map<String, DocTable> parseDocTables(List<String> lines) {
        Map<String, DocTable> out = new LinkedHashMap<>();
        DocTable cur = null;
        boolean inIdx = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#### ")) {
                cur = null;
                inIdx = false;
                String t = firstBacktick(line);
                if (t != null && TABLES.contains(t)) {
                    cur = new DocTable();
                    out.put(t, cur);
                }
                continue;
            }
            if (cur == null) {
                continue;
            }
            if (line.equals("**索引**：")) {
                inIdx = true;
                continue;
            }
            if (line.startsWith("**备注**")) {
                cur = null;
                inIdx = false;
                continue;
            }
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> c = cells(line);
            if (c.size() < 3) {
                continue;
            }
            String first = c.get(0).trim();
            if (first.startsWith("字段") || first.startsWith("---") || first.startsWith("索引名")) {
                continue;
            }
            if (inIdx) {
                String name = firstBacktick(c.get(0));
                if (name == null) {
                    continue;
                }
                if (c.get(2).contains("唯一")) {
                    cur.unique.add(name);
                } else if (c.get(2).contains("普通")) {
                    cur.normal.add(name);
                }
            } else {
                List<String> names = identsInBackticks(c.get(0));
                if (names.isEmpty()) {
                    continue;
                }
                String type = baseType(firstBacktick(c.get(1)));
                String cons = c.get(2);
                for (String n : names) {
                    cur.colType.put(n, type);
                    cur.colNotNull.put(n, cons.contains("NOT NULL"));
                }
                if (cons.contains("FK")) {
                    cur.fkTargets += names.size();
                }
            }
        }
        return out;
    }

    /** 取 §4.3 中某具名 CHECK 的条件文本（介于 CHECK ( 与 行尾 ); 之间）。 */
    private static String extractCheck(String dm, String name) {
        String marker = "ADD CONSTRAINT " + name + " CHECK (";
        int i = dm.indexOf(marker);
        if (i < 0) {
            return null;
        }
        int start = i + marker.length();
        int end = dm.indexOf("\n);", start);
        return end < 0 ? null : dm.substring(start, end);
    }

    // ------------------------------------------------------------------ DDL 解析

    /** 抽取某张表的 CREATE TABLE 主体（括号内文本）。 */
    private static String sqlBlock(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE " + table + " (");
        assertTrue(start >= 0, "DDL 缺表：" + table);
        int open = sql.indexOf('(', start);
        int end = sql.indexOf("\n);", open);
        assertTrue(end > open, "DDL 表未以 ); 收尾：" + table);
        return sql.substring(open + 1, end);
    }

    /** 列名 → {类型, 是否 NOT NULL}。 */
    private static Map<String, String[]> parseDdlTable(String sql, String table) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String raw : sqlBlock(sql, table).split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            int sp = firstWhitespace(line);
            if (sp <= 0) {
                continue;
            }
            String name = line.substring(0, sp);
            if (!isLowerIdent(name)) {
                continue;
            }
            String rest = line.substring(sp).trim();
            int sp2 = firstWhitespace(rest);
            String type = sp2 < 0 ? rest : rest.substring(0, sp2);
            type = type.trim();
            boolean notNull = line.toUpperCase(Locale.ROOT).contains("NOT NULL");
            out.put(name, new String[]{type, Boolean.toString(notNull)});
        }
        return out;
    }

    /** 抽取某表 DDL（去注释）中「CONSTRAINT <名> CHECK」的约束名集合。 */
    private static TreeSet<String> namedCheckNames(String block) {
        TreeSet<String> out = new TreeSet<>();
        for (String raw : stripComments(block).split("\n", -1)) {
            String line = raw.trim();
            if (!line.startsWith("CONSTRAINT ")) {
                continue;
            }
            String rest = line.substring("CONSTRAINT ".length()).trim();
            int sp = firstWhitespace(rest);
            if (sp <= 0) {
                continue;
            }
            String name = rest.substring(0, sp).trim();
            if (isLowerIdent(name) && rest.substring(sp).trim().startsWith("CHECK ")) {
                out.add(name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 小工具

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isLowerIdent(String s) {
        if (s.isEmpty() || !(Character.isLowerCase(s.charAt(0)) || s.charAt(0) == '_')) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLowerCase(c) || Character.isDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static String baseType(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static final char BT = (char) 96;

    /** 提取首个反引号引段内容。 */
    private static String firstBacktick(String s) {
        int a = s.indexOf(BT);
        if (a < 0) {
            return null;
        }
        int b = s.indexOf(BT, a + 1);
        return b < 0 ? null : s.substring(a + 1, b).trim();
    }

    /** 所有反引号段中形如小写标识符者。 */
    private static List<String> identsInBackticks(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int a = s.indexOf(BT, i);
            if (a < 0) {
                break;
            }
            int b = s.indexOf(BT, a + 1);
            if (b < 0) {
                break;
            }
            String v = s.substring(a + 1, b).trim();
            if (isLowerIdent(v)) {
                out.add(v);
            }
            i = b + 1;
        }
        return out;
    }

    /** 以竖线分列（去首尾空段）。 */
    private static List<String> cells(String line) {
        List<String> raw = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '|') {
                raw.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        raw.add(sb.toString());
        if (!raw.isEmpty() && raw.get(0).trim().isEmpty()) {
            raw.remove(0);
        }
        if (!raw.isEmpty() && raw.get(raw.size() - 1).trim().isEmpty()) {
            raw.remove(raw.size() - 1);
        }
        return raw;
    }

    private static int countToken(String s, String token) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(token, i)) >= 0) {
            n++;
            i += token.length();
        }
        return n;
    }

    private static int countOccurrences(String haystack, String needle) {
        return countToken(haystack, needle);
    }

    /** 去空白与连字符（使文件头注释行前缀与表定义对齐差异归一）。 */
    private static String compact(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            int i = line.indexOf("--");
            sb.append(i < 0 ? line : line.substring(0, i)).append('\n');
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
        throw new IllegalStateException("未找到仓库根；cwd=" + Paths.get("").toAbsolutePath());
    }
}
