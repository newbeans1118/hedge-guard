package com.hedgeguard.risk_intraday;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 异常下单行为统计快照（内存计算产物；落库目标 {@code t_abnormal_order_stat}，{@code FR-RISK-14}）。
 *
 * <p>由 {@link AbnormalOrderTracker#snapshot} 按「子账户 × 品种 × 时间窗」聚合产出，只读、不落库；
 * 落库由 {@code T-065} 的 {@code RiskMetricSink} 实现异步承接（{@code R-RISK-10}：计算过程零 DB）。
 * 字段与 {@code DATA_MODEL.md} §2.8 TB-RISK-6 逐列对应。
 */
public final class ProductAbnormalStat {

    /** 子账户。 */
    public int subAccountId;

    /** 品种。 */
    public long productId;

    /** 合约（可选维度；聚合口径为品种级故为 {@code null}）。 */
    public String instrumentId;

    /** 时间窗（分钟；{@code CFG-05}，默认 1440=1 日；7 日滚动=10080）。 */
    public int windowMinutes;

    /** 时间窗内开平仓次数。 */
    public int openCloseCount;

    /** 最短持仓周期（分钟）；无闭合回合为 {@code null}。 */
    public Integer minHoldMinutes;

    /** 平均持仓周期（分钟）；无闭合回合为 {@code null}。 */
    public BigDecimal avgHoldMinutes;

    /** 回合数（一次开 + 平记 1；无回合即为 0）。 */
    public int roundTripCount;

    /** 最近下单时间（窗内最近成交活动时刻）；窗内无活动为 {@code null}。 */
    public LocalDateTime lastOrderTime;

    /** 是否判定为异常（按 {@code RiskThresholds} 与该窗口口径）。 */
    public boolean abnormal;

    /** 触发的动作：{@code ALERT_ONLY} / {@code ALERT_AND_SUSPEND_OPEN}。 */
    public String actionTaken;
}
