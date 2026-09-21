package com.hedgeguard.web.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 6455 帧编解码单测（`T-028` 验收 6 的**核心证据**）。
 *
 * <p>**完全离线**：全部输入为构造的字节数组/内存流，不依赖网络与 DM8。
 * 覆盖三层：
 * <ol>
 *   <li><b>官方向量</b>：RFC 6455 §1.3 的握手向量（{@code dGhlIHNhbXBsZSBub25jZQ==} → {@code s3pPLMBiTxaQ9kYGzzhZRbK+xOo=}）；</li>
 *   <li><b>编码边界</b>：125 / 126 / 65535 / 65536 三档长度编码（7 位、16 位、64 位）；</li>
 *   <li><b>解码拒收</b>：未掩码、RSV 置位、未知 opcode、控制帧超长/分片、数据帧分片、非法 UTF-8、超大长度、EOF。</li>
 * </ol>
 */
class WsFrameCodecTest {

    // ---------------------------------------------------------------- 官方向量

    @Test
    @DisplayName("RFC 6455 §1.3 官方握手测试向量：Accept = s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
    void officialHandshakeVector() {
        assertEquals(WsTestSupport.RFC_ACCEPT,
                WsHandshake.acceptKey(WsTestSupport.RFC_KEY));
        assertEquals("258EAFA5-E914-47DA-95CA-C5AB0DC85B11", WsHandshake.WS_GUID);
    }

    // ---------------------------------------------------------------- 编码

    @Test
    @DisplayName("文本帧编码：FIN=1、opcode=1、不掩码（小载荷逐字节断言）")
    void encodesSmallTextFrame() {
        byte[] frame = WsFrameCodec.encodeText("Hello");
        assertArrayEquals(new byte[]{ (byte) 0x81, 0x05, 'H', 'e', 'l', 'l', 'o' }, frame);
        // 服务端帧的 MASK 位必须为 0（RFC 6455 §5.1）
        assertEquals(0, frame[1] & 0x80);
    }

    @Test
    @DisplayName("长度编码三档：≤125 用 7 位；126..65535 用 16 位（0x7E）；>65535 用 64 位（0x7F）")
    void encodesLengthBoundaries() {
        byte[] at125 = WsFrameCodec.encodeText(repeat('a', 125));
        assertEquals(2 + 125, at125.length);
        assertEquals(125, at125[1] & 0x7F);

        byte[] at126 = WsFrameCodec.encodeText(repeat('a', 126));
        assertEquals(4 + 126, at126.length);
        assertEquals(126, at126[1] & 0x7F);
        assertEquals(0, at126[2]);
        assertEquals(126, at126[3] & 0xFF);

        byte[] at65535 = WsFrameCodec.encodeText(repeat('a', 65535));
        assertEquals(4 + 65535, at65535.length);
        assertEquals(65535, ((at65535[2] & 0xFF) << 8) | (at65535[3] & 0xFF));

        byte[] at65536 = WsFrameCodec.encodeText(repeat('a', 65536));
        assertEquals(10 + 65536, at65536.length);
        assertEquals(127, at65536[1] & 0x7F);
        long declared = 0;
        for (int i = 0; i < 8; i++) {
            declared = (declared << 8) | (at65536[2 + i] & 0xFF);
        }
        assertEquals(65536L, declared);
    }

    @Test
    @DisplayName("控制帧编码：close 帧带 2 字节大端关闭码 + UTF-8 原因；超长原因被截断为合法控制帧")
    void encodesCloseFrame() {
        byte[] f = WsFrameCodec.encodeClose(WsCloseCode.POLICY_VIOLATION, "10004 子账户权限不足");
        assertEquals(0x88, f[0] & 0xFF);
        int len = f[1] & 0x7F;
        assertTrue(len <= WsFrameCodec.MAX_CONTROL_PAYLOAD);
        assertEquals(WsCloseCode.POLICY_VIOLATION, ((f[2] & 0xFF) << 8) | (f[3] & 0xFF));

        byte[] longReason = WsFrameCodec.encodeClose(WsCloseCode.NORMAL, repeat('x', 300));
        assertTrue((longReason[1] & 0x7F) <= WsFrameCodec.MAX_CONTROL_PAYLOAD,
                "控制帧载荷不得超过 125 字节");
    }

    @Test
    @DisplayName("控制帧载荷超限即拒绝编码（防自造非法帧）")
    void rejectsOversizedControlFrame() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WsFrameCodec.encodePing(new byte[126]));
        assertTrue(e.getMessage().contains("125"));
    }

    // ---------------------------------------------------------------- 解码：正常路径

    @Test
    @DisplayName("解码客户端掩码文本帧（服务端唯一真实输入形态）")
    void decodesMaskedClientText() throws IOException {
        byte[] wire = WsTestSupport.clientText("{\"type\":\"subscribe\"}");
        WsFrame f = WsFrameCodec.readFrame(new ByteArrayInputStream(wire));
        assertEquals(WsFrame.Opcode.TEXT, f.opcode());
        assertTrue(f.fin());
        assertEquals("{\"type\":\"subscribe\"}", f.text());
    }

    @Test
    @DisplayName("解码客户端掩码控制帧：ping / pong / close（含关闭码与原因）")
    void decodesControlFrames() throws IOException {
        WsFrame ping = WsFrameCodec.readFrame(new ByteArrayInputStream(
                WsTestSupport.clientControl(WsFrame.Opcode.PING, true, "hb".getBytes(StandardCharsets.US_ASCII))));
        assertEquals(WsFrame.Opcode.PING, ping.opcode());
        assertEquals("hb", new String(ping.payload(), StandardCharsets.US_ASCII));

        WsFrame close = WsFrameCodec.readFrame(new ByteArrayInputStream(
                WsFrameCodecMaskedClose(WsCloseCode.NORMAL, "bye")));
        assertEquals(WsFrame.Opcode.CLOSE, close.opcode());
        assertEquals(WsCloseCode.NORMAL, WsFrameCodec.closeCodeOf(close));
        assertEquals("bye", WsFrameCodec.closeReasonOf(close));
    }

    @Test
    @DisplayName("掩码解码正确性：同一载荷换掩码键，解出的明文一致（证明 XOR 还原正确）")
    void unmaskingIsCorrect() throws IOException {
        String text = "行情 ru2601 14500.000000";
        for (int seed = 0; seed < 4; seed++) {
            byte[] wire = maskedTextWithSeed(text, seed);
            WsFrame f = WsFrameCodec.readFrame(new ByteArrayInputStream(wire));
            assertEquals(text, f.text());
        }
    }

    @Test
    @DisplayName("大载荷边界：65536 字节掩码帧可正确解码（64 位长度路径）")
    void decodesLargeMaskedFrame() throws IOException {
        String text = repeat('中', 22000);   // UTF-8 下 > 65535 字节
        byte[] wire = WsTestSupport.clientText(text);
        assertTrue(wire.length > 65536);
        WsFrame f = WsFrameCodec.readFrame(new ByteArrayInputStream(wire));
        assertEquals(text, f.text());
    }

    @Test
    @DisplayName("流正常结束返回 null（连接被对端关闭）")
    void eofReturnsNull() throws IOException {
        assertNull(WsFrameCodec.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    @DisplayName("帧中途断开抛 EOFException（区别于正常结束）")
    void truncatedFrameThrows() {
        byte[] wire = WsTestSupport.clientText("abcdef");
        byte[] truncated = java.util.Arrays.copyOf(wire, wire.length - 3);
        assertThrows(EOFException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(truncated)));
    }

    // ---------------------------------------------------------------- 解码：拒收路径

    @Test
    @DisplayName("客户端帧未掩码 → 1002（RFC 6455 §5.1 硬性要求）")
    void rejectsUnmaskedClientFrame() {
        WsProtocolException e = assertThrows(WsProtocolException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(
                        WsTestSupport.unmaskedClientText("hi"))));
        assertEquals(WsCloseCode.PROTOCOL_ERROR, e.closeCode());
    }

    @Test
    @DisplayName("RSV 位置位（未协商扩展）→ 1002")
    void rejectsRsvBits() {
        WsProtocolException e = assertThrows(WsProtocolException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(
                        WsTestSupport.rsvSetFrame("hi"))));
        assertEquals(WsCloseCode.PROTOCOL_ERROR, e.closeCode());
    }

    @Test
    @DisplayName("未知 opcode（0x3）→ 1002")
    void rejectsUnknownOpcode() {
        byte[] wire = WsTestSupport.clientFrame(0x3, true, "x".getBytes(StandardCharsets.UTF_8));
        WsProtocolException e = assertThrows(WsProtocolException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(wire)));
        assertEquals(WsCloseCode.PROTOCOL_ERROR, e.closeCode());
    }

    @Test
    @DisplayName("控制帧分片（ping FIN=0）→ 1002；控制帧载荷 >125 → 1002")
    void rejectsIllegalControlFrames() {
        WsProtocolException fragmented = assertThrows(WsProtocolException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(
                        WsTestSupport.clientControl(WsFrame.Opcode.PING, false, new byte[]{1}))));
        assertEquals(WsCloseCode.PROTOCOL_ERROR, fragmented.closeCode());

        WsProtocolException tooLong = assertThrows(WsProtocolException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(
                        WsTestSupport.clientControl(WsFrame.Opcode.PING, true, new byte[126]))));
        assertEquals(WsCloseCode.PROTOCOL_ERROR, tooLong.closeCode());
    }

    @Test
    @DisplayName("数据帧分片（text FIN=0）→ 1003（范围裁剪：不支持分片重组）")
    void rejectsFragmentedDataFrame() {
        byte[] wire = WsTestSupport.clientFrame(WsFrame.Opcode.TEXT.code(), false,
                "part".getBytes(StandardCharsets.UTF_8));
        WsProtocolException e = assertThrows(WsProtocolException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(wire)));
        assertEquals(WsCloseCode.UNSUPPORTED_DATA, e.closeCode());
    }

    @Test
    @DisplayName("文本帧非法 UTF-8 → 1007")
    void rejectsInvalidUtf8() {
        byte[] bad = {(byte) 0xC3, (byte) 0x28};          // 非法 UTF-8 序列
        byte[] wire = WsTestSupport.clientFrame(WsFrame.Opcode.TEXT.code(), true, bad);
        WsProtocolException e = assertThrows(WsProtocolException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(wire)));
        assertEquals(WsCloseCode.INVALID_PAYLOAD, e.closeCode());
    }

    @Test
    @DisplayName("声明长度超上限 → 1009（防超大帧内存耗尽）")
    void rejectsOversizedDeclaration() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        out.write(0x80 | 127);
        long huge = (long) WsFrameCodec.MAX_PAYLOAD_BYTES + 1;
        for (int i = 0; i < 8; i++) {
            out.write((int) ((huge >> (56 - 8 * i)) & 0xFF));
        }
        out.write(new byte[]{1, 2, 3, 4}, 0, 4);
        WsProtocolException e = assertThrows(WsProtocolException.class,
                () -> WsFrameCodec.readFrame(new ByteArrayInputStream(out.toByteArray())));
        assertEquals(WsCloseCode.TOO_BIG, e.closeCode());
    }

    // ---------------------------------------------------------------- 工具

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /** 用给定掩码种子构造客户端掩码文本帧（用于验证 XOR 还原）。 */
    private static byte[] maskedTextWithSeed(String text, int seed) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = {(byte) (0x10 + seed), (byte) (0x20 + seed), (byte) (0x30 + seed),
                (byte) (0x40 + seed)};
        byte[] out = new byte[2 + 4 + payload.length];
        out[0] = (byte) (0x80 | WsFrame.Opcode.TEXT.code());
        out[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, out, 2, 4);
        for (int i = 0; i < payload.length; i++) {
            out[6 + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return out;
    }

    /** 构造掩码的 close 帧（带码与原因）。 */
    private static byte[] WsFrameCodecMaskedClose(int code, String reason) {
        byte[] r = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + r.length];
        payload[0] = (byte) ((code >> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(r, 0, payload, 2, r.length);
        return WsTestSupport.clientFrame(WsFrame.Opcode.CLOSE.code(), true, payload);
    }

    @Test
    @DisplayName("常量与拒绝语义自检（防常量被误改）")
    void constantsAreStable() {
        assertEquals(1024 * 1024, WsFrameCodec.MAX_PAYLOAD_BYTES);
        assertEquals(125, WsFrameCodec.MAX_CONTROL_PAYLOAD);
        assertTrue(WsFrame.Opcode.TEXT.code() == 0x1);
        assertTrue(WsFrame.Opcode.CLOSE.isControl());
        assertTrue(WsFrame.Opcode.PONG.isControl());
        assertFalse(WsFrame.Opcode.TEXT.isControl());
        assertEquals(WsCloseCode.POLICY_VIOLATION, 1008);
        assertEquals(WsCloseCode.TRY_AGAIN_LATER, 1013);
    }
}
