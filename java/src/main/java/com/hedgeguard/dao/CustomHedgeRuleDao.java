package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.CustomHedgeRule;

import java.time.LocalDate;

import java.util.List;

/**
 * {@code TB-PLAN-8 t_custom_hedge_rule} 数据访问（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface CustomHedgeRuleDao extends BaseDao<CustomHedgeRule, Long> {

    /**
     * 取当前生效的候选规则集，按确定性取一算法排序（ratio_cap 升序 → priority 升序 → id 升序，CFG-06）。
     *
     * @param subAccountId 查询条件
     * @param productId 查询条件
     * @param direction 查询条件
     * @param bizDate 查询条件
     * @return 命中列表（无命中返回空列表）
     */
    List<CustomHedgeRule> findEffective(int subAccountId, long productId, String direction, LocalDate bizDate);
}
