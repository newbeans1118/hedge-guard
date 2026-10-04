package com.hedgeguard.risk_intraday;

import java.math.BigDecimal;

/**
 * 指标读模型（REST 响应专用；{@code ARCHITECTURE.md} §4.1.1「金额 / 数量一律字符串化十进制」）。
 *
 * <p>内部计算模型 {@link SubAccountRiskMetrics} 用 {@link BigDecimal}；对外 JSON 一律字符串，
 * 避免前端 {@code Number} 精度丢失。{@code null} 表示不可计算（不伪造 0）。
 */
public final class SubAccountMetricsView {

    /** 子账户 ID（{@code 0} = 汇总）。 */
    public int subAccountId;

    /** ① 实时持仓（手）。 */
    public String positionQty;

    /** ② 浮动盈亏。 */
    public String floatPnl;

    /** ③ 保证金占用。 */
    public String marginOccupied;

    /** 资金余额。 */
    public String balance;

    /** ⑤ 资金可用量。 */
    public String available;

    /** ④ 风险度；不可计算为 {@code null}。 */
    public String riskRatio;

    /** ③ 保证金占用率；不可计算为 {@code null}。 */
    public String marginUsageRate;

    /** ⑥ 日内开平合计次数。 */
    public int abnormalIntradayCount;

    /** ⑥ 7 日滚动开平回合数。 */
    public int abnormalRollingRounds;

    /** ⑥ 平均持仓（交易日）；样本不足为 {@code null}。 */
    public String averageHoldingDays;

    /**
     * 由内部模型转换。
     *
     * @param m 内部指标
     * @return 读模型
     */
    public static SubAccountMetricsView of(SubAccountRiskMetrics m) {
        SubAccountMetricsView v = new SubAccountMetricsView();
        v.subAccountId = m.subAccountId;
        v.positionQty = plain(m.positionQty);
        v.floatPnl = plain(m.floatPnl);
        v.marginOccupied = plain(m.marginOccupied);
        v.balance = plain(m.balance);
        v.available = plain(m.available);
        v.riskRatio = plain(m.riskRatio);
        v.marginUsageRate = plain(m.marginUsageRate);
        v.abnormalIntradayCount = m.abnormalIntradayCount;
        v.abnormalRollingRounds = m.abnormalRollingRounds;
        v.averageHoldingDays = plain(m.averageHoldingDays);
        return v;
    }

    private static String plain(BigDecimal d) {
        return d == null ? null : d.toPlainString();
    }
}
