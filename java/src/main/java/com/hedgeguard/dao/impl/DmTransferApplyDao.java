package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TransferApplyDao;
import com.hedgeguard.dao.entity.TransferApply;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_transfer_apply} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-4）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmTransferApplyDao extends AbstractDmDao<TransferApply, Long> implements TransferApplyDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "from_sub_account_id", "to_sub_account_id", "apply_no", "amount",
            "reason", "expected_effective_date", "status", "applicant_id", "approver_id",
            "approve_time", "reject_reason", "agg_check_id", "agg_check_passed", "rollback_time",
            "posting_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTransferApplyDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_transfer_apply";
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
    protected void bindBusiness(PreparedStatement ps, TransferApply e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.fromSubAccountId);
        bind(ps, i++, e.toSubAccountId);
        bind(ps, i++, e.applyNo);
        bind(ps, i++, e.amount);
        bind(ps, i++, e.reason);
        bind(ps, i++, e.expectedEffectiveDate);
        bind(ps, i++, e.status);
        bind(ps, i++, e.applicantId);
        bind(ps, i++, e.approverId);
        bind(ps, i++, e.approveTime);
        bind(ps, i++, e.rejectReason);
        bind(ps, i++, e.aggCheckId);
        bind(ps, i++, e.aggCheckPassed);
        bind(ps, i++, e.rollbackTime);
        bind(ps, i, e.postingTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, TransferApply e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.fromSubAccountId = nullableInt(rs, "from_sub_account_id");
        e.toSubAccountId = nullableInt(rs, "to_sub_account_id");
        e.applyNo = string(rs, "apply_no");
        e.amount = decimal(rs, "amount");
        e.reason = string(rs, "reason");
        e.expectedEffectiveDate = localDate(rs, "expected_effective_date");
        e.status = string(rs, "status");
        e.applicantId = nullableLong(rs, "applicant_id");
        e.approverId = nullableLong(rs, "approver_id");
        e.approveTime = localDateTime(rs, "approve_time");
        e.rejectReason = string(rs, "reject_reason");
        e.aggCheckId = nullableLong(rs, "agg_check_id");
        e.aggCheckPassed = string(rs, "agg_check_passed");
        e.rollbackTime = localDateTime(rs, "rollback_time");
        e.postingTime = localDateTime(rs, "posting_time");
    }

    @Override
    protected Long idOf(TransferApply entity) {
        return entity.id;
    }

    @Override
    protected TransferApply newEntity() {
        return new TransferApply();
    }

    @Override
    protected void applyGeneratedId(TransferApply entity, long generated) {
        entity.id = generated;
    }

    @Override
    public TransferApply findByApplyNo(String applyNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE apply_no = ?";
        return queryOne(sql, Arrays.asList(applyNo), this::mapRow);
    }

    @Override
    public List<TransferApply> findByFromSubAccountAndStatus(int fromSubAccountId, String status) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE from_sub_account_id = ? AND status = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(fromSubAccountId, status), this::mapRow);
    }

    @Override
    public List<TransferApply> findByToSubAccountAndStatus(int toSubAccountId, String status) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE to_sub_account_id = ? AND status = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(toSubAccountId, status), this::mapRow);
    }
}
