package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.HgcpClient;
import com.hedgeguard.bridge.HgcpConstants;
import com.hedgeguard.bridge.HgcpEventListener;
import com.hedgeguard.bridge.HgcpFrame;
import com.hedgeguard.bridge.HgcpTransport;
import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.common.Clocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * {@code T-172} 事件面接线：{@code EVT_TRADE_FILL → recordTrade} + {@code evaluate()} 评估泵。
 *
 * <p><b>端到端形态</b>：经 {@link HgcpClient#onFrame}（公开接缝）喂<b>真实线格式帧</b>，
 * 断言事中风控引擎的可观测产物——<b>而非</b>「监听器被调用」这类结构性断言。
 *
 * <p><b>变异承重</b>：
 * <ul>
 *   <li>删 {@link IntradayEventFeed#onTradeFill} 内的 {@code monitor.recordTrade(...)} ⇒
 *       {@link #tradeFillReachesTracker()} / {@link #sixFillsRaiseAbnormalOrderAlert()} 必红；</li>
 *   <li>删 {@link IntradayEventFeed#onEventConsumed} 内的 {@code monitor.evaluate()} ⇒
 *       {@link #pumpEvaluatesOnEachConsumedEvent()} / {@link #sixFillsRaiseAbnormalOrderAlert()} 必红。</li>
 * </ul>
 *
 * <p><b>⚠️ 线格式缺口（据实披露）</b>：{@code EvtTradeFill} 不含 {@code product_id}，
 * 故此处品种解析走缺省（{@code 0}）；见 {@link IntradayEventFeed} 类注释与交付回执。
 */
class IntradayEventFeedTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 10, 0);

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
        // 良性镜像：无风险度/保证金/资金告警，避免噪声干扰断言
        bridge.setMirror(TestSnapshots.snapshot(NOW.atZone(ZONE).toInstant().toString(), 1,
                TestSnapshots.list(TestSnapshots.position(7, "rb2601", "1", "1", "0", "1")),
                TestSnapshots.funds(TestSnapshots.fund(7, "100000", "99000", "1", "0.01"))));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    private static HgcpClient newClient() {
        return new HgcpClient("java-test", "token-not-logged", "boot-1", 1,
                () -> {
                    throw new UnsupportedOperationException("测试不建连");
                });
    }

    private static void feedFill(HgcpClient client, String tradeId, long seq, int subAccountId,
                                 long orderId, int volume, String offsetFlag) {
        Hgcp.EvtTradeFill p = Hgcp.EvtTradeFill.newBuilder()
                .setTradeId(tradeId)
                .setOrderId(orderId)
                .setOrderSysId("sys-" + orderId)
                .setInstrumentId("rb2601")
                .setDirection(Hgcp.Direction.DIRECTION_BUY)
                .setOffsetFlag(Hgcp.OffsetFlag.valueOf(offsetFlag))
                .setPrice("3500.000000")
                .setVolume(volume)
                .setTradeTsMs(NOW.atZone(ZONE).toInstant().toEpochMilli())
                .setSubAccountId(subAccountId)
                .setTradePlanId(9L)
                .setExposureId(99L)
                .build();
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.EVT_TRADE_FILL;
        h.flags = 0;
        h.seq = seq;
        h.msgId = seq;
        byte[] frame = HgcpFrame.encode(h, p.toByteArray());
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        client.onFrame(d);
    }

    private IntradayRiskMonitor monitor(RiskMetricSink sink) {
        return new IntradayRiskMonitor(bridge, RiskThresholds.defaults(),
                new AbnormalOrderTracker(Clocks.clock()), sink, null, 42L);
    }

    @Test
    @DisplayName("T-172：EVT_TRADE_FILL 经接缝投喂 AbnormalOrderTracker.recordTrade（生产接线）")
    void tradeFillReachesTracker() {
        IntradayRiskMonitor m = monitor(new RecordingSink());
        HgcpClient client = newClient();
        client.setEventListener(new IntradayEventFeed(m));

        feedFill(client, "T-1", 1L, 7, 1001L, 2, "OFFSET_FLAG_OPEN");

        assertEquals(1, m.tracker().maxIntradayOpenClose(7),
                "成交事件须投喂 recordTrade；删投喂 ⇒ 计数恒 0 ⇒ 本断言必红");
    }

    @Test
    @DisplayName("T-172：每消费一事件触发 evaluate() 评估泵（生产接线）")
    void pumpEvaluatesOnEachConsumedEvent() {
        RecordingSink sink = new RecordingSink();
        IntradayRiskMonitor m = monitor(sink);
        HgcpClient client = newClient();
        client.setEventListener(new IntradayEventFeed(m));

        feedFill(client, "T-1", 1L, 7, 1001L, 1, "OFFSET_FLAG_OPEN");

        assertEquals(1, sink.published.size(),
                "每消费一事件须触发一次 evaluate()（异步落库端口收到一次）；删 evaluate ⇒ 0 ⇒ 本断言必红");
    }

    @Test
    @DisplayName("T-172：6 笔日内开平（≥告警阈值 6）→ 经评估泵产出 ABNORMAL_ORDER 告警（T-050 验收1⑥ 生产可观测）")
    void sixFillsRaiseAbnormalOrderAlert() {
        RecordingSink sink = new RecordingSink();
        IntradayRiskMonitor m = monitor(sink);
        HgcpClient client = newClient();
        client.setEventListener(new IntradayEventFeed(m));

        for (int i = 1; i <= 6; i++) {
            feedFill(client, "T-" + i, i, 7, 1000L + i, 1, "OFFSET_FLAG_OPEN");
        }

        assertNotNull(sink.published.isEmpty() ? null : sink.published.get(sink.published.size() - 1),
                "评估泵须已发布评估产物");
        RiskEvaluation last = sink.published.get(sink.published.size() - 1);
        boolean abnormal = false;
        for (RiskAlert a : last.alerts) {
            if (a.subAccountId == 7 && a.metricType == RiskMetricType.ABNORMAL_ORDER) {
                abnormal = true;
            }
        }
        assertTrue(abnormal, "6 笔日内开平合计须产出「异常下单行为」告警（FR-RISK-14 / T-050 验收1⑥）；"
                + "删投喂或删评估泵均使本断言必红");
    }

    @Test
    @DisplayName("T-172/NFR-REL-5：监听器抛异常不得中断事件消费")
    void throwingListenerDoesNotBreakConsumption() {
        List<Long> consumedSeqs = new ArrayList<>();
        HgcpClient client = newClient();
        client.setEventListener(new HgcpEventListener() {
            @Override
            public void onEventConsumed(int eventType, long seq) {
                throw new IllegalStateException("模拟监听器故障");
            }

            @Override
            public void onTradeFill(TradeFill e) {
                consumedSeqs.add(e.orderId);
            }
        });

        assertDoesNotThrow(() -> {
            feedFill(client, "T-1", 1L, 7, 101L, 1, "OFFSET_FLAG_OPEN");
            feedFill(client, "T-2", 2L, 7, 102L, 1, "OFFSET_FLAG_OPEN");
        }, "监听器异常须被吞掉记日志，不得中断消费（NFR-REL-5）");

        assertEquals(List.of(101L, 102L), consumedSeqs,
                "两次成交事件均须被投递（第一次的泵异常不得阻断后续事件）");
    }

    @Test
    @DisplayName("T-172：接缝 nullable —— 未注册监听器时事件消费不抛异常（与接线前一致）")
    void nullListenerKeepsPriorBehaviour() {
        HgcpClient client = newClient();
        client.setEventListener(null);
        assertDoesNotThrow(() -> feedFill(client, "T-1", 1L, 7, 101L, 1, "OFFSET_FLAG_OPEN"));
    }
}
