package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.FundLedgerDao;
import com.hedgeguard.dao.entity.FundLedger;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_fund_ledger} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-1）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmFundLedgerDao extends AbstractDmDao<FundLedger, Long> implements FundLedgerDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "ledger_date", "begin_balance", "amount_in", "amount_out",
            "transfer_in", "transfer_out", "pnl_carryover", "commission", "margin_change",
            "difference_adjust", "end_balance", "available_fund", "margin_occupied", "equity",
            "risk_ratio", "is_closed"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmFundLedgerDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_fund_ledger";
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
    protected void bindBusiness(PreparedStatement ps, FundLedger e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.ledgerDate);
        bind(ps, i++, e.beginBalance);
        bind(ps, i++, e.amountIn);
        bind(ps, i++, e.amountOut);
        bind(ps, i++, e.transferIn);
        bind(ps, i++, e.transferOut);
        bind(ps, i++, e.pnlCarryover);
        bind(ps, i++, e.commission);
        bind(ps, i++, e.marginChange);
        bind(ps, i++, e.differenceAdjust);
        bind(ps, i++, e.endBalance);
        bind(ps, i++, e.availableFund);
        bind(ps, i++, e.marginOccupied);
        bind(ps, i++, e.equity);
        bind(ps, i++, e.riskRatio);
        bind(ps, i, e.isClosed);
    }

    @Override
    protected void readBusiness(ResultSet rs, FundLedger e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.ledgerDate = localDate(rs, "ledger_date");
        e.beginBalance = decimal(rs, "begin_balance");
        e.amountIn = decimal(rs, "amount_in");
        e.amountOut = decimal(rs, "amount_out");
        e.transferIn = decimal(rs, "transfer_in");
        e.transferOut = decimal(rs, "transfer_out");
        e.pnlCarryover = decimal(rs, "pnl_carryover");
        e.commission = decimal(rs, "commission");
        e.marginChange = decimal(rs, "margin_change");
        e.differenceAdjust = decimal(rs, "difference_adjust");
        e.endBalance = decimal(rs, "end_balance");
        e.availableFund = decimal(rs, "available_fund");
        e.marginOccupied = decimal(rs, "margin_occupied");
        e.equity = decimal(rs, "equity");
        e.riskRatio = decimal(rs, "risk_ratio");
        e.isClosed = string(rs, "is_closed");
    }

    @Override
    protected Long idOf(FundLedger entity) {
        return entity.id;
    }

    @Override
    protected FundLedger newEntity() {
        return new FundLedger();
    }

    @Override
    protected void applyGeneratedId(FundLedger entity, long generated) {
        entity.id = generated;
    }

    @Override
    public FundLedger findBySubAccountAndDate(int subAccountId, LocalDate ledgerDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND ledger_date = ?";
        return queryOne(sql, Arrays.asList(subAccountId, ledgerDate), this::mapRow);
    }

    @Override
    public List<FundLedger> findByDate(LocalDate ledgerDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE ledger_date = ? ORDER BY sub_account_id ASC, id ASC";
        return queryList(sql, Arrays.asList(ledgerDate), this::mapRow);
    }
}
