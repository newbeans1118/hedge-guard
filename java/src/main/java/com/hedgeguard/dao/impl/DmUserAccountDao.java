package com.hedgeguard.dao.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

/**
 * {@code t_user} 的达梦实现（{@code DATA_MODEL.md} §2.2 TB-AUTH-1）。
 *
 * <p>零落盘纪律：本类只搬运 {@code password_hash}（PBKDF2 串），
 * <b>不接收、不返回、不记录</b>任何明文口令（{@code NFR-SEC-1}）。
 */
public final class DmUserAccountDao extends AbstractDmDao<UserAccount, Long> implements UserAccountDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "username", "real_name", "natural_person_key", "department", "is_enterprise_leader",
            "leader_flag_reviewer_id", "status", "password_hash", "must_change_password",
            "password_updated_time", "fail_count", "locked_until", "last_login_time", "disabled_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmUserAccountDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_user";
    }

    @Override
    protected String idColumn() {
        return "id";
    }

    @Override
    protected String[] businessColumns() {
        return COLUMNS.clone();
    }

    @Override
    protected void bindBusiness(PreparedStatement ps, UserAccount e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.username);
        bind(ps, i++, e.realName);
        bind(ps, i++, e.naturalPersonKey);
        bind(ps, i++, e.department);
        bind(ps, i++, e.isEnterpriseLeader);
        bind(ps, i++, e.leaderFlagReviewerId);
        bind(ps, i++, e.status);
        bind(ps, i++, e.passwordHash);
        bind(ps, i++, e.mustChangePassword);
        bind(ps, i++, e.passwordUpdatedTime);
        ps.setInt(i++, e.failCount);
        bind(ps, i++, e.lockedUntil);
        bind(ps, i++, e.lastLoginTime);
        bind(ps, i, e.disabledReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, UserAccount e) throws SQLException {
        e.id = rs.getLong("id");
        e.username = string(rs, "username");
        e.realName = string(rs, "real_name");
        e.naturalPersonKey = string(rs, "natural_person_key");
        e.department = string(rs, "department");
        e.isEnterpriseLeader = string(rs, "is_enterprise_leader");
        e.leaderFlagReviewerId = nullableLong(rs, "leader_flag_reviewer_id");
        e.status = string(rs, "status");
        e.passwordHash = string(rs, "password_hash");
        e.mustChangePassword = string(rs, "must_change_password");
        e.passwordUpdatedTime = localDateTime(rs, "password_updated_time");
        e.failCount = rs.getInt("fail_count");
        e.lockedUntil = localDateTime(rs, "locked_until");
        e.lastLoginTime = localDateTime(rs, "last_login_time");
        e.disabledReason = string(rs, "disabled_reason");
    }

    @Override
    protected UserAccount newEntity() {
        return new UserAccount();
    }

    @Override
    protected Long idOf(UserAccount entity) {
        return entity.id;
    }

    @Override
    protected void applyGeneratedId(UserAccount entity, long generated) {
        entity.id = generated;
    }

    @Override
    public UserAccount findByUsername(String username) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE username = ?";
        return queryOne(sql, Collections.singletonList(username), this::mapRow);
    }

    @Override
    public UserAccount findByNaturalPersonKey(String naturalPersonKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE natural_person_key = ?";
        return queryOne(sql, Collections.singletonList(naturalPersonKey), this::mapRow);
    }

    @Override
    public int incrementFailCount(long userId) {
        String sql = "UPDATE " + tableName()
                + " SET fail_count = fail_count + 1, updated_time = ?, row_version = row_version + 1"
                + " WHERE id = ?";
        return executeUpdateSql(sql, Arrays.asList(Timestamp.valueOf(Clocks.now()), userId));
    }

    @Override
    public int lockUntil(long userId, LocalDateTime lockedUntil) {
        String sql = "UPDATE " + tableName()
                + " SET locked_until = ?, updated_time = ?, row_version = row_version + 1"
                + " WHERE id = ?";
        return executeUpdateSql(sql, Arrays.asList(Timestamp.valueOf(lockedUntil),
                Timestamp.valueOf(Clocks.now()), userId));
    }

    @Override
    public int unlock(long userId) {
        String sql = "UPDATE " + tableName()
                + " SET fail_count = 0, locked_until = NULL, updated_time = ?, row_version = row_version + 1"
                + " WHERE id = ?";
        return executeUpdateSql(sql, Arrays.asList(Timestamp.valueOf(Clocks.now()), userId));
    }

    @Override
    public int updatePasswordHash(long userId, String passwordHash, LocalDateTime passwordUpdatedTime) {
        String sql = "UPDATE " + tableName()
                + " SET password_hash = ?, password_updated_time = ?, must_change_password = ?,"
                + " updated_time = ?, row_version = row_version + 1 WHERE id = ?";
        return executeUpdateSql(sql, Arrays.asList(passwordHash,
                Timestamp.valueOf(passwordUpdatedTime), "N",
                Timestamp.valueOf(Clocks.now()), userId));
    }

    @Override
    public int touchLastLogin(long userId, LocalDateTime lastLoginTime) {
        String sql = "UPDATE " + tableName()
                + " SET last_login_time = ?, updated_time = ?, row_version = row_version + 1"
                + " WHERE id = ?";
        return executeUpdateSql(sql, Arrays.asList(Timestamp.valueOf(lastLoginTime),
                Timestamp.valueOf(Clocks.now()), userId));
    }
}
