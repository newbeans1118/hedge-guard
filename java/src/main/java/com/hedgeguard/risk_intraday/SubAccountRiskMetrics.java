package com.hedgeguard.risk_intraday;

import java.math.BigDecimal;

/**
 * 单个子账户（或汇总）的风险指标（{@code FR-RISK-12} / {@code FR-RISK-13}）。
 *
 * <p>金额 / 数量一律 {@link BigDecimal}（源为 {@code §4.1.1} 字符串化十进制）。
 * <b>汇总 = 明细之和</b>（{@code FR-RISK-12} 验收 2）。
 */
public final class SubAccountRiskMetrics {

    /** 子账户 ID；{@code 0} 表示汇总（哨兵，与 {@code SubAccountScope.ALL_ACCOUNT_SENTINEL} 同值）。 */
    public int subAccountId;

    /** ① 实时持仓（合计手数）。 */
    public BigDecimal positionQty = BigDecimal.ZERO;

    /** ② 浮动盈亏。 */
    public BigDecimal floatPnl = BigDecimal.ZERO;

    /** ③ 保证金占用。 */
    public BigDecimal marginOccupied = BigDecimal.ZERO;

    /** 资金余额（保证金占用率分母）。 */
    public BigDecimal balance = BigDecimal.ZERO;

    /** ⑤ 资金可用量。 */
    public BigDecimal available = BigDecimal.ZERO;

    /** ④ 风险度（占用 / 权益；无权益时为 {@code null}，表示不可计算）。 */
    public BigDecimal riskRatio;

    /** ③ 保证金占用率（占用 / 余额；余额为 0 时为 {@code null}，表示不可计算）。 */
    public BigDecimal marginUsageRate;

    /** ⑥ 日内开平合计次数（同一子账户 + 同一品种滚动窗口的最严者）。 */
    public int abnormalIntradayCount;

    /** ⑥ 7 日滚动开平回合数。 */
    public int abnormalRollingRounds;

    /** ⑥ 平均持仓（交易日）；样本不足时为 {@code null}。 */
    public BigDecimal averageHoldingDays;

    /** 无参构造。 */
    public SubAccountRiskMetrics() {
    }

    /**
     * 构造。
     *
     * @param subAccountId 子账户
     */
    public SubAccountRiskMetrics(int subAccountId) {
        this.subAccountId = subAccountId;
    }

    /** 是否为汇总行。 */
    public boolean aggregate() {
        return subAccountId == 0;
    }
}
