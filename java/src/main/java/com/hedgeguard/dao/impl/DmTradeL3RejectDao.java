package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradeL3RejectDao;
import com.hedgeguard.dao.entity.TradeL3Reject;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_trade_l3_reject} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-10；v1.3.10，{@code DC-047}）。
 *
 * <p>合规证据，只增不改不删（{@code R-TRADE-14} 同族）：{@link #updateById} 显式抛
 * {@link UnsupportedOperationException}（{@code java/CONTRACTS.md} §2.3）。
 */
public final class DmTradeL3RejectDao extends AbstractDmDao<TradeL3Reject, Long>
        implements TradeL3RejectDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "rule_id", "trigger_source", "quota_scope", "product_id",
            "current_value", "threshold_value", "blocked_operation", "operator_id", "event_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradeL3RejectDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_trade_l3_reject";
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
    protected void bindBusiness(PreparedStatement ps, TradeL3Reject e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.ruleId);
        bind(ps, i++, e.triggerSource);
        bind(ps, i++, e.quotaScope);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.currentValue);
        bind(ps, i++, e.thresholdValue);
        bind(ps, i++, e.blockedOperation);
        bind(ps, i++, e.operatorId);
        bind(ps, i, e.eventTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradeL3Reject e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.ruleId = string(rs, "rule_id");
        e.triggerSource = string(rs, "trigger_source");
        e.quotaScope = string(rs, "quota_scope");
        e.productId = nullableLong(rs, "product_id");
        e.currentValue = string(rs, "current_value");
        e.thresholdValue = string(rs, "threshold_value");
        e.blockedOperation = string(rs, "blocked_operation");
        e.operatorId = nullableLong(rs, "operator_id");
        e.eventTime = localDateTime(rs, "event_time");
    }

    @Override
    protected Long idOf(TradeL3Reject entity) {
        return entity.id;
    }

    @Override
    protected TradeL3Reject newEntity() {
        return new TradeL3Reject();
    }

    @Override
    protected void applyGeneratedId(TradeL3Reject entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(TradeL3Reject entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_trade_l3_reject 为只增不改不删表（合规证据，R-TRADE-14 同族；T-206），不提供 updateById；"
                        + "复现=对已落库 L3 拒绝事件调用 updateById");
    }
}
