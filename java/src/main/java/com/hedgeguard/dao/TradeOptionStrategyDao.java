package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradeOptionStrategy;
import java.util.List;

/**
 * {@code TB-TRADE-8 t_option_strategy} 期权策略数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）；子账户权限由服务层注入 scope。
 */
public interface TradeOptionStrategyDao extends BaseDao<TradeOptionStrategy, Long> {

    /**
     * 按策略编号查策略（{@code uk_option_strategy_no}）。
     *
     * @param strategyNo 策略编号
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradeOptionStrategy findByStrategyNo(String strategyNo);

    /**
     * 按套保计划取策略（{@code idx_option_strategy_hedge_plan}；核验「各腿绑定同一套保计划」，
     * {@code FR-TRADE-13} 验收 1）。
     *
     * @param hedgePlanId 套保计划
     * @return 列表（{@code id} 升序）
     */
    List<TradeOptionStrategy> findByHedgePlan(long hedgePlanId);
}
