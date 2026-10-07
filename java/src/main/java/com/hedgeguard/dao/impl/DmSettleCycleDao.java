package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleCycleDao;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_settle_cycle} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-5）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleCycleDao extends AbstractDmDao<SettleCycle, Long> implements SettleCycleDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "settle_no", "trade_date", "is_aggregate", "status", "close_pnl", "float_pnl", "commission", "margin_occupied", "available_fund", "equity", "risk_ratio", "negative_fund_flag", "confirmed_by", "confirmed_time", "voided_flag", "void_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleCycleDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_settle_cycle";
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
    protected void bindBusiness(PreparedStatement ps, SettleCycle e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.settleNo);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.isAggregate);
        bind(ps, i++, e.status);
        bind(ps, i++, e.closePnl);
        bind(ps, i++, e.floatPnl);
        bind(ps, i++, e.commission);
        bind(ps, i++, e.marginOccupied);
        bind(ps, i++, e.availableFund);
        bind(ps, i++, e.equity);
        bind(ps, i++, e.riskRatio);
        bind(ps, i++, e.negativeFundFlag);
        bind(ps, i++, e.confirmedBy);
        bind(ps, i++, e.confirmedTime);
        bind(ps, i++, e.voidedFlag);
        bind(ps, i++, e.voidReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleCycle e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.settleNo = string(rs, "settle_no");
        e.tradeDate = localDate(rs, "trade_date");
        e.isAggregate = string(rs, "is_aggregate");
        e.status = string(rs, "status");
        e.closePnl = decimal(rs, "close_pnl");
        e.floatPnl = decimal(rs, "float_pnl");
        e.commission = decimal(rs, "commission");
        e.marginOccupied = decimal(rs, "margin_occupied");
        e.availableFund = decimal(rs, "available_fund");
        e.equity = decimal(rs, "equity");
        e.riskRatio = decimal(rs, "risk_ratio");
        e.negativeFundFlag = string(rs, "negative_fund_flag");
        e.confirmedBy = nullableLong(rs, "confirmed_by");
        e.confirmedTime = localDateTime(rs, "confirmed_time");
        e.voidedFlag = string(rs, "voided_flag");
        e.voidReason = string(rs, "void_reason");
    }

    @Override
    protected Long idOf(SettleCycle entity) {
        return entity.id;
    }

    @Override
    protected SettleCycle newEntity() {
        return new SettleCycle();
    }

    @Override
    protected void applyGeneratedId(SettleCycle entity, long generated) {
        entity.id = generated;
    }

    @Override
    public java.util.List<SettleCycle> findBySubAccountAndDate(int subAccountId, java.time.LocalDate tradeDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND trade_date = ? ORDER BY id ASC";
        return queryList(sql, java.util.Arrays.asList(subAccountId, tradeDate), this::mapRow);
    }
}
