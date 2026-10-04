package com.hedgeguard.risk_intraday;

import java.math.BigDecimal;

/**
 * 强制平仓提示（{@code FR-RISK-16}）。
 *
 * <p><b>只提示、不自动下单</b>（{@code R-RISK-13}；{@code PROJECT.md} §4.2 排除自动交易）。
 * 提示给出建议平仓的<b>合约、方向、数量</b>供交易岗人工确认后发出。
 *
 * <p>方向取值 {@code "SELL"} / {@code "BUY"}：持仓为多头（净持仓 &gt; 0）建议 {@code SELL} 平多，
 * 空头建议 {@code BUY} 平空。
 */
public final class ForceCloseHint {

    /** 子账户。 */
    public int subAccountId;

    /** 建议平仓合约。 */
    public String instrumentId;

    /** 建议方向（{@code SELL} 平多 / {@code BUY} 平空）。 */
    public String direction;

    /** 建议数量（手；字符串化十进制，{@code §4.1.1}）。 */
    public String quantity = "0";

    /** 触发时的风险度。 */
    public BigDecimal riskRatio;

    /** 触发原因（审计）。 */
    public String reason;

    /** 无参构造。 */
    public ForceCloseHint() {
    }

    /**
     * 构造。
     *
     * @param subAccountId 子账户
     * @param instrumentId 合约
     * @param direction    方向
     * @param quantity     数量
     * @param riskRatio    风险度
     * @param reason       原因
     */
    public ForceCloseHint(int subAccountId, String instrumentId, String direction, BigDecimal quantity,
                          BigDecimal riskRatio, String reason) {
        this.subAccountId = subAccountId;
        this.instrumentId = instrumentId;
        this.direction = direction;
        this.quantity = (quantity == null ? BigDecimal.ZERO : quantity).toPlainString();
        this.riskRatio = riskRatio;
        this.reason = reason;
    }
}
