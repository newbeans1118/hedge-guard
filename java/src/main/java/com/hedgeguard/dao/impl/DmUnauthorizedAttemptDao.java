package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_unauthorized_attempt} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-13}）。
 *
 * <p><b>只增不改</b>：{@link #updateById} 显式拒绝（越权留痕不可篡改，{@code R-AUTH-11}）。
 *
 * <p>{@code attempt_type} 取值域（{@code ck_unauth_attempt_type}）：
 * {@code POSITION_CONFLICT}/{@code LEADER_TRADE}/{@code CROSS_SUB_WRITE}/{@code SYSADMIN_BIZ_OP}/
 * {@code DUAL_REVIEW_SELF}/{@code PERMISSION_DENIED}/{@code SELF_APPROVE}。
 * 本 DAO **不校验**该取值域——由服务层按场景选择，DB 的 {@code CHECK} 兜底。
 */
public final class DmUnauthorizedAttemptDao extends AbstractDmDao<UnauthorizedAttempt, Long>
        implements UnauthorizedAttemptDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "operator_id", "attempt_type", "attempt_desc", "target_resource", "sub_account_id",
            "reject_reason", "reject_code", "attempt_time", "client_ip"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmUnauthorizedAttemptDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_unauthorized_attempt";
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
    protected void bindBusiness(PreparedStatement ps, UnauthorizedAttempt e, int start)
            throws SQLException {
        int i = start;
        ps.setLong(i++, e.operatorId);
        bind(ps, i++, e.attemptType);
        bind(ps, i++, e.attemptDesc);
        bind(ps, i++, e.targetResource);
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.rejectReason);
        bind(ps, i++, e.rejectCode);
        bind(ps, i++, e.attemptTime);
        bind(ps, i, e.clientIp);
    }

    @Override
    protected void readBusiness(ResultSet rs, UnauthorizedAttempt e) throws SQLException {
        e.id = rs.getLong("id");
        e.operatorId = rs.getLong("operator_id");
        e.attemptType = string(rs, "attempt_type");
        e.attemptDesc = string(rs, "attempt_desc");
        e.targetResource = string(rs, "target_resource");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.rejectReason = string(rs, "reject_reason");
        e.rejectCode = string(rs, "reject_code");
        e.attemptTime = localDateTime(rs, "attempt_time");
        e.clientIp = string(rs, "client_ip");
    }

    @Override
    protected Long idOf(UnauthorizedAttempt entity) {
        return entity.id;
    }

    @Override
    protected UnauthorizedAttempt newEntity() {
        return new UnauthorizedAttempt();
    }

    @Override
    protected void applyGeneratedId(UnauthorizedAttempt entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(UnauthorizedAttempt entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_unauthorized_attempt 只增不改（越权留痕不可篡改，R-AUTH-11）；"
                        + "复现=检查调用点 UnauthorizedAttemptDao.updateById");
    }

    @Override
    public List<UnauthorizedAttempt> findByOperatorSince(long operatorId, LocalDateTime from) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE operator_id = ? AND attempt_time >= ?"
                + " ORDER BY attempt_time DESC, id DESC";
        return queryList(sql, new ArrayList<>(Arrays.asList(operatorId, from)), this::mapRow);
    }

    @Override
    public long countByTypeBetween(String attemptType, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COUNT(*) FROM " + tableName()
                + " WHERE attempt_type = ? AND attempt_time BETWEEN ? AND ?";
        Long n = queryOne(sql, Arrays.asList(attemptType, from, to), rs -> rs.getLong(1));
        return n == null ? 0L : n;
    }
}
