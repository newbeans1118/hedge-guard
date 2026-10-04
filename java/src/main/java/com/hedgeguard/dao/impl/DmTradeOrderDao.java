package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradeOrderDao;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_trade_order} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-1）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmTradeOrderDao extends AbstractDmDao<TradeOrder, Long> implements TradeOrderDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "client_order_ref", "order_id", "trade_plan_id", "hedge_plan_id",
            "exposure_id", "product_id", "instrument_id", "instrument_type", "direction",
            "offset_flag", "price_type", "limit_price", "volume", "operator_id", "strategy_id",
            "leg_no", "trade_reason_id", "status", "reject_rule_id", "reject_code", "reject_reason",
            "confirm_operator_id", "confirm_time", "accept_time", "submit_time", "client_ts_ms",
            "expire_time", "cancel_reason", "cancel_operator_id", "cancel_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradeOrderDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_trade_order";
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
    protected void bindBusiness(PreparedStatement ps, TradeOrder e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.clientOrderRef);
        bind(ps, i++, e.orderId);
        bind(ps, i++, e.tradePlanId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.exposureId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.instrumentType);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.offsetFlag);
        bind(ps, i++, e.priceType);
        bind(ps, i++, e.limitPrice);
        bind(ps, i++, e.volume);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.strategyId);
        bind(ps, i++, e.legNo);
        bind(ps, i++, e.tradeReasonId);
        bind(ps, i++, e.status);
        bind(ps, i++, e.rejectRuleId);
        bind(ps, i++, e.rejectCode);
        bind(ps, i++, e.rejectReason);
        bind(ps, i++, e.confirmOperatorId);
        bind(ps, i++, e.confirmTime);
        bind(ps, i++, e.acceptTime);
        bind(ps, i++, e.submitTime);
        bind(ps, i++, e.clientTsMs);
        bind(ps, i++, e.expireTime);
        bind(ps, i++, e.cancelReason);
        bind(ps, i++, e.cancelOperatorId);
        bind(ps, i, e.cancelTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradeOrder e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.clientOrderRef = string(rs, "client_order_ref");
        e.orderId = nullableLong(rs, "order_id");
        e.tradePlanId = nullableLong(rs, "trade_plan_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.exposureId = nullableLong(rs, "exposure_id");
        e.productId = nullableLong(rs, "product_id");
        e.instrumentId = string(rs, "instrument_id");
        e.instrumentType = string(rs, "instrument_type");
        e.direction = string(rs, "direction");
        e.offsetFlag = string(rs, "offset_flag");
        e.priceType = string(rs, "price_type");
        e.limitPrice = decimal(rs, "limit_price");
        e.volume = nullableInt(rs, "volume");
        e.operatorId = nullableLong(rs, "operator_id");
        e.strategyId = nullableLong(rs, "strategy_id");
        e.legNo = nullableInt(rs, "leg_no");
        e.tradeReasonId = nullableLong(rs, "trade_reason_id");
        e.status = string(rs, "status");
        e.rejectRuleId = string(rs, "reject_rule_id");
        e.rejectCode = string(rs, "reject_code");
        e.rejectReason = string(rs, "reject_reason");
        e.confirmOperatorId = nullableLong(rs, "confirm_operator_id");
        e.confirmTime = localDateTime(rs, "confirm_time");
        e.acceptTime = localDateTime(rs, "accept_time");
        e.submitTime = localDateTime(rs, "submit_time");
        e.clientTsMs = nullableLong(rs, "client_ts_ms");
        e.expireTime = localDateTime(rs, "expire_time");
        e.cancelReason = string(rs, "cancel_reason");
        e.cancelOperatorId = nullableLong(rs, "cancel_operator_id");
        e.cancelTime = localDateTime(rs, "cancel_time");
    }

    @Override
    protected Long idOf(TradeOrder entity) {
        return entity.id;
    }

    @Override
    protected TradeOrder newEntity() {
        return new TradeOrder();
    }

    @Override
    protected void applyGeneratedId(TradeOrder entity, long generated) {
        entity.id = generated;
    }

    @Override
    public TradeOrder findByClientOrderRef(String clientOrderRef) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE client_order_ref = ?";
        return queryOne(sql, Arrays.asList(clientOrderRef), this::mapRow);
    }

    @Override
    public TradeOrder findByOrderId(long orderId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE order_id = ?";
        return queryOne(sql, Arrays.asList(orderId), this::mapRow);
    }

    @Override
    public List<TradeOrder> findBySubAccountAndStatus(int subAccountId, String status) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND status = ? ORDER BY submit_time ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId, status), this::mapRow);
    }
}
