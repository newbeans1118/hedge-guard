package com.hedgeguard.risk_intraday;

/**
 * 处置动作（{@code FR-RISK-15} / {@code FR-RISK-16}）。
 *
 * <p>取值与 {@code DATA_MODEL.md} §枚举 {@code t_risk_disposal.disposal_type}
 * 逐字一致：{@code ALERT_ONLY} / {@code SUSPEND_OPEN} / {@code FORCE_CLOSE_HINT} /
 * {@code RELEASE_SUSPEND}。
 *
 * <p><b>系统不自动交易</b>（{@code R-RISK-13}）：任何动作都<b>不含</b>自动下单；
 * {@link #FORCE_CLOSE_HINT} 仅生成建议方案供交易岗人工确认。
 */
public enum DisposalAction {

    /** 仅告警。 */
    ALERT_ONLY("仅告警"),

    /** 限制新开仓（{@code CMD_SET_TRADING_SUSPEND}，{@code scan_scope=OPEN_ONLY}；平仓放行）。 */
    SUSPEND_OPEN("限制新开仓"),

    /** 强制平仓提示（只提示，不自动下单）。 */
    FORCE_CLOSE_HINT("强制平仓提示"),

    /** 解除限制。 */
    RELEASE_SUSPEND("解除限制");

    private final String label;

    DisposalAction(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String label() {
        return label;
    }
}
