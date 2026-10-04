package com.hedgeguard.risk_intraday;

/**
 * 告警级别（{@code FR-RISK-13} 验收 3「按级别：提示/警告/严重」）。
 *
 * <p>取值与 {@code DATA_MODEL.md} §枚举 {@code t_risk_alert.alert_level}
 * （{@code INFO} / {@code WARNING} / {@code CRITICAL}）逐字一致。
 */
public enum RiskAlertLevel {

    /** 提示。 */
    INFO(1),

    /** 警告。 */
    WARNING(2),

    /** 严重（{@code FR-RISK-16} 的「严重阈值」触发强制平仓提示）。 */
    CRITICAL(3);

    private final int rank;

    RiskAlertLevel(int rank) {
        this.rank = rank;
    }

    /** 严重度序（数值越大越严重），供聚合取最严者。 */
    public int rank() {
        return rank;
    }

    /**
     * 取更严重的一级。
     *
     * @param other 另一级别
     * @return 两者中严重度更高者
     */
    public RiskAlertLevel max(RiskAlertLevel other) {
        return other != null && other.rank > this.rank ? other : this;
    }
}
