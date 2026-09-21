package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-PLAN-2 t_hedge_plan_exposure} 计划-敞口绑定（追溯链物理连接点）（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class HedgePlanExposure extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（必须与计划、与敞口三者一致，R-PLAN-7）。 */
    public Integer subAccountId;

    /** 计划。 */
    public Long hedgePlanId;

    /** 被绑定的实货敞口（追溯链上游）。 */
    public Long exposureId;

    /** 本计划对应该敞口的覆盖数量；> 0。 */
    public BigDecimal coveredQty;

    /** 绑定时间。 */
    public LocalDateTime bindTime;

    /** 解绑时间（已批准计划解绑须走变更流程，FR-PLAN-19 验收 4）。 */
    public LocalDateTime unbindTime;

    /** BOUND / UNBOUND（解绑保留历史，不物理删除）。 */
    public String status;
}
