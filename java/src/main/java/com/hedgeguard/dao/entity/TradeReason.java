package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-4 t_trade_reason} 交易理由（正当性说明；每笔交易可自证正当性）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradeReason extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 关联指令（一条指令一条理由）。 */
    public Long tradeOrderId;

    /** 关联类型（结构化必填，8 值）。 */
    public String relatedType;

    /** 关联对象①计划 ID（可空仅用于补填/异常态）。 */
    public Long relatedPlanId;

    /** 关联对象②实货敞口 ID（可空条件同①）。 */
    public Long relatedExposureId;

    /** 时点说明（5 值）。 */
    public String timeDesc;

    /** 可选补充说明。 */
    public String reasonText;

    /** 是否因触发频繁开平仓而强制填写（Y/N）。 */
    public String forcedByFreqAlert;

    /** 是否关联对象缺失（Y/N；与 reason_missing_flag 并列独立）。 */
    public String relatedObjectMissingFlag;

    /** 填写人。 */
    public Long operatorId;

    /** 填写时间。 */
    public LocalDateTime fillTime;

    /** 是否被补填/修改过（Y/N）。 */
    public String isModified;

    /** 修改前编码。 */
    public String beforeCode;

    /** 修改前文本。 */
    public String beforeText;

    /** 修改人。 */
    public Long modifiedBy;

    /** 修改时间。 */
    public LocalDateTime modifiedTime;

    /** 是否理由缺失（Y/N）。 */
    public String reasonMissingFlag;

    /** 标记为缺失的时间。 */
    public LocalDateTime missingMarkedTime;
}
