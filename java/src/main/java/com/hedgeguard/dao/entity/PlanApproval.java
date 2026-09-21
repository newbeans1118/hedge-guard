package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-PLAN-5 t_plan_approval} 计划审批链（永久留痕、不可修改）（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class PlanApproval extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 计划。 */
    public Long hedgePlanId;

    /** 节点序号（支持多级审批）。 */
    public Integer nodeSeq;

    /** SUBMIT / APPROVE / REJECT / WITHDRAW / FREEZE / UNFREEZE / TERMINATE / EXTEND。 */
    public String nodeType;

    /** 发起人。 */
    public Long applicantId;

    /** 审批人（必须 ≠ applicant_id，R-PLAN-2）。 */
    public Long approverId;

    /** APPROVED / REJECTED / WITHDRAWN。 */
    public String approvalResult;

    /** 审批意见。 */
    public String approvalComment;

    /** 驳回原因（驳回时必填），FR-PLAN-12。 */
    public String rejectReason;

    /** 提交前合规预校验结果（逐项：规则编号 + 当前值 + 阈值 + 判定），R-PLAN-3。 */
    public String precheckResult;

    /** 动作时间。 */
    public LocalDateTime actionTime;
}
