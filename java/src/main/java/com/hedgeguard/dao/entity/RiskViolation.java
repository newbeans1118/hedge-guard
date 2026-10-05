package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-RISK-8 t_risk_violation} 违规事项（{@code FR-RISK-18}、{@code FR-AUDIT-4}；{@code DATA_MODEL.md} §2.8）。
 *
 * <p>六类违规（超品种 / 超规模 / 超期限 / 超授权 / 频繁开平仓 / 文档缺失）按<b>类型</b>与
 * <b>严重级别</b>分类汇总（{@code FR-RISK-18}）。{@code ruleId} 复用 {@code RISK} 规则编号
 * （口径一致，避免两套标准）。实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class RiskViolation extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（{@code NOT NULL}）。 */
    public Integer subAccountId;

    /** 发生日期。 */
    public LocalDate violationDate;

    /** 违规类型（{@code OVER_PRODUCT} / {@code OVER_SCALE} / {@code OVER_TERM} / {@code OVER_AUTH} / {@code FREQUENT_TRADE} / {@code DOC_MISSING} / {@code REASON_MISSING} / {@code RELATED_MISSING}）。 */
    public String violationType;

    /** 严重级别：{@code LOW} / {@code MEDIUM} / {@code HIGH}。 */
    public String severity;

    /** 命中的 {@code RISK} 规则编号（可空；口径复用）。 */
    public String ruleId;

    /** 涉及交易指令。 */
    public Long tradeOrderId;

    /** 涉及成交。 */
    public Long tradeFillId;

    /** 涉及计划（文档缺失类）。 */
    public Long hedgePlanId;

    /** 违规描述（含当前值 / 阈值）。 */
    public String violationDesc;

    /** 该笔交易的交易理由（供自证或整改）。 */
    public String tradeReasonText;

    /** 来源：{@code PRE_TRADE_REJECT} / {@code INTRADAY_ALERT} / {@code POST_AUDIT_SCREEN} / {@code MANUAL}。 */
    public String source;

    /** 发生时间。 */
    public LocalDateTime occurTime;
}
