package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUDIT-5 t_violation_screen_item} 筛查命中项（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-4}）。
 *
 * <p>筛查命中的具体记录。{@code REASON_MISSING} 与 {@code RELATED_MISSING} 为 {@code Q-13} 下
 * 并列独立的筛查维度（{@code DC-014}）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class ViolationScreenItem extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public int subAccountId;

    /** 所属筛查批次（{@code t_violation_screen_run.id}）。 */
    public long screenRunId;

    /** 违规类型（与 {@code t_risk_violation.violation_type} 同域）。 */
    public String violationType;

    /** 严重级别：{@code LOW} / {@code MEDIUM} / {@code HIGH}。 */
    public String severity;

    /** 规则编号（复用 {@code RISK}）。 */
    public String ruleId;

    /** 对象类型（{@code TRADE_ORDER} / {@code TRADE_FILL} / {@code HEDGE_PLAN}）。 */
    public String objectType;

    /** 对象 ID。 */
    public long objectId;

    /** 发生时间。 */
    public LocalDateTime occurTime;

    /** 描述（含当前值/阈值）。 */
    public String violationDesc;

    /** 关联交易理由（供自证或整改）。 */
    public Long tradeReasonId;

    /** 关联对象信息（计划 ID / 实货敞口 ID；{@code RELATED_MISSING} 时记录缺失的具体项）。 */
    public String relatedObjectInfo;
}
