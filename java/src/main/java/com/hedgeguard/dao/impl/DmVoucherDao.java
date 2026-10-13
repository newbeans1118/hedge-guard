package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.VoucherDao;
import com.hedgeguard.dao.entity.Voucher;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_voucher} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-4）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。状态流转经 {@code updateById} + 乐观锁，由服务层经业务动作触发。
 */
public final class DmVoucherDao extends AbstractDmDao<Voucher, Long> implements VoucherDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "voucher_no", "period_key", "voucher_date", "voucher_type",
            "hedge_plan_id", "hedge_relation_id", "settle_cycle_id", "exposure_id", "status",
            "total_amount", "generated_by", "generated_time", "reversal_of_id", "reversed_by_id",
            "reversal_reason", "pending_external_review"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmVoucherDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_voucher";
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
    protected void bindBusiness(PreparedStatement ps, Voucher e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.voucherNo);
        bind(ps, i++, e.periodKey);
        bind(ps, i++, e.voucherDate);
        bind(ps, i++, e.voucherType);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.hedgeRelationId);
        bind(ps, i++, e.settleCycleId);
        bind(ps, i++, e.exposureId);
        bind(ps, i++, e.status);
        bind(ps, i++, e.totalAmount);
        bind(ps, i++, e.generatedBy);
        bind(ps, i++, e.generatedTime);
        bind(ps, i++, e.reversalOfId);
        bind(ps, i++, e.reversedById);
        bind(ps, i++, e.reversalReason);
        bind(ps, i, e.pendingExternalReview);
    }

    @Override
    protected void readBusiness(ResultSet rs, Voucher e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.voucherNo = string(rs, "voucher_no");
        e.periodKey = string(rs, "period_key");
        e.voucherDate = localDate(rs, "voucher_date");
        e.voucherType = string(rs, "voucher_type");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.hedgeRelationId = nullableLong(rs, "hedge_relation_id");
        e.settleCycleId = nullableLong(rs, "settle_cycle_id");
        e.exposureId = nullableLong(rs, "exposure_id");
        e.status = string(rs, "status");
        e.totalAmount = decimal(rs, "total_amount");
        e.generatedBy = nullableLong(rs, "generated_by");
        e.generatedTime = localDateTime(rs, "generated_time");
        e.reversalOfId = nullableLong(rs, "reversal_of_id");
        e.reversedById = nullableLong(rs, "reversed_by_id");
        e.reversalReason = string(rs, "reversal_reason");
        e.pendingExternalReview = string(rs, "pending_external_review");
    }

    @Override
    protected Long idOf(Voucher entity) {
        return entity.id;
    }

    @Override
    protected Voucher newEntity() {
        return new Voucher();
    }

    @Override
    protected void applyGeneratedId(Voucher entity, long generated) {
        entity.id = generated;
    }

    @Override
    public Voucher findByVoucherNo(String voucherNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE voucher_no = ?";
        return queryOne(sql, Arrays.asList(voucherNo), this::mapRow);
    }

    @Override
    public List<Voucher> findBySettleCycle(long settleCycleId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE settle_cycle_id = ? ORDER BY voucher_no ASC, id ASC";
        return queryList(sql, Arrays.asList(settleCycleId), this::mapRow);
    }

    @Override
    public List<Voucher> findByExposure(long exposureId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE exposure_id = ? ORDER BY voucher_no ASC, id ASC";
        return queryList(sql, Arrays.asList(exposureId), this::mapRow);
    }

    @Override
    public List<Voucher> findBySubPeriodStatus(int subAccountId, String periodKey, String status) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND period_key = ? AND status = ?"
                + " ORDER BY voucher_no ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId, periodKey, status), this::mapRow);
    }
}
