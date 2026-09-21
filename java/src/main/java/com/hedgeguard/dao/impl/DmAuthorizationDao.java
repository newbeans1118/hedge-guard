package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_authorization} 的达梦实现（{@code DATA_MODEL.md} §2.2 TB-AUTH-7）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 *
 * <p>本表含行级归属列 {@code sub_account_id}（恒 {@code > 0}）；{@link #findEffective(long, LocalDate)}
 * 为「某人在某日的有效授权」取数入口（{@code idx_authorization_user_eff}），供 {@code FR-PLAN-10} ④
 * 操作授权服务端强制使用。
 */
public final class DmAuthorizationDao extends AbstractDmDao<Authorization, Long>
        implements AuthorizationDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "user_id", "sub_account_id", "product_id", "quota_limit", "effective_from", "effective_to",
            "status", "applicant_id", "reviewer_id", "dispatch_status", "dispatch_version",
            "reject_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmAuthorizationDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_authorization";
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
    protected void bindBusiness(PreparedStatement ps, Authorization e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.userId);
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.quotaLimit);
        bind(ps, i++, e.effectiveFrom);
        bind(ps, i++, e.effectiveTo);
        bind(ps, i++, e.status);
        bind(ps, i++, e.applicantId);
        bind(ps, i++, e.reviewerId);
        bind(ps, i++, e.dispatchStatus);
        bind(ps, i++, e.dispatchVersion);
        bind(ps, i, e.rejectReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, Authorization e) throws SQLException {
        e.id = rs.getLong("id");
        e.userId = rs.getLong("user_id");
        e.subAccountId = rs.getInt("sub_account_id");
        e.productId = rs.getLong("product_id");
        e.quotaLimit = decimal(rs, "quota_limit");
        e.effectiveFrom = localDate(rs, "effective_from");
        e.effectiveTo = localDate(rs, "effective_to");
        e.status = string(rs, "status");
        e.applicantId = rs.getLong("applicant_id");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.dispatchStatus = string(rs, "dispatch_status");
        e.dispatchVersion = nullableLong(rs, "dispatch_version");
        e.rejectReason = string(rs, "reject_reason");
    }

    @Override
    protected Long idOf(Authorization entity) {
        return entity.id;
    }

    @Override
    protected Authorization newEntity() {
        return new Authorization();
    }

    @Override
    protected void applyGeneratedId(Authorization entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<Authorization> findEffective(long userId, LocalDate onDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE user_id = ? AND status = 'EFFECTIVE'"
                + " AND (effective_from IS NULL OR effective_from <= ?)"
                + " AND (effective_to IS NULL OR effective_to >= ?)"
                + " ORDER BY sub_account_id ASC, product_id ASC, id ASC";
        return queryList(sql, Arrays.asList(userId, onDate, onDate), this::mapRow);
    }

    @Override
    public List<Authorization> findBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? ORDER BY product_id ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId), this::mapRow);
    }

    @Override
    public int updateDispatchStatus(long id, String status, Long version) {
        String sql = "UPDATE " + tableName()
                + " SET dispatch_status = ?, dispatch_version = ? WHERE id = ?";
        return executeUpdateSql(sql, Arrays.asList(status, version, id));
    }
}
