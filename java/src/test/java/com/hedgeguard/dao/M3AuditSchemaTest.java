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
 * {@code T-062}：AUDIT 模块 8 表建表脚本与 {@code DATA_MODEL.md} 的机器校验
 * （替代证据；本机无 DM8 实例，归 {@code T-052}）。
 *
 * <p>三项判据：
 * <ol>
 *   <li><b>列级</b>：8 表逐列（列集合 / 类型 / 非空）与 §2.9 一致，且文档声明的唯一 / 普通索引与
 *       外键数逐表吻合；</li>
 *   <li><b>具名 CHECK 清单</b>：8 表逐表比对（缺一必红；未登记新增同红）；</li>
 *   <li><b>防篡改列如实转录</b>：{@code t_audit_log} 含 {@code prev_hash}/{@code row_hash}/{@code seq_in_chain}
 *       （{@code FR-AUDIT-10}/{@code FR-AUDIT-13} 的机制承载），{@code t_audit_log_checksum} 含批次链校验列。</li>
 * </ol>
 */
class M3AuditSchemaTest {

    /** T-062 交付的 8 张表（编号以 DATA_MODEL §2.9 实际编号为准）。 */
    private static final List<String> TABLES = Arrays.asList(
            "t_operation_log", "t_audit_log", "t_audit_log_checksum", "t_violation_screen_run",
            "t_violation_screen_item", "t_audit_report", "t_evidence_export", "t_major_event");

    /** AUDIT 8 表逐表具名 CHECK 清单（数据驱动，双向比对）。 */
    private static final Map<String, List<String>> NAMED_CHECKS = namedChecks();

    private static Map<String, List<String>> namedChecks() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("t_operation_log", Arrays.asList("ck_operation_log_action_result"));
        m.put("t_audit_log", Arrays.asList(
                "ck_audit_log_action_type", "ck_audit_log_chain_integrity"));
        m.put("t_audit_log_checksum", Arrays.asList());
        m.put("t_violation_screen_run", Arrays.asList());
        m.put("t_violation_screen_item", Arrays.asList("ck_violation_screen_item_severity"));
        m.put("t_audit_report", Arrays.asList(
                "ck_audit_report_type", "ck_audit_report_dual_pnl",
                "ck_audit_report_integrity", "ck_audit_report_submitted"));
        m.put("t_evidence_export", Arrays.asList(
                "ck_evidence_export_start_point", "ck_evidence_export_source_detail",
                "ck_evidence_export_integrity", "ck_evidence_export_dual_pnl",
                "ck_evidence_export_reason"));
        m.put("t_major_event", Arrays.asList(
                "ck_major_event_type", "ck_major_event_status"));
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
    @DisplayName("T-062 八表列/类型/非空 + 文档声明的索引/外键 与 §2.9 逐表一致")
    void schemaMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        List<String> dm = Files.readAllLines(repo.resolve("docs/DATA_MODEL.md"), StandardCharsets.UTF_8);
        String sql = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_audit.sql")),
                StandardCharsets.UTF_8);
        Map<String, DocTable> docs = parseDocTables(dm);

        assertEquals(new TreeSet<>(TABLES), new TreeSet<>(docs.keySet()),
                "§2.9 未解析到全部 8 张目标表；实测=" + docs.keySet());

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
        assertTrue(problems.isEmpty(), "脚本与 §2.9 不一致：\n  - " + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("具名 CHECK 清单：AUDIT 8 表逐表比对（缺一必红；未登记新增同红）")
    void namedChecksPresent() throws IOException {
        Path repo = repoRoot();
        String sql = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_audit.sql")),
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
    @DisplayName("防篡改列如实转录（FR-AUDIT-10/13）：t_audit_log 链式三列 + checksum 批次列")
    void tamperEvidentColumnsTranscribed() throws IOException {
        Path repo = repoRoot();
        String sql = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_audit.sql")),
                StandardCharsets.UTF_8);
        Map<String, String[]> log = parseDdlTable(sql, "t_audit_log");
        for (String c : Arrays.asList("prev_hash", "row_hash", "seq_in_chain")) {
            assertTrue(log.containsKey(c), "t_audit_log 缺防篡改列：" + c);
        }
        assertTrue(sql.contains("CONSTRAINT uk_audit_log_chain_seq UNIQUE (seq_in_chain)"),
                "t_audit_log 缺链内序号唯一约束 uk_audit_log_chain_seq");
        Map<String, String[]> ck = parseDdlTable(sql, "t_audit_log_checksum");
        for (String c : Arrays.asList("batch_no", "batch_start_seq", "batch_end_seq",
                "batch_head_hash", "batch_tail_hash", "batch_checksum", "record_count")) {
            assertTrue(ck.containsKey(c), "t_audit_log_checksum 缺批次校验列：" + c);
        }
        // 只增不改不删（不得出现物理删除/级联）
        assertTrue(!stripComments(sql).toUpperCase(Locale.ROOT).contains("ON DELETE CASCADE"),
                "禁止级联删除（§0.4）");
    }

    @Test
    @DisplayName("脚本纪律：8 表齐全、无存储过程/触发器/函数、DM 特性带注释")
    void discipline() throws IOException {
        Path repo = repoRoot();
        String raw = new String(Files.readAllBytes(repo.resolve("docs/DEPLOY/schema/m3_audit.sql")),
                StandardCharsets.UTF_8);
        int createTables = 0;
        for (String line : raw.split("\n", -1)) {
            if (line.startsWith("CREATE TABLE ")) {
                createTables++;
            }
        }
        assertEquals(8, createTables, "m3_audit.sql 的 CREATE TABLE 应为 8（TB-AUDIT-1~8）");
        String upper = stripComments(raw).toUpperCase(Locale.ROOT);
        assertTrue(!upper.contains("CREATE PROCEDURE"), "禁止存储过程");
        assertTrue(!upper.contains("CREATE TRIGGER"), "禁止触发器");
        assertTrue(!upper.contains("CREATE FUNCTION"), "禁止自定义函数");
        assertTrue(raw.contains("-- DM-SPECIFIC:"), "DM 特性须带注释");
        assertTrue(raw.contains("md5 201682e5aff5ee9f01fec9dd1728a4c5"), "文件头须引 DATA_MODEL 现行 md5");
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
            if (line.startsWith("#")) {
                // 任意标题行都结束当前块（含 ### 与 ####），避免末表无「备注」时把下一节字段表误并入
                cur = null;
                inIdx = false;
                if (line.startsWith("#### ")) {
                    String t = firstBacktick(line);
                    if (t != null && TABLES.contains(t)) {
                        cur = new DocTable();
                        out.put(t, cur);
                    }
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

    // ------------------------------------------------------------------ DDL 解析

    private static String sqlBlock(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE " + table + " (");
        assertTrue(start >= 0, "DDL 缺表：" + table);
        int open = sql.indexOf('(', start);
        int end = sql.indexOf("\n);", open);
        assertTrue(end > open, "DDL 表未以 ); 收尾：" + table);
        return sql.substring(open + 1, end);
    }

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

    private static String firstBacktick(String s) {
        int a = s.indexOf(BT);
        if (a < 0) {
            return null;
        }
        int b = s.indexOf(BT, a + 1);
        return b < 0 ? null : s.substring(a + 1, b).trim();
    }

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
