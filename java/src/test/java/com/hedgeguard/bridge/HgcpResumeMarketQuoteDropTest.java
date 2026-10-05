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
 * {@code CH-007} 消费侧规则（{@code T-174}）：补数批次（{@code MSG_RESUME_DATA}）内的
 * {@code EVT_MARKET_QUOTE}（{@code 0x020C}）须遭<b>接收侧丢弃</b>。
 *
 * <p>对应设计用例 {@code docs/TEST_PLAN/M2.md} {@code TC-M2-21}（层②：一律丢弃、不消费）与
 * {@code TC-M2-37}（补数批次内行情帧不触发消费泵），并旁证 {@code TC-M2-22}
 * （关键事件不误伤：{@code 0x0202..0x020B} 十种照常消费）。
 *
 * <p><b>判据（验收 4）= 去掉丢弃分支必红</b>：丢弃分支被移除后，行情帧照常
 * {@code advanceWatermark} + {@code notifyConsumed}，
 * {@link #resumeBatchMarketQuoteIsDroppedAndKeyEventsStillConsumed()} 与
 * {@link #resumeBatchAllTenCriticalEventsSurviveAlongsideQuotes()} 必然失败
 * （前者的消费序列会混入 {@code 0x020C}、水位被推到被丢帧的 {@code SEQ}）。
 *
 * <p><b>驱动面</b>：公开接缝 {@link HgcpClient#onFrame(HgcpFrame.Decoded)}（整条
 * {@code MSG_RESUME_DATA}）+ 事件监听器接缝 {@link HgcpEventListener#onEventConsumed}；
 * 不修改生产接缝签名、不改线格式、不动 {@code proto/}。
 *
 * <p><b>来源选择性</b>：丢弃<strong>只对补数批次</strong>内的行情帧生效
 * （{@link #quoteOutsideResumeBatchIsNotDropped()} 守卫「一刀切丢弃」的过度实现）。
 */
class HgcpResumeMarketQuoteDropTest {

    private static final int SUB_ACCOUNT_ID = 7;
    private static final String INSTRUMENT = "ru2601";

    // ==================== 夹具 ====================

    /** 记录型监听器：按类型统计消费泵回调与各事件回调。 */
    private static final class RecordingListener implements HgcpEventListener {
        final List<Integer> consumedTypes = new ArrayList<>();
        int orderAccepted;
        int preTradeReject;
        int orderReport;
        int tradeFill;
        int positionChange;
        int orderCancelResult;
        int orderExpired;
        int commandFlow;

        @Override
        public void onEventConsumed(int eventType, long seq) {
            consumedTypes.add(eventType);
        }

        @Override
        public void onOrderAccepted(HgcpEventListener.OrderAccepted e) {
            orderAccepted++;
        }

        @Override
        public void onPreTradeReject(HgcpEventListener.PreTradeReject e) {
            preTradeReject++;
        }

        @Override
        public void onOrderReport(HgcpEventListener.OrderReport e) {
            orderReport++;
        }

        @Override
        public void onTradeFill(HgcpEventListener.TradeFill e) {
            tradeFill++;
        }

        @Override
        public void onPositionChange(HgcpEventListener.PositionChange e) {
            positionChange++;
        }

        @Override
        public void onOrderCancelResult(HgcpEventListener.OrderCancelResult e) {
            orderCancelResult++;
        }

        @Override
        public void onOrderExpired(HgcpEventListener.OrderExpired e) {
            orderExpired++;
        }

        @Override
        public void onCommandFlow(HgcpEventListener.CommandFlow e) {
            commandFlow++;
        }

        int quoteConsumed() {
            int n = 0;
            for (int t : consumedTypes) {
                if (t == HgcpConstants.EVT_MARKET_QUOTE) {
                    n++;
                }
            }
            return n;
        }
    }

    private static HgcpClient newClient(RecordingListener listener) {
        HgcpClient client = new HgcpClient("java-test", "token-not-logged", "boot-1", 1,
                () -> {
                    throw new UnsupportedOperationException("本用例只走 onFrame 接缝，不建连");
                });
        client.setEventListener(listener);
        return client;
    }

    /** 造一条携带 payload 的完整 wire 帧（含 MAGIC），通道 SEQ = seq。 */
    private static byte[] eventFrame(int type, long seq, byte[] payload) {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = type;
        h.seq = seq;
        h.msgId = seq;
        return HgcpFrame.encode(h, payload);
    }

    private static byte[] quoteFrame(long seq) {
        Hgcp.EvtMarketQuote quote = Hgcp.EvtMarketQuote.newBuilder()
                .setInstrumentId(INSTRUMENT)
                .setLastPrice("12345")
                .setBidPrice1("12344")
                .setAskPrice1("12346")
                .setQuoteTsMs(1_700_000_000_000L)
                .build();
        return eventFrame(HgcpConstants.EVT_MARKET_QUOTE, seq, quote.toByteArray());
    }

    private static byte[] positionChangeFrame(long seq) {
        Hgcp.EvtPositionChange evt = Hgcp.EvtPositionChange.newBuilder()
                .setSubAccountId(SUB_ACCOUNT_ID)
                .setInstrumentId(INSTRUMENT)
                .setDirection(Hgcp.Direction.DIRECTION_BUY)
                .setPositionType(Hgcp.PositionType.POSITION_TYPE_TODAY)
                .setPositionQty(22)
                .setAvailableQty(22)
                .setFrozenQty(0)
                .setAsOfTsMs(1_700_000_000_000L)
                .build();
        return eventFrame(HgcpConstants.EVT_POSITION_CHANGE, seq, evt.toByteArray());
    }

    /** 经公开接缝喂入一条 {@code MSG_RESUME_DATA}（外层帧 SEQ = 0，与内层 SEQ 不同域）。 */
    private static void fireResumeBatch(HgcpClient client, long fromSeq, long toSeq,
                                        List<byte[]> frames) {
        Hgcp.MsgResumeData.Builder b = Hgcp.MsgResumeData.newBuilder()
                .setFromSeq(fromSeq)
                .setToSeq(toSeq)
                .setIsLast(true);
        for (byte[] f : frames) {
            b.addFrames(com.google.protobuf.ByteString.copyFrom(f));
        }
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.MSG_RESUME_DATA;
        h.flags = HgcpFrame.FLAG_IS_RESUME_DATA;
        h.seq = 0L;
        byte[] outer = HgcpFrame.encode(h, b.build().toByteArray());
        HgcpFrame.Decoded d = HgcpFrame.decode(outer, 0, outer.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        client.onFrame(d);
    }

    /** 经公开接缝喂入一条普通（非补数批次）事件帧。 */
    private static void fireDirect(HgcpClient client, byte[] frame) {
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        client.onFrame(d);
    }

    private static int positionQty(HgcpClient client) {
        for (PositionView v : client.mirrorState().snapshot().positions()) {
            if (v.subAccountId == SUB_ACCOUNT_ID && INSTRUMENT.equals(v.instrumentId)) {
                return Integer.parseInt(v.positionQty);
            }
        }
        return -1;
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("TC-M2-21/37：补数批次内 7 条行情帧 + 5 条关键事件 ⇒ 行情帧消费 0、关键事件全消费、水位止于末关键事件 SEQ")
    void resumeBatchMarketQuoteIsDroppedAndKeyEventsStillConsumed() {
        RecordingListener listener = new RecordingListener();
        HgcpClient client = newClient(listener);

        // SEQ 1..12：7 条行情（1/3/5/7/9/11/12）+ 5 条关键事件（2/4/6/8/10）
        List<byte[]> frames = Arrays.asList(
                quoteFrame(1),
                positionChangeFrame(2),
                quoteFrame(3),
                eventFrame(HgcpConstants.EVT_ORDER_ACCEPTED, 4,
                        Hgcp.EvtOrderAccepted.newBuilder().setOrderId(1001)
                                .setClientOrderRef("C-1").setRiskPassed(true).build().toByteArray()),
                quoteFrame(5),
                eventFrame(HgcpConstants.EVT_TRADE_FILL, 6,
                        Hgcp.EvtTradeFill.newBuilder().setTradeId("T-1").setOrderId(1001)
                                .setInstrumentId(INSTRUMENT).build().toByteArray()),
                quoteFrame(7),
                eventFrame(HgcpConstants.EVT_ORDER_REPORT, 8,
                        Hgcp.EvtOrderReport.newBuilder().setOrderId(1001)
                                .setOrderSysId("S-1").build().toByteArray()),
                quoteFrame(9),
                eventFrame(HgcpConstants.EVT_ORDER_CANCEL_RESULT, 10,
                        Hgcp.EvtOrderCancelResult.newBuilder().setOrderId(1001)
                                .setCancelledVolume(1).build().toByteArray()),
                quoteFrame(11),
                quoteFrame(12)); // 最高 SEQ 的行情帧——若被消费，水位将被推到 12

        fireResumeBatch(client, 1L, 12L, frames);

        // ① 消费泵序列 = 恰 5 条关键事件（按 SEQ 顺序），不含任何 0x020C（TC-M2-37）
        assertEquals(Arrays.asList(
                        HgcpConstants.EVT_POSITION_CHANGE,
                        HgcpConstants.EVT_ORDER_ACCEPTED,
                        HgcpConstants.EVT_TRADE_FILL,
                        HgcpConstants.EVT_ORDER_REPORT,
                        HgcpConstants.EVT_ORDER_CANCEL_RESULT),
                listener.consumedTypes,
                "补数批次内行情帧不得进入消费泵；关键事件须按 SEQ 顺序消费");
        assertEquals(0, listener.quoteConsumed(), "行情帧消费计数须 = 0（不消费、不透传、不推前端）");

        // ② 各关键事件监听器回调透传正常（不误伤）
        assertEquals(1, listener.positionChange, "持仓变化须透传一次");
        assertEquals(1, listener.orderAccepted, "委托受理须透传一次");
        assertEquals(1, listener.tradeFill, "成交须透传一次");
        assertEquals(1, listener.orderReport, "委托回报须透传一次");
        assertEquals(1, listener.orderCancelResult, "撤单结果须透传一次");

        // ③ 行为断言：水位止于末关键事件 SEQ=10，而非被丢行情帧的 SEQ=12
        assertEquals(10L, client.mirrorState().watermarkEventSeq(),
                "补数批次内行情帧不得推进水位");
        assertTrue(client.mirrorState().watermarkEventSeq() < 12L,
                "本用例须确有一条 SEQ 高于水位的被丢行情帧（否则无守卫力）");

        // ④ 镜像：持仓事件正常落地
        assertEquals(22, positionQty(client), "关键事件（持仓变化）须正常应用");
    }

    @Test
    @DisplayName("TC-M2-22：补数批次内 0x0202..0x020B 十种关键事件 + 3 条行情 ⇒ 十种全消费、行情 0")
    void resumeBatchAllTenCriticalEventsSurviveAlongsideQuotes() {
        RecordingListener listener = new RecordingListener();
        HgcpClient client = newClient(listener);

        List<byte[]> frames = new ArrayList<>();
        // 十种须落库事件，SEQ 1..10，顺序即 0x0202..0x020B
        frames.add(eventFrame(HgcpConstants.EVT_PRE_TRADE_REJECT, 1,
                Hgcp.EvtPreTradeReject.newBuilder().setClientOrderRef("C-1")
                        .setSubAccountId(SUB_ACCOUNT_ID).setInstrumentId(INSTRUMENT).build().toByteArray()));
        frames.add(eventFrame(HgcpConstants.EVT_ORDER_REPORT, 2,
                Hgcp.EvtOrderReport.newBuilder().setOrderId(1001).setOrderSysId("S-1").build().toByteArray()));
        frames.add(eventFrame(HgcpConstants.EVT_TRADE_FILL, 3,
                Hgcp.EvtTradeFill.newBuilder().setTradeId("T-1").setOrderId(1001)
                        .setInstrumentId(INSTRUMENT).setSubAccountId(SUB_ACCOUNT_ID).build().toByteArray()));
        frames.add(positionChangeFrame(4));
        frames.add(eventFrame(HgcpConstants.EVT_ORDER_CANCEL_RESULT, 5,
                Hgcp.EvtOrderCancelResult.newBuilder().setOrderId(1001).setCancelledVolume(1).build().toByteArray()));
        frames.add(eventFrame(HgcpConstants.EVT_ORDER_EXPIRED, 6,
                Hgcp.EvtOrderExpired.newBuilder().setOrderId(1001).setClientOrderRef("C-1").build().toByteArray()));
        frames.add(eventFrame(HgcpConstants.EVT_COMMAND_FLOW, 7,
                Hgcp.EvtCommandFlow.newBuilder().setOrderId(1001).build().toByteArray()));
        frames.add(eventFrame(HgcpConstants.EVT_REPLAY_PROGRESS, 8,
                Hgcp.EvtReplayProgress.newBuilder().build().toByteArray()));
        frames.add(eventFrame(HgcpConstants.EVT_SYSTEM_STATE, 9,
                Hgcp.EvtSystemState.newBuilder().build().toByteArray()));
        frames.add(eventFrame(HgcpConstants.EVT_FUND_RECEIPT, 10,
                Hgcp.EvtFundReceipt.newBuilder().setIdempotencyKey("K-1").setApplyId("A-1")
                        .setAmount("1000").build().toByteArray()));
        // 3 条行情帧，SEQ 11/12/13（均高于全部关键事件）
        frames.add(quoteFrame(11));
        frames.add(quoteFrame(12));
        frames.add(quoteFrame(13));

        fireResumeBatch(client, 1L, 13L, frames);

        assertEquals(Arrays.asList(
                        HgcpConstants.EVT_PRE_TRADE_REJECT,
                        HgcpConstants.EVT_ORDER_REPORT,
                        HgcpConstants.EVT_TRADE_FILL,
                        HgcpConstants.EVT_POSITION_CHANGE,
                        HgcpConstants.EVT_ORDER_CANCEL_RESULT,
                        HgcpConstants.EVT_ORDER_EXPIRED,
                        HgcpConstants.EVT_COMMAND_FLOW,
                        HgcpConstants.EVT_REPLAY_PROGRESS,
                        HgcpConstants.EVT_SYSTEM_STATE,
                        HgcpConstants.EVT_FUND_RECEIPT),
                listener.consumedTypes,
                "补数批次内十种须落库事件须全部照常消费（不误伤）");
        assertEquals(0, listener.quoteConsumed(), "行情帧消费计数须 = 0");
        assertEquals(1, listener.preTradeReject);
        assertEquals(1, listener.orderReport);
        assertEquals(1, listener.tradeFill);
        assertEquals(1, listener.positionChange);
        assertEquals(1, listener.orderCancelResult);
        assertEquals(1, listener.orderExpired);
        assertEquals(1, listener.commandFlow);
        assertEquals(10L, client.mirrorState().watermarkEventSeq(),
                "水位须止于末关键事件 SEQ=10（三条行情帧 11/12/13 全被丢弃）");
    }

    @Test
    @DisplayName("来源选择性：非补数批次的行情帧照常消费（守卫「一刀切丢弃」的过度实现）")
    void quoteOutsideResumeBatchIsNotDropped() {
        RecordingListener listener = new RecordingListener();
        HgcpClient client = newClient(listener);

        fireDirect(client, quoteFrame(77));

        assertEquals(Arrays.asList(HgcpConstants.EVT_MARKET_QUOTE), listener.consumedTypes,
                "非补数批次的行情帧走既有路径（本任务只对补数批次内行情帧收口）");
        assertEquals(1, listener.quoteConsumed());
        assertEquals(77L, client.mirrorState().watermarkEventSeq(),
                "非批次路径水位推进不变");
    }
}
