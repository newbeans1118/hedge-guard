package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.LoginLogDao;
import com.hedgeguard.dao.entity.LoginLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_login_log} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-10}）。
 *
 * <p><b>只增不改</b>：{@link #updateById} 显式拒绝（{@code CONTRACTS.md} §2.3）。登录成功/失败/锁定/解锁
 * 全部留痕（{@code R-AUTH-7}），故本表**不含**任何修改或删除路径。
 *
 * <p>{@code user_id} 可空——**用户不存在时**为 {@code NULL}（{@code TB-AUTH-10} 备注）：
 * 故按用户检索时**必须**处理 {@code null}（表示"不限用户"），否则会漏掉"用不存在的用户名尝试登录"的记录。
 */
public final class DmLoginLogDao extends AbstractDmDao<LoginLog, Long> implements LoginLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "user_id", "username_input", "event_type", "fail_count_after", "client_ip",
            "user_agent", "fail_reason_code", "event_time"
    };

    /** 登录失败事件（锁定判据的统计口径，{@code ck_login_log_event_type}）。 */
    private static final String EVENT_LOGIN_FAIL = "LOGIN_FAIL";

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmLoginLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_login_log";
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
    protected void bindBusiness(PreparedStatement ps, LoginLog e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.userId);
        bind(ps, i++, e.usernameInput);
        bind(ps, i++, e.eventType);
        bind(ps, i++, e.failCountAfter);
        bind(ps, i++, e.clientIp);
        bind(ps, i++, e.userAgent);
        bind(ps, i++, e.failReasonCode);
        bind(ps, i, e.eventTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, LoginLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.userId = nullableLong(rs, "user_id");
        e.usernameInput = string(rs, "username_input");
        e.eventType = string(rs, "event_type");
        e.failCountAfter = nullableInt(rs, "fail_count_after");
        e.clientIp = string(rs, "client_ip");
        e.userAgent = string(rs, "user_agent");
        e.failReasonCode = string(rs, "fail_reason_code");
        e.eventTime = localDateTime(rs, "event_time");
    }

    @Override
    protected Long idOf(LoginLog entity) {
        return entity.id;
    }

    @Override
    protected LoginLog newEntity() {
        return new LoginLog();
    }

    @Override
    protected void applyGeneratedId(LoginLog entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(LoginLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_login_log 只增不改（R-AUTH-7 留痕不可篡改）；如需修正请新增一条事件，"
                        + "禁止原地改写；复现=检查调用点 LoginLogDao.updateById");
    }

    @Override
    public List<LoginLog> findByUserBetween(Long userId, LocalDateTime from, LocalDateTime to) {
        // userId 为 null ⇒ 不限用户（可覆盖"用不存在用户名尝试登录"的记录，其 user_id 为 NULL）
        StringBuilder sql = new StringBuilder("SELECT ").append(selectList())
                .append(" FROM ").append(tableName())
                .append(" WHERE event_time BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(3);
        args.add(from);
        args.add(to);
        if (userId != null) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        sql.append(" ORDER BY event_time DESC, id DESC");
        return queryList(sql.toString(), args, this::mapRow);
    }

    @Override
    public long countFailByIpSince(String clientIp, LocalDateTime from) {
        String sql = "SELECT COUNT(*) FROM " + tableName()
                + " WHERE client_ip = ? AND event_type = ? AND event_time >= ?";
        Long n = queryOne(sql, Arrays.asList(clientIp, EVENT_LOGIN_FAIL, from),
                rs -> rs.getLong(1));
        return n == null ? 0L : n;
    }
}
