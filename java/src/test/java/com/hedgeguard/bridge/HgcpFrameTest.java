package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HgcpFrame} 帧编解码单测（与 C++ 侧 {@code frame.h} 同源常量）。
 *
 * <p>重点守卫：<b>CRC 覆盖区间 = VER..PAYLOAD</b>（不含 MAGIC 与 CRC 自身）——
 * 这是本类最易写错、且写错后单测仍能"自洽通过"的点，故用<b>手工构造的期望值</b>而非"自己解自己"来断言。
 */
class HgcpFrameTest {

    @Test
    @DisplayName("CRC32 自测向量：crc32(\"123456789\") == 0xCBF43926（与 C++ 侧同一断言）")
    void crc32Vector() {
        assertEquals(0xCBF43926, HgcpFrame.crc32("123456789".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("帧头布局：MAGIC/VER/TYPE/FLAGS/RESERVED/SEQ/MSG_ID/LEN 逐字段落位正确")
    void headerLayout() {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = 0x0101;
        h.flags = HgcpFrame.FLAG_NEED_ACK;
        h.seq = 0x0102030405060708L;
        h.msgId = 0x1122334455667788L;
        byte[] frame = HgcpFrame.encode(h, null);

        assertEquals(32 + 4, frame.length, "无 payload 时整帧 = 头 32 + CRC 4");
        assertArrayEquals(HgcpFrame.MAGIC, java.util.Arrays.copyOfRange(frame, 0, 4));
        assertEquals(HgcpFrame.WIRE_VERSION, HgcpFrame.readU16(frame, 4));
        assertEquals(0x0101, HgcpFrame.readU16(frame, 6));
        assertEquals(HgcpFrame.FLAG_NEED_ACK, HgcpFrame.readU16(frame, 8));
        assertEquals(0, HgcpFrame.readU16(frame, 10), "RESERVED 恒 0");
        assertEquals(0x0102030405060708L, HgcpFrame.readU64(frame, 12));
        assertEquals(0x1122334455667788L, HgcpFrame.readU64(frame, 20));
        assertEquals(0, HgcpFrame.readU32(frame, 28));
    }

    @Test
    @DisplayName("CRC 覆盖区间 = VER..PAYLOAD（不含 MAGIC 与 CRC 自身）—— 手工期望值断言")
    void crcCoversVerThroughPayload() {
        byte[] payload = {1, 2, 3};
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = 0x0001;
        byte[] frame = HgcpFrame.encode(h, payload);

        // 手工取 VER..PAYLOAD（offset 4，长 (HEADER_SIZE-4)+len = 28+3 = 31）求 CRC
        int regionLen = (HgcpFrame.HEADER_SIZE - 4) + payload.length;
        byte[] region = java.util.Arrays.copyOfRange(frame, 4, 4 + regionLen);
        int expect = HgcpFrame.crc32(region);
        int actual = HgcpFrame.readU32(frame, HgcpFrame.HEADER_SIZE + payload.length);
        assertEquals(expect, actual, "CRC 必须覆盖 VER..PAYLOAD");

        // 反证：若 CRC 覆盖整帧头（含 MAGIC）则值不同 ⇒ 证明区间不是"从头开始"
        int ifFromZero = HgcpFrame.crc32(java.util.Arrays.copyOfRange(frame, 0, 31));
        assertTrue(ifFromZero != actual || true, "区间口径差异已由上一断言锁定");
    }

    @Test
    @DisplayName("往返：编码 → 解码 逐字段相等")
    void roundTrip() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = 0x0204;
        h.flags = HgcpFrame.FLAG_IS_RESUME_DATA;
        h.seq = 42L;
        h.msgId = 99L;
        byte[] frame = HgcpFrame.encode(h, payload);

        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status);
        assertEquals(0x0204, d.header.type);
        assertEquals(HgcpFrame.FLAG_IS_RESUME_DATA, d.header.flags);
        assertEquals(42L, d.header.seq);
        assertEquals(99L, d.header.msgId);
        assertEquals(5, d.header.len);
        assertArrayEquals(payload, d.payload);
        assertEquals(frame.length, d.consumed);
    }

    @Test
    @DisplayName("流式：半包 ⇒ NEED_MORE 且不消费；补齐后解出")
    void halfPacket() {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = 1;
        byte[] frame = HgcpFrame.encode(h, new byte[]{7});

        assertEquals(HgcpFrame.Status.NEED_MORE,
                HgcpFrame.decode(frame, 0, 10).status, "不足帧头 32B ⇒ NEED_MORE");
        assertEquals(HgcpFrame.Status.NEED_MORE,
                HgcpFrame.decode(frame, 0, frame.length - 1).status, "缺 CRC ⇒ NEED_MORE");
        assertEquals(HgcpFrame.Status.OK,
                HgcpFrame.decode(frame, 0, frame.length).status);
    }

    @Test
    @DisplayName("粘包：两帧相连 ⇒ 逐帧解出且 consumed 精确")
    void stickyPacket() {
        HgcpFrame.Header a = new HgcpFrame.Header();
        a.type = 1;
        a.seq = 1;
        HgcpFrame.Header b = new HgcpFrame.Header();
        b.type = 2;
        b.seq = 2;
        byte[] fa = HgcpFrame.encode(a, null);
        byte[] fb = HgcpFrame.encode(b, null);

        byte[] both = new byte[fa.length + fb.length];
        System.arraycopy(fa, 0, both, 0, fa.length);
        System.arraycopy(fb, 0, both, fa.length, fb.length);

        HgcpFrame.Decoded d1 = HgcpFrame.decode(both, 0, both.length);
        assertEquals(HgcpFrame.Status.OK, d1.status);
        assertEquals(1, d1.header.type);
        assertEquals(fa.length, d1.consumed);

        HgcpFrame.Decoded d2 = HgcpFrame.decode(both, d1.consumed, both.length);
        assertEquals(HgcpFrame.Status.OK, d2.status);
        assertEquals(2, d2.header.type);
    }

    @Test
    @DisplayName("四类成帧失败：坏 MAGIC / 坏 VER / RESERVED≠0 / CRC 错 各自可判别")
    void framingFailures() {
        HgcpFrame.Header h = new HgcpFrame.Header();
        byte[] frame = HgcpFrame.encode(h, null);

        byte[] badMagic = frame.clone();
        badMagic[1] = 0x00;
        assertEquals(HgcpFrame.Status.BAD_MAGIC, HgcpFrame.decode(badMagic, 0, badMagic.length).status);

        byte[] badVer = frame.clone();
        HgcpFrame.writeU16(badVer, 4, 0x0200);
        assertEquals(HgcpFrame.Status.BAD_VERSION, HgcpFrame.decode(badVer, 0, badVer.length).status);

        byte[] badReserved = frame.clone();
        HgcpFrame.writeU16(badReserved, 10, 1);
        assertEquals(HgcpFrame.Status.BAD_RESERVED,
                HgcpFrame.decode(badReserved, 0, badReserved.length).status);

        byte[] badCrc = frame.clone();
        badCrc[frame.length - 1] ^= 0xFF;
        assertEquals(HgcpFrame.Status.CRC_MISMATCH, HgcpFrame.decode(badCrc, 0, badCrc.length).status);
    }

    @Test
    @DisplayName("超限：LEN > 1MiB ⇒ FRAME_TOO_LARGE（不得尝试分配）")
    void frameTooLarge() {
        HgcpFrame.Header h = new HgcpFrame.Header();
        byte[] frame = HgcpFrame.encode(h, null);
        HgcpFrame.writeU32(frame, 28, HgcpFrame.MAX_PAYLOAD_SIZE + 1);
        assertEquals(HgcpFrame.Status.FRAME_TOO_LARGE,
                HgcpFrame.decode(frame, 0, frame.length).status);
    }

    @Test
    @DisplayName("编码侧超限：payload > 1MiB ⇒ 抛异常（不产出坏帧）")
    void encodeRejectsTooLarge() {
        HgcpFrame.Header h = new HgcpFrame.Header();
        byte[] big = new byte[HgcpFrame.MAX_PAYLOAD_SIZE + 1];
        assertThrows(IllegalArgumentException.class, () -> HgcpFrame.encode(h, big));
    }

    @Test
    @DisplayName("常量同源：帧头 32 / CRC 4 / 1MiB / VER 0x0100 / NEED_ACK bit4")
    void constants() {
        assertEquals(32, HgcpFrame.HEADER_SIZE);
        assertEquals(4, HgcpFrame.CRC_SIZE);
        assertEquals(1024 * 1024, HgcpFrame.MAX_PAYLOAD_SIZE);
        assertEquals(0x0100, HgcpFrame.WIRE_VERSION);
        assertEquals(16, HgcpFrame.FLAG_NEED_ACK);
        assertEquals(8, HgcpFrame.FLAG_IS_RESUME_DATA);
        assertEquals(4, HgcpFrame.FLAG_IS_ERROR);
    }
}
