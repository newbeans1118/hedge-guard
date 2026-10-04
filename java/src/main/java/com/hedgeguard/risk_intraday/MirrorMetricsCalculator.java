package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.FundView;
import com.hedgeguard.bridge.MirrorSnapshot;
import com.hedgeguard.bridge.PositionView;
import com.hedgeguard.bridge.ReplayProgress;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 镜像快照 → 内存指标 的纯函数计算器（{@code FR-RISK-12}/{@code FR-RISK-13}）。
 *
 * <p><b>零 I/O</b>：只读 {@link MirrorSnapshot}（不可变），不查库、不查 C++、不改镜像
 * （{@code R-RISK-10}；{@code FR-RISK-13} 验收 4；{@code FR-RISK-12} 验收 4）。
 *
 * <p>汇总口径：持仓 / 浮动盈亏 / 保证金 / 资金余额 / 可用量 = 明细之和
 * （{@code FR-RISK-12} 验收 2）；风险度 / 占用率 = 汇总分子 ÷ 汇总分母（非明细简单相加）；
 * 异常下单行为取明细的最严者（行为是子账户维度，不相加）。
 */
public final class MirrorMetricsCalculator {

    /** 金额保留 4 位小数（{@code §4.1.1} 字符串化十进制口径）。 */
    private static final int SCALE = 4;

    private MirrorMetricsCalculator() {
    }

    /**
     * 计算指标。
     *
     * @param snapshot   镜像快照
     * @param thresholds 阈值（用于判定滞后）
     * @param tracker    异常下单跟踪器
     * @param nowMillis  当前时刻（epochMilli；测试可注入）
     * @return 指标集
     */
    public static MirrorMetrics compute(MirrorSnapshot snapshot, RiskThresholds thresholds,
                                        AbnormalOrderTracker tracker, long nowMillis) {
        MirrorSnapshot snap = snapshot == null ? MirrorSnapshot.empty() : snapshot;
        Map<Integer, SubAccountRiskMetrics> bySub = new LinkedHashMap<>();

        for (PositionView p : snap.positions()) {
            SubAccountRiskMetrics m = bySub.computeIfAbsent(p.subAccountId,
                    SubAccountRiskMetrics::new);
            m.positionQty = m.positionQty.add(decimal(p.positionQty));
            m.floatPnl = m.floatPnl.add(decimal(p.floatPnl));
            m.marginOccupied = m.marginOccupied.add(decimal(p.marginOccupied));
        }
        for (FundView f : snap.funds()) {
            SubAccountRiskMetrics m = bySub.computeIfAbsent(f.subAccountId,
                    SubAccountRiskMetrics::new);
            m.balance = decimal(f.balance);
            m.available = decimal(f.available);
            // 资金视图的保证金占用为账户级权威；持仓行合计仅在其缺省时兜底。
            BigDecimal fundMargin = decimal(f.marginOccupied);
            if (fundMargin.signum() != 0) {
                m.marginOccupied = fundMargin;
            }
            m.riskRatio = f.riskRatio == null || f.riskRatio.isEmpty()
                    ? ratio(m.marginOccupied, m.balance)
                    : decimal(f.riskRatio);
            m.marginUsageRate = ratio(m.marginOccupied, m.balance);
        }

        for (SubAccountRiskMetrics m : bySub.values()) {
            m.abnormalIntradayCount = tracker == null ? 0 : tracker.maxIntradayOpenClose(m.subAccountId);
            m.abnormalRollingRounds = tracker == null ? 0 : tracker.maxRollingRounds(m.subAccountId);
            m.averageHoldingDays = tracker == null ? null : tracker.averageHoldingDays(m.subAccountId);
        }

        // 汇总行
        SubAccountRiskMetrics agg = new SubAccountRiskMetrics(0);
        for (SubAccountRiskMetrics m : bySub.values()) {
            agg.positionQty = agg.positionQty.add(m.positionQty);
            agg.floatPnl = agg.floatPnl.add(m.floatPnl);
            agg.marginOccupied = agg.marginOccupied.add(m.marginOccupied);
            agg.balance = agg.balance.add(m.balance);
            agg.available = agg.available.add(m.available);
            agg.abnormalIntradayCount = Math.max(agg.abnormalIntradayCount, m.abnormalIntradayCount);
            agg.abnormalRollingRounds = Math.max(agg.abnormalRollingRounds, m.abnormalRollingRounds);
        }
        agg.riskRatio = ratio(agg.marginOccupied, agg.balance);
        agg.marginUsageRate = ratio(agg.marginOccupied, agg.balance);

        long lagMillis = lagMillis(snap.takenAt, nowMillis);
        boolean lagging = lagMillis >= 0 && thresholds != null
                && lagMillis > thresholds.mirrorLagMillis;

        ReplayProgress.State state = snap.readyState == null
                ? ReplayProgress.State.NOT_READY : snap.readyState;
        return new MirrorMetrics(state, snap.watermarkEventSeq, snap.paramVersion, snap.takenAt,
                lagMillis, lagging, new ArrayList<>(bySub.values()), agg);
    }

    /** 计算滞后毫秒；{@code takenAt} 缺失或不可解析返回 {@code -1}（不可判定，不误报）。 */
    static long lagMillis(String takenAt, long nowMillis) {
        if (takenAt == null || takenAt.isEmpty()) {
            return -1L;
        }
        try {
            Instant taken = Instant.parse(takenAt);
            long lag = nowMillis - taken.toEpochMilli();
            return Math.max(lag, 0L);
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /** 安全比率：分母非正返回 {@code null}（不可计算，不得伪造成 0）。 */
    static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    /** 字符串化十进制 → BigDecimal；空 / 非法按 {@code 0} 兜底（镜像非权威源，不作记账依据）。 */
    static BigDecimal decimal(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
