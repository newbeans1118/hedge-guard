package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleCtpFundDao;
import com.hedgeguard.dao.entity.SettleCtpFund;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_ctp_settle_fund} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-3）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleCtpFundDao extends AbstractDmDao<SettleCtpFund, Long> implements SettleCtpFundDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "trade_date", "settle_batch_id", "equity", "available_fund", "margin_occupied", "frozen_margin", "commission_today", "deposit_today", "withdraw_today", "close_pnl", "float_pnl"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleCtpFundDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_ctp_settle_fund";
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
    protected void bindBusiness(PreparedStatement ps, SettleCtpFund e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.settleBatchId);
        bind(ps, i++, e.equity);
        bind(ps, i++, e.availableFund);
        bind(ps, i++, e.marginOccupied);
        bind(ps, i++, e.frozenMargin);
        bind(ps, i++, e.commissionToday);
        bind(ps, i++, e.depositToday);
        bind(ps, i++, e.withdrawToday);
        bind(ps, i++, e.closePnl);
        bind(ps, i++, e.floatPnl);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleCtpFund e) throws SQLException {
        e.id = rs.getLong("id");
        e.tradeDate = localDate(rs, "trade_date");
        e.settleBatchId = nullableLong(rs, "settle_batch_id");
        e.equity = decimal(rs, "equity");
        e.availableFund = decimal(rs, "available_fund");
        e.marginOccupied = decimal(rs, "margin_occupied");
        e.frozenMargin = decimal(rs, "frozen_margin");
        e.commissionToday = decimal(rs, "commission_today");
        e.depositToday = decimal(rs, "deposit_today");
        e.withdrawToday = decimal(rs, "withdraw_today");
        e.closePnl = decimal(rs, "close_pnl");
        e.floatPnl = decimal(rs, "float_pnl");
    }

    @Override
    protected Long idOf(SettleCtpFund entity) {
        return entity.id;
    }

    @Override
    protected SettleCtpFund newEntity() {
        return new SettleCtpFund();
    }

    @Override
    protected void applyGeneratedId(SettleCtpFund entity, long generated) {
        entity.id = generated;
    }
}
