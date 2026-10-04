package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradeOrderReportDao;
import com.hedgeguard.dao.entity.TradeOrderReport;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_trade_order_report} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-2）。
 *
 * <p>回报逐条追加（只增不改）：{@link #updateById} 显式抛 {@link UnsupportedOperationException}
 * （{@code java/CONTRACTS.md} §2.3）。本类是<b>唯一</b>出现该表 SQL 的位置。
 */
public final class DmTradeOrderReportDao extends AbstractDmDao<TradeOrderReport, Long>
        implements TradeOrderReportDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "order_sys_id", "order_id", "order_local_id", "exchange_id",
            "instrument_id", "direction", "offset_flag", "order_status", "limit_price",
            "volume_total", "volume_traded", "volume_left", "ctp_error_id", "ctp_error_msg",
            "report_ts_ms", "report_time", "event_seq"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradeOrderReportDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_trade_order_report";
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
    protected void bindBusiness(PreparedStatement ps, TradeOrderReport e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.orderSysId);
        bind(ps, i++, e.orderId);
        bind(ps, i++, e.orderLocalId);
        bind(ps, i++, e.exchangeId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.offsetFlag);
        bind(ps, i++, e.orderStatus);
        bind(ps, i++, e.limitPrice);
        bind(ps, i++, e.volumeTotal);
        bind(ps, i++, e.volumeTraded);
        bind(ps, i++, e.volumeLeft);
        bind(ps, i++, e.ctpErrorId);
        bind(ps, i++, e.ctpErrorMsg);
        bind(ps, i++, e.reportTsMs);
        bind(ps, i++, e.reportTime);
        bind(ps, i, e.eventSeq);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradeOrderReport e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.orderSysId = string(rs, "order_sys_id");
        e.orderId = nullableLong(rs, "order_id");
        e.orderLocalId = string(rs, "order_local_id");
        e.exchangeId = string(rs, "exchange_id");
        e.instrumentId = string(rs, "instrument_id");
        e.direction = string(rs, "direction");
        e.offsetFlag = string(rs, "offset_flag");
        e.orderStatus = string(rs, "order_status");
        e.limitPrice = decimal(rs, "limit_price");
        e.volumeTotal = nullableInt(rs, "volume_total");
        e.volumeTraded = nullableInt(rs, "volume_traded");
        e.volumeLeft = nullableInt(rs, "volume_left");
        e.ctpErrorId = nullableInt(rs, "ctp_error_id");
        e.ctpErrorMsg = string(rs, "ctp_error_msg");
        e.reportTsMs = nullableLong(rs, "report_ts_ms");
        e.reportTime = localDateTime(rs, "report_time");
        e.eventSeq = nullableLong(rs, "event_seq");
    }

    @Override
    protected Long idOf(TradeOrderReport entity) {
        return entity.id;
    }

    @Override
    protected TradeOrderReport newEntity() {
        return new TradeOrderReport();
    }

    @Override
    protected void applyGeneratedId(TradeOrderReport entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(TradeOrderReport entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_trade_order_report 为只增不改表（回报逐条追加），不提供 updateById；"
                        + "复现=对已落库回报调用 updateById");
    }

    @Override
    public List<TradeOrderReport> findByOrder(long orderId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE order_id = ? ORDER BY report_ts_ms ASC, id ASC";
        return queryList(sql, Arrays.asList(orderId), this::mapRow);
    }

    @Override
    public TradeOrderReport findByIdem(String orderSysId, String orderStatus, int volumeTraded) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE order_sys_id = ? AND order_status = ? AND volume_traded = ?";
        return queryOne(sql, Arrays.asList(orderSysId, orderStatus, volumeTraded), this::mapRow);
    }
}
