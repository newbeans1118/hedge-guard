package com.hedgeguard.web.ws;

/**
 * WebSocket 帧（RFC 6455 §5.2）。
 *
 * <p>形态依据 {@code ARCHITECTURE.md} §7.1.11 定调的 **A′**（自实现 RFC 6455，零依赖）；
 * 范围裁剪见 {@code java/CONTRACTS.md} §4.2.1：**不支持分片、不支持 {@code permessage-deflate}、不支持 subprotocol**。
 *
 * <p>不可变值对象：编解码由 {@link WsFrameCodec} 负责，本类只承载结构。
 */
public final class WsFrame {

    /** 操作码（RFC 6455 §5.2）。 */
    public enum Opcode {

        /** 延续帧（{@code 0x0}）。本系统**不接收**客户端分片，故解出该操作码即视为协议错误。 */
        CONTINUATION(0x0),
        /** 文本帧（{@code 0x1}）。 */
        TEXT(0x1),
        /** 二进制帧（{@code 0x2}）。本系统不接收客户端二进制数据帧。 */
        BINARY(0x2),
        /** 关闭帧（{@code 0x8}，控制帧）。 */
        CLOSE(0x8),
        /** ping（{@code 0x9}，控制帧；由 {@code CFG-32 hg.web.ws.heartbeat_ms} 驱动，默认 30 s）。 */
        PING(0x9),
        /** pong（{@code 0xA}，控制帧）。 */
        PONG(0xA);

        private final int code;

        Opcode(int code) {
            this.code = code;
        }

        /** 线格式操作码。 */
        public int code() {
            return code;
        }

        /** 是否控制帧（RFC 6455 §5.5：载荷 ≤ 125 字节且不得分片）。 */
        public boolean isControl() {
            return code >= 0x8;
        }

        /**
         * 由线格式操作码解析。
         *
         * @param code 操作码
         * @return 操作码枚举
         * @throws WsProtocolException 未知操作码（应关闭连接，码 {@code 1002}）
         */
        public static Opcode of(int code) {
            for (Opcode o : values()) {
                if (o.code == code) {
                    return o;
                }
            }
            throw new WsProtocolException(WsCloseCode.PROTOCOL_ERROR,
                    "未知操作码 0x" + Integer.toHexString(code));
        }
    }

    private final Opcode opcode;
    private final boolean fin;
    private final byte[] payload;

    /**
     * 构造。
     *
     * @param opcode  操作码
     * @param fin     是否终帧（本系统发送恒为 {@code true}；接收非终数据帧视为不支持）
     * @param payload 载荷（不复制，调用方须勿再改动）
     */
    public WsFrame(Opcode opcode, boolean fin, byte[] payload) {
        this.opcode = opcode;
        this.fin = fin;
        this.payload = payload;
    }

    /** 操作码。 */
    public Opcode opcode() {
        return opcode;
    }

    /** 是否终帧。 */
    public boolean fin() {
        return fin;
    }

    /** 载荷字节（只读语义）。 */
    public byte[] payload() {
        return payload;
    }

    /**
     * 载荷的 UTF-8 文本。
     *
     * <p>UTF-8 合法性已在 {@link WsFrameCodec#readFrame(java.io.InputStream)} 内校验（非法 → `1007`），
     * 故此处直接解码。
     *
     * @return 文本
     */
    public String text() {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 载荷长度。 */
    public int length() {
        return payload.length;
    }
}
