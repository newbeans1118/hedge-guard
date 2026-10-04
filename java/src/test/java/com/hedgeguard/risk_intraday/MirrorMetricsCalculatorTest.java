package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.MirrorSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-050} 验收 1：6 类指标的内存计算 + 汇总口径 + 滞后判定。
 *
 * <p>覆盖 {@code FR-RISK-12}/{@code FR-RISK-13}。全程不触库、不触 C++（{@code R-RISK-10}）。
 */
class MirrorMetricsCalculatorTest {

    @Test
    @DisplayName("6 类指标：持仓/浮盈/保证金/风险度/可用量均按子账户算出")
    void computesSixMetricsPerSubAccount() {
        MirrorSnapshot snap = TestSnapshots.snapshot("2026-10-04T10:00:00Z", 99,
                TestSnapshots.list(
                        TestSnapshots.position(1, "rb2601", "10", "8", "100", "1000"),
                        TestSnapshots.position(2, "cu2601", "5", "5", "50", "500")),
                TestSnapshots.funds(
                        TestSnapshots.fund(1, "10000", "8000", "1000", "0.10"),
                        TestSnapshots.fund(2, "5000", "4000", "500", "0.10")));

        MirrorMetrics m = MirrorMetricsCalculator.compute(snap, RiskThresholds.defaults(), null, 0L);

        assertEquals(2, m.subAccounts.size());
        SubAccountRiskMetrics s1 = m.find(1);
        assertEquals(new BigDecimal("10"), s1.positionQty);
        assertEquals(new BigDecimal("100"), s1.floatPnl);
        assertEquals(new BigDecimal("1000"), s1.marginOccupied);
        assertEquals(new BigDecimal("0.10"), s1.riskRatio);
        assertEquals(new BigDecimal("0.1000"), s1.marginUsageRate);
        assertEquals(new BigDecimal("8000"), s1.available);
    }

    @Test
    @DisplayName("汇总 = 明细之和（FR-RISK-12 验收 2）")
    void aggregateEqualsSumOfDetails() {
        MirrorSnapshot snap = TestSnapshots.snapshot("2026-10-04T10:00:00Z", 1,
                TestSnapshots.list(
                        TestSnapshots.position(1, "rb2601", "10", "8", "100", "1000"),
                        TestSnapshots.position(2, "cu2601", "5", "5", "50", "500")),
                TestSnapshots.funds(
                        TestSnapshots.fund(1, "10000", "8000", "1000", "0.10"),
                        TestSnapshots.fund(2, "5000", "4000", "500", "0.10")));

        MirrorMetrics m = MirrorMetricsCalculator.compute(snap, RiskThresholds.defaults(), null, 0L);
        SubAccountRiskMetrics agg = m.aggregate;

        assertEquals(0, agg.subAccountId);
        assertEquals(new BigDecimal("15"), agg.positionQty);
        assertEquals(new BigDecimal("150"), agg.floatPnl);
        assertEquals(new BigDecimal("1500"), agg.marginOccupied);
        assertEquals(new BigDecimal("15000"), agg.balance);
        assertEquals(new BigDecimal("12000"), agg.available);
        assertEquals(new BigDecimal("0.1000"), agg.riskRatio);
    }

    @Test
    @DisplayName("资金视图缺省：仅持仓行也能算出持仓 / 浮盈；风险度不可算时为 null（不伪造 0）")
    void positionOnlyAndUndefinedRatio() {
        MirrorSnapshot snap = TestSnapshots.snapshot("2026-10-04T10:00:00Z", 1,
                TestSnapshots.list(TestSnapshots.position(3, "ag2601", "7", "7", "70", "700")),
                Collections.emptyList());
        MirrorMetrics m = MirrorMetricsCalculator.compute(snap, RiskThresholds.defaults(), null, 0L);
        SubAccountRiskMetrics s3 = m.find(3);
        assertEquals(new BigDecimal("7"), s3.positionQty);
        assertEquals(new BigDecimal("70"), s3.floatPnl);
        assertEquals(new BigDecimal("700"), s3.marginOccupied);
        assertNull(s3.riskRatio, "无权益 ⇒ 风险度不可计算，须为 null 而非 0");
        assertNull(s3.marginUsageRate, "余额为 0 ⇒ 占用率不可计算，须为 null");
    }

    @Test
    @DisplayName("滞后判定：takenAt 落后超阈值 → lagging=true（FR-RISK-12 验收 3）；不可判定不误报")
    void lagDetection() {
        RiskThresholds t = RiskThresholds.defaults();   // 30000ms
        String takenAt = Instant.ofEpochMilli(1_000_000L).toString();

        MirrorMetrics fresh = MirrorMetricsCalculator.compute(
                TestSnapshots.snapshot(takenAt, 1), t, null, 1_000_000L);
        assertFalse(fresh.lagging);
        assertEquals(0L, fresh.lagMillis);

        MirrorMetrics lag = MirrorMetricsCalculator.compute(
                TestSnapshots.snapshot(takenAt, 1), t, null, 1_040_000L);
        assertTrue(lag.lagging);
        assertEquals(40_000L, lag.lagMillis);

        MirrorMetrics unknown = MirrorMetricsCalculator.compute(
                TestSnapshots.snapshot(null, 1), t, null, 1_040_000L);
        assertFalse(unknown.lagging, "takenAt 缺失时不可判定，不得误报滞后");
        assertEquals(-1L, unknown.lagMillis);
    }
}
