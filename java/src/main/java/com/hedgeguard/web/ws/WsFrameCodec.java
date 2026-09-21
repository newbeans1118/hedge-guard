package com.hedgeguard.web.ws;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * RFC 6455 帧编解码（**纯函数 / 无 I/O 依赖以外的状态**，可完全离线单测）。
 *
 * <p>实现范围（`CONTRACTS.md` §4.2.1 的范围裁剪）：
 * <ul>
 *   <li><b>发送</b>：文本帧（**不掩码**，RFC 6455 §5.1 服务端规则）、ping/pong/close 控制帧；</li>
 *   <li><b>接收</b>：**必须掩码**（客户端规则，未掩码 → `1002`）、控制帧（载荷 ≤ 125 B 且不得分片）、
 *       7/16/64 位长度解析；</li>
 *   <li><b>不做</b>：分片重组（收到非终止数据帧 → `1003`）、{@code permessage-deflate}（RSV 置位 → `1002`）、subprotocol。</li>
 * </ul>
 *
 * <p><b>安全要点</b>：单帧载荷上限 {@link #MAX_PAYLOAD_BYTES}，防超大帧内存耗尽（`1009`）；
 * 文本帧强制 UTF-8 校验（非法 → `1007`）。
 */
public final class WsFrameCodec {

    /** 单帧载荷上限（1 MiB）。行情/告警消息远小于此；超限即断，防内存耗尽。 */
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    /** 控制帧载荷上限（RFC 6455 §5.5 硬性规定 125）。 */
    public static final int MAX_CONTROL_PAYLOAD = 125;

    private WsFrameCodec() {
    }

    // ------------------------------------------------------------------ 编码（服务端 → 客户端）

    /**
     * 编码文本帧（FIN=1，不掩码）。
     *
     * @param text 文本（UTF-8）
     * @return 线格式字节
     */
    public static byte[] encodeText(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        return encode(WsFrame.Opcode.TEXT, payload);
    }

    /**
     * 编码 ping 帧。
     *
     * @param payload 载荷（≤ 125 B，可为空）
     * @return 线格式字节
     */
    public static byte[] encodePing(byte[] payload) {
        return encode(WsFrame.Opcode.PING, payload);
    }

    /**
     * 编码 pong 帧。
     *
     * @param payload 载荷（通常回显 ping 的载荷）
     * @return 线格式字节
     */
    public static byte[] encodePong(byte[] payload) {
        return encode(WsFrame.Opcode.PONG, payload);
    }

    /**
     * 编码关闭帧（2 字节大端关闭码 + UTF-8 原因）。
     *
     * @param closeCode 关闭码（见 {@link WsCloseCode}）
     * @param reason    原因（可为 {@code null}；**不得含敏感信息**）
     * @return 线格式字节
     */
    public static byte[] encodeClose(int closeCode, String reason) {
        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        int maxReason = MAX_CONTROL_PAYLOAD - 2;
        if (reasonBytes.length > maxReason) {
            // 截断到合法长度，避免自己造出非法控制帧
            byte[] truncated = new byte[maxReason];
            System.arraycopy(reasonBytes, 0, truncated, 0, maxReason);
            reasonBytes = truncated;
        }
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((closeCode >> 8) & 0xFF);
        payload[1] = (byte) (closeCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return encode(WsFrame.Opcode.CLOSE, payload);
    }

    /**
     * 编码任意帧（FIN=1、不掩码）。
     *
     * @param opcode  操作码
     * @param payload 载荷
     * @return 线格式字节
     */
    public static byte[] encode(WsFrame.Opcode opcode, byte[] payload) {
        if (opcode.isControl() && payload.length > MAX_CONTROL_PAYLOAD) {
            throw new IllegalArgumentException("控制帧载荷不得超过 " + MAX_CONTROL_PAYLOAD
                    + " 字节：opcode=" + opcode + " len=" + payload.length
                    + "；复现=检查调用点（RFC 6455 §5.5）");
        }
        int len = payload.length;
        int headerLen = 2 + (len <= 125 ? 0 : len <= 0xFFFF ? 2 : 8);
        byte[] out = new byte[headerLen + len];
        out[0] = (byte) (0x80 | opcode.code());               // FIN=1，RSV=0
        if (len <= 125) {
            out[1] = (byte) len;
        } else if (len <= 0xFFFF) {
            out[1] = (byte) 126;
            out[2] = (byte) ((len >> 8) & 0xFF);
            out[3] = (byte) (len & 0xFF);
        } else {
            out[1] = (byte) 127;
            long l = len;
            for (int i = 0; i < 8; i++) {
                out[2 + i] = (byte) ((l >> (56 - 8 * i)) & 0xFF);
            }
        }
        System.arraycopy(payload, 0, out, headerLen, len);
        return out;
    }

    // ------------------------------------------------------------------ 解码（客户端 → 服务端）

    /**
     * 从输入流读取一帧（阻塞读满）。
     *
     * @param in 输入流
     * @return 帧；**流正常结束（EOF）返回 {@code null}**
     * @throws IOException          读失败
     * @throws WsProtocolException  协议违规（携带应回给对端的关闭码）
     */
    public static WsFrame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = readByte(in);
        boolean fin = (b0 & 0x80) != 0;
        int rsv = b0 & 0x70;
        if (rsv != 0) {
            // RSV 置位 = 使用了未协商的扩展（permessage-deflate 等）
            throw new WsProtocolException(WsCloseCode.PROTOCOL_ERROR,
                    "RSV 位非 0（未协商扩展）");
        }
        WsFrame.Opcode opcode = WsFrame.Opcode.of(b0 & 0x0F);
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte(in);
            }
            if (len < 0) {
                throw new WsProtocolException(WsCloseCode.PROTOCOL_ERROR, "64 位长度最高位被置位");
            }
        }
        if (!masked) {
            // RFC 6455 §5.1：客户端发往服务端的帧**必须**掩码
            throw new WsProtocolException(WsCloseCode.PROTOCOL_ERROR, "客户端帧未掩码");
        }
        if (opcode.isControl()) {
            if (!fin) {
                throw new WsProtocolException(WsCloseCode.PROTOCOL_ERROR, "控制帧不得分片（FIN=0）");
            }
            if (len > MAX_CONTROL_PAYLOAD) {
                throw new WsProtocolException(WsCloseCode.PROTOCOL_ERROR, "控制帧载荷超过 125 字节");
            }
        } else if (!fin) {
            // 本系统不接收客户端分片数据帧（范围裁剪）
            throw new WsProtocolException(WsCloseCode.UNSUPPORTED_DATA, "不支持分片数据帧");
        }
        if (len > MAX_PAYLOAD_BYTES) {
            throw new WsProtocolException(WsCloseCode.TOO_BIG,
                    "帧载荷超过上限 " + MAX_PAYLOAD_BYTES + " 字节");
        }
        byte[] mask = new byte[4];
        readFully(in, mask, 4);
        byte[] payload = new byte[(int) len];
        if (payload.length > 0) {
            readFully(in, payload, payload.length);
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i & 3]);
            }
        }
        if (opcode == WsFrame.Opcode.TEXT) {
            requireValidUtf8(payload);
        }
        return new WsFrame(opcode, fin, payload);
    }

    /**
     * 校验载荷为合法 UTF-8。
     *
     * @param payload 载荷
     * @throws WsProtocolException 非法时抛 {@code 1007}
     */
    public static void requireValidUtf8(byte[] payload) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload));
        } catch (CharacterCodingException e) {
            throw new WsProtocolException(WsCloseCode.INVALID_PAYLOAD, "文本帧载荷非合法 UTF-8");
        }
    }

    /**
     * 解析关闭帧载荷中的关闭码（无载荷时返回 {@link WsCloseCode#NORMAL}）。
     *
     * @param frame 关闭帧
     * @return 关闭码
     */
    public static int closeCodeOf(WsFrame frame) {
        if (frame.opcode() != WsFrame.Opcode.CLOSE || frame.length() < 2) {
            return WsCloseCode.NORMAL;
        }
        byte[] p = frame.payload();
        return ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
    }

    /**
     * 解析关闭帧载荷中的原因（可能为空串）。
     *
     * @param frame 关闭帧
     * @return 原因
     */
    public static String closeReasonOf(WsFrame frame) {
        if (frame.opcode() != WsFrame.Opcode.CLOSE || frame.length() <= 2) {
            return "";
        }
        byte[] p = frame.payload();
        return new String(p, 2, p.length - 2, StandardCharsets.UTF_8);
    }

    private static int readByte(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) {
            throw new EOFException("WebSocket 帧在读满前流已结束");
        }
        return v;
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new EOFException("WebSocket 帧在读满前流已结束");
            }
            off += n;
        }
    }
}
