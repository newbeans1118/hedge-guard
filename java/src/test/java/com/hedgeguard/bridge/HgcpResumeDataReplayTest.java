package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * {@link HgcpClient} 补数（{@code MSG_RESUME_DATA}，{@code 0x0009}）重放路径直接测试。
 *
 * <p>覆盖缺口来源：`java/src/test` 对 {@code onResumeData} / {@code MsgResumeData} <b>0 用例</b>
 * （既有 {@code HgcpFrameTest} 仅断言 {@code FLAG_IS_RESUME_DATA} 位标志，不触重放语义）。
 * 本类经<b>公开接缝</b> {@link HgcpClient#onFrame(HgcpFrame.Decoded)} 驱动，<b>不改生产代码</b>。
 *
 * <p>断言对象 = `java/CONTRACTS.md` §3.2.3（{@code CH-017} 生效）登记的三条不变式：
 * <ol>
 *   <li>① 批内 {@code SEQ} 连续，且 {@code decode(frames[k]).seq} 与 {@code from_seq + k} 对齐，末帧 {@code == to_seq}；</li>
 *   <li>② 跨批 {@code to_seq(i) + 1 == from_seq(i+1)}；</li>
 *   <li>③ 空批（{@code is_last=true} + {@code frames} 空）不崩，且 {@code from_seq == to_seq}，且水位不倒退。</li>
 * </ol>
 *
 * <p>行为断言优先：喂帧 → 看镜像状态变化（持仓写入 / 水位推进），而非仅断言「能解析」。
 */
class HgcpResumeDataReplayTest {

    private static final int SUB_ACCOUNT_ID = 7;
    private static final String INSTRUMENT = "ru2601";

    private static HgcpFrame.Decoded fire(HgcpClient client, int type, int flags, long seq,
                                          byte[] payload) {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = type;
        h.flags = flags;
        h.seq = seq;
        h.msgId = seq;
        byte[] frame = HgcpFrame.encode(h, payload);
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        client.onFrame(d);
        return d;
    }

    /** 造一条携带一个持仓事件的完整 wire 帧（含 MAGIC），通道 SEQ = seq。 */
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
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.EVT_POSITION_CHANGE;
        h.seq = seq;
        h.msgId = seq;
        return HgcpFrame.encode(h, evt.toByteArray());
    }

    private static HgcpFrame.Decoded resumeData(HgcpClient client, long fromSeq, long toSeq,
                                                boolean isLast, List<byte[]> frames) {
        Hgcp.MsgResumeData.Builder b = Hgcp.MsgResumeData.newBuilder()
                .setFromSeq(fromSeq)
                .setToSeq(toSeq)
                .setIsLast(isLast);
        for (byte[] f : frames) {
            b.addFrames(com.google.protobuf.ByteString.copyFrom(f));
        }
        return fire(client, HgcpConstants.MSG_RESUME_DATA,
                HgcpFrame.FLAG_IS_RESUME_DATA, 0L, b.build().toByteArray());
    }

    private static int positionQty(HgcpClient client) {
        for (PositionView v : client.mirrorState().snapshot().positions()) {
            if (v.subAccountId == SUB_ACCOUNT_ID && INSTRUMENT.equals(v.instrumentId)) {
                return Integer.parseInt(v.positionQty);
            }
        }
        return -1;
    }

    private static HgcpClient newClient() {
        return new HgcpClient("java-test", "token-not-logged", "boot-1", 1,
                () -> {
                    throw new UnsupportedOperationException("本用例只走 onFrame 接缝，不建连");
                });
    }

    @Test
    @DisplayName("不变式①：批内 SEQ 连续且与 from_seq..to_seq 逐一对齐；末帧 SEQ == to_seq（CH-017 per-batch）")
    void batchFramesAlignWithFromToSeq() {
        HgcpClient client = newClient();
        List<byte[]> frames = new ArrayList<>();
        for (long seq = 2; seq <= 6; seq++) {
            frames.add(positionChangeFrame(seq, (int) seq));
        }

        // from=2 / to=6 —— 与 §3.2.3 登记的「单批 5 帧 ⇒ 2/6」同形
        resumeData(client, 2L, 6L, true, frames);

        // ① 批内 SEQ 连续、首帧 == from_seq、末帧 == to_seq（对 frames 逐元素独立复解）
        List<Long> seqs = new ArrayList<>();
        for (byte[] f : frames) {
            HgcpFrame.Decoded inner = HgcpFrame.decode(f, 0, f.length);
            assertEquals(HgcpFrame.Status.OK, inner.status, "frames[k] 须为可解完整 wire 帧");
            seqs.add(inner.header.seq);
        }
        assertEquals(java.util.Arrays.asList(2L, 3L, 4L, 5L, 6L), seqs, "批内 SEQ 须连续");
        assertEquals(2L, (long) seqs.get(0), "decode(frames[0]).seq == from_seq");
        assertEquals(6L, (long) seqs.get(seqs.size() - 1), "decode(frames[last]).seq == to_seq");

        // 行为断言：5 帧全被消费 ⇒ 末帧（覆盖式，最后一条 positionQty=6）落地、水位 == 6
        assertEquals(6, positionQty(client), "末帧事件须已应用（覆盖式取最后一条）");
        assertEquals(6L, client.mirrorState().watermarkEventSeq(), "水位须推进到末帧 SEQ");
    }

    @Test
    @DisplayName("不变式②：501 帧分两批 ⇒ 2/501、502/502；跨批 to_seq(i)+1 == from_seq(i+1)")
    void crossBatchSequenceIsContiguous() {
        HgcpClient client = newClient();

        long batch1From = 2L;
        long batch1To = 501L;
        List<byte[]> batch1 = new ArrayList<>();
        for (long seq = batch1From; seq <= batch1To; seq++) {
            batch1.add(positionChangeFrame(seq, (int) (seq % 97)));
        }
        resumeData(client, batch1From, batch1To, false, batch1);

        long batch2From = 502L;
        long batch2To = 502L;
        List<byte[]> batch2 = new ArrayList<>();
        batch2.add(positionChangeFrame(batch2To, 42));
        resumeData(client, batch2From, batch2To, true, batch2);

        // ② 跨批衔接：to_seq(1) + 1 == from_seq(2)
        assertEquals(batch2From, batch1To + 1, "跨批 to_seq(i) + 1 须等于 from_seq(i+1)");

        // 两批的 frames 拼起来 SEQ 整体连续（从 2 到 502，无缺无重）
        List<Long> all = new ArrayList<>();
        for (byte[] f : batch1) {
            all.add(HgcpFrame.decode(f, 0, f.length).header.seq);
        }
        for (byte[] f : batch2) {
            all.add(HgcpFrame.decode(f, 0, f.length).header.seq);
        }
        assertEquals(501, all.size(), "两批合计 501 帧");
        assertEquals(2L, (long) all.get(0));
        assertEquals(502L, (long) all.get(all.size() - 1));
        for (int i = 1; i < all.size(); i++) {
            assertEquals(all.get(i - 1) + 1, (long) all.get(i), "跨批拼接后 SEQ 须逐帧连续");
        }

        // 行为断言：同一 (子账户, 合约) 键被两批各覆盖一次，末批为准；水位 == 502
        assertEquals(42, positionQty(client), "末批事件须生效");
        assertEquals(502L, client.mirrorState().watermarkEventSeq());
    }

    @Test
    @DisplayName("不变式③：空批（is_last=true + frames 空）不崩、from_seq == to_seq；水位不倒退，且空批后仍可继续重放")
    void emptyLastBatchIsTolerated() {
        HgcpClient client = newClient();

        // 首批：SEQ 2..4（is_last=false）
        List<byte[]> batch = new ArrayList<>();
        for (long seq = 2; seq <= 4; seq++) {
            batch.add(positionChangeFrame(seq, (int) seq));
        }
        resumeData(client, 2L, 4L, false, batch);
        assertEquals(4, positionQty(client), "首批末帧事件须生效");
        assertEquals(4L, client.mirrorState().watermarkEventSeq(), "首批后水位 == 4");

        // ③ 空批：from_seq == to_seq == 上一批末帧 SEQ（此处 4），is_last=true
        resumeData(client, 4L, 4L, true, java.util.Collections.emptyList());

        assertNotNull(client.mirrorState().snapshot(), "空批不得使镜像状态崩坏");
        assertEquals(4L, client.mirrorState().watermarkEventSeq(),
                "空批不携带事件 ⇒ 水位不得倒退、也不得被伪推进");
        assertEquals(4, positionQty(client), "空批不得清空既有镜像持仓");

        // 空批不是终止态：其后再来一批应正常重放（水位继续推进到 6）
        List<byte[]> after = new ArrayList<>();
        for (long seq = 5; seq <= 6; seq++) {
            after.add(positionChangeFrame(seq, (int) seq));
        }
        resumeData(client, 5L, 6L, true, after);
        assertEquals(6, positionQty(client), "空批之后的重放须正常生效");
        assertEquals(6L, client.mirrorState().watermarkEventSeq());
    }

    @Test
    @DisplayName("外层帧 SEQ ≠ 本批末帧 SEQ：客户端水位须由内层事件推进（不得把外层 0 当事件 SEQ）")
    void outerFrameSeqDoesNotDriveWatermark() {
        HgcpClient client = newClient();
        List<byte[]> frames = new ArrayList<>();
        frames.add(positionChangeFrame(11, 11));
        frames.add(positionChangeFrame(12, 12));

        Hgcp.MsgResumeData body = Hgcp.MsgResumeData.newBuilder()
                .setFromSeq(11L)
                .setToSeq(12L)
                .setIsLast(true)
                .addFrames(com.google.protobuf.ByteString.copyFrom(frames.get(0)))
                .addFrames(com.google.protobuf.ByteString.copyFrom(frames.get(1)))
                .build();
        // 外层补数帧自身 seq = 0（外层 SEQ 与内层通道 SEQ 不同域）
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.MSG_RESUME_DATA;
        h.flags = HgcpFrame.FLAG_IS_RESUME_DATA;
        h.seq = 0L;
        byte[] outer = HgcpFrame.encode(h, body.toByteArray());
        HgcpFrame.Decoded d = HgcpFrame.decode(outer, 0, outer.length);
        assertEquals(HgcpFrame.Status.OK, d.status);
        client.onFrame(d);

        assertEquals(12L, client.mirrorState().watermarkEventSeq(),
                "水位须由内层事件 SEQ 推进（末帧 12），不受外层帧 seq=0 影响");
        assertEquals(12, positionQty(client));
        assertTrue(client.mirrorState().watermarkEventSeq() > d.header.seq,
                "断言本用例确已区分内外层 SEQ（否则本用例无守卫力）");
    }

    @Test
    @DisplayName("frames[k] 非完整 wire 帧（坏 CRC）⇒ 抛 HgcpProtocolException（接收侧不得静默跳过该帧）")
    void corruptInnerFrameIsRejected() {
        HgcpClient client = newClient();
        byte[] good = positionChangeFrame(3, 3);
        byte[] bad = good.clone();
        bad[bad.length - 1] ^= 0xFF; // 破坏 CRC32
        assertEquals(HgcpFrame.Status.CRC_MISMATCH, HgcpFrame.decode(bad, 0, bad.length).status,
                "夹具：坏帧须自成帧层可判别");

        Hgcp.MsgResumeData body = Hgcp.MsgResumeData.newBuilder()
                .setFromSeq(3L)
                .setToSeq(4L)
                .setIsLast(true)
                .addFrames(com.google.protobuf.ByteString.copyFrom(good))
                .addFrames(com.google.protobuf.ByteString.copyFrom(bad))
                .build();
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.MSG_RESUME_DATA;
        h.flags = HgcpFrame.FLAG_IS_RESUME_DATA;
        byte[] outer = HgcpFrame.encode(h, body.toByteArray());
        HgcpFrame.Decoded d = HgcpFrame.decode(outer, 0, outer.length);
        assertEquals(HgcpFrame.Status.OK, d.status);

        HgcpProtocolException ex = assertThrows(HgcpProtocolException.class,
                () -> client.onFrame(d),
                "批内坏帧 => 帧边界不可信，须抛协议异常而非静默跳过（CH-014 同姿态）");
        assertEquals(HgcpFrame.Status.CRC_MISMATCH, ex.status(),
                "异常须携带内层帧的实际成帧失败状态");
    }
}
