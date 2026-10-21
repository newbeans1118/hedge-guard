package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-FUND-4 t_transfer_apply} 子账户间划拨申请（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class TransferApply extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 划出子账户（本表的子账户维度取划出方）。 */
    public Integer subAccountId;

    /** 划出子账户。 */
    public Integer fromSubAccountId;

    /** 划入子账户（必须 ≠ from_sub_account_id）。 */
    public Integer toSubAccountId;

    /** 划拨单号（全系统唯一；两条流水共用）。 */
    public String applyNo;

    /** 幂等标识（同一标识重复提交不得产生第二笔划转，FR-FUND-10；v1.3.9/T-202 增列）。 */
    public String idempotencyKey;

    /** 金额；必须 > 0。 */
    public BigDecimal amount;

    /** 划拨理由（必填）。 */
    public String reason;

    /** 期望生效日。 */
    public LocalDate expectedEffectiveDate;

    /** 申请状态（6 值，见 ck_transfer_apply_status）。 */
    public String status;

    /** 发起人（资金岗）。 */
    public Long applicantId;

    /** 审批人（必须 ≠ applicant_id，且不得为交易岗）。 */
    public Long approverId;

    /** 审批时间。 */
    public LocalDateTime approveTime;

    /** 驳回原因（驳回时必填）。 */
    public String rejectReason;

    /** 加总校验记录（划拨后须校验加总不变）。 */
    public Long aggCheckId;

    /** 加总校验是否通过（N → 状态 ROLLED_BACK）。 */
    public String aggCheckPassed;

    /** 回滚时间。 */
    public LocalDateTime rollbackTime;

    /** 记账时间。 */
    public LocalDateTime postingTime;

}
