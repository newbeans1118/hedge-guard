package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleCtpPositionDao;
import com.hedgeguard.dao.entity.SettleCtpPosition;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_ctp_settle_position} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-2）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleCtpPositionDao extends AbstractDmDao<SettleCtpPosition, Long> implements SettleCtpPositionDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "trade_date", "settle_batch_id", "instrument_id", "direction", "position_type", "position_qty", "avg_price", "settlement_price", "margin", "close_pnl", "position_pnl"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleCtpPositionDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_ctp_settle_position";
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
    protected void bindBusiness(PreparedStatement ps, SettleCtpPosition e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.settleBatchId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.positionType);
        bind(ps, i++, e.positionQty);
        bind(ps, i++, e.avgPrice);
        bind(ps, i++, e.settlementPrice);
        bind(ps, i++, e.margin);
        bind(ps, i++, e.closePnl);
        bind(ps, i++, e.positionPnl);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleCtpPosition e) throws SQLException {
        e.id = rs.getLong("id");
        e.tradeDate = localDate(rs, "trade_date");
        e.settleBatchId = nullableLong(rs, "settle_batch_id");
        e.instrumentId = string(rs, "instrument_id");
        e.direction = string(rs, "direction");
        e.positionType = string(rs, "position_type");
        e.positionQty = decimal(rs, "position_qty");
        e.avgPrice = decimal(rs, "avg_price");
        e.settlementPrice = decimal(rs, "settlement_price");
        e.margin = decimal(rs, "margin");
        e.closePnl = decimal(rs, "close_pnl");
        e.positionPnl = decimal(rs, "position_pnl");
    }

    @Override
    protected Long idOf(SettleCtpPosition entity) {
        return entity.id;
    }

    @Override
    protected SettleCtpPosition newEntity() {
        return new SettleCtpPosition();
    }

    @Override
    protected void applyGeneratedId(SettleCtpPosition entity, long generated) {
        entity.id = generated;
    }
}
