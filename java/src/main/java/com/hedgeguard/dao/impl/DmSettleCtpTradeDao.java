package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleCtpTradeDao;
import com.hedgeguard.dao.entity.SettleCtpTrade;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_ctp_settle_trade} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-4）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleCtpTradeDao extends AbstractDmDao<SettleCtpTrade, Long> implements SettleCtpTradeDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "trade_date", "settle_batch_id", "trade_id", "instrument_id", "direction", "offset_flag", "fill_price", "fill_volume", "trade_ts_ms", "commission"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleCtpTradeDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_ctp_settle_trade";
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
    protected void bindBusiness(PreparedStatement ps, SettleCtpTrade e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.settleBatchId);
        bind(ps, i++, e.tradeId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.offsetFlag);
        bind(ps, i++, e.fillPrice);
        bind(ps, i++, e.fillVolume);
        bind(ps, i++, e.tradeTsMs);
        bind(ps, i++, e.commission);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleCtpTrade e) throws SQLException {
        e.id = rs.getLong("id");
        e.tradeDate = localDate(rs, "trade_date");
        e.settleBatchId = nullableLong(rs, "settle_batch_id");
        e.tradeId = string(rs, "trade_id");
        e.instrumentId = string(rs, "instrument_id");
        e.direction = string(rs, "direction");
        e.offsetFlag = string(rs, "offset_flag");
        e.fillPrice = decimal(rs, "fill_price");
        e.fillVolume = nullableInt(rs, "fill_volume");
        e.tradeTsMs = nullableLong(rs, "trade_ts_ms");
        e.commission = decimal(rs, "commission");
    }

    @Override
    protected Long idOf(SettleCtpTrade entity) {
        return entity.id;
    }

    @Override
    protected SettleCtpTrade newEntity() {
        return new SettleCtpTrade();
    }

    @Override
    protected void applyGeneratedId(SettleCtpTrade entity, long generated) {
        entity.id = generated;
    }
}
