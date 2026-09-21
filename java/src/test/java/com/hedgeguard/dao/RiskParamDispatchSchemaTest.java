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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TB-RISK-2 t_param_dispatch} 建表脚本与 {@code DATA_MODEL.md} 的逐列一致性校验
 * （{@code T-061} 首个增量；替代证据，DM8 实机建表未执行）。
 *
 * <p>与 {@code ConfigAuthSchemaTest} 同为"解析文档 vs 解析 DDL"的机器校验：列集合 / 类型 / 非空 /
 * 文档声明的索引 / 文档声明的外键，逐项比对。
 */
class RiskParamDispatchSchemaTest {

    private static final String TABLE = "t_param_dispatch";

    /** §0.3 通用审计字段（本表可变，故 6 项齐全）。 */
    private static final Map<String, String> AUDIT = auditColumns();

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

    @Test
    @DisplayName("t_param_dispatch 与 DATA_MODEL §2.8 逐列一致（含类型/非空/索引/外键）")
    void ddlMatchesDataModel() throws IOException {
        Path repo = repoRoot();
        Path model = repo.resolve("docs/DATA_MODEL.md");
        Path ddlFile = repo.resolve("docs/DEPLOY/schema/m3_risk.sql");
        assertTrue(Files.isRegularFile(ddlFile), "缺少建表脚本：" + ddlFile);

        DocTable spec = parseDocTable(Files.readAllLines(model, StandardCharsets.UTF_8));
        Map<String, String[]> ddl = parseDdlTable(Files.readAllLines(ddlFile, StandardCharsets.UTF_8));
        String ddlText = new String(Files.readAllBytes(ddlFile), StandardCharsets.UTF_8);

        Map<String, String> expectedType = new LinkedHashMap<>();
        Map<String, Boolean> expectedNotNull = new LinkedHashMap<>();
        for (DocColumn c : spec.columns) {
            expectedType.put(c.name, c.type);
            expectedNotNull.put(c.name, c.notNull);
        }
        expectedType.putIfAbsent("id", AUDIT.get("id"));
        expectedNotNull.putIfAbsent("id", true);
        for (Map.Entry<String, String> a : AUDIT.entrySet()) {
            if (!"id".equals(a.getKey())) {
                expectedType.putIfAbsent(a.getKey(), a.getValue());
                expectedNotNull.putIfAbsent(a.getKey(), !a.getKey().startsWith("updated_"));
            }
        }

        assertEquals(new TreeSet<>(expectedType.keySet()), new TreeSet<>(ddl.keySet()),
                "列集合不一致：文档+审计=" + expectedType.keySet() + " 脚本=" + ddl.keySet());

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : expectedType.entrySet()) {
            String col = e.getKey();
            String[] act = ddl.get(col);
            if (!e.getValue().equalsIgnoreCase(act[0])) {
                problems.add(col + " 类型不一致：文档=" + e.getValue() + " 脚本=" + act[0]);
            }
            boolean expNotNull = Boolean.TRUE.equals(expectedNotNull.get(col));
            boolean actNotNull = Boolean.parseBoolean(act[1]);
            if (expNotNull != actNotNull) {
                problems.add(col + " 非空不一致：文档 NOT NULL=" + expNotNull + " 脚本=" + actNotNull);
            }
        }
        for (String idx : spec.normalIndexes) {
            if (!ddlText.contains("CREATE INDEX " + idx + " ")) {
                problems.add("缺少文档声明的普通索引：" + idx);
            }
        }
        for (String idx : spec.uniqueIndexes) {
            if (!ddlText.contains("CONSTRAINT " + idx + " ")) {
                problems.add("缺少文档声明的唯一约束：" + idx);
            }
        }
        assertEquals(spec.fkTargets, countForeignKeys(ddlText),
                "外键不一致：文档 FK 标注数=" + spec.fkTargets
                        + " 脚本 FOREIGN KEY 数=" + countForeignKeys(ddlText));
        assertTrue(problems.isEmpty(), "脚本与文档不一致：\n  - " + String.join("\n  - ", problems));
        assertTrue(ddl.size() >= 19, "列数过少（" + ddl.size() + "），疑似解析失效");
    }

    @Test
    @DisplayName("脚本纪律 + 文档已声明的具名 CHECK 在位")
    void respectsDiscipline() throws IOException {
        Path repo = repoRoot();
        String raw = new String(Files.readAllBytes(
                repo.resolve("docs/DEPLOY/schema/m3_risk.sql")), StandardCharsets.UTF_8);
        String sql = stripComments(raw).toUpperCase(Locale.ROOT);

        assertFalse(sql.contains("CREATE PROCEDURE"), "禁止存储过程");
        assertFalse(sql.contains("CREATE TRIGGER"), "禁止触发器");
        assertFalse(sql.contains("CREATE FUNCTION"), "禁止自定义函数");
        assertFalse(sql.contains("ON DELETE CASCADE"), "禁止级联删除（§0.4）");
        assertTrue(raw.contains("CONSTRAINT ck_param_dispatch_type "), "缺下发类型取值域 CHECK");
        assertTrue(raw.contains("CONSTRAINT ck_param_dispatch_status "), "缺下发状态取值域 CHECK");
        assertTrue(raw.contains("CONSTRAINT fk_param_dispatch_sub_account "), "缺子账户外键");
        assertTrue(raw.contains("-- DM-SPECIFIC:"), "DM 特性须带注释");
    }

    // ---------------- 解析 ----------------

    private static final class DocColumn {
        final String name;
        final String type;
        final boolean notNull;

        DocColumn(String name, String type, boolean notNull) {
            this.name = name;
            this.type = type;
            this.notNull = notNull;
        }
    }

    private static final class DocTable {
        final List<DocColumn> columns = new ArrayList<>();
        final List<String> uniqueIndexes = new ArrayList<>();
        final List<String> normalIndexes = new ArrayList<>();
        int fkTargets;
    }

    private static DocTable parseDocTable(List<String> lines) {
        DocTable t = new DocTable();
        boolean inTable = false;
        boolean inIndex = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#### ")) {
                inTable = line.contains("`" + TABLE + "`");
                inIndex = false;
                continue;
            }
            if (!inTable) {
                continue;
            }
            if (line.equals("**索引**：")) {
                inIndex = true;
                continue;
            }
            if (line.startsWith("**备注**")) {
                break;
            }
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = splitRow(line);
            if (cells.size() < 3 || cells.get(0).startsWith("字段") || cells.get(0).startsWith("---")) {
                continue;
            }
            if (inIndex) {
                String name = firstBacktick(cells.get(0));
                if (name == null) {
                    continue;
                }
                if (cells.get(2).contains("唯一")) {
                    t.uniqueIndexes.add(name);
                } else if (cells.get(2).contains("普通")) {
                    t.normalIndexes.add(name);
                }
                continue;
            }
            List<String> names = new ArrayList<>();
            for (String tok : backticked(cells.get(0))) {
                if (tok.matches("[a-z][a-z0-9_]*")) {
                    names.add(tok);
                }
            }
            if (names.isEmpty()) {
                continue;
            }
            String type = baseType(firstBacktick(cells.get(1)));
            String cons = cells.get(2);
            for (String n : names) {
                t.columns.add(new DocColumn(n, type, cons.contains("NOT NULL")));
            }
            if (cons.contains("FK")) {
                t.fkTargets += names.size();
            }
        }
        return t;
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?s)CREATE TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\n\\);");
    private static final Pattern COLUMN_LINE =
            Pattern.compile("^([a-z][a-z0-9_]*)\\s+([A-Za-z]+(\\s*\\([0-9,\\s]*\\))?).*$");

    /** @return 列名 → {类型, 是否 NOT NULL} */
    private static Map<String, String[]> parseDdlTable(List<String> lines) {
        Map<String, String[]> out = new TreeMap<>();
        Matcher m = CREATE_TABLE.matcher(String.join("\n", lines));
        while (m.find()) {
            if (!TABLE.equals(m.group(1))) {
                continue;
            }
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
                boolean notNull = line.toUpperCase(Locale.ROOT).contains("NOT NULL");
                out.put(c.group(1), new String[]{baseType(c.group(2)), Boolean.toString(notNull)});
            }
        }
        return out;
    }

    private static int countForeignKeys(String ddlText) {
        int n = 0;
        int i = 0;
        while ((i = ddlText.indexOf("FOREIGN KEY", i)) >= 0) {
            n++;
            i += "FOREIGN KEY".length();
        }
        return n;
    }

    private static List<String> splitRow(String line) {
        String body = line.startsWith("|") ? line.substring(1) : line;
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        return Arrays.asList(body.split("\\|", -1));
    }

    private static String firstBacktick(String cell) {
        List<String> all = backticked(cell);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<String> backticked(String cell) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(cell);
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
        int sp = t.indexOf(' ');
        return (sp > 0 ? t.substring(0, sp) : t).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
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
        throw new IllegalStateException("未找到仓库根；当前目录=" + Paths.get("").toAbsolutePath());
    }
}
