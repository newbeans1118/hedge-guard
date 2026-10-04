package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradePreTradeRejectDao;
import com.hedgeguard.dao.entity.TradePreTradeReject;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_pre_trade_reject} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-6）。
 *
 * <p>合规证据，只增不改不删（{@code R-TRADE-14}）：{@link #updateById} 显式抛
 * {@link UnsupportedOperationException}（{@code java/CONTRACTS.md} §2.3）。
 */
public final class DmTradePreTradeRejectDao extends AbstractDmDao<TradePreTradeReject, Long>
        implements TradePreTradeRejectDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "client_order_ref", "order_digest", "product_id", "instrument_id",
            "direction", "volume", "rule_id", "reject_code", "limit_layer", "exposure_snapshot_id",
            "exposure_snapshot_time", "current_value", "threshold_value", "base_price_used",
            "base_price_source", "reject_reason", "operator_id", "reject_ts_ms", "reject_time",
            "trade_date", "event_seq", "reported_retry_count"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradePreTradeRejectDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_pre_trade_reject";
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
    protected void bindBusiness(PreparedStatement ps, TradePreTradeReject e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.clientOrderRef);
        bind(ps, i++, e.orderDigest);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.volume);
        bind(ps, i++, e.ruleId);
        bind(ps, i++, e.rejectCode);
        bind(ps, i++, e.limitLayer);
        bind(ps, i++, e.exposureSnapshotId);
        bind(ps, i++, e.exposureSnapshotTime);
        bind(ps, i++, e.currentValue);
        bind(ps, i++, e.thresholdValue);
        bind(ps, i++, e.basePriceUsed);
        bind(ps, i++, e.basePriceSource);
        bind(ps, i++, e.rejectReason);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.rejectTsMs);
        bind(ps, i++, e.rejectTime);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.eventSeq);
        bind(ps, i, e.reportedRetryCount);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradePreTradeReject e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.clientOrderRef = string(rs, "client_order_ref");
        e.orderDigest = string(rs, "order_digest");
        e.productId = nullableLong(rs, "product_id");
        e.instrumentId = string(rs, "instrument_id");
        e.direction = string(rs, "direction");
        e.volume = nullableInt(rs, "volume");
        e.ruleId = string(rs, "rule_id");
        e.rejectCode = string(rs, "reject_code");
        e.limitLayer = string(rs, "limit_layer");
        e.exposureSnapshotId = nullableLong(rs, "exposure_snapshot_id");
        e.exposureSnapshotTime = localDateTime(rs, "exposure_snapshot_time");
        e.currentValue = string(rs, "current_value");
        e.thresholdValue = string(rs, "threshold_value");
        e.basePriceUsed = decimal(rs, "base_price_used");
        e.basePriceSource = string(rs, "base_price_source");
        e.rejectReason = string(rs, "reject_reason");
        e.operatorId = nullableLong(rs, "operator_id");
        e.rejectTsMs = nullableLong(rs, "reject_ts_ms");
        e.rejectTime = localDateTime(rs, "reject_time");
        e.tradeDate = localDate(rs, "trade_date");
        e.eventSeq = nullableLong(rs, "event_seq");
        e.reportedRetryCount = nullableInt(rs, "reported_retry_count");
    }

    @Override
    protected Long idOf(TradePreTradeReject entity) {
        return entity.id;
    }

    @Override
    protected TradePreTradeReject newEntity() {
        return new TradePreTradeReject();
    }

    @Override
    protected void applyGeneratedId(TradePreTradeReject entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(TradePreTradeReject entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_pre_trade_reject 为只增不改不删表（合规证据，R-TRADE-14），不提供 updateById；"
                        + "复现=对已落库拒绝记录调用 updateById");
    }

    @Override
    public List<TradePreTradeReject> findByClientOrderRef(String clientOrderRef) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE client_order_ref = ? ORDER BY reject_time ASC, id ASC";
        return queryList(sql, Arrays.asList(clientOrderRef), this::mapRow);
    }

    @Override
    public TradePreTradeReject findByIdem(String clientOrderRef, String ruleId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE client_order_ref = ? AND rule_id = ?";
        return queryOne(sql, Arrays.asList(clientOrderRef, ruleId), this::mapRow);
    }
}
