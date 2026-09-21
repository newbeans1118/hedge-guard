package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.HedgePlanExposure;
import java.util.List;

/**
 * {@code TB-PLAN-2 t_hedge_plan_exposure} 数据访问（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface HedgePlanExposureDao extends BaseDao<HedgePlanExposure, Long> {

    /**
     * 按计划取绑定行（{@code FR-PLAN-5} 详情、{@code FR-AUDIT-1} 正向追溯）。
     *
     * @param hedgePlanId 计划
     * @return 列表（{@code status} 升序、{@code id} 升序）
     */
    List<HedgePlanExposure> findByPlan(long hedgePlanId);
}
