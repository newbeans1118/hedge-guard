package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PositionConflictDao;
import com.hedgeguard.dao.UserRoleDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TB-AUTH-4 t_user_role} / {@code TB-AUTH-6 t_position_conflict} 两个 DAO 实现的
 * <b>结构性契约</b>校验（{@code D-19} 收编；完全离线，不接触 DM8）。
 *
 * <p>为何要单独一条：这两张表的 DAO 实现是全仓此前缺失的一环（`D-19` 的装配前置）。
 * 本用例以<b>运行期反射到的列清单</b>（{@link DmUserRoleDao#businessColumns()} 等）
 * 与<b>建表脚本</b>逐列双向比对，把"实机才会报的无效列名/静默漏列"前置为离线可查
 * （{@code CONTRACTS.md} §2.3、{@code D-1}：环境无 DM 实例）。
 */
class AuthRoleConflictDaoContractTest {

    /** 由基座统一处理的审计列（不进 {@code COLUMNS}），与 {@code DaoColumnAlignmentTest} 同口径。 */
    private static final Set<String> AUDIT = new LinkedHashSet<>(List.of(
            "id", "created_by", "created_time", "updated_by", "updated_time", "row_version"));

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?is)CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\n\\s*\\);");
    private static final Pattern IDENTIFIER_LINE = Pattern.compile("^[a-z_][a-z0-9_]*\\s");

    @Test
    @DisplayName("t_user_role：businessColumns() 与 DDL 业务列双向一致，主键列为 id")
    void userRoleColumnsAlignWithDdl() throws IOException {
        DmUserRoleDao dao = new DmUserRoleDao(null);
        assertEquals("t_user_role", dao.tableName());
        assertEquals("id", dao.idColumn());
        assertColumnsAligned("t_user_role", dao.businessColumns());
    }

    @Test
    @DisplayName("t_position_conflict：businessColumns() 与 DDL 业务列双向一致，主键列为 id")
    void positionConflictColumnsAlignWithDdl() throws IOException {
        DmPositionConflictDao dao = new DmPositionConflictDao(null);
        assertEquals("t_position_conflict", dao.tableName());
        assertEquals("id", dao.idColumn());
        assertColumnsAligned("t_position_conflict", dao.businessColumns());
    }

    @Test
    @DisplayName("接口—实现闭合：可空事务构造（支撑受配置保护装配）且实现对应接口")
    void interfacesClosedAndConstructibleWithoutTx() {
        assertTrue(UserRoleDao.class.isAssignableFrom(DmUserRoleDao.class));
        assertTrue(PositionConflictDao.class.isAssignableFrom(DmPositionConflictDao.class));
        assertTrue(AbstractDmDao.class.isAssignableFrom(DmUserRoleDao.class));
        assertTrue(AbstractDmDao.class.isAssignableFrom(DmPositionConflictDao.class));
        // tx=null 构造不触碰事务；本用例仅做结构校验，不调用任何 DB 方法
        new DmUserRoleDao(null);
        new DmPositionConflictDao(null);
    }

    // ------------------------------------------------------------------ 工具

    private static void assertColumnsAligned(String table, String[] declared) throws IOException {
        Set<String> ddl = ddlColumns(table);
        assertTrue(ddl.size() > AUDIT.size(), "未解析到表 " + table + " 的 DDL 列，疑似解析失效");

        Set<String> declaredSet = new LinkedHashSet<>();
        for (String c : declared) {
            declaredSet.add(c.toLowerCase(Locale.ROOT));
        }
        Set<String> ddlBusiness = new TreeSet<>(ddl);
        ddlBusiness.removeAll(AUDIT);

        Set<String> daoNotInDdl = new TreeSet<>(declaredSet);
        daoNotInDdl.removeAll(ddl);
        assertTrue(daoNotInDdl.isEmpty(),
                table + "：DAO 声明的列在建表脚本中不存在（实机将报无效列名）→ " + daoNotInDdl);

        Set<String> ddlNotInDao = new TreeSet<>(ddlBusiness);
        ddlNotInDao.removeAll(declaredSet);
        assertTrue(ddlNotInDao.isEmpty(),
                table + "：DDL 业务列未出现在 DAO COLUMNS 中（静默漏列）→ " + ddlNotInDao);

        // 双向已证，再断言集合相等（更可读的等价表述）
        assertEquals(ddlBusiness, declaredSet, table + " 业务列集合应完全一致");
    }

    /** 从建表脚本解析某表全部列名。 */
    private static Set<String> ddlColumns(String table) throws IOException {
        Path schema = repoRoot().resolve("docs/DEPLOY/schema");
        Set<String> cols = new LinkedHashSet<>();
        boolean found = false;
        try (Stream<Path> stream = Files.list(schema)) {
            List<Path> sqlFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path p : sqlFiles) {
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Matcher m = CREATE_TABLE.matcher(text);
                while (m.find()) {
                    if (!m.group(1).equalsIgnoreCase(table)) {
                        continue;
                    }
                    found = true;
                    for (String raw : m.group(2).split("\n")) {
                        String line = raw.trim();
                        if (line.isEmpty() || line.startsWith("--")) {
                            continue;
                        }
                        String upper = line.toUpperCase(Locale.ROOT);
                        if (upper.startsWith("CONSTRAINT") || upper.startsWith("PRIMARY")
                                || upper.startsWith("FOREIGN") || upper.startsWith("UNIQUE")
                                || upper.startsWith("CHECK")) {
                            continue;
                        }
                        if (IDENTIFIER_LINE.matcher(line).find()) {
                            cols.add(line.split("\\s+")[0].toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        assertTrue(found, "建表脚本中找不到表 " + table + "（docs/DEPLOY/schema/*.sql）");
        return cols;
    }

    /** 向上寻找含 {@code docs/DEPLOY/schema} 的仓库根。 */
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
