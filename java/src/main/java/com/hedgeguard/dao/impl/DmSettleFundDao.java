package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleFundDao;
import com.hedgeguard.dao.entity.SettleFund;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_settle_fund} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-7）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleFundDao extends AbstractDmDao<SettleFund, Long> implements SettleFundDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "settle_cycle_id", "trade_date", "begin_balance", "equity", "available_fund", "margin_occupied", "frozen_margin", "amount_in", "amount_out", "transfer_in", "transfer_out", "close_pnl", "float_pnl", "commission", "margin_change", "end_balance", "fund_ledger_id", "attribution_source"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleFundDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_settle_fund";
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
    protected void bindBusiness(PreparedStatement ps, SettleFund e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.settleCycleId);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.beginBalance);
        bind(ps, i++, e.equity);
        bind(ps, i++, e.availableFund);
        bind(ps, i++, e.marginOccupied);
        bind(ps, i++, e.frozenMargin);
        bind(ps, i++, e.amountIn);
        bind(ps, i++, e.amountOut);
        bind(ps, i++, e.transferIn);
        bind(ps, i++, e.transferOut);
        bind(ps, i++, e.closePnl);
        bind(ps, i++, e.floatPnl);
        bind(ps, i++, e.commission);
        bind(ps, i++, e.marginChange);
        bind(ps, i++, e.endBalance);
        bind(ps, i++, e.fundLedgerId);
        bind(ps, i++, e.attributionSource);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleFund e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.settleCycleId = nullableLong(rs, "settle_cycle_id");
        e.tradeDate = localDate(rs, "trade_date");
        e.beginBalance = decimal(rs, "begin_balance");
        e.equity = decimal(rs, "equity");
        e.availableFund = decimal(rs, "available_fund");
        e.marginOccupied = decimal(rs, "margin_occupied");
        e.frozenMargin = decimal(rs, "frozen_margin");
        e.amountIn = decimal(rs, "amount_in");
        e.amountOut = decimal(rs, "amount_out");
        e.transferIn = decimal(rs, "transfer_in");
        e.transferOut = decimal(rs, "transfer_out");
        e.closePnl = decimal(rs, "close_pnl");
        e.floatPnl = decimal(rs, "float_pnl");
        e.commission = decimal(rs, "commission");
        e.marginChange = decimal(rs, "margin_change");
        e.endBalance = decimal(rs, "end_balance");
        e.fundLedgerId = nullableLong(rs, "fund_ledger_id");
        e.attributionSource = string(rs, "attribution_source");
    }

    @Override
    protected Long idOf(SettleFund entity) {
        return entity.id;
    }

    @Override
    protected SettleFund newEntity() {
        return new SettleFund();
    }

    @Override
    protected void applyGeneratedId(SettleFund entity, long generated) {
        entity.id = generated;
    }
}
