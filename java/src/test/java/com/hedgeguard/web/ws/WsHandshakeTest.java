package com.hedgeguard.web.ws;

import com.hedgeguard.web.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 握手单测（`T-028` 验收 1/4；**完全离线**）。
 *
 * <p>覆盖：官方 Accept 向量、请求解析（方法/路径/查询串/头名小写）、升级合法性校验
 * （缺 {@code Upgrade}/{@code Connection}/版本、扩展请求拒收）、失败响应（**握手期 401**、`10006`、带 traceId）。
 */
class WsHandshakeTest {

    private static final String TRACE = "HGTESTWS";

    @Test
    @DisplayName("官方向量：acceptKey 与 RFC 6455 §1.3 一致；空 key 拒绝")
    void acceptKeyMatchesSpec() {
        assertEquals(WsTestSupport.RFC_ACCEPT, WsHandshake.acceptKey(WsTestSupport.RFC_KEY));
        assertEquals(WsTestSupport.RFC_ACCEPT, WsHandshake.acceptKey(" " + WsTestSupport.RFC_KEY + " "));
        assertThrows(IllegalArgumentException.class, () -> WsHandshake.acceptKey(null));
        assertThrows(IllegalArgumentException.class, () -> WsHandshake.acceptKey("  "));
    }

    @Test
    @DisplayName("解析握手请求：方法大写、路径与查询串分离、头名统一小写、空白剔除")
    void parsesRequest() {
        Map<String, String> extra = new TreeMap<>();
        extra.put("Sec-WebSocket-Protocol", "hg.v1");
        String raw = WsTestSupport.upgradeRequest("/ws/quote", "S-7", WsTestSupport.RFC_KEY, extra);

        RequestContext ctx = WsHandshake.parse(raw, TRACE, "127.0.0.1");
        assertEquals("GET", ctx.method());
        assertEquals("/ws/quote", ctx.path());
        assertEquals(WsTestSupport.RFC_KEY, ctx.header("sec-websocket-key"));
        assertEquals("websocket", ctx.header("Upgrade"));
        assertEquals("hg.v1", ctx.header("sec-websocket-protocol"));
        assertEquals("127.0.0.1", ctx.clientIp());
        assertEquals(TRACE, ctx.traceId());
        assertNull(ctx.session(), "解析阶段不得建立会话（鉴权是后续独立步骤）");
    }

    @Test
    @DisplayName("路径带查询串时正确分离（WS 端点不使用查询串，但不得因此解析失败）")
    void parsesQueryString() {
        String raw = "GET /ws/alert?since=1 HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n\r\n";
        RequestContext ctx = WsHandshake.parse(raw, TRACE, "127.0.0.1");
        assertEquals("/ws/alert", ctx.path());
        assertEquals("1", ctx.queryParam("since"));
    }

    @Test
    @DisplayName("readRequest 读到空行为止；首个字节前 EOF 返回 null；非 GET 校验失败")
    void readsRequestFromStream() throws IOException {
        String raw = WsTestSupport.upgradeRequest("/ws/quote", null, WsTestSupport.RFC_KEY);
        RequestContext ctx = WsHandshake.readRequest(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)), TRACE, "ip");
        assertNotNull(ctx);
        assertEquals("/ws/quote", ctx.path());

        assertNull(WsHandshake.readRequest(new ByteArrayInputStream(new byte[0]), TRACE, "ip"));

        String post = "POST /ws/quote HTTP/1.1\r\nUpgrade: websocket\r\n\r\n";
        RequestContext ctx2 = WsHandshake.parse(post, TRACE, "ip");
        assertEquals("仅支持 GET 升级", WsHandshake.validateUpgrade(ctx2));
    }

    @Test
    @DisplayName("升级校验：合法请求通过；缺 Upgrade / 缺 Connection / 版本非 13 / 无 Key / 请求扩展 各自被拒")
    void validatesUpgrade() {
        String ok = WsTestSupport.upgradeRequest("/ws/quote", "S-1", WsTestSupport.RFC_KEY);
        assertNull(WsHandshake.validateUpgrade(WsHandshake.parse(ok, TRACE, "ip")));

        assertRejected("GET /ws/quote HTTP/1.1\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: k\r\n\r\n", "Upgrade");
        assertRejected("GET /ws/quote HTTP/1.1\r\nUpgrade: websocket\r\n"
                + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: k\r\n\r\n", "Connection");
        assertRejected("GET /ws/quote HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 8\r\nSec-WebSocket-Key: k\r\n\r\n", "13");
        assertRejected("GET /ws/quote HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n", "Key");
        // 扩展协商（permessage-deflate）属范围外，明确拒绝而不是静默忽略
        assertRejected("GET /ws/quote HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: k\r\n"
                + "Sec-WebSocket-Extensions: permessage-deflate\r\n\r\n", "扩展");
    }

    @Test
    @DisplayName("101 响应含 Upgrade/Connection/Sec-WebSocket-Accept，且无 Content-Length（无响应体）")
    void buildsUpgradeResponse() {
        String resp = new String(WsHandshake.upgradeResponse(WsTestSupport.RFC_ACCEPT),
                StandardCharsets.US_ASCII);
        assertTrue(resp.startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
        assertTrue(resp.contains("Upgrade: websocket\r\n"));
        assertTrue(resp.contains("Connection: Upgrade\r\n"));
        assertTrue(resp.contains("Sec-WebSocket-Accept: " + WsTestSupport.RFC_ACCEPT + "\r\n"));
        assertTrue(resp.endsWith("\r\n\r\n"));
        assertFalse(resp.contains("Content-Length"));
    }

    @Test
    @DisplayName("失败响应：401 携带业务码 10006 与 traceId（与 REST 同口径），且状态行正确")
    void buildsErrorResponses() {
        String unauthorized = new String(
                WsHandshake.errorResponse(401, "未提供有效会话凭据：请先登录", TRACE),
                StandardCharsets.UTF_8);
        assertTrue(unauthorized.startsWith("HTTP/1.1 401 Unauthorized\r\n"));
        assertTrue(unauthorized.contains("\"code\":10006"));
        assertTrue(unauthorized.contains("\"traceId\":\"" + TRACE + "\""));
        assertTrue(unauthorized.contains("Connection: close"));

        assertTrue(new String(WsHandshake.errorResponse(400, "缺少 Upgrade: websocket", TRACE),
                StandardCharsets.UTF_8).startsWith("HTTP/1.1 400 Bad Request"));
        assertTrue(new String(WsHandshake.errorResponse(404, "no", TRACE),
                StandardCharsets.UTF_8).startsWith("HTTP/1.1 404 Not Found"));
    }

    @Test
    @DisplayName("JSON 中包含引号/反斜杠时正确转义（防响应体被注入破坏）")
    void escapesJsonInErrorBody() {
        String body = new String(WsHandshake.errorResponse(400, "bad \"x\" \\ y", TRACE),
                StandardCharsets.UTF_8);
        assertTrue(body.contains("bad \\\"x\\\" \\\\ y"));
    }

    @Test
    @DisplayName("仅放行 /ws/quote 与 /ws/alert 两个升级路径")
    void allowedPathsAreExactlyTwo() {
        assertEquals(2, WsServer.ALLOWED_PATHS.size());
        assertTrue(WsServer.ALLOWED_PATHS.contains("/ws/quote"));
        assertTrue(WsServer.ALLOWED_PATHS.contains("/ws/alert"));
        assertFalse(WsServer.ALLOWED_PATHS.contains("/ws/other"));
    }

    @Test
    @DisplayName("超长请求头被拒（防内存耗尽）；非法请求行被拒")
    void rejectsAbusiveHeaders() {
        StringBuilder huge = new StringBuilder("GET /ws/quote HTTP/1.1\r\n");
        for (int i = 0; i < 200; i++) {
            huge.append("X-Pad-").append(i).append(": ").append("a".repeat(60)).append("\r\n");
        }
        huge.append("\r\n");
        assertThrows(WsProtocolException.class, () -> WsHandshake.readRequest(
                new ByteArrayInputStream(huge.toString().getBytes(StandardCharsets.US_ASCII)),
                TRACE, "ip"));
        assertThrows(WsProtocolException.class,
                () -> WsHandshake.parse("BADLINE\r\n\r\n", TRACE, "ip"));
        assertThrows(WsProtocolException.class,
                () -> WsHandshake.parse("\r\n", TRACE, "ip"));
    }

    private static void assertRejected(String raw, String expectInMessage) {
        RequestContext ctx = WsHandshake.parse(raw, TRACE, "ip");
        String reason = WsHandshake.validateUpgrade(ctx);
        assertNotNull(reason, "应被拒绝：" + raw.replace("\r\n", " | "));
        assertTrue(reason.contains(expectInMessage),
                "拒绝原因应含「" + expectInMessage + "」，实际=" + reason);
    }

    @Test
    @DisplayName("空头集合安全（不抛 NPE）")
    void handlesEmptyHeaders() {
        RequestContext ctx = new RequestContext("GET", "/ws/quote", null,
                Collections.emptyMap(), "", TRACE, "ip");
        assertNotNull(WsHandshake.validateUpgrade(ctx));
    }
}
