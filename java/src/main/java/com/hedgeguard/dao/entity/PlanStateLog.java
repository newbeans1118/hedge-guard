package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-PLAN-7 t_plan_state_log} 计划状态流转与额度占用/释放留痕（只增不改不删）（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class PlanStateLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 计划。 */
    public Long hedgePlanId;

    /** 变更前状态。 */
    public String fromStatus;

    /** 变更后状态。 */
    public String toStatus;

    /** OCCUPY / RELEASE / NONE（额度动作）。 */
    public String quotaAction;

    /** 占用/释放的额度数量。 */
    public BigDecimal quotaQty;

    /** 涉及的品种。 */
    public Long quotaProductId;

    /** 额度变更下发 C++ 的结果（失败则该变更不得视为生效，R-PRODUCT-9）。 */
    public String dispatchStatus;

    /** 下发所用 param_version。 */
    public Long dispatchVersion;

    /** 操作人。 */
    public Long operatorId;

    /** 原因（冻结/终止/撤销/驳回必填）。 */
    public String reason;

    /** 动作时间。 */
    public LocalDateTime actionTime;
}
