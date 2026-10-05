package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AbnormalOrderStatDao;
import com.hedgeguard.dao.entity.AbnormalOrderStat;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_abnormal_order_stat} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-6；{@code FR-RISK-14}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmAbnormalOrderStatDao extends AbstractDmDao<AbnormalOrderStat, Long>
        implements AbnormalOrderStatDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "product_id", "instrument_id", "stat_date", "window_minutes",
            "open_close_count", "min_hold_minutes", "avg_hold_minutes", "round_trip_count",
            "last_order_time", "is_abnormal", "action_taken", "order_ids"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmAbnormalOrderStatDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_abnormal_order_stat";
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
    protected void bindBusiness(PreparedStatement ps, AbnormalOrderStat e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.statDate);
        bind(ps, i++, e.windowMinutes);
        ps.setInt(i++, e.openCloseCount);
        bind(ps, i++, e.minHoldMinutes);
        bind(ps, i++, e.avgHoldMinutes);
        ps.setInt(i++, e.roundTripCount);
        bind(ps, i++, e.lastOrderTime);
        bind(ps, i++, e.isAbnormal);
        bind(ps, i++, e.actionTaken);
        bind(ps, i, e.orderIds);
    }

    @Override
    protected void readBusiness(ResultSet rs, AbnormalOrderStat e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productId = nullableLong(rs, "product_id");
        e.instrumentId = string(rs, "instrument_id");
        e.statDate = localDate(rs, "stat_date");
        e.windowMinutes = nullableInt(rs, "window_minutes");
        e.openCloseCount = rs.getInt("open_close_count");
        e.minHoldMinutes = nullableInt(rs, "min_hold_minutes");
        e.avgHoldMinutes = decimal(rs, "avg_hold_minutes");
        e.roundTripCount = rs.getInt("round_trip_count");
        e.lastOrderTime = localDateTime(rs, "last_order_time");
        e.isAbnormal = string(rs, "is_abnormal");
        e.actionTaken = string(rs, "action_taken");
        e.orderIds = string(rs, "order_ids");
    }

    @Override
    protected Long idOf(AbnormalOrderStat entity) {
        return entity.id;
    }

    @Override
    protected AbnormalOrderStat newEntity() {
        return new AbnormalOrderStat();
    }

    @Override
    protected void applyGeneratedId(AbnormalOrderStat entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<AbnormalOrderStat> findByStatDate(LocalDate statDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE stat_date = ? ORDER BY id ASC";
        return queryList(sql, Collections.singletonList((Object) statDate), this::mapRow);
    }
}
