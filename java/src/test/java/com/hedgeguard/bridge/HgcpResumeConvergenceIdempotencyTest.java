package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hgcp.v1.Hgcp;

/**
 * {@code T-057} 协议专项：断连重连补数（{@code MSG_RESUME_DATA}）的<b>收敛</b>与
 * <b>幂等去重</b>在 Java 接收侧的直测。对应设计用例
 * {@code docs/TEST_PLAN/M2.md}：
 * <ul>
 *   <li>{@code TC-M2-16}（断连 5s 内 200 事件 ⇒ 重连全补齐、无重复落库）；</li>
 *   <li>{@code TC-M2-19}（补数完成判据：两侧逐帧数量与顺序一致、水位收敛——<b>以线路帧为准</b>）；</li>
 *   <li>{@code TC-M2-26}（重复成交回报幂等）；</li>
 *   <li>{@code TC-M2-29}（补数造成的重复投递幂等，与既有已收事件重叠）。</li>
 * </ul>
 *
 * <p><b>驱动面</b>：公开接缝 {@link HgcpClient#onFrame(HgcpFrame.Decoded)}（整条
 * {@code MSG_RESUME_DATA} 或单条事件帧）+ 事件监听器接缝；不再建连（假 transport 抛
 * {@code UnsupportedOperationException}）。<b>不改生产代码</b>。
 *
 * <p><b>判据锚</b>（{@code CH-007}）：补数批次判据以<b>线路帧</b>为准；本类全部断言落在
 * 「接收侧消费层」（监听器回调 + 镜像水位），与线路级回放口径一致（无行情帧参与，故无
 * {@code CH-007} 丢弃干扰）。
 */
class HgcpResumeConvergenceIdempotencyTest {

    private static final int SUB_ACCOUNT_ID = 7;
    private static final String INSTRUMENT = "ru2601";

    // ==================== 夹具 ====================

    /** 记录型监听器：统计每类事件回调与消费泵回调。 */
    private static final class RecordingListener implements HgcpEventListener {
        final List<Integer> consumedTypes = new ArrayList<>();
        final List<Long> consumedSeqs = new ArrayList<>();
        int positionChange;
        int tradeFill;
        final List<Integer> positionQtys = new ArrayList<>();

        @Override
        public void onEventConsumed(int eventType, long seq) {
            consumedTypes.add(eventType);
            consumedSeqs.add(seq);
        }

        @Override
        public void onPositionChange(HgcpEventListener.PositionChange e) {
            positionChange++;
            positionQtys.add(e.positionQty);
        }

        @Override
        public void onTradeFill(HgcpEventListener.TradeFill e) {
            tradeFill++;
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

    /** 造一条携带持仓事件的完整 wire 帧（含 MAGIC），通道 SEQ = seq。 */
    private static byte[] positionChangeFrame(long seq, int qty) {
        Hgcp.EvtPositionChange evt = Hgcp.EvtPositionChange.newBuilder()
                .setSubAccountId(SUB_ACCOUNT_ID)
                .setInstrumentId(INSTRUMENT)
                .setDirection(Hgcp.Direction.DIRECTION_BUY)
                .setPositionType(Hgcp.PositionType.POSITION_TYPE_TODAY)
                .setPositionQty(qty)
                .setAvailableQty(qty)
                .setFrozenQty(0)
                .setAsOfTsMs(1_700_000_000_000L)
                .build();
        return eventFrame(HgcpConstants.EVT_POSITION_CHANGE, seq, evt.toByteArray());
    }

    /** 造一条携带成交回报的完整 wire 帧（含 MAGIC），通道 SEQ = seq。 */
    private static byte[] tradeFillFrame(long seq, String tradeId) {
        Hgcp.EvtTradeFill evt = Hgcp.EvtTradeFill.newBuilder()
                .setTradeId(tradeId)
                .setOrderId(1001)
                .setInstrumentId(INSTRUMENT)
                .build();
        return eventFrame(HgcpConstants.EVT_TRADE_FILL, seq, evt.toByteArray());
    }

    private static byte[] eventFrame(int type, long seq, byte[] payload) {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = type;
        h.seq = seq;
        h.msgId = seq;
        return HgcpFrame.encode(h, payload);
    }

    /** 经公开接缝喂入一条 {@code MSG_RESUME_DATA}（外层帧 SEQ = 0，与内层 SEQ 不同域）。 */
    private static void fireResumeBatch(HgcpClient client, long fromSeq, long toSeq, List<byte[]> frames) {
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
    @DisplayName("TC-M2-16/19：补数批次 200 事件（SEQ 101..300）全补齐、逐帧保序、水位收敛到 300；"
            + "重放同一批次不产生重复落库（幂等）")
    void resumeBatchDeliversAll200EventsInOrderAndDeduplicatesReplay() {
        RecordingListener listener = new RecordingListener();
        HgcpClient client = newClient(listener);

        final long from = 101L;
        final long to = 300L;
        final int n = 200;
        List<byte[]> frames = new ArrayList<>();
        for (long seq = from; seq <= to; seq++) {
            frames.add(positionChangeFrame(seq, (int) seq));
        }

        fireResumeBatch(client, from, to, frames);

        // ① 全补齐：恰 200 条持仓事件被消费（不丢）
        assertEquals(n, listener.positionChange, "补数批内 200 条关键事件须全部消费（不丢）");
        // ② 逐帧保序：线路 SEQ 101..300 按序被消费（不乱序）
        assertEquals(n, listener.consumedSeqs.size(), "消费泵回调数须 = 线路帧数（本批无行情帧）");
        for (int i = 0; i < n; i++) {
            assertEquals(from + i, (long) listener.consumedSeqs.get(i), "第 " + i + " 帧 SEQ 须按序");
        }
        // ③ 水位收敛到末帧 SEQ；末帧事件生效
        assertEquals(to, client.mirrorState().watermarkEventSeq(), "水位须收敛到末帧 SEQ=300");
        assertEquals(300, positionQty(client), "末帧事件须生效（覆盖式）");

        // ④ 重放同一批次：按 (business_id, seq) 去重 ⇒ 新增落库 = 0，水位不倒退
        fireResumeBatch(client, from, to, frames);
        assertEquals(n, listener.positionChange, "重复投递不得新增消费（幂等 = 0 新增）");
        assertEquals(to, client.mirrorState().watermarkEventSeq(), "重复投递不得使水位倒退或伪推进");
    }

    @Test
    @DisplayName("TC-M2-26：同一 EVT_TRADE_FILL（trade_id=T-1）重复推送 3 次 ⇒ 成交回调仅 1 次（幂等）")
    void duplicateTradeFillIsConsumedOnce() {
        RecordingListener listener = new RecordingListener();
        HgcpClient client = newClient(listener);

        byte[] fill = tradeFillFrame(6L, "T-1");
        fireDirect(client, fill);
        fireDirect(client, fill);
        fireDirect(client, fill);

        assertEquals(1, listener.tradeFill, "同一 (trade_id, seq) 的成交回报重复投递须被丢弃（幂等）");
    }

    @Test
    @DisplayName("TC-M2-29：补数批次与既有已收事件重叠（同 trade_id 同 seq）⇒ 重叠帧不重复落库，"
            + "仅新帧生效（幂等键含 business_id）")
    void resumeOverlapWithAlreadyConsumedEventIsIdempotent() {
        RecordingListener listener = new RecordingListener();
        HgcpClient client = newClient(listener);

        // 既有：已收 SEQ=50 的成交回报 T-9
        fireDirect(client, tradeFillFrame(50L, "T-9"));
        assertEquals(1, listener.tradeFill, "首批直投须消费一次");
        assertEquals(50L, client.mirrorState().watermarkEventSeq());

        // 补数批次：重叠帧 SEQ=50（同 T-9）+ 新帧 SEQ=51（T-10）
        List<byte[]> frames = new ArrayList<>();
        frames.add(tradeFillFrame(50L, "T-9"));
        frames.add(tradeFillFrame(51L, "T-10"));
        fireResumeBatch(client, 50L, 51L, frames);

        assertEquals(2, listener.tradeFill, "重叠帧不得重复落库；仅新帧新增（共 2）");
        assertEquals(51L, client.mirrorState().watermarkEventSeq(), "水位须推进到新帧 SEQ=51");
    }

    @Test
    @DisplayName("验收2/NFR-PERF-6：连续 1000 笔事件流（SEQ 1..1000）经补数通道批量发射 ⇒ 不丢、不乱序、水位收敛")
    void thousandEventStreamNoLossNoReorder() {
        RecordingListener listener = new RecordingListener();
        HgcpClient client = newClient(listener);

        final int n = 1000;
        List<byte[]> frames = new ArrayList<>();
        for (long seq = 1; seq <= n; seq++) {
            frames.add(positionChangeFrame(seq, (int) seq));
        }
        fireResumeBatch(client, 1L, (long) n, frames);

        // ① 不丢：恰 1000 笔被消费
        assertEquals(n, listener.positionChange, "1000 笔事件须全部消费（丢失帧数 = 0）");
        assertEquals(n, listener.consumedSeqs.size(), "消费泵回调数须 = 线路帧数（1000）");
        // ② 不乱序：SEQ 1..1000 严格递增到达
        for (int i = 0; i < n; i++) {
            assertEquals((long) (i + 1), (long) listener.consumedSeqs.get(i),
                    "第 " + i + " 帧 SEQ 须严格递增（乱序帧数 = 0）");
        }
        // ③ 水位收敛到末帧
        assertEquals((long) n, client.mirrorState().watermarkEventSeq());
        assertEquals(n, positionQty(client), "末帧事件须生效");
    }

    @Test
    @DisplayName("TC-M2-26 正控：不同 trade_id 各自生效（证明「丢弃」确由业务键匹配所致，非一刀切）")
    void distinctTradeIdsAreBothConsumed() {
        RecordingListener listener = new RecordingListener();
        HgcpClient client = newClient(listener);

        fireDirect(client, tradeFillFrame(7L, "T-A"));
        fireDirect(client, tradeFillFrame(8L, "T-B"));

        assertEquals(2, listener.tradeFill, "不同业务主键须各自生效");
    }
}
