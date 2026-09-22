package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PermissionChangeLogDao;
import com.hedgeguard.dao.entity.PermissionChangeLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_permission_change_log} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-12}）。
 *
 * <p><b>只增不改不删</b>：{@link #updateById} 显式拒绝（权限变更留痕不可篡改，{@code R-AUTH-4}）；
 * 被拒绝的尝试（{@code rejected='Y'}）亦须留痕（{@code R-AUTH-8}）。
 *
 * <p>{@code before_value} / {@code after_value} 为 {@code CLOB}（{@code DATA_MODEL.md} §6.1 D7），
 * 经标准 {@code getCharacterStream} / {@code setCharacterStream} 读写（不依赖 DM 专有 API）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmPermissionChangeLogDao extends AbstractDmDao<PermissionChangeLog, Long>
        implements PermissionChangeLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "change_target", "target_id", "target_user_id", "sub_account_id", "before_value",
            "after_value", "operator_id", "reviewer_id", "change_reason", "rejected",
            "reject_reason", "change_time", "dispatch_status"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmPermissionChangeLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_permission_change_log";
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
    protected void bindBusiness(PreparedStatement ps, PermissionChangeLog e, int start)
            throws SQLException {
        int i = start;
        bind(ps, i++, e.changeTarget);
        bind(ps, i++, e.targetId);
        bind(ps, i++, e.targetUserId);
        bind(ps, i++, e.subAccountId);
        bindClob(ps, i++, e.beforeValue);
        bindClob(ps, i++, e.afterValue);
        ps.setLong(i++, e.operatorId);
        bind(ps, i++, e.reviewerId);
        bind(ps, i++, e.changeReason);
        bind(ps, i++, e.rejected);
        bind(ps, i++, e.rejectReason);
        bind(ps, i++, e.changeTime);
        bind(ps, i, e.dispatchStatus);
    }

    @Override
    protected void readBusiness(ResultSet rs, PermissionChangeLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.changeTarget = string(rs, "change_target");
        e.targetId = nullableLong(rs, "target_id");
        e.targetUserId = nullableLong(rs, "target_user_id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.beforeValue = clob(rs, "before_value");
        e.afterValue = clob(rs, "after_value");
        e.operatorId = rs.getLong("operator_id");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.changeReason = string(rs, "change_reason");
        e.rejected = string(rs, "rejected");
        e.rejectReason = string(rs, "reject_reason");
        e.changeTime = localDateTime(rs, "change_time");
        e.dispatchStatus = string(rs, "dispatch_status");
    }

    @Override
    protected Long idOf(PermissionChangeLog entity) {
        return entity.id;
    }

    @Override
    protected PermissionChangeLog newEntity() {
        return new PermissionChangeLog();
    }

    @Override
    protected void applyGeneratedId(PermissionChangeLog entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(PermissionChangeLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_permission_change_log 只增不改（权限变更留痕不可篡改，R-AUTH-4）；"
                        + "复现=检查调用点 PermissionChangeLogDao.updateById");
    }

    @Override
    public List<PermissionChangeLog> findByTargetUserBetween(long targetUserId, LocalDateTime from,
                                                             LocalDateTime to) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE target_user_id = ? AND change_time >= ? AND change_time <= ?"
                + " ORDER BY change_time DESC, id DESC";
        return queryList(sql, Arrays.asList(targetUserId, from, to), this::mapRow);
    }

    @Override
    public List<PermissionChangeLog> findRejectedSince(LocalDateTime from) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE rejected = ? AND change_time >= ?"
                + " ORDER BY change_time DESC, id DESC";
        return queryList(sql, Arrays.asList("Y", from), this::mapRow);
    }
}
