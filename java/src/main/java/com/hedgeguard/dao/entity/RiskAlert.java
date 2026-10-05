package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-RISK-4 t_risk_alert} 风险告警（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-12}~{@code FR-RISK-14}）。
 *
 * <p>事中指标超阈值生成的告警；<b>须留痕、不得静默丢弃</b>（{@code R-RISK-11}）。
 * <p><b>命名辨析</b>：本类为<b>持久化告警行</b>（表 {@code t_risk_alert}）；
 * 与 {@code com.hedgeguard.risk_intraday.RiskAlert}（事中内存告警对象）<b>不同类、不同包</b>，勿混用。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class RiskAlert extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 告警类型：{@code POSITION}/{@code FLOAT_PNL}/{@code MARGIN_RATIO}/{@code RISK_RATIO}/{@code AVAILABLE_FUND}/{@code ABNORMAL_ORDER}/{@code MIRROR_LAG}/{@code FUND_QUOTA}。 */
    public String alertType;

    /** 告警级别：{@code INFO} / {@code WARNING} / {@code CRITICAL}。 */
    public String alertLevel;

    /** 指标名。 */
    public String metricName;

    /** 指标值。 */
    public String metricValue;

    /** 阈值。 */
    public String thresholdValue;

    /** 涉及合约。 */
    public String instrumentId;

    /** 详情。 */
    public String alertDetail;

    /** 告警毫秒时间戳。 */
    public long alertTsMs;

    /** 落库时间。 */
    public LocalDateTime alertTime;

    /** 是否已处置：{@code Y} / {@code N}（未处置须显式标注）。 */
    public String disposed;

    /** 关联的处置动作（{@code → t_risk_disposal}）。 */
    public Long disposalId;
}
