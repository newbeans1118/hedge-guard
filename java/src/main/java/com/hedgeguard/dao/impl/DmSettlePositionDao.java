package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettlePositionDao;
import com.hedgeguard.dao.entity.SettlePosition;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_settle_position} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-6）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettlePositionDao extends AbstractDmDao<SettlePosition, Long> implements SettlePositionDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "settle_cycle_id", "trade_date", "instrument_id", "product_id", "instrument_type", "direction", "position_type", "position_qty", "attribution_source", "unattributed_qty"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettlePositionDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_settle_position";
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
    protected void bindBusiness(PreparedStatement ps, SettlePosition e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.settleCycleId);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.instrumentType);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.positionType);
        bind(ps, i++, e.positionQty);
        bind(ps, i++, e.attributionSource);
        bind(ps, i++, e.unattributedQty);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettlePosition e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.settleCycleId = nullableLong(rs, "settle_cycle_id");
        e.tradeDate = localDate(rs, "trade_date");
        e.instrumentId = string(rs, "instrument_id");
        e.productId = nullableLong(rs, "product_id");
        e.instrumentType = string(rs, "instrument_type");
        e.direction = string(rs, "direction");
        e.positionType = string(rs, "position_type");
        e.positionQty = decimal(rs, "position_qty");
        e.attributionSource = string(rs, "attribution_source");
        e.unattributedQty = decimal(rs, "unattributed_qty");
    }

    @Override
    protected Long idOf(SettlePosition entity) {
        return entity.id;
    }

    @Override
    protected SettlePosition newEntity() {
        return new SettlePosition();
    }

    @Override
    protected void applyGeneratedId(SettlePosition entity, long generated) {
        entity.id = generated;
    }
}
