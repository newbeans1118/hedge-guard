package com.hedgeguard.bridge;

/**
 * HGCP 帧编解码（{@code ARCHITECTURE.md} §4.2.2；与 C++ 侧 {@code cpp/src/hgcp/include/hg/hgcp/frame.h}
 * <b>逐字同源</b>——常量、布局、CRC 覆盖区间均取自该文件，不得单侧改动）。
 *
 * <p>布局（<b>全部大端</b>，帧头固定 32 字节）：
 * <pre>
 *   MAGIC(4) | VER(2) | TYPE(2) | FLAGS(2) | RESERVED(2) | SEQ(8) | MSG_ID(8) | LEN(4)
 *   | PAYLOAD(LEN) | CRC32(4)
 * </pre>
 *
 * <p>CRC32 = IEEE 802.3（多项式 {@code 0xEDB88320}，init/final {@code 0xFFFFFFFF}），
 * 覆盖区间为 <b>VER..PAYLOAD</b>（<b>不含</b> MAGIC 与 CRC 自身）。
 * 自测向量（与 C++ 侧同一断言）：{@code crc32("123456789") == 0xCBF43926}。
 *
 * <p><b>范围</b>：本类只处理帧，<b>不含</b>消息语义、<b>不依赖</b> protobuf ⇒ 可独立单测。
 */
public final class HgcpFrame {

    /** MAGIC 字面量 {@code 'H','G','C','P'}。 */
    public static final byte[] MAGIC = {0x48, 0x47, 0x43, 0x50};

    /** 线格式版本（本版 {@code 0x0100}）。 */
    public static final int WIRE_VERSION = 0x0100;

    /** 帧头固定 32 字节。 */
    public static final int HEADER_SIZE = 32;

    /** CRC32 尾长度 4 字节。 */
    public static final int CRC_SIZE = 4;

    /** payload 上限 1 MiB（超限 ⇒ {@code 0x0308 ERR_FRAME_TOO_LARGE}）。 */
    public static final int MAX_PAYLOAD_SIZE = 1024 * 1024;

    /** FLAGS bit0 COMPRESSED（保留，恒 0）。 */
    public static final int FLAG_COMPRESSED = 1 << 0;
    /** FLAGS bit1 IS_REPLY。 */
    public static final int FLAG_IS_REPLY = 1 << 1;
    /** FLAGS bit2 IS_ERROR。 */
    public static final int FLAG_IS_ERROR = 1 << 2;
    /** FLAGS bit3 IS_RESUME_DATA。 */
    public static final int FLAG_IS_RESUME_DATA = 1 << 3;
    /** FLAGS bit4 NEED_ACK。 */
    public static final int FLAG_NEED_ACK = 1 << 4;

    private static final int[] CRC_TABLE = buildCrcTable();

    private HgcpFrame() {
    }

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            table[i] = c;
        }
        return table;
    }

    /**
     * CRC-32（IEEE 802.3）。init/final 均为 {@code 0xFFFFFFFF}。
     *
     * @param data 数据
     * @return CRC32
     */
    public static int crc32(byte[] data) {
        return crc32(data, 0, data.length);
    }

    /**
     * CRC-32（IEEE 802.3）指定区间。
     *
     * @param data   数据
     * @param offset 起始
     * @param length 长度
     * @return CRC32
     */
    public static int crc32(byte[] data, int offset, int length) {
        int c = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            c = CRC_TABLE[(c ^ (data[i] & 0xFF)) & 0xFF] ^ (c >>> 8);
        }
        return c ^ 0xFFFFFFFF;
    }

    /**
     * 帧头（可变；编码时 {@code magic} 由 {@link #encode(Header, byte[])} 恒定写入）。
     */
    public static final class Header {
        /** 线格式版本。 */
        public int ver = WIRE_VERSION;
        /** 消息类型码（§4.2.3）。 */
        public int type;
        /** FLAGS bitmask。 */
        public int flags;
        /** 恒 0。 */
        public int reserved;
        /** 通道级单调递增，从 1 开始。 */
        public long seq;
        /** 消息唯一 id（请求-回执关联）。 */
        public long msgId;
        /** payload 字节数。 */
        public int len;

        /** 无参构造。 */
        public Header() {
        }

        /**
         * 构造。
         *
         * @param type  消息类型码
         * @param flags FLAGS
         * @param seq   通道序号
         * @param msgId 消息 id
         */
        public Header(int type, int flags, long seq, long msgId) {
            this.type = type;
            this.flags = flags;
            this.seq = seq;
            this.msgId = msgId;
        }
    }

    /** 解码/校验结果（对应 §4.3.2 组件错误码）。 */
    public enum Status {
        /** 成功。 */
        OK(0),
        /** 缓冲不足一帧（流式，非错误）。 */
        NEED_MORE(0),
        /** {@code 0x0102 ERR_BAD_MAGIC}。 */
        BAD_MAGIC(0x0102),
        /** {@code 0x0101 ERR_VERSION_MISMATCH}。 */
        BAD_VERSION(0x0101),
        /** RESERVED ≠ 0（协议不变量违规）。 */
        BAD_RESERVED(0x0104),
        /** {@code 0x0308 ERR_FRAME_TOO_LARGE}。 */
        FRAME_TOO_LARGE(0x0308),
        /** {@code 0x0103 ERR_CRC_MISMATCH}。 */
        CRC_MISMATCH(0x0103);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        /** §4.3.2 组件错误码（{@code OK}/{@code NEED_MORE} 为 0）。 */
        public int componentCode() {
            return code;
        }
    }

    /** 解码产出（状态 + 帧头 + payload）。 */
    public static final class Decoded {
        /** 状态。 */
        public final Status status;
        /** 帧头（仅 {@code status == OK} 时有效）。 */
        public final Header header;
        /** payload（仅 {@code status == OK} 时有效；可为长度 0）。 */
        public final byte[] payload;
        /** 本帧消耗的总字节数（仅 {@code status == OK} 时有效）。 */
        public final int consumed;

        Decoded(Status status, Header header, byte[] payload, int consumed) {
            this.status = status;
            this.header = header;
            this.payload = payload;
            this.consumed = consumed;
        }

        static Decoded fail(Status s) {
            return new Decoded(s, null, null, 0);
        }
    }

    /**
     * 编码整帧（HEADER(32) + PAYLOAD + CRC32(4)）。
     *
     * @param h          帧头（{@code len} 由 {@code payload} 推出，不取 {@code h.len}）
     * @param payload    payload，可为 {@code null} 或长度 0
     * @return 整帧字节
     * @throws IllegalArgumentException payload 超过 {@link #MAX_PAYLOAD_SIZE}
     */
    public static byte[] encode(Header h, byte[] payload) {
        int len = (payload == null) ? 0 : payload.length;
        if (len > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("payload 超过上限 " + MAX_PAYLOAD_SIZE + "：" + len);
        }
        byte[] out = new byte[HEADER_SIZE + len + CRC_SIZE];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        writeU16(out, 4, h.ver);
        writeU16(out, 6, h.type);
        writeU16(out, 8, h.flags);
        writeU16(out, 10, h.reserved);
        writeU64(out, 12, h.seq);
        writeU64(out, 20, h.msgId);
        writeU32(out, 28, len);
        if (len > 0) {
            System.arraycopy(payload, 0, out, HEADER_SIZE, len);
        }
        // CRC 覆盖 VER..PAYLOAD：offset 4，长度 = 28 - 4 + len
        int crc = crc32(out, 4, (HEADER_SIZE - 4) + len);
        writeU32(out, HEADER_SIZE + len, crc);
        return out;
    }

    /**
     * 从缓冲解码一帧（流式：支持粘包/半包）。
     *
     * @param buf    缓冲
     * @param offset 起始
     * @param limit  可用上界（exclusive）
     * @return 解码结果；{@code NEED_MORE} 表示缓冲不足
     */
    public static Decoded decode(byte[] buf, int offset, int limit) {
        int avail = limit - offset;
        if (avail < HEADER_SIZE) {
            return Decoded.fail(Status.NEED_MORE);
        }
        for (int i = 0; i < 4; i++) {
            if (buf[offset + i] != MAGIC[i]) {
                return Decoded.fail(Status.BAD_MAGIC);
            }
        }
        Header h = new Header();
        h.ver = readU16(buf, offset + 4);
        if (h.ver != WIRE_VERSION) {
            return Decoded.fail(Status.BAD_VERSION);
        }
        h.type = readU16(buf, offset + 6);
        h.flags = readU16(buf, offset + 8);
        h.reserved = readU16(buf, offset + 10);
        if (h.reserved != 0) {
            return Decoded.fail(Status.BAD_RESERVED);
        }
        h.seq = readU64(buf, offset + 12);
        h.msgId = readU64(buf, offset + 20);
        h.len = readU32(buf, offset + 28);
        if (h.len > MAX_PAYLOAD_SIZE) {
            return Decoded.fail(Status.FRAME_TOO_LARGE);
        }
        int total = HEADER_SIZE + h.len + CRC_SIZE;
        if (avail < total) {
            return Decoded.fail(Status.NEED_MORE);
        }
        int expect = readU32(buf, offset + HEADER_SIZE + h.len);
        int actual = crc32(buf, offset + 4, (HEADER_SIZE - 4) + h.len);
        if (expect != actual) {
            return Decoded.fail(Status.CRC_MISMATCH);
        }
        byte[] payload = new byte[h.len];
        System.arraycopy(buf, offset + HEADER_SIZE, payload, 0, h.len);
        return new Decoded(Status.OK, h, payload, total);
    }

    /** 写 uint16 大端。 */
    public static void writeU16(byte[] out, int off, int v) {
        out[off] = (byte) ((v >>> 8) & 0xFF);
        out[off + 1] = (byte) (v & 0xFF);
    }

    /** 写 uint32 大端。 */
    public static void writeU32(byte[] out, int off, int v) {
        out[off] = (byte) ((v >>> 24) & 0xFF);
        out[off + 1] = (byte) ((v >>> 16) & 0xFF);
        out[off + 2] = (byte) ((v >>> 8) & 0xFF);
        out[off + 3] = (byte) (v & 0xFF);
    }

    /** 写 uint64 大端。 */
    public static void writeU64(byte[] out, int off, long v) {
        for (int i = 0; i < 8; i++) {
            out[off + i] = (byte) ((v >>> (56 - 8 * i)) & 0xFF);
        }
    }

    /** 读 uint16 大端。 */
    public static int readU16(byte[] in, int off) {
        return ((in[off] & 0xFF) << 8) | (in[off + 1] & 0xFF);
    }

    /** 读 uint32 大端。 */
    public static int readU32(byte[] in, int off) {
        return ((in[off] & 0xFF) << 24) | ((in[off + 1] & 0xFF) << 16)
                | ((in[off + 2] & 0xFF) << 8) | (in[off + 3] & 0xFF);
    }

    /** 读 uint64 大端。 */
    public static long readU64(byte[] in, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (in[off + i] & 0xFFL);
        }
        return v;
    }
}
