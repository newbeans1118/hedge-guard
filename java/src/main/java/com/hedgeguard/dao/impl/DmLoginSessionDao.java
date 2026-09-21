package com.hedgeguard.dao.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.LoginSessionDao;
import com.hedgeguard.dao.entity.LoginSession;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_login_session} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-9}）。
 *
 * <p><b>本表可变</b>（{@code status}/{@code last_active_time} 随会话生命周期推进），且是 {@code §0.3}
 * 显式允许<b>物理删除</b>的例外表（清理历史会话）——故实现 {@link LoginSessionDao} 的
 * {@code deleteById} 是**契约要求**，不是越权写。
 *
 * <p>{@code status} 取值域：{@code ACTIVE}/{@code LOGGED_OUT}/{@code EXPIRED}/{@code KICKED}
 * （{@code ck_login_session_status}）。
 */
public final class DmLoginSessionDao extends AbstractDmDao<LoginSession, Long>
        implements LoginSessionDao {

    /** 会话有效状态（作废/心跳只作用于该状态的行，避免重写已终结的会话）。 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "session_id", "user_id", "login_time", "last_active_time", "idle_timeout_minutes",
            "expire_time", "client_ip", "user_agent", "status", "kicked_by",
            "permission_version_snapshot"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmLoginSessionDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_login_session";
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
    protected void bindBusiness(PreparedStatement ps, LoginSession e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.sessionId);
        ps.setLong(i++, e.userId);
        bind(ps, i++, e.loginTime);
        bind(ps, i++, e.lastActiveTime);
        ps.setInt(i++, e.idleTimeoutMinutes);
        bind(ps, i++, e.expireTime);
        bind(ps, i++, e.clientIp);
        bind(ps, i++, e.userAgent);
        bind(ps, i++, e.status);
        bind(ps, i++, e.kickedBy);
        ps.setLong(i, e.permissionVersionSnapshot);
    }

    @Override
    protected void readBusiness(ResultSet rs, LoginSession e) throws SQLException {
        e.id = rs.getLong("id");
        e.sessionId = string(rs, "session_id");
        e.userId = rs.getLong("user_id");
        e.loginTime = localDateTime(rs, "login_time");
        e.lastActiveTime = localDateTime(rs, "last_active_time");
        e.idleTimeoutMinutes = rs.getInt("idle_timeout_minutes");
        e.expireTime = localDateTime(rs, "expire_time");
        e.clientIp = string(rs, "client_ip");
        e.userAgent = string(rs, "user_agent");
        e.status = string(rs, "status");
        e.kickedBy = nullableLong(rs, "kicked_by");
        e.permissionVersionSnapshot = rs.getLong("permission_version_snapshot");
    }

    @Override
    protected Long idOf(LoginSession entity) {
        return entity.id;
    }

    @Override
    protected LoginSession newEntity() {
        return new LoginSession();
    }

    @Override
    protected void applyGeneratedId(LoginSession entity, long generated) {
        entity.id = generated;
    }

    @Override
    public LoginSession findBySessionId(String sessionId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE session_id = ?";
        return queryOne(sql, Collections.singletonList(sessionId), this::mapRow);
    }

    @Override
    public int invalidateByUser(long userId, String status, Long kickedBy, long permissionVersion) {
        // 只作废仍在 ACTIVE 的会话：已 LOGGED_OUT/EXPIRED/KICKED 的行不再改写（保留其终结时点的审计语义）
        String sql = "UPDATE " + tableName()
                + " SET status = ?, kicked_by = ?, permission_version_snapshot = ?,"
                + " updated_time = ?, row_version = row_version + 1"
                + " WHERE user_id = ? AND status = ?";
        List<Object> args = Arrays.asList(status, kickedBy, permissionVersion, Clocks.now(),
                userId, STATUS_ACTIVE);
        return executeUpdateSql(sql, args);
    }

    @Override
    public int touch(String sessionId, LocalDateTime lastActiveTime) {
        String sql = "UPDATE " + tableName()
                + " SET last_active_time = ?, updated_time = ?, row_version = row_version + 1"
                + " WHERE session_id = ? AND status = ?";
        List<Object> args = Arrays.asList(lastActiveTime, Clocks.now(), sessionId, STATUS_ACTIVE);
        return executeUpdateSql(sql, args);
    }

    @Override
    public int deleteExpired(LocalDateTime before) {
        String sql = "DELETE FROM " + tableName() + " WHERE expire_time < ?";
        return executeUpdateSql(sql, Collections.singletonList(before));
    }

    @Override
    public int deleteById(Long id) {
        String sql = "DELETE FROM " + tableName() + " WHERE " + idColumn() + " = ?";
        return executeUpdateSql(sql, Collections.singletonList(id));
    }
}
