package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradeReplayLogDao;
import com.hedgeguard.dao.entity.TradeReplayLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_replay_log} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-9）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmTradeReplayLogDao extends AbstractDmDao<TradeReplayLog, Long>
        implements TradeReplayLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "component_id", "replay_seq", "trigger_source", "state", "replayed_records",
            "total_records", "in_flight_orders", "ctp_matched_count", "ctp_mismatch_count",
            "mismatch_detail", "started_ts_ms", "finished_ts_ms", "duration_ms", "result",
            "operator_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradeReplayLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_replay_log";
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
    protected void bindBusiness(PreparedStatement ps, TradeReplayLog e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.componentId);
        bind(ps, i++, e.replaySeq);
        bind(ps, i++, e.triggerSource);
        bind(ps, i++, e.state);
        bind(ps, i++, e.replayedRecords);
        bind(ps, i++, e.totalRecords);
        bind(ps, i++, e.inFlightOrders);
        bind(ps, i++, e.ctpMatchedCount);
        bind(ps, i++, e.ctpMismatchCount);
        bindClob(ps, i++, e.mismatchDetail);
        bind(ps, i++, e.startedTsMs);
        bind(ps, i++, e.finishedTsMs);
        bind(ps, i++, e.durationMs);
        bind(ps, i++, e.result);
        bind(ps, i, e.operatorId);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradeReplayLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.componentId = string(rs, "component_id");
        e.replaySeq = nullableInt(rs, "replay_seq");
        e.triggerSource = string(rs, "trigger_source");
        e.state = string(rs, "state");
        e.replayedRecords = nullableLong(rs, "replayed_records");
        e.totalRecords = nullableLong(rs, "total_records");
        e.inFlightOrders = nullableInt(rs, "in_flight_orders");
        e.ctpMatchedCount = nullableInt(rs, "ctp_matched_count");
        e.ctpMismatchCount = nullableInt(rs, "ctp_mismatch_count");
        e.mismatchDetail = clob(rs, "mismatch_detail");
        e.startedTsMs = nullableLong(rs, "started_ts_ms");
        e.finishedTsMs = nullableLong(rs, "finished_ts_ms");
        e.durationMs = nullableLong(rs, "duration_ms");
        e.result = string(rs, "result");
        e.operatorId = nullableLong(rs, "operator_id");
    }

    @Override
    protected Long idOf(TradeReplayLog entity) {
        return entity.id;
    }

    @Override
    protected TradeReplayLog newEntity() {
        return new TradeReplayLog();
    }

    @Override
    protected void applyGeneratedId(TradeReplayLog entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<TradeReplayLog> findByComponent(String componentId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE component_id = ? ORDER BY replay_seq ASC";
        return queryList(sql, Arrays.asList(componentId), this::mapRow);
    }
}
