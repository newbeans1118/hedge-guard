package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.PlanApproval;

/**
 * {@code TB-PLAN-5 t_plan_approval} 数据访问（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface PlanApprovalDao extends BaseDao<PlanApproval, Long> {

    /**
     * 按计划取审批链（{@code FR-AUDIT-1} 正向追溯「审批」段；{@code FR-AUDIT-2} 反向同段）。
     *
     * <p>本方法为跨账户只读路径（{@code T-067}；子账户可见性由调用方 {@code AuditService}
     * 统一经 {@code AuthService.visibleSubAccountIds} 校验，DAO 不再叠加 scope）。
     *
     * @param hedgePlanId 套保计划
     * @return 审批节点列表（{@code node_seq} 升序、{@code id} 升序）；无则空列表
     */
    java.util.List<PlanApproval> findByPlan(long hedgePlanId);
}
