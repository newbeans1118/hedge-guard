package com.hedgeguard.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **DAO ↔ 建表脚本 列对齐**机器校验（跨模块不变量）。
 *
 * <p><b>为什么需要它</b>：DAO 的 {@code COLUMNS} 数组是 SQL 列清单的唯一来源（`CONTRACTS.md` §2.3）。
 * 一旦某列在 DDL 与 DAO 之间**漂移**（拼错、漏加、或 DDL 加了列而 DAO 未跟），
 * **单测（假连接）不会发现，只有实机 DM8 才会报"无效的列名"**——而本项目**没有 DM8 实例**（`CONTRACTS.md` `D-1`）。
 * 故用"解析文档/脚本 vs 解析源码"的机制把该缺陷**前置为离线可查**。
 *
 * <p><b>判据</b>（对每个 {@code Dm*Dao}）：
 * <ol>
 *   <li>其 {@code tableName()} 必须能在 {@code docs/DEPLOY/schema/*.sql} 中找到；</li>
 *   <li>{@code COLUMNS} 中的每个列名必须真实存在于该表的 DDL；</li>
 *   <li>DDL 中除**通用审计列**外的每个业务列，都必须出现在 {@code COLUMNS} 中（防漏列）。</li>
 * </ol>
 *
 * <p>审计列（{@code id} / {@code created_by} / {@code created_time} / {@code updated_by} /
 * {@code updated_time} / {@code row_version}）由基座统一处理，按约定**不进** {@code COLUMNS}，故两侧都排除。
 *
 * <p>覆盖面：本校验**跨模块、跨写者**（CONFIG/AUTH + PRODUCT + SPOT + PLAN + RISK），
 * 因此也是"多人协作下 DDL 与实现未漂移"的一条**客观证据**。
 */
class DaoColumnAlignmentTest {

    /** 由基座统一处理的审计列（不进 COLUMNS）。 */
    private static final Set<String> AUDIT = new LinkedHashSet<>(List.of(
            "id", "created_by", "created_time", "updated_by", "updated_time", "row_version"));

    // 【T-094 已回收 DDL_PENDING 豁免】T-180 事后风控报表三表（TB-RISK-7/8/9）的建表 DDL 已随
    // T-094 续写进 docs/DEPLOY/schema/m3_risk.sql ⇒ 三表连同其余 RISK 表全部进入下方双向对齐断言
    // （原「自失效」用例已完成使命、随之移除，不允许豁免长期滞留）。

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?is)CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\n\\s*\\);");
    private static final Pattern TABLE_NAME =
            Pattern.compile("tableName\\(\\)\\s*\\{\\s*return\\s+\"(\\w+)\"");
    private static final Pattern COLUMNS_ARRAY =
            Pattern.compile("(?s)COLUMNS\\s*=\\s*\\{(.*?)\\}\\s*;");
    private static final Pattern QUOTED = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"");
    private static final Pattern IDENTIFIER_LINE = Pattern.compile("^[a-z_][a-z0-9_]*\\s");

    @Test
    @DisplayName("每个 Dm*Dao 的 tableName() 都能在建表脚本中找到（防表名拼错/脚本缺表）")
    void everyDaoTableExistsInDdl() throws IOException {
        Map<String, Set<String>> ddl = parseDdlTables();
        Map<Path, String> daoTables = parseDaoTables();
        assertFalse(daoTables.isEmpty(), "应至少解析到一个 Dm*Dao 实现");

        List<String> missing = new ArrayList<>();
        for (Map.Entry<Path, String> e : daoTables.entrySet()) {
            if (!ddl.containsKey(e.getValue())) {
                missing.add(e.getKey().getFileName() + " → " + e.getValue());
            }
        }
        assertTrue(missing.isEmpty(),
                "以下 DAO 的 tableName() 在任何建表脚本中都找不到（实机必报表不存在）：" + missing
                        + "；复现=检查 docs/DEPLOY/schema/*.sql 是否含该表");
    }

    @Test
    @DisplayName("DAO 的 COLUMNS 与 DDL 的业务列**双向一致**（既无多余列，也不漏列）")
    void daoColumnsMatchDdlBusinessColumns() throws IOException {
        Map<String, Set<String>> ddl = parseDdlTables();
        Map<Path, Set<String>> daoColumns = parseDaoColumns();

        int checked = 0;
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Path, Set<String>> e : daoColumns.entrySet()) {
            String file = e.getKey().getFileName().toString();
            String table = tableNameOf(e.getKey());
            if (table == null || !ddl.containsKey(table)) {
                continue;   // 由上一个用例负责报"表不存在"
            }
            Set<String> declared = e.getValue();
            Set<String> inDdl = ddl.get(table);
            Set<String> businessInDdl = new TreeSet<>(inDdl);
            businessInDdl.removeAll(AUDIT);

            // ① DAO 有而 DDL 无 ⇒ 实机"无效的列名"
            Set<String> notInDdl = new TreeSet<>(declared);
            notInDdl.removeAll(inDdl);
            if (!notInDdl.isEmpty()) {
                problems.add(file + "（表 " + table + "）**DAO 有而 DDL 无** → " + notInDdl
                        + "（实机将报无效列名）");
            }
            // ② DDL 业务列而 DAO 无 ⇒ 该列永远不会被读写（静默漏列）
            Set<String> notInDao = new TreeSet<>(businessInDdl);
            notInDao.removeAll(declared);
            if (!notInDao.isEmpty()) {
                problems.add(file + "（表 " + table + "）**DDL 有而 DAO 无** → " + notInDao
                        + "（该列不会被读写，属静默漏列）");
            }
            checked++;
        }
        assertTrue(checked > 0, "应至少完成一张表的列对齐比对");
        assertTrue(problems.isEmpty(),
                "DAO 与建表脚本的列清单不一致（共 " + checked + " 张表已比对）：\n  - "
                        + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("自检：解析器确实读到了多模块的表与实现（防止解析失效导致'空跑即绿'）")
    void parserSelfCheck() throws IOException {
        Map<String, Set<String>> ddl = parseDdlTables();
        Map<Path, Set<String>> daoColumns = parseDaoColumns();
        assertTrue(ddl.size() >= 40,
                "建表脚本解析出的表数过少（" + ddl.size() + "），解析器可能已失效");
        assertTrue(daoColumns.size() >= 30,
                "解析出的 Dm*Dao 数过少（" + daoColumns.size() + "），解析器可能已失效");
        for (String t : List.of("t_config_item", "t_sub_account", "t_product", "t_param_dispatch")) {
            assertTrue(ddl.containsKey(t), "抽样表应存在：" + t);
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 建表脚本 → 表名 → 列名集合。 */
    static Map<String, Set<String>> parseDdlTables() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Path p : schemaFiles()) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Matcher m = CREATE_TABLE.matcher(text);
            while (m.find()) {
                Set<String> cols = new LinkedHashSet<>();
                for (String raw : m.group(2).split("\n")) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("--")) {
                        continue;
                    }
                    String upper = line.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("CONSTRAINT ") || upper.startsWith("PRIMARY ")
                            || upper.startsWith("FOREIGN ") || upper.startsWith("UNIQUE ")
                            || upper.startsWith("CHECK ")) {
                        continue;
                    }
                    Matcher id = IDENTIFIER_LINE.matcher(line);
                    if (id.find()) {
                        String name = line.split("\\s+")[0];
                        cols.add(name.toLowerCase(Locale.ROOT));
                    }
                }
                out.put(m.group(1).toLowerCase(Locale.ROOT), cols);
            }
        }
        return out;
    }

    /** DAO 文件 → COLUMNS 集合。 */
    static Map<Path, Set<String>> parseDaoColumns() throws IOException {
        Map<Path, Set<String>> out = new LinkedHashMap<>();
        for (Path p : daoFiles()) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Matcher m = COLUMNS_ARRAY.matcher(text);
            if (!m.find()) {
                continue;
            }
            Set<String> cols = new LinkedHashSet<>();
            Matcher q = QUOTED.matcher(m.group(1));
            while (q.find()) {
                cols.add(q.group(1).toLowerCase(Locale.ROOT));
            }
            out.put(p, cols);
        }
        return out;
    }

    /** DAO 文件 → tableName()。 */
    static String tableNameOf(Path p) throws IOException {
        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        Matcher m = TABLE_NAME.matcher(text);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static Map<Path, String> parseDaoTables() throws IOException {
        Map<Path, String> out = new LinkedHashMap<>();
        for (Path p : daoFiles()) {
            String t = tableNameOf(p);
            if (t != null) {
                out.put(p, t);
            }
        }
        return out;
    }

    private static List<Path> schemaFiles() throws IOException {
        Path dir = repoRoot().resolve("docs/DEPLOY/schema");
        assertTrue(Files.isDirectory(dir), "缺少建表脚本目录：" + dir);
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static List<Path> daoFiles() throws IOException {
        Path dir = repoRoot().resolve("java/src/main/java/com/hedgeguard/dao/impl");
        assertTrue(Files.isDirectory(dir), "缺少 DAO 实现目录：" + dir);
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("Dm") && n.endsWith("Dao.java");
            }).sorted().collect(Collectors.toList());
        }
    }

    /** 仓库根：向上找到含 {@code docs/DEPLOY/schema} 的目录。 */
    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isDirectory(p.resolve("docs/DEPLOY/schema"))) {
                return p;
            }
            Path parent = p.getParent();
            if (parent != null && Files.isDirectory(parent.resolve("docs/DEPLOY/schema"))) {
                return parent;
            }
            p = parent;
        }
        throw new IllegalStateException("未找到仓库根（docs/DEPLOY/schema 不存在）；cwd="
                + Paths.get("").toAbsolutePath());
    }
}
