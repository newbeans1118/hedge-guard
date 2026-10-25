package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * {@code T-040} 行情桥接（裁定方案 A）：{@code HgcpClient.onEvent} 显式 {@code EVT_MARKET_QUOTE}
 * case —— parseFrom 后<b>逐字段透传</b>给 {@link HgcpEventListener#onMarketQuote}，
 * <b>不写镜像水位</b>（0x020C 为可丢例外，不占 SEQ：C++ 侧 {@code EventEmitter::emit_quote}）。
 *
 * <p><b>判据（变异锚）</b>：摘除 {@code onEvent} 的新 case ⇒ 行情帧回落 default 分支，
 * {@link #quoteOutsideResumeBatchReachesListenerWithAllFields()} 必红（监听器收不到载荷）；
 * 在新 case 里补 {@code advanceWatermark} ⇒ {@link #quoteDoesNotAdvanceWatermark()} 必红
 * （水位被 0x020C 推进，破坏「水位 = 可靠事件流连续性判据」口径）。
 *
 * <p><b>CH-007 回归锚</b>：{@link HgcpResumeMarketQuoteDropTest} 既有三用例须保持全绿——
 * 「补数批次内行情帧一律丢弃」的拦截分支（{@code HgcpClient.onFrame}）本单<b>一行未动</b>；
 * 但其守卫用例 {@code quoteOutsideResumeBatchIsNotDropped} 原断言「非批次行情推进水位=77」
 * 锁定的是接线前形态（0x020C 走 default 分支顺带推水位），T-040b 裁定「不写 mirror 水位」
 * 后该断言已随形态订正（见该文件的订正加注）——本类的
 * {@link #quoteDoesNotAdvanceWatermark()} 是订正后的正锚。
 */
class HgcpMarketQuoteBridgeTest {

    private static final String INSTRUMENT = "au2512";

    /** 记录型监听器：捕获行情载荷全字段。 */
    private static final class QuoteListener implements HgcpEventListener {
        final List<HgcpEventListener.MarketQuote> quotes = new ArrayList<>();

        @Override
        public void onMarketQuote(MarketQuote e) {
            quotes.add(e);
        }
    }

    private static HgcpClient newClient(QuoteListener listener) {
        HgcpClient client = new HgcpClient("java-test", "token-not-logged", "boot-1", 1,
                () -> {
                    throw new UnsupportedOperationException("本用例只走 onFrame 接缝，不建连");
                });
        client.setEventListener(listener);
        return client;
    }

    private static byte[] eventFrame(int type, long seq, byte[] payload) {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = type;
        h.seq = seq;
        h.msgId = seq;
        return HgcpFrame.encode(h, payload);
    }

    private static HgcpFrame.Decoded feedDirect(HgcpClient client, byte[] frame) {
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        client.onFrame(d);
        return d;
    }

    @Test
    @DisplayName("非补数批次行情帧：9 字段逐字段透传监听器（parseFrom 后不自造字段）")
    void quoteOutsideResumeBatchReachesListenerWithAllFields() {
        QuoteListener listener = new QuoteListener();
        HgcpClient client = newClient(listener);

        Hgcp.EvtMarketQuote src = Hgcp.EvtMarketQuote.newBuilder()
                .setInstrumentId(INSTRUMENT)
                .setLastPrice("785.00")
                .setBidPrice1("784.90")
                .setAskPrice1("785.10")
                .setBidVolume1(12)
                .setAskVolume1(34)
                .setPreSettlement("780.00")
                .setQuoteTsMs(1_760_000_000_000L)
                .setSourceTsMs(1_760_000_000_123L)
                .build();
        feedDirect(client, eventFrame(HgcpConstants.EVT_MARKET_QUOTE, 5L, src.toByteArray()));

        assertEquals(1, listener.quotes.size(), "非批次行情帧须透传一次 onMarketQuote");
        HgcpEventListener.MarketQuote out = listener.quotes.get(0);
        assertEquals(INSTRUMENT, out.instrumentId);
        assertEquals("785.00", out.lastPrice);
        assertEquals("784.90", out.bidPrice1);
        assertEquals("785.10", out.askPrice1);
        assertEquals(12, out.bidVolume1);
        assertEquals(34, out.askVolume1);
        assertEquals("780.00", out.preSettlement);
        assertEquals(1_760_000_000_000L, out.quoteTsMs);
        assertEquals(1_760_000_000_123L, out.sourceTsMs);
    }

    @Test
    @DisplayName("行情帧不写镜像水位（0x020C 不占 SEQ；水位 = 可靠事件流连续性判据）")
    void quoteDoesNotAdvanceWatermark() {
        QuoteListener listener = new QuoteListener();
        HgcpClient client = newClient(listener);

        // 先喂可靠事件把水位推到 100，再喂 3 条「通道视角」的行情帧
        feedDirect(client, eventFrame(HgcpConstants.EVT_ORDER_ACCEPTED, 100L,
                Hgcp.EvtOrderAccepted.newBuilder().setOrderId(1L).setClientOrderRef("C")
                        .setRiskPassed(true).build().toByteArray()));
        assertEquals(100L, client.mirrorState().watermarkEventSeq());
        for (long seq : new long[]{101L, 102L, 103L}) {
            Hgcp.EvtMarketQuote q = Hgcp.EvtMarketQuote.newBuilder()
                    .setInstrumentId(INSTRUMENT).setLastPrice("1").setQuoteTsMs(seq).build();
            feedDirect(client, eventFrame(HgcpConstants.EVT_MARKET_QUOTE, seq, q.toByteArray()));
        }

        assertEquals(3, listener.quotes.size(), "三条行情帧均须透传监听器");
        assertEquals(100L, client.mirrorState().watermarkEventSeq(),
                "行情帧不得推进镜像水位（若在 EVT_MARKET_QUOTE case 内补 advanceWatermark，本断言必红）");
    }

    @Test
    @DisplayName("CH-007 不破坏回归：补数批次内行情帧仍被丢弃、不触发 onMarketQuote")
    void resumeBatchQuotesStillDroppedAfterBridgeWiring() {
        QuoteListener listener = new QuoteListener();
        HgcpClient client = newClient(listener);

        // 组装一条 MSG_RESUME_DATA：内含 2 条行情帧 + 1 条可靠事件
        Hgcp.EvtMarketQuote q = Hgcp.EvtMarketQuote.newBuilder()
                .setInstrumentId(INSTRUMENT).setLastPrice("999").setQuoteTsMs(1L).build();
        List<byte[]> inner = Arrays.asList(
                eventFrame(HgcpConstants.EVT_MARKET_QUOTE, 1L, q.toByteArray()),
                eventFrame(HgcpConstants.EVT_ORDER_ACCEPTED, 2L,
                        Hgcp.EvtOrderAccepted.newBuilder().setOrderId(2L).setClientOrderRef("C2")
                                .setRiskPassed(true).build().toByteArray()),
                eventFrame(HgcpConstants.EVT_MARKET_QUOTE, 3L, q.toByteArray()));
        Hgcp.MsgResumeData.Builder rb = Hgcp.MsgResumeData.newBuilder()
                .setFromSeq(1L).setToSeq(3L).setIsLast(true);
        for (byte[] f : inner) {
            rb.addFrames(com.google.protobuf.ByteString.copyFrom(f));
        }
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.MSG_RESUME_DATA;
        h.flags = HgcpFrame.FLAG_IS_RESUME_DATA;
        h.seq = 0L;
        byte[] outer = HgcpFrame.encode(h, rb.build().toByteArray());
        HgcpFrame.Decoded od = HgcpFrame.decode(outer, 0, outer.length);
        assertEquals(HgcpFrame.Status.OK, od.status);
        client.onFrame(od);

        assertEquals(0, listener.quotes.size(),
                "补数批次内行情帧须仍被 CH-007 分支丢弃、不透传 onMarketQuote（摘除拦截分支必红）");
        assertEquals(2L, client.mirrorState().watermarkEventSeq(),
                "水位止于可靠事件 SEQ=2（两条被丢行情帧 1/3 不推进）");
    }

    @Test
    @DisplayName("消费泵照常回调（onEventConsumed 含 0x020C）且监听器空实现时行为不变")
    void quoteConsumedPumpUnchangedWithDefaultListener() {
        // 换回「无行情回调」的纯泵监听器：default 空实现零破坏既有消费者（T-172 组合器）
        List<Integer> pumped = new ArrayList<>();
        HgcpClient client = newClient(new QuoteListener());
        client.setEventListener(new HgcpEventListener() {
            @Override
            public void onEventConsumed(int eventType, long seq) {
                pumped.add(eventType);
            }
        });
        Hgcp.EvtMarketQuote q = Hgcp.EvtMarketQuote.newBuilder()
                .setInstrumentId(INSTRUMENT).setLastPrice("1").setQuoteTsMs(1L).build();
        feedDirect(client, eventFrame(HgcpConstants.EVT_MARKET_QUOTE, 9L, q.toByteArray()));

        assertTrue(pumped.contains(HgcpConstants.EVT_MARKET_QUOTE),
                "消费泵 onEventConsumed 对 0x020C 照常回调（评估泵语义不变）");
        assertEquals(Arrays.asList(HgcpConstants.EVT_MARKET_QUOTE), pumped,
                "泵回调序列恰一条（不因桥接新增 case 而重复回调）");
    }
}
