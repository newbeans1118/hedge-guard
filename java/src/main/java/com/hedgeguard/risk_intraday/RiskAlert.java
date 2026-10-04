package com.hedgeguard.risk_intraday;

/**
 * 风险告警（内存态；落库经 {@link RiskMetricSink} 异步承接，{@code T-065}）。
 *
 * <p>告警<b>不可静默</b>（{@code R-RISK-11}）：本模块不提供删除入口；
 * 未处置的告警 {@link #disposed} 恒为 {@code false}（前端显式标注「未处置」，
 * {@code FR-RISK-19} 验收 2）。
 */
public final class RiskAlert {

    /** 子账户（{@code 0} 表示全账户 / 汇总面）。 */
    public int subAccountId;

    /** 指标类型。 */
    public RiskMetricType metricType;

    /** 告警级别。 */
    public RiskAlertLevel level;

    /** 触发时的指标值（字符串化十进制或计数）。 */
    public String value;

    /** 阈值（字符串化十进制或计数）。 */
    public String threshold;

    /** 处置动作。 */
    public DisposalAction disposalAction = DisposalAction.ALERT_ONLY;

    /** 是否已处置（本模块内恒 {@code false}；处置属 {@code T-065}）。 */
    public boolean disposed;

    /** 触发时刻（ISO-8601）。 */
    public String raisedAt;

    /** 告警文案（含指标值、阈值、子账户与时间，{@code FR-RISK-13} 验收 2）。 */
    public String message;

    /** 无参构造。 */
    public RiskAlert() {
    }

    /**
     * 构造。
     *
     * @param subAccountId 子账户
     * @param metricType   指标
     * @param level        级别
     * @param value        指标值
     * @param threshold    阈值
     * @param raisedAt     触发时刻
     * @param message      文案
     */
    public RiskAlert(int subAccountId, RiskMetricType metricType, RiskAlertLevel level,
                     String value, String threshold, String raisedAt, String message) {
        this.subAccountId = subAccountId;
        this.metricType = metricType;
        this.level = level;
        this.value = value;
        this.threshold = threshold;
        this.raisedAt = raisedAt;
        this.message = message;
    }
}
