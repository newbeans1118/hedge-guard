package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradeFillDao;
import com.hedgeguard.dao.entity.TradeFill;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_trade_fill} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-3）。
 *
 * <p>结算与审计原始依据，不得修改/删除（{@code R-TRADE-15}）：{@link #updateById} 显式抛
 * {@link UnsupportedOperationException}（{@code java/CONTRACTS.md} §2.3）。
 */
public final class DmTradeFillDao extends AbstractDmDao<TradeFill, Long> implements TradeFillDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "trade_id", "order_id", "order_sys_id", "trade_plan_id",
            "hedge_plan_id", "exposure_id", "product_id", "instrument_id", "instrument_type",
            "direction", "offset_flag", "fill_price", "fill_volume", "contract_multiplier",
            "commission", "trade_ts_ms", "trade_date", "fill_time", "event_seq", "settled_flag"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradeFillDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_trade_fill";
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
    protected void bindBusiness(PreparedStatement ps, TradeFill e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.tradeId);
        bind(ps, i++, e.orderId);
        bind(ps, i++, e.orderSysId);
        bind(ps, i++, e.tradePlanId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.exposureId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.instrumentType);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.offsetFlag);
        bind(ps, i++, e.fillPrice);
        bind(ps, i++, e.fillVolume);
        bind(ps, i++, e.contractMultiplier);
        bind(ps, i++, e.commission);
        bind(ps, i++, e.tradeTsMs);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.fillTime);
        bind(ps, i++, e.eventSeq);
        bind(ps, i, e.settledFlag);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradeFill e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.tradeId = string(rs, "trade_id");
        e.orderId = nullableLong(rs, "order_id");
        e.orderSysId = string(rs, "order_sys_id");
        e.tradePlanId = nullableLong(rs, "trade_plan_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.exposureId = nullableLong(rs, "exposure_id");
        e.productId = nullableLong(rs, "product_id");
        e.instrumentId = string(rs, "instrument_id");
        e.instrumentType = string(rs, "instrument_type");
        e.direction = string(rs, "direction");
        e.offsetFlag = string(rs, "offset_flag");
        e.fillPrice = decimal(rs, "fill_price");
        e.fillVolume = nullableInt(rs, "fill_volume");
        e.contractMultiplier = decimal(rs, "contract_multiplier");
        e.commission = decimal(rs, "commission");
        e.tradeTsMs = nullableLong(rs, "trade_ts_ms");
        e.tradeDate = localDate(rs, "trade_date");
        e.fillTime = localDateTime(rs, "fill_time");
        e.eventSeq = nullableLong(rs, "event_seq");
        e.settledFlag = string(rs, "settled_flag");
    }

    @Override
    protected Long idOf(TradeFill entity) {
        return entity.id;
    }

    @Override
    protected TradeFill newEntity() {
        return new TradeFill();
    }

    @Override
    protected void applyGeneratedId(TradeFill entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(TradeFill entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_trade_fill 为只增不改不删表（结算与审计原始依据，R-TRADE-15），不提供 updateById；"
                        + "复现=对已落库成交调用 updateById");
    }

    @Override
    public TradeFill findByTradeId(String tradeId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE trade_id = ?";
        return queryOne(sql, Arrays.asList(tradeId), this::mapRow);
    }

    @Override
    public List<TradeFill> findByOrder(long orderId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE order_id = ? ORDER BY trade_ts_ms ASC, id ASC";
        return queryList(sql, Arrays.asList(orderId), this::mapRow);
    }
}
