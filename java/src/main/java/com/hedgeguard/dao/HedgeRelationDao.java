package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.HedgeRelation;

import java.util.List;

/**
 * {@code TB-ACCT-1 t_hedge_relation} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface HedgeRelationDao extends BaseDao<HedgeRelation, Long> {

    /**
     * 按编号取套期关系（{@code uk_hedge_relation_no}；CAS24 自检①正式指定核对，FR-ACCT-14）。
     *
     * @param relationNo 套期关系编号
     * @return 命中的套期关系；不存在返回 {@code null}
     */
    HedgeRelation findByRelationNo(String relationNo);

    /**
     * 按套保计划取全部套期关系（{@code idx_hedge_relation_plan}；FR-ACCT-1、有效性评估入口）。
     *
     * @param hedgePlanId 套保计划
     * @return 列表（{@code designated_time} 升序，{@code id} 升序收官）
     */
    List<HedgeRelation> findByHedgePlan(long hedgePlanId);

    /**
     * 从敞口反查套期关系（{@code idx_hedge_relation_exposure}；追溯链 FR-AUDIT-1 正向终点延伸）。
     *
     * @param exposureId 实货敞口
     * @return 列表（{@code designated_time} 升序，{@code id} 升序收官）
     */
    List<HedgeRelation> findByExposure(long exposureId);
}
