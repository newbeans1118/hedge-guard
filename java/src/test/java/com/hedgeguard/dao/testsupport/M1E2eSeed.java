package com.hedgeguard.dao.testsupport;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code T-024} M1 端到端验收用的<b>最小播种件</b>（建在 {@code dao} 命名空间内）。
 *
 * <p><b>为何单独成类</b>：E5（{@code dao} 之外不得出现 SQL）对 {@code src/test} 同规则，
 * 而 {@code T-024} 的验收需要两行 {@code DATA_MODEL.md} §2.1 / §2.3 的<b>保留系统行</b>
 * 与用户/权限行。{@code data-init.sql} 使用 DM 专有 {@code SET IDENTITY_INSERT}，本内存引擎
 * 不解析该语句，故此处按等价语义直接写入（与 {@code M1Dm8EquivalentIntegrationTest#seedSubAccount}
 * 同一手法）。SQL 落在 {@code dao} 命名空间之下（该目录被 E5 排除），满足 E5 分域。
 *
 * <p>本类<b>不</b>属于产品代码，仅测试夹具；不改变任何 {@code src/main} 行为。
 */
public final class M1E2eSeed {

    private M1E2eSeed() {
    }

    /**
     * 写入保留系统行 {@code t_sub_account(id=0)}（作用域哨兵值 0 的外键载体，DC-020）。
     *
     * @param engine 内存假 JDBC 引擎
     * @throws SQLException 写入失败
     */
    public static void reservedSubAccountZero(InMemoryDmEngine engine) throws SQLException {
        exec(engine, "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, status,"
                        + " category, is_default, disabled_reason, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                0, "__ALL_ACCOUNT__", "全账户（系统保留）", "DISABLED", "COMMODITY", "N",
                "系统保留行：账户级/全账户口径的作用域哨兵值载体", 0L);
    }

    /**
     * 写入一个用户行（{@code t_user}）——仅供子账户授权外键与非空列使用。
     *
     * @param engine          内存假 JDBC 引擎
     * @param id              主键（显式）
     * @param username        登录名
     * @param realName        姓名
     * @param naturalPersonKey 自然人唯一键
     * @throws SQLException 写入失败
     */
    public static void user(InMemoryDmEngine engine, long id, String username, String realName,
                            String naturalPersonKey) throws SQLException {
        exec(engine, "INSERT INTO t_user (id, username, real_name, natural_person_key, status,"
                        + " password_hash, is_enterprise_leader, must_change_password, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, username, realName, naturalPersonKey, "ENABLED", "pbkdf2$1$x$y", "N", "N", 0L);
    }

    /**
     * 写入一条子账户数据权限行（{@code t_user_sub_account_perm}）。
     *
     * @param engine       内存假 JDBC 引擎
     * @param userId       用户
     * @param subAccountId 子账户
     * @param permType     {@code LIMITED} / {@code CROSS_READONLY}
     * @param grantedBy    授予人
     * @throws SQLException 写入失败
     */
    public static void perm(InMemoryDmEngine engine, long userId, int subAccountId, String permType,
                            long grantedBy) throws SQLException {
        exec(engine, "INSERT INTO t_user_sub_account_perm (user_id, sub_account_id, perm_type,"
                        + " granted_by, effective_from, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                userId, subAccountId, permType, grantedBy, LocalDate.of(2026, 1, 1), grantedBy);
    }

    /**
     * 写入一个岗位组行（{@code t_position_group}；{@code t_role.position_group_id} 的外键载体）。
     *
     * <p>{@code T-119} 新增：账户级（层③）写面授权判据为<b>角色近似</b>（{@code FIN_RISK} /
     * {@code SYS_ADMIN}），端到端用例须先有角色行方可复现合法主体。
     *
     * @param engine 内存假 JDBC 引擎
     * @param id     主键（显式）
     * @param code   岗位组编码
     * @param name   岗位组名
     * @param fmb    前中后台（{@code FRONT}/{@code MID}/{@code BACK}/{@code MANAGE}/{@code SYSTEM}）
     * @throws SQLException 写入失败
     */
    public static void positionGroup(InMemoryDmEngine engine, long id, String code, String name,
                                     String fmb) throws SQLException {
        exec(engine, "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back,"
                        + " is_conflict_enforced, status, created_by)"
                        + " VALUES (?, ?, ?, ?, 'Y', 'ENABLED', ?)",
                id, code, name, fmb, 0L);
    }

    /**
     * 写入一个角色行（{@code t_role}）。
     *
     * @param engine              内存假 JDBC 引擎
     * @param id                  主键（显式）
     * @param code                角色编码
     * @param name                角色名
     * @param positionGroupId     岗位组
     * @param isBusinessRole      是否业务角色（{@code SYS_ADMIN} 取 {@code 'N'}，{@code R-AUTH-10}）
     * @param hasTradingOperation {@code Y} / {@code N}
     * @throws SQLException 写入失败
     */
    public static void role(InMemoryDmEngine engine, long id, String code, String name,
                            long positionGroupId, String isBusinessRole, String hasTradingOperation)
            throws SQLException {
        exec(engine, "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset,"
                        + " is_business_role, has_trading_operation, status, created_by)"
                        + " VALUES (?, ?, ?, ?, 'Y', ?, ?, 'ENABLED', ?)",
                id, code, name, positionGroupId, isBusinessRole, hasTradingOperation, 0L);
    }

    /**
     * 写入一个用户角色绑定行（{@code t_user_role}）。
     *
     * @param engine   内存假 JDBC 引擎
     * @param id       主键（显式）
     * @param userId   用户
     * @param roleId   角色
     * @param grantedBy 授予人
     * @throws SQLException 写入失败
     */
    public static void userRole(InMemoryDmEngine engine, long id, long userId, long roleId,
                                long grantedBy) throws SQLException {
        exec(engine, "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time,"
                        + " created_by) VALUES (?, ?, ?, ?, ?, ?)",
                id, userId, roleId, grantedBy, LocalDate.of(2026, 1, 1).atStartOfDay(), grantedBy);
    }

    private static void exec(InMemoryDmEngine engine, String sql, Object... args) throws SQLException {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                int idx = i + 1;
                if (a instanceof Integer) {
                    ps.setInt(idx, (Integer) a);
                } else if (a instanceof Long) {
                    ps.setLong(idx, (Long) a);
                } else if (a instanceof LocalDate) {
                    ps.setDate(idx, Date.valueOf((LocalDate) a));
                } else if (a instanceof LocalDateTime) {
                    ps.setTimestamp(idx, Timestamp.valueOf((LocalDateTime) a));
                } else if (a == null) {
                    ps.setNull(idx, Types.NULL);
                } else {
                    ps.setString(idx, String.valueOf(a));
                }
            }
            ps.executeUpdate();
        }
    }
}
