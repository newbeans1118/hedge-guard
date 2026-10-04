package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradePositionDao;
import com.hedgeguard.dao.entity.TradePosition;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_position} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-5）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmTradePositionDao extends AbstractDmDao<TradePosition, Long> implements TradePositionDao {

    /** 业务列（不含主键与通用审计字段；row_version 属审计列，不在此列）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "instrument_id", "product_id", "direction", "position_type",
            "position_qty", "available_qty", "frozen_qty", "avg_open_price",
            "last_settlement_price", "hedge_plan_id", "as_of_time", "event_seq"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradePositionDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_position";
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
    protected void bindBusiness(PreparedStatement ps, TradePosition e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.positionType);
        bind(ps, i++, e.positionQty);
        bind(ps, i++, e.availableQty);
        bind(ps, i++, e.frozenQty);
        bind(ps, i++, e.avgOpenPrice);
        bind(ps, i++, e.lastSettlementPrice);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.asOfTime);
        bind(ps, i, e.eventSeq);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradePosition e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.instrumentId = string(rs, "instrument_id");
        e.productId = nullableLong(rs, "product_id");
        e.direction = string(rs, "direction");
        e.positionType = string(rs, "position_type");
        e.positionQty = decimal(rs, "position_qty");
        e.availableQty = decimal(rs, "available_qty");
        e.frozenQty = decimal(rs, "frozen_qty");
        e.avgOpenPrice = decimal(rs, "avg_open_price");
        e.lastSettlementPrice = decimal(rs, "last_settlement_price");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.asOfTime = localDateTime(rs, "as_of_time");
        e.eventSeq = nullableLong(rs, "event_seq");
    }

    @Override
    protected Long idOf(TradePosition entity) {
        return entity.id;
    }

    @Override
    protected TradePosition newEntity() {
        return new TradePosition();
    }

    @Override
    protected void applyGeneratedId(TradePosition entity, long generated) {
        entity.id = generated;
    }

    @Override
    public TradePosition findByKey(int subAccountId, String instrumentId, String direction,
                                   String positionType) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND instrument_id = ? AND direction = ? AND position_type = ?";
        return queryOne(sql, Arrays.asList(subAccountId, instrumentId, direction, positionType),
                this::mapRow);
    }

    @Override
    public List<TradePosition> findBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? ORDER BY instrument_id ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId), this::mapRow);
    }
}
