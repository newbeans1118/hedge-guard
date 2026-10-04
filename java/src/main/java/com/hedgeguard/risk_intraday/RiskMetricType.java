package com.hedgeguard.risk_intraday;

/**
 * 事中风控 6 类指标（{@code ARCHITECTURE.md} §3.11 / {@code §6.8}）。
 *
 * <p>与需求对应：{@code FR-RISK-12}（①实时持仓 ②浮动盈亏）、{@code FR-RISK-13}
 * （③保证金占用率 ④风险度 ⑤资金可用量）、{@code FR-RISK-14}（⑥异常下单行为）。
 */
public enum RiskMetricType {

    /** ① 实时持仓。 */
    POSITION("实时持仓"),

    /** ② 浮动盈亏。 */
    FLOAT_PNL("浮动盈亏"),

    /** ③ 保证金占用率。 */
    MARGIN_USAGE_RATE("保证金占用率"),

    /** ④ 风险度。 */
    RISK_RATIO("风险度"),

    /** ⑤ 资金可用量。 */
    AVAILABLE_FUND("资金可用量"),

    /** ⑥ 异常下单行为（开平仓频次 / 最短持仓周期）。 */
    ABNORMAL_ORDER("异常下单行为");

    private final String label;

    RiskMetricType(String label) {
        this.label = label;
    }

    /** 中文标签（告警文案用）。 */
    public String label() {
        return label;
    }
}
