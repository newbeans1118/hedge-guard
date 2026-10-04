package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettlePnlDao;
import com.hedgeguard.dao.entity.SettlePnl;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_settle_pnl} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-8）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettlePnlDao extends AbstractDmDao<SettlePnl, Long> implements SettlePnlDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "settle_cycle_id", "trade_date", "instrument_id", "product_id", "hedge_plan_id", "pnl_type", "amount", "qty"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettlePnlDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_settle_pnl";
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
    protected void bindBusiness(PreparedStatement ps, SettlePnl e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.settleCycleId);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.pnlType);
        bind(ps, i++, e.amount);
        bind(ps, i++, e.qty);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettlePnl e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.settleCycleId = nullableLong(rs, "settle_cycle_id");
        e.tradeDate = localDate(rs, "trade_date");
        e.instrumentId = string(rs, "instrument_id");
        e.productId = nullableLong(rs, "product_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.pnlType = string(rs, "pnl_type");
        e.amount = decimal(rs, "amount");
        e.qty = decimal(rs, "qty");
    }

    @Override
    protected Long idOf(SettlePnl entity) {
        return entity.id;
    }

    @Override
    protected SettlePnl newEntity() {
        return new SettlePnl();
    }

    @Override
    protected void applyGeneratedId(SettlePnl entity, long generated) {
        entity.id = generated;
    }
}
