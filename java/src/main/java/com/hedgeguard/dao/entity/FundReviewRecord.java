package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-FUND-6 t_fund_review_record} 资金复核记录（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class FundReviewRecord extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** DEPOSIT_WITHDRAW / TRANSFER / DIFFERENCE_ADJUST / LEDGER_INIT。 */
    public String bizType;

    /** 业务对象 ID。 */
    public Long bizId;

    /** 发起人。 */
    public Long applicantId;

    /** 复核人（必须 ≠ applicant_id）。 */
    public Long reviewerId;

    /** APPROVED / REJECTED。 */
    public String reviewResult;

    /** 复核意见。 */
    public String reviewComment;

    /** 驳回原因（驳回时必填）。 */
    public String rejectReason;

    /** 是否因「发起人=复核人」被系统拒绝（越权尝试留痕）。 */
    public String selfReviewRejected;

    /** 复核时间。 */
    public LocalDateTime reviewTime;

}
