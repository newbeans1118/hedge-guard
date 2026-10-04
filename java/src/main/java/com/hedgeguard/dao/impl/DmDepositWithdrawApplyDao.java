package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.DepositWithdrawApplyDao;
import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_deposit_withdraw_apply} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-3）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmDepositWithdrawApplyDao extends AbstractDmDao<DepositWithdrawApply, Long> implements DepositWithdrawApplyDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "apply_no", "idempotency_key", "direction", "amount",
            "fund_source_desc", "purpose_desc", "status", "applicant_id", "reviewer_id",
            "review_time", "reject_reason", "submit_time", "available_before", "retry_of_id",
            "original_confirmed"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmDepositWithdrawApplyDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_deposit_withdraw_apply";
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
    protected void bindBusiness(PreparedStatement ps, DepositWithdrawApply e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.applyNo);
        bind(ps, i++, e.idempotencyKey);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.amount);
        bind(ps, i++, e.fundSourceDesc);
        bind(ps, i++, e.purposeDesc);
        bind(ps, i++, e.status);
        bind(ps, i++, e.applicantId);
        bind(ps, i++, e.reviewerId);
        bind(ps, i++, e.reviewTime);
        bind(ps, i++, e.rejectReason);
        bind(ps, i++, e.submitTime);
        bind(ps, i++, e.availableBefore);
        bind(ps, i++, e.retryOfId);
        bind(ps, i, e.originalConfirmed);
    }

    @Override
    protected void readBusiness(ResultSet rs, DepositWithdrawApply e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.applyNo = string(rs, "apply_no");
        e.idempotencyKey = string(rs, "idempotency_key");
        e.direction = string(rs, "direction");
        e.amount = decimal(rs, "amount");
        e.fundSourceDesc = string(rs, "fund_source_desc");
        e.purposeDesc = string(rs, "purpose_desc");
        e.status = string(rs, "status");
        e.applicantId = nullableLong(rs, "applicant_id");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.reviewTime = localDateTime(rs, "review_time");
        e.rejectReason = string(rs, "reject_reason");
        e.submitTime = localDateTime(rs, "submit_time");
        e.availableBefore = decimal(rs, "available_before");
        e.retryOfId = nullableLong(rs, "retry_of_id");
        e.originalConfirmed = string(rs, "original_confirmed");
    }

    @Override
    protected Long idOf(DepositWithdrawApply entity) {
        return entity.id;
    }

    @Override
    protected DepositWithdrawApply newEntity() {
        return new DepositWithdrawApply();
    }

    @Override
    protected void applyGeneratedId(DepositWithdrawApply entity, long generated) {
        entity.id = generated;
    }

    @Override
    public DepositWithdrawApply findByIdempotencyKey(String idempotencyKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE idempotency_key = ?";
        return queryOne(sql, Arrays.asList(idempotencyKey), this::mapRow);
    }

    @Override
    public DepositWithdrawApply findByApplyNo(String applyNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE apply_no = ?";
        return queryOne(sql, Arrays.asList(applyNo), this::mapRow);
    }

    @Override
    public List<DepositWithdrawApply> findBySubAccountAndStatus(int subAccountId, String status) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND status = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(subAccountId, status), this::mapRow);
    }
}
