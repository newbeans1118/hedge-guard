package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-FUND-3 t_deposit_withdraw_apply} 出入金申请（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class DepositWithdrawApply extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 记账归属子账户（必填）。 */
    public Integer subAccountId;

    /** 申请单号（全系统唯一）。 */
    public String applyNo;

    /** 幂等标识（同一标识重复提交不得产生第二笔划转）。 */
    public String idempotencyKey;

    /** IN（入金）/ OUT（出金）。 */
    public String direction;

    /** 金额；必须 > 0。 */
    public BigDecimal amount;

    /** 资金来源说明（入金）。 */
    public String fundSourceDesc;

    /** 用途说明（出金）。 */
    public String purposeDesc;

    /** 申请状态（7 值，见 ck_dw_status）。 */
    public String status;

    /** 发起人。 */
    public Long applicantId;

    /** 复核人（必须 ≠ applicant_id）。 */
    public Long reviewerId;

    /** 复核时间。 */
    public LocalDateTime reviewTime;

    /** 驳回原因（驳回时必填）。 */
    public String rejectReason;

    /** 经 CTP 提交时间。 */
    public LocalDateTime submitTime;

    /** 出金前的子账户可用资金（出金前置校验依据）。 */
    public BigDecimal availableBefore;

    /** 重试来源申请（不得因重试产生重复划转）。 */
    public Long retryOfId;

    /** 重试前是否已确认原请求真实结果（未确认不得重试）。 */
    public String originalConfirmed;

}
