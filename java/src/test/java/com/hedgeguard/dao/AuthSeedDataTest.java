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
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-010} 缺口 (d)「AUTH 预设数据零播种」的验收证据（{@code FR-AUTH-6} / {@code FR-AUTH-7}）。
 *
 * <p>本环境无 DM8 实例，无法"执行脚本后查行数"；本用例是<b>可机器复现的替代证据</b>：
 * 直接解析 {@code docs/DEPLOY/schema/data-init.sql} 第 ③ 段，断言
 * <ul>
 *   <li><b>播种行数</b>：岗位组 7 / 角色 7 / 默认互斥 5（由脚本内 {@code UNION ALL} 行数计得）；</li>
 *   <li><b>关键字段</b>：系统管理员 {@code is_business_role='N'}、仅 {@code TRADER} 具交易能力、
 *       互斥对为 {@code (TRADER,FIN_RISK)/(TRADER,SETTLER)/(FIN_RISK,SETTLER)/(TRADER,CASHIER)/(TRADER,APPROVER)}；</li>
 *   <li><b>幂等</b>：三段 {@code INSERT} 均含 {@code NOT EXISTS}（重复执行不重复插入）；</li>
 *   <li><b>不破坏既有</b>：{@code t_sub_account} / {@code t_product} 两条保留系统行仍在；无存储过程/触发器。</li>
 * </ul>
 *
 * <p>「行数」口径：脚本用 {@code INSERT ... SELECT ... FROM (SELECT ... UNION ALL SELECT ...)}，
 * 首行 1 + N 个 {@code UNION ALL} ⇒ 行数 = {@code UNION ALL} 计数 + 1。
 */
class AuthSeedDataTest {

    private static String initSql() throws IOException {
        Path repo = repoRoot();
        Path file = repo.resolve("docs/DEPLOY/schema/data-init.sql");
        assertTrue(Files.isRegularFile(file), "缺少初始化数据脚本：" + file);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** 取 {@code INSERT INTO <table> ... ;} 语句块。 */
    private static String block(String sql, String table) {
        int at = sql.indexOf("INSERT INTO " + table);
        assertTrue(at >= 0, "脚本缺 " + table + " 的 INSERT（缺口 (d) 未闭合）");
        int semi = sql.indexOf(';', at);
        return sql.substring(at, semi < 0 ? sql.length() : semi);
    }

    private static int rowCount(String block) {
        return count(block, "UNION ALL") + 1;
    }

    private static int count(String text, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return n;
            }
            n++;
            from = idx + needle.length();
        }
    }

    @Test
    @DisplayName("播种行数：岗位组 7 / 角色 7 / 默认互斥 5（T-010 缺口 d）")
    void seedsAuthPresetRows() throws IOException {
        String sql = initSql();
        assertEquals(7, rowCount(block(sql, "t_position_group")), "岗位组应为 7 行");
        assertEquals(7, rowCount(block(sql, "t_role")), "角色应为 7 行（六类业务角色 + SYS_ADMIN）");
        assertEquals(5, rowCount(block(sql, "t_position_conflict")), "默认互斥集应为 5 对");
    }

    @Test
    @DisplayName("关键字段：SYS_ADMIN 非业务角色、仅 TRADER 具交易能力、互斥对逐条正确")
    void seedsKeyFields() throws IOException {
        String sql = initSql();

        String roleBlock = block(sql, "t_role");
        for (String code : Arrays.asList("TRADER", "FIN_RISK", "SETTLER", "CASHIER", "APPROVER",
                "AUDITOR", "SYS_ADMIN")) {
            assertTrue(roleBlock.contains("'" + code + "'"), "角色缺 " + code);
        }
        // 系统管理员：is_business_role='N'（R-AUTH-10）
        assertTrue(roleBlock.contains("'SYS_ADMIN', '系统管理员', 'SYS_ADMIN', 'N', 'N'"),
                "系统管理员须 is_business_role='N' 且 has_trading_operation='N'");
        // 仅 TRADER 具交易能力
        assertTrue(roleBlock.contains("'TRADER' AS code, '期货交易岗' AS name, 'TRADER' AS grp, 'Y' AS biz, 'Y' AS trade"),
                "TRADER 须 has_trading_operation='Y'");
        assertTrue(roleBlock.contains("'FIN_RISK', '财务/风控岗', 'FIN_RISK', 'Y', 'N'"),
                "风控岗不得具交易能力");
        assertTrue(roleBlock.contains("'SETTLER', '结算岗', 'SETTLER', 'Y', 'N'"),
                "结算岗不得具交易能力");

        String groupBlock = block(sql, "t_position_group");
        for (String fmb : Arrays.asList("FRONT", "MID", "MANAGE", "BACK", "SYSTEM")) {
            assertTrue(groupBlock.contains("'" + fmb + "'"), "岗位组缺 front_mid_back 类别 " + fmb);
        }

        String conflictBlock = block(sql, "t_position_conflict").replace(" AS ga", "").replace(" AS gb", "");
        List<String> pairs = new ArrayList<>(Arrays.asList(
                "TRADER|FIN_RISK", "TRADER|SETTLER", "FIN_RISK|SETTLER",
                "TRADER|CASHIER", "TRADER|APPROVER"));
        for (String pair : pairs) {
            String[] ab = pair.split("\\|");
            assertTrue(conflictBlock.contains("'" + ab[0] + "', '" + ab[1] + "'"),
                    "默认互斥集缺对：" + pair);
        }
    }

    @Test
    @DisplayName("幂等：三段 INSERT 均以 NOT EXISTS 去重（重复执行行数不变）")
    void seedsAreIdempotent() throws IOException {
        String sql = initSql();
        for (String table : Arrays.asList("t_position_group", "t_role", "t_position_conflict")) {
            String b = block(sql, table);
            assertTrue(b.contains("NOT EXISTS"),
                    table + " 的播种须幂等（NOT EXISTS 去重），否则重复部署会产生重复行");
        }
    }

    @Test
    @DisplayName("不破坏既有：保留系统行仍在；无存储过程/触发器/级联删除")
    void doesNotBreakExistingSeeds() throws IOException {
        String sql = initSql();
        assertTrue(sql.contains("__ALL_ACCOUNT__"), "t_sub_account 保留行应仍在");
        assertTrue(sql.contains("'__UNMAPPED__'"), "t_product 保留行应仍在");
        String upper = sql.toUpperCase(Locale.ROOT);
        assertFalse(upper.contains("CREATE PROCEDURE"), "禁止存储过程（PROJECT.md §6）");
        assertFalse(upper.contains("CREATE TRIGGER"), "禁止触发器（PROJECT.md §6）");
        assertFalse(upper.contains("ON DELETE CASCADE"), "禁止级联删除（DATA_MODEL.md §0.4）");
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
