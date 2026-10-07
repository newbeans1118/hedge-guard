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

    /**
     * 按敞口取绑定行（{@code FR-AUDIT-1} 正向追溯「敞口 → 套保计划」段反查）。
     *
     * <p>本方法为跨账户只读路径（{@code T-067}；子账户可见性由调用方 {@code AuditService}
     * 统一经 {@code AuthService.visibleSubAccountIds} 校验，DAO 不再叠加 scope，
     * 与 {@link TradePlanDao#findByHedgePlan(long)} 同姿态）。
     *
     * @param exposureId 实货敞口
     * @return 绑定行列表（{@code status} 升序、{@code id} 升序）；无则空列表
     */
    List<HedgePlanExposure> findByExposure(long exposureId);
}
