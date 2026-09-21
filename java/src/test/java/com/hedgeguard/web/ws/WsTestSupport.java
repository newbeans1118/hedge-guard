package com.hedgeguard.web.ws;

import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 测试支撑（**完全离线**：仅回环 socket 与内存字节流，不依赖任何外部服务）。
 *
 * <p>核心能力：**构造客户端掩码帧**——客户端→服务端的帧必须掩码（RFC 6455 §5.1），
 * 这是服务端解析路径唯一"真实"的输入形态，故单测一律用它。
 */
final class WsTestSupport {

    /** 固定掩码键（测试可复现；真实客户端为随机 4 字节）。 */
    static final byte[] MASK = {0x12, 0x34, 0x56, 0x78};

    private WsTestSupport() {
    }

    /**
     * 构造客户端掩码帧。
     *
     * @param opcode  操作码
     * @param fin     终帧标志
     * @param payload 载荷
     * @return 线格式字节
     */
    static byte[] clientFrame(int opcode, boolean fin, byte[] payload) {
        int len = payload.length;
        int ext = len <= 125 ? 0 : len <= 0xFFFF ? 2 : 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(2 + ext + 4 + len);
        out.write((fin ? 0x80 : 0x00) | opcode);
        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 0; i < 8; i++) {
                out.write((int) (((long) len >> (56 - 8 * i)) & 0xFF));
            }
        }
        out.write(MASK, 0, 4);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ MASK[i & 3]);
        }
        out.write(masked, 0, len);
        return out.toByteArray();
    }

    /** 构造客户端掩码文本帧。 */
    static byte[] clientText(String text) {
        return clientFrame(WsFrame.Opcode.TEXT.code(), true, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 构造**未掩码**的客户端帧（用于验证"必须掩码"拒收）。 */
    static byte[] unmaskedClientText(String text) {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + p.length];
        out[0] = (byte) (0x80 | WsFrame.Opcode.TEXT.code());
        out[1] = (byte) p.length;
        System.arraycopy(p, 0, out, 2, p.length);
        return out;
    }

    /** 构造带 RSV 置位的帧（验证扩展未协商即拒）。 */
    static byte[] rsvSetFrame(String text) {
        byte[] f = clientText(text);
        f[0] = (byte) (f[0] | 0x40);
        return f;
    }

    /** 构造掩码的 ping/pong/close 控制帧。 */
    static byte[] clientControl(WsFrame.Opcode opcode, boolean fin, byte[] payload) {
        return clientFrame(opcode.code(), fin, payload);
    }

    /** 会话（固定权限版本，便于断言）。 */
    static Session session(long userId, String username) {
        return new Session("S-" + userId, userId, username, 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30));
    }

    /**
     * 内存会话存储，预置一个有效会话。
     *
     * @param userId 用户
     * @return 已含该用户会话的存储
     */
    static SessionStore storeWith(long userId) {
        SessionStore.InMemory store = new SessionStore.InMemory();
        store.save(session(userId, "u" + userId));
        return store;
    }

    /**
     * 标准升级请求头。
     *
     * @param path      目标路径（如 {@code /ws/alert}）
     * @param sessionId 会话 ID（{@code null} 表示不带凭据，用于验证 401）
     * @param key       {@code Sec-WebSocket-Key}
     * @param extra     额外头（可为空）
     * @return 请求报文字符串
     */
    static String upgradeRequest(String path, String sessionId, String key,
                                 Map<String, String> extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: 127.0.0.1\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Version: 13\r\n");
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        if (sessionId != null) {
            sb.append("X-HG-Session: ").append(sessionId).append("\r\n");
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /** 标准升级请求（含会话）。 */
    static String upgradeRequest(String path, String sessionId, String key) {
        return upgradeRequest(path, sessionId, key, Collections.emptyMap());
    }

    /** 常用官方测试向量 key（RFC 6455 §1.3）。 */
    static final String RFC_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    /** 常用官方测试向量 Accept。 */
    static final String RFC_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

    /**
     * 读取 HTTP 响应头（到空行为止）。
     *
     * @param in 输入流
     * @return 响应头文本
     * @throws IOException 读失败
     */
    static String readHttpHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int consecutive = 0;
        int b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (b == '\n') {
                consecutive++;
                if (consecutive == 2) {
                    break;
                }
            } else if (b != '\r') {
                consecutive = 0;
            }
        }
        return buf.toString(StandardCharsets.ISO_8859_1.name());
    }

    /** 便捷：建立一次已升级的原始连接（测试内部使用，失败即抛）。 */
    static Socket connectAndUpgrade(int port, String path, String sessionId) throws IOException {
        Socket s = new Socket(java.net.InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(5000);
        s.getOutputStream().write(upgradeRequest(path, sessionId, RFC_KEY)
                .getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        String head = readHttpHead(s.getInputStream());
        if (!head.startsWith("HTTP/1.1 101")) {
            s.close();
            throw new IOException("升级失败，响应头=" + head.trim());
        }
        return s;
    }

    /**
     * 便捷：读一帧**服务端→客户端**的帧（超时由 socket 的 soTimeout 控制）。
     *
     * <p>⚠️ 必须用本方法而非 {@link WsFrameCodec#readFrame}：后者是**服务端解码路径**，按 RFC 6455 §5.1
     * 强制要求**客户端帧必须掩码**；而服务端发来的帧是**不掩码**的，用服务端解码器读会误判为协议违规。
     * 测试端因此自备一个客户端视角的解码器（T-028 首轮实测暴露的真实差异）。
     */
    static WsFrame readFrame(Socket s) throws IOException {
        return readServerFrame(s.getInputStream());
    }

    /**
     * 客户端视角解码：读取服务端发来的帧（**不要求掩码**；若带掩码也兼容）。
     *
     * @param in 输入流
     * @return 帧；EOF 返回 {@code null}
     * @throws IOException 读失败
     */
    static WsFrame readServerFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = readByte(in);
        boolean fin = (b0 & 0x80) != 0;
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
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask, 4);
        }
        byte[] payload = new byte[(int) len];
        if (payload.length > 0) {
            readFully(in, payload, payload.length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i & 3]);
                }
            }
        }
        return new WsFrame(opcode, fin, payload);
    }

    private static int readByte(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) {
            throw new java.io.EOFException("帧在读满前流已结束");
        }
        return v;
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new java.io.EOFException("帧在读满前流已结束");
            }
            off += n;
        }
    }

    /** 便捷：发送掩码文本帧。 */
    static void sendText(Socket s, String text) throws IOException {
        s.getOutputStream().write(clientText(text));
        s.getOutputStream().flush();
    }

    /** 空额外头。 */
    static Map<String, String> noHeaders() {
        return new LinkedHashMap<>();
    }
}
