package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-10 t_voucher_review} 凭证审核记录（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>凭证审核链（FR-ACCT-7）。草稿生成人与审核人不得为同一人（R-ACCT-6）；
 * {@code coReviewerId} 为 v1.2 补列（PI-11/DC-032）：冲销/调整类凭证的第二复核人，
 * 须 ≠ reviewerId 且 ≠ generatorId（服务层校验）；非冲销/调整类为 null。
 */
public final class VoucherReview extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 凭证。 */
    public Long voucherId;

    /** 审核节点序号。 */
    public Integer nodeSeq;

    /** 动作：{@code SUBMIT} / {@code APPROVE} / {@code REJECT} / {@code WITHDRAW}。 */
    public String action;

    /** 凭证生成人（冗余，供"生成人≠审核人"校验）。 */
    public Long generatorId;

    /** 审核人（必须 ≠ generatorId，服务层校验）。 */
    public Long reviewerId;

    /** 第二复核人（冲销/调整类双人复核，PI-11/DC-032）。 */
    public Long coReviewerId;

    /** 审核结论：{@code APPROVED} / {@code REJECTED}。 */
    public String reviewResult;

    /** 审核意见。 */
    public String reviewComment;

    /** 驳回原因（驳回时必填，R-ACCT-6）。 */
    public String rejectReason;

    /** 动作时间。 */
    public LocalDateTime actionTime;
}
