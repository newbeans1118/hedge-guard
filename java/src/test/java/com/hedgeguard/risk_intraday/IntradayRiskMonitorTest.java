package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.bridge.PlaceOrderCmd;
import com.hedgeguard.common.Clocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-050} 验收 2/3/4 的核心引擎测试。
 *
 * <p>覆盖：超阈值告警（含级别/值/阈值/子账户/时间）、强制平仓只提示不自动下单（{@code R-RISK-13}）、
 * 限制新开仓经 {@code OPEN_ONLY} 端口（{@code R-RISK-5}）、内存计算不阻塞事件消费
 * （先算后异步落库，{@code FR-RISK-13} 验收 4）、只读监控（{@code R-RISK-10}）。
 */
class IntradayRiskMonitorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 10, 0);

    /** 记录式限制端口。 */
    private static final class RecordingSuspender implements TradingSuspender {
        final List<Integer> subs = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final List<Long> operators = new ArrayList<>();

        @Override
        public void suspendOpenOnly(int subAccountId, String reason, long operatorId) {
            subs.add(subAccountId);
            reasons.add(reason);
            operators.add(operatorId);
        }
    }

    /** 记录式落库端口。 */
    private static final class RecordingSink implements RiskMetricSink {
        final List<RiskEvaluation> published = new ArrayList<>();

        @Override
        public void publish(RiskEvaluation evaluation) {
            published.add(evaluation);
        }
    }

    private InMemoryTradingBridge bridge;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        bridge = new InMemoryTradingBridge();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("超阈值告警：风险度 ≥ 严重阈值 → CRITICAL，含指标值/阈值/子账户/时间（FR-RISK-13 验收 2）")
    void criticalRiskRatioRaisesAlertWithContext() {
        bridge.setMirror(TestSnapshots.snapshot(NOW.minusSeconds(1).atZone(ZONE).toInstant().toString(), 5,
                TestSnapshots.list(
                        TestSnapshots.position(1, "rb2601", "10", "8", "0", "9000")),
                TestSnapshots.funds(
                        TestSnapshots.fund(1, "10000", "500", "9000", "0.95"))));

        IntradayRiskMonitor monitor = newMonitor(RiskThresholds.defaults(), new RecordingSink(),
                new RecordingSuspender());
        RiskEvaluation e = monitor.evaluate();

        RiskAlert critical = null;
        for (RiskAlert a : e.alerts) {
            if (a.subAccountId == 1 && a.metricType == RiskMetricType.RISK_RATIO
                    && a.level == RiskAlertLevel.CRITICAL) {
                critical = a;
            }
        }
        assertNotNull(critical, "风险度 0.95 ≥ 0.90 须产生严重告警");
        assertEquals("0.95", critical.value);
        assertEquals("0.90", critical.threshold);
        assertTrue(critical.message.contains("子账户 1"), "告警须含子账户：" + critical.message);
        assertNotNull(critical.raisedAt);
        assertFalse(critical.disposed, "未处置须显式标注（FR-RISK-19 验收 2）");
    }

    @Test
    @DisplayName("强制平仓只提示不自动下单：给建议合约/方向/数量，且桥无任何下单（R-RISK-13）")
    void forceCloseHintWithoutAutoOrder() {
        bridge.setMirror(TestSnapshots.snapshot(NOW.minusSeconds(1).atZone(ZONE).toInstant().toString(), 5,
                TestSnapshots.list(
                        TestSnapshots.position(1, "rb2601", "10", "8", "0", "9000")),
                TestSnapshots.funds(
                        TestSnapshots.fund(1, "10000", "500", "9000", "0.95"))));

        IntradayRiskMonitor monitor = newMonitor(RiskThresholds.defaults(), new RecordingSink(),
                new RecordingSuspender());
        RiskEvaluation e = monitor.evaluate();

        ForceCloseHint hint = null;
        for (ForceCloseHint h : e.forceCloseHints) {
            if (h.subAccountId == 1) {
                hint = h;
            }
        }
        assertNotNull(hint, "风险度严重须给强制平仓提示");
        assertEquals("rb2601", hint.instrumentId);
        assertEquals("SELL", hint.direction, "多头净持仓应建议 SELL 平多");
        assertEquals("8", hint.quantity);

        List<PlaceOrderCmd> placed = bridge.placedOrders();
        assertTrue(placed.isEmpty(), "系统不得自动下单（R-RISK-13）：实际下单 " + placed.size() + " 笔");
        assertTrue(bridge.dispatchedChanges().isEmpty(), "监控只读，不得向组件下发任何变更");
    }

    @Test
    @DisplayName("限制新开仓：日内开平合计 ≥10 且自动限制启用 → 经 OPEN_ONLY 端口下发（R-RISK-5）")
    void suspendOpenOnlyDispatched() {
        RiskThresholds t = RiskThresholds.defaults();
        System.setProperty(RiskThresholds.KEY_AUTO_SUSPEND, "true");
        try {
            t = RiskThresholds.from(com.hedgeguard.common.Config.empty());
        } finally {
            System.clearProperty(RiskThresholds.KEY_AUTO_SUSPEND);
        }
        bridge.setMirror(TestSnapshots.snapshot(NOW.minusSeconds(1).atZone(ZONE).toInstant().toString(), 5,
                TestSnapshots.list(TestSnapshots.position(1, "rb2601", "1", "1", "0", "1")),
                TestSnapshots.funds(TestSnapshots.fund(1, "100000", "99000", "1", "0.01"))));

        AbnormalOrderTracker tracker = new AbnormalOrderTracker(Clocks.clock());
        for (int i = 0; i < 5; i++) {
            tracker.record(new TradeActivity(1, 100, "rb2601", TradeAction.OPEN, BigDecimal.ONE,
                    NOW.minusMinutes(10 + i)));
            tracker.record(new TradeActivity(1, 100, "rb2601", TradeAction.CLOSE, BigDecimal.ONE,
                    NOW.minusMinutes(5 + i)));
        }
        RecordingSuspender suspender = new RecordingSuspender();
        IntradayRiskMonitor monitor = new IntradayRiskMonitor(bridge, t, tracker,
                new RecordingSink(), suspender, 42L);

        RiskEvaluation e = monitor.evaluate();

        assertEquals(List.of(1), suspender.subs, "应限制子账户 1 的新开仓");
        assertTrue(suspender.reasons.get(0).contains("OPEN_ONLY"),
                "下发须携带 scope=OPEN_ONLY（只限开仓、平仓放行）：" + suspender.reasons.get(0));
        assertEquals(42L, suspender.operators.get(0).longValue(), "处置操作人须留痕");
        assertTrue(e.suspendedSubAccounts.contains(1));
        for (RiskAlert a : e.alerts) {
            if (a.subAccountId == 1 && a.metricType == RiskMetricType.ABNORMAL_ORDER
                    && a.level == RiskAlertLevel.CRITICAL) {
                assertEquals(DisposalAction.SUSPEND_OPEN, a.disposalAction);
            }
        }
    }

    @Test
    @DisplayName("自动限制未启用（缺省）：不调用限制端口，处置降级为「建议」（FR-RISK-15）")
    void autoSuspendDisabledKeepsSuggestionOnly() {
        bridge.setMirror(TestSnapshots.snapshot(NOW.minusSeconds(1).atZone(ZONE).toInstant().toString(), 5,
                TestSnapshots.list(TestSnapshots.position(1, "rb2601", "1", "1", "0", "1")),
                TestSnapshots.funds(TestSnapshots.fund(1, "100000", "99000", "1", "0.01"))));
        AbnormalOrderTracker tracker = new AbnormalOrderTracker(Clocks.clock());
        for (int i = 0; i < 10; i++) {
            tracker.record(new TradeActivity(1, 100, "rb2601", TradeAction.OPEN, BigDecimal.ONE,
                    NOW.minusMinutes(20 + i)));
        }
        RecordingSuspender suspender = new RecordingSuspender();
        IntradayRiskMonitor monitor = new IntradayRiskMonitor(bridge, RiskThresholds.defaults(), tracker,
                new RecordingSink(), suspender, 42L);

        RiskEvaluation e = monitor.evaluate();

        assertTrue(suspender.subs.isEmpty(), "缺省不得自动限制（须明确配置启用）");
        assertTrue(e.suspendedSubAccounts.isEmpty());
        boolean suggested = false;
        for (RiskAlert a : e.alerts) {
            if (a.metricType == RiskMetricType.ABNORMAL_ORDER && a.level == RiskAlertLevel.CRITICAL) {
                assertEquals(DisposalAction.ALERT_ONLY, a.disposalAction);
                assertTrue(a.message.contains("建议限制新开仓"));
                suggested = true;
            }
        }
        assertTrue(suggested, "应保留「建议限制新开仓」告警");
    }

    @Test
    @DisplayName("先算后异步落库：慢下游不阻塞事件消费路径（FR-RISK-13 验收 4）")
    void asyncSinkDoesNotBlockEvaluate() throws Exception {
        bridge.setMirror(TestSnapshots.snapshot(NOW.minusSeconds(1).atZone(ZONE).toInstant().toString(), 5,
                TestSnapshots.list(TestSnapshots.position(1, "rb2601", "1", "1", "0", "1")),
                TestSnapshots.funds(TestSnapshots.fund(1, "100000", "99000", "1", "0.01"))));

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch downstreamEntered = new CountDownLatch(1);
        RiskMetricSink slowDownstream = evaluation -> {
            downstreamEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        AsyncRiskMetricSink sink = new AsyncRiskMetricSink(slowDownstream, 8);
        try {
            IntradayRiskMonitor monitor = new IntradayRiskMonitor(bridge, RiskThresholds.defaults(),
                    new AbnormalOrderTracker(Clocks.clock()), sink, null, 0L);
            long t0 = System.nanoTime();
            monitor.evaluate();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 500, "evaluate 入队即返回，不应等待慢下游；实际 " + elapsedMs + "ms");
            assertTrue(downstreamEntered.await(2, TimeUnit.SECONDS), "样本应被异步投递给下游");
        } finally {
            release.countDown();
            sink.close();
        }
    }

    @Test
    @DisplayName("只读监控：evaluate 不修改镜像快照（FR-RISK-12 验收 4）")
    void monitoringIsReadOnly() {
        String takenAt = NOW.minusSeconds(1).atZone(ZONE).toInstant().toString();
        bridge.setMirror(TestSnapshots.snapshot(takenAt, 7,
                TestSnapshots.list(TestSnapshots.position(1, "rb2601", "10", "8", "0", "1000")),
                TestSnapshots.funds(TestSnapshots.fund(1, "10000", "8000", "1000", "0.10"))));
        IntradayRiskMonitor monitor = newMonitor(RiskThresholds.defaults(), new RecordingSink(),
                new RecordingSuspender());

        int before = bridge.mirror().positions().size();
        String qtyBefore = bridge.mirror().positions().get(0).positionQty;
        monitor.evaluate();
        assertEquals(before, bridge.mirror().positions().size());
        assertEquals(qtyBefore, bridge.mirror().positions().get(0).positionQty);
        assertTrue(bridge.placedOrders().isEmpty());
        assertTrue(bridge.dispatchedChanges().isEmpty());
    }

    private IntradayRiskMonitor newMonitor(RiskThresholds t, RiskMetricSink sink,
                                           TradingSuspender suspender) {
        return new IntradayRiskMonitor(bridge, t, new AbnormalOrderTracker(Clocks.clock()),
                sink, suspender, 42L);
    }
}
