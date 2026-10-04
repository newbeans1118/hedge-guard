package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradeCommandFlowDao;
import com.hedgeguard.dao.entity.TradeCommandFlow;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_command_flow} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-7）。
 *
 * <p>镜像按事件逐条追加（只增不改）：{@link #updateById} 显式抛 {@link UnsupportedOperationException}
 * （{@code java/CONTRACTS.md} §2.3）。
 */
public final class DmTradeCommandFlowDao extends AbstractDmDao<TradeCommandFlow, Long>
        implements TradeCommandFlowDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "order_id", "client_order_ref", "flow_type", "flow_detail",
            "flow_ts_ms", "flow_time", "event_seq", "replay_marker"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradeCommandFlowDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_command_flow";
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
    protected void bindBusiness(PreparedStatement ps, TradeCommandFlow e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.orderId);
        bind(ps, i++, e.clientOrderRef);
        bind(ps, i++, e.flowType);
        bind(ps, i++, e.flowDetail);
        bind(ps, i++, e.flowTsMs);
        bind(ps, i++, e.flowTime);
        bind(ps, i++, e.eventSeq);
        bind(ps, i, e.replayMarker);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradeCommandFlow e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.orderId = nullableLong(rs, "order_id");
        e.clientOrderRef = string(rs, "client_order_ref");
        e.flowType = string(rs, "flow_type");
        e.flowDetail = string(rs, "flow_detail");
        e.flowTsMs = nullableLong(rs, "flow_ts_ms");
        e.flowTime = localDateTime(rs, "flow_time");
        e.eventSeq = nullableLong(rs, "event_seq");
        e.replayMarker = string(rs, "replay_marker");
    }

    @Override
    protected Long idOf(TradeCommandFlow entity) {
        return entity.id;
    }

    @Override
    protected TradeCommandFlow newEntity() {
        return new TradeCommandFlow();
    }

    @Override
    protected void applyGeneratedId(TradeCommandFlow entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(TradeCommandFlow entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_command_flow 为只增不改表（镜像按事件逐条追加），不提供 updateById；"
                        + "复现=对已落库流水调用 updateById");
    }

    @Override
    public List<TradeCommandFlow> findByOrderId(long orderId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE order_id = ? ORDER BY flow_ts_ms ASC, id ASC";
        return queryList(sql, Arrays.asList(orderId), this::mapRow);
    }

    @Override
    public List<TradeCommandFlow> findByClientOrderRef(String clientOrderRef) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE client_order_ref = ? ORDER BY flow_ts_ms ASC, id ASC";
        return queryList(sql, Arrays.asList(clientOrderRef), this::mapRow);
    }
}
