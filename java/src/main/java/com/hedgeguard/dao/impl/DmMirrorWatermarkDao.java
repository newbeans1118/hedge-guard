package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.MirrorWatermarkDao;
import com.hedgeguard.dao.entity.MirrorWatermark;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_mirror_watermark} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-3；{@code FR-RISK-12}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmMirrorWatermarkDao extends AbstractDmDao<MirrorWatermark, Long>
        implements MirrorWatermarkDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "component_id", "stream_type", "last_consumed_seq", "last_produced_seq", "lag",
            "lag_threshold", "lag_alarm", "last_update_time", "connection_state"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmMirrorWatermarkDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_mirror_watermark";
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
    protected void bindBusiness(PreparedStatement ps, MirrorWatermark e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.componentId);
        bind(ps, i++, e.streamType);
        ps.setLong(i++, e.lastConsumedSeq);
        ps.setLong(i++, e.lastProducedSeq);
        ps.setLong(i++, e.lag);
        ps.setLong(i++, e.lagThreshold);
        bind(ps, i++, e.lagAlarm);
        bind(ps, i++, e.lastUpdateTime);
        bind(ps, i, e.connectionState);
    }

    @Override
    protected void readBusiness(ResultSet rs, MirrorWatermark e) throws SQLException {
        e.id = rs.getLong("id");
        e.componentId = string(rs, "component_id");
        e.streamType = string(rs, "stream_type");
        e.lastConsumedSeq = rs.getLong("last_consumed_seq");
        e.lastProducedSeq = rs.getLong("last_produced_seq");
        e.lag = rs.getLong("lag");
        e.lagThreshold = rs.getLong("lag_threshold");
        e.lagAlarm = string(rs, "lag_alarm");
        e.lastUpdateTime = localDateTime(rs, "last_update_time");
        e.connectionState = string(rs, "connection_state");
    }

    @Override
    protected Long idOf(MirrorWatermark entity) {
        return entity.id;
    }

    @Override
    protected MirrorWatermark newEntity() {
        return new MirrorWatermark();
    }

    @Override
    protected void applyGeneratedId(MirrorWatermark entity, long generated) {
        entity.id = generated;
    }

    @Override
    public MirrorWatermark findByComponentAndStream(String componentId, String streamType) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE component_id = ? AND stream_type = ? ORDER BY id ASC";
        return queryOne(sql, Arrays.<Object>asList(componentId, streamType), this::mapRow);
    }
}
