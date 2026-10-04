package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.FundFlowDao;
import com.hedgeguard.dao.entity.FundFlow;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_fund_flow} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-2）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 *
 * <p>t_fund_flow 为只增不改不删表（资金凭证与操作留痕，R-FUND-2）：{@link #updateById} 显式抛 {@link UnsupportedOperationException}。
 */
public final class DmFundFlowDao extends AbstractDmDao<FundFlow, Long> implements FundFlowDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "voucher_no", "ledger_date", "flow_type", "amount", "direction",
            "balance_after", "transfer_apply_id", "deposit_withdraw_id", "settle_cycle_id",
            "trade_fill_id", "counterpart_sub_account_id", "operator_id", "flow_time", "remark",
            "double_review_required"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmFundFlowDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_fund_flow";
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
    protected void bindBusiness(PreparedStatement ps, FundFlow e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.voucherNo);
        bind(ps, i++, e.ledgerDate);
        bind(ps, i++, e.flowType);
        bind(ps, i++, e.amount);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.balanceAfter);
        bind(ps, i++, e.transferApplyId);
        bind(ps, i++, e.depositWithdrawId);
        bind(ps, i++, e.settleCycleId);
        bind(ps, i++, e.tradeFillId);
        bind(ps, i++, e.counterpartSubAccountId);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.flowTime);
        bind(ps, i++, e.remark);
        bind(ps, i, e.doubleReviewRequired);
    }

    @Override
    protected void readBusiness(ResultSet rs, FundFlow e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.voucherNo = string(rs, "voucher_no");
        e.ledgerDate = localDate(rs, "ledger_date");
        e.flowType = string(rs, "flow_type");
        e.amount = decimal(rs, "amount");
        e.direction = string(rs, "direction");
        e.balanceAfter = decimal(rs, "balance_after");
        e.transferApplyId = nullableLong(rs, "transfer_apply_id");
        e.depositWithdrawId = nullableLong(rs, "deposit_withdraw_id");
        e.settleCycleId = nullableLong(rs, "settle_cycle_id");
        e.tradeFillId = nullableLong(rs, "trade_fill_id");
        e.counterpartSubAccountId = nullableInt(rs, "counterpart_sub_account_id");
        e.operatorId = nullableLong(rs, "operator_id");
        e.flowTime = localDateTime(rs, "flow_time");
        e.remark = string(rs, "remark");
        e.doubleReviewRequired = string(rs, "double_review_required");
    }

    @Override
    protected Long idOf(FundFlow entity) {
        return entity.id;
    }

    @Override
    protected FundFlow newEntity() {
        return new FundFlow();
    }

    @Override
    protected void applyGeneratedId(FundFlow entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(FundFlow entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_fund_flow 为只增不改不删表（资金凭证与操作留痕，R-FUND-2），不提供 updateById；"
                        + "复现=对已落库流水调用 updateById");
    }

    @Override
    public FundFlow findByVoucherNo(String voucherNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE voucher_no = ?";
        return queryOne(sql, Arrays.asList(voucherNo), this::mapRow);
    }

    @Override
    public List<FundFlow> findBySubAccountAndDate(int subAccountId, LocalDate ledgerDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND ledger_date = ? ORDER BY flow_time ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId, ledgerDate), this::mapRow);
    }

    @Override
    public List<FundFlow> findByTransferApplyId(long transferApplyId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE transfer_apply_id = ? ORDER BY flow_time ASC, id ASC";
        return queryList(sql, Arrays.asList(transferApplyId), this::mapRow);
    }

    @Override
    public List<FundFlow> findByDate(LocalDate ledgerDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE ledger_date = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(ledgerDate), this::mapRow);
    }
}
