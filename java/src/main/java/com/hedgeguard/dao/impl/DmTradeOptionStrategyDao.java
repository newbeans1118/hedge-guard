package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradeOptionStrategyDao;
import com.hedgeguard.dao.entity.TradeOptionStrategy;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_option_strategy} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-8）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmTradeOptionStrategyDao extends AbstractDmDao<TradeOptionStrategy, Long>
        implements TradeOptionStrategyDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "strategy_no", "strategy_type", "hedge_plan_id", "trade_plan_id",
            "leg_count", "total_quota_occupied", "status", "operator_id", "submit_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradeOptionStrategyDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_option_strategy";
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
    protected void bindBusiness(PreparedStatement ps, TradeOptionStrategy e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.strategyNo);
        bind(ps, i++, e.strategyType);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.tradePlanId);
        bind(ps, i++, e.legCount);
        bind(ps, i++, e.totalQuotaOccupied);
        bind(ps, i++, e.status);
        bind(ps, i++, e.operatorId);
        bind(ps, i, e.submitTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradeOptionStrategy e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.strategyNo = string(rs, "strategy_no");
        e.strategyType = string(rs, "strategy_type");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.tradePlanId = nullableLong(rs, "trade_plan_id");
        e.legCount = nullableInt(rs, "leg_count");
        e.totalQuotaOccupied = decimal(rs, "total_quota_occupied");
        e.status = string(rs, "status");
        e.operatorId = nullableLong(rs, "operator_id");
        e.submitTime = localDateTime(rs, "submit_time");
    }

    @Override
    protected Long idOf(TradeOptionStrategy entity) {
        return entity.id;
    }

    @Override
    protected TradeOptionStrategy newEntity() {
        return new TradeOptionStrategy();
    }

    @Override
    protected void applyGeneratedId(TradeOptionStrategy entity, long generated) {
        entity.id = generated;
    }

    @Override
    public TradeOptionStrategy findByStrategyNo(String strategyNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE strategy_no = ?";
        return queryOne(sql, Arrays.asList(strategyNo), this::mapRow);
    }

    @Override
    public List<TradeOptionStrategy> findByHedgePlan(long hedgePlanId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE hedge_plan_id = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(hedgePlanId), this::mapRow);
    }
}
