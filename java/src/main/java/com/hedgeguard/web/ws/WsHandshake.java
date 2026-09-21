package com.hedgeguard.web.ws;

import com.hedgeguard.web.RequestContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WebSocket 握手（RFC 6455 §4）：**手工完成 HTTP Upgrade**——这是 `ARCHITECTURE.md` §7.1.11 定调 A′ 的核心原因
 * （`com.sun.net.httpserver` 无协议升级能力，见 `CONTRACTS.md` §4.2.1(1) 的 `javap` 证据）。
 *
 * <p>本类为**纯逻辑**（输入流 → {@link RequestContext}；key → Accept 响应字节），可完全离线单测。
 * 鉴权**复用** {@link com.hedgeguard.web.AuthFilter}（`HGSESSION` Cookie / `X-HG-Session` 头，与 REST 同口径）：
 * 握手期未通过 → **HTTP 401**（不升级），见 `CONTRACTS.md` §4.1.2 `W-4`。
 */
public final class WsHandshake {

    /** RFC 6455 §1.3 规定的固定 GUID。 */
    public static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** 握手请求头上限（8 KiB），防超长头耗尽内存。 */
    public static final int MAX_HEADER_BYTES = 8192;

    /** 握手请求头行数上限。 */
    public static final int MAX_HEADER_LINES = 64;

    private WsHandshake() {
    }

    /**
     * 计算 {@code Sec-WebSocket-Accept}（RFC 6455 §4.2.2）：
     * {@code base64(SHA-1(key + GUID))}。
     *
     * <p>官方测试向量（RFC 6455 §1.3）：{@code dGhlIHNhbXBsZSBub25jZQ==} → {@code s3pPLMBiTxaQ9kYGzzhZRbK+xOo=}。
     *
     * @param secWebSocketKey 客户端 {@code Sec-WebSocket-Key}
     * @return Accept 值
     */
    public static String acceptKey(String secWebSocketKey) {
        if (secWebSocketKey == null || secWebSocketKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Sec-WebSocket-Key 不得为空；复现=检查握手请求头");
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((secWebSocketKey.trim() + WS_GUID)
                    .getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-1；到此即环境异常
            throw new IllegalStateException("JDK 缺少 SHA-1 实现；复现=检查 JRE 完整性", e);
        }
    }

    /**
     * 读取并解析握手请求（读到空行为止）。
     *
     * @param in       连接输入流
     * @param traceId  追踪号（由服务端生成）
     * @param clientIp 对端地址
     * @return 请求上下文；**流在首个字节前结束（EOF）返回 {@code null}**
     * @throws IOException          读失败
     * @throws WsProtocolException  请求头非法/超长（携带关闭码，实际尚未升级，仅用于统一错误处理）
     */
    public static RequestContext readRequest(InputStream in, String traceId, String clientIp)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int b;
        int consecutiveNewlines = 0;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) {
                throw new WsProtocolException(WsCloseCode.POLICY_VIOLATION,
                        "握手请求头超过 " + MAX_HEADER_BYTES + " 字节");
            }
            if (b == '\n') {
                consecutiveNewlines++;
                if (consecutiveNewlines == 2) {
                    break;
                }
            } else if (b != '\r') {
                consecutiveNewlines = 0;
            }
        }
        if (buf.size() == 0) {
            return null;
        }
        return parse(buf.toString(StandardCharsets.ISO_8859_1.name()), traceId, clientIp);
    }

    /**
     * 解析握手报文（供单测直接调用）。
     *
     * @param raw      原始报文字符串
     * @param traceId  追踪号
     * @param clientIp 对端地址
     * @return 请求上下文
     */
    public static RequestContext parse(String raw, String traceId, String clientIp) {
        String[] lines = raw.split("\r?\n");
        if (lines.length == 0 || lines[0].trim().isEmpty()) {
            throw new WsProtocolException(WsCloseCode.POLICY_VIOLATION, "握手请求行为空");
        }
        if (lines.length > MAX_HEADER_LINES) {
            throw new WsProtocolException(WsCloseCode.POLICY_VIOLATION, "握手请求头行数超限");
        }
        String[] parts = lines[0].trim().split("\\s+");
        if (parts.length < 3) {
            throw new WsProtocolException(WsCloseCode.POLICY_VIOLATION, "握手请求行格式非法");
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        String path = target;
        String rawQuery = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            rawQuery = target.substring(q + 1);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }
        return new RequestContext(method, path, rawQuery, headers, "", traceId, clientIp);
    }

    /**
     * 判断请求是否为合法的 WebSocket 升级请求。
     *
     * @param ctx 请求上下文
     * @return 合法返回 {@code null}，否则返回**拒绝原因**（供 400 响应体）
     */
    public static String validateUpgrade(RequestContext ctx) {
        if (!"GET".equals(ctx.method())) {
            return "仅支持 GET 升级";
        }
        String upgrade = ctx.header("Upgrade");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade.trim())) {
            return "缺少 Upgrade: websocket";
        }
        String connection = ctx.header("Connection");
        if (connection == null || !connection.toLowerCase(Locale.ROOT).contains("upgrade")) {
            return "缺少 Connection: Upgrade";
        }
        String version = ctx.header("Sec-WebSocket-Version");
        if (version == null || !"13".equals(version.trim())) {
            return "仅支持 Sec-WebSocket-Version: 13";
        }
        String key = ctx.header("Sec-WebSocket-Key");
        if (key == null || key.trim().isEmpty()) {
            return "缺少 Sec-WebSocket-Key";
        }
        // 请求头中含子协议/扩展请求时明确拒绝（范围裁剪：不协商 subprotocol / permessage-deflate）
        if (ctx.header("Sec-WebSocket-Extensions") != null) {
            return "不支持扩展协商（permessage-deflate 等）";
        }
        return null;
    }

    /**
     * 生成 101 升级响应。
     *
     * @param acceptKey {@link #acceptKey(String)} 的结果
     * @return 响应字节
     */
    public static byte[] upgradeResponse(String acceptKey) {
        String head = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey + "\r\n"
                + "\r\n";
        return head.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 生成握手失败响应（**不升级**，见 `CONTRACTS.md` §4.1.2 `W-4`）。
     *
     * @param status   HTTP 状态（400 / 401 / 404 / 405）
     * @param message  提示（**不得含敏感信息**）
     * @param traceId  追踪号
     * @return 响应字节
     */
    public static byte[] errorResponse(int status, String message, String traceId) {
        String reason = status == 401 ? "Unauthorized" : status == 404 ? "Not Found"
                : status == 405 ? "Method Not Allowed" : "Bad Request";
        // 用统一信封承载 traceId（与 REST 一致，便于前端关联日志）
        int code = status == 401 ? 10006 : 10001;
        String body = "{\"code\":" + code + ",\"message\":\"" + escape(message)
                + "\",\"traceId\":\"" + escape(traceId) + "\"}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[headBytes.length + bodyBytes.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(bodyBytes, 0, out, headBytes.length, bodyBytes.length);
        return out;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
