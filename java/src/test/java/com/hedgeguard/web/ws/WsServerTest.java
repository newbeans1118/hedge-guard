package com.hedgeguard.web.ws;

import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 服务端**端到端**单测（`T-028` 验收 1~5）。
 *
 * <p>**完全离线**：只用回环 socket 与内存会话/权限桩，不依赖 DM8 与外部网络。
 * 覆盖：仅回环绑定、握手鉴权（401）与路径/协议校验（404/400）、文本帧往返、
 * 告警下发与**授权子账户过滤**（`W-2`）、越权拒收 + 留痕、首帧快照（`W-5`）、连接数上限（503）。
 */
class WsServerTest {

    private static final long USER_WITH_GRANTS = 7L;
    private static final long USER_WITHOUT_GRANTS = 8L;

    private SessionStore store;
    private List<WsAuthorizer.WsViolation> violations;
    private WsServer server;

    @BeforeEach
    void startServer() throws IOException {
        store = WsTestSupport.storeWith(USER_WITH_GRANTS);
        store.save(WsTestSupport.session(USER_WITHOUT_GRANTS, "u8"));
        violations = new ArrayList<>();
        // 默认不装快照源：使「首帧 = subscribed」这一断言不被快照帧干扰（快照单独在 W-5 用例验证）
        server = new WsServer(0, 30000, 4,
                new SessionAuthFilter(store, "HGSESSION"),
                new WsAuthorizer(this::grants, violations::add),
                null);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /** 授权桩：用户 7 → {1,2}；其余 → 空集（安全默认）。 */
    private Set<Integer> grants(long userId) {
        return userId == USER_WITH_GRANTS
                ? new LinkedHashSet<>(Arrays.asList(1, 2)) : Collections.emptySet();
    }

    /** 快照桩：行情给一帧（验证 `W-5` 首帧快照）；告警给空。 */
    private List<String> snapshot(Set<String> instruments) {
        return Collections.singletonList(
                WsMessages.quote("ru2601", "14500.000000", "14499.000000", "14501.000000"));
    }

    private List<String> snapshotAlerts(Set<Integer> subAccounts) {
        return Collections.emptyList();
    }

    private WsServer.SnapshotSource snapshotSource() {
        return new WsServer.SnapshotSource() {
            @Override
            public List<String> latestQuoteFrames(Set<String> instruments) {
                return snapshot(instruments);
            }

            @Override
            public List<String> pendingAlertFrames(Set<Integer> subAccounts) {
                return snapshotAlerts(subAccounts);
            }
        };
    }

    private int port() {
        return server.boundPort();
    }

    // ---------------------------------------------------------------- 绑定与握手

    @Test
    @DisplayName("🔴 仅绑定回环 127.0.0.1（红线 9：绝不 0.0.0.0）")
    void bindsLoopbackOnly() {
        assertTrue(server.boundPort() > 0, "应已绑定端口");
        assertNotNull(server.boundAddress());
        assertTrue(server.boundAddress().getAddress().isLoopbackAddress(),
                "WS 必须仅监听回环，实际=" + server.boundAddress());
        assertFalse(server.boundAddress().getAddress().isAnyLocalAddress(),
                "绝不允许通配地址（0.0.0.0）");
    }

    @Test
    @DisplayName("握手成功：101 + Sec-WebSocket-Accept 为官方向量值；首帧即 subscribed（W-1）")
    void handshakeSucceedsAndFirstFrameIsSubscribed() throws IOException {
        try (Socket s = WsTestSupport.connectAndUpgrade(port(), "/ws/quote", "S-" + USER_WITH_GRANTS)) {
            WsFrame first = WsTestSupport.readFrame(s);
            assertNotNull(first, "应收到首帧");
            assertEquals(WsFrame.Opcode.TEXT, first.opcode());
            assertTrue(first.text().contains("\"type\":\"subscribed\""),
                    "首帧应为订阅应答，实际=" + first.text());
            assertTrue(first.text().contains("\"tsMillis\""), "应含 tsMillis（W-6）");
            assertEquals(1, server.connectionCount());
        }
    }

    @Test
    @DisplayName("握手鉴权：无会话凭据 → HTTP 401（不升级）+ 业务码 10006；计数 authFailures 递增")
    void handshakeWithoutSessionIs401() throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port())) {
            s.setSoTimeout(3000);
            s.getOutputStream().write(WsTestSupport.upgradeRequest("/ws/quote", null,
                    WsTestSupport.RFC_KEY).getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String head = WsTestSupport.readHttpHead(s.getInputStream());
            assertTrue(head.startsWith("HTTP/1.1 401 Unauthorized"), "实际=" + head.trim());
            String body = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("10006"), "错误体应含业务码 10006，实际=" + body);
        }
        assertEquals(1, server.stats().authFailures());
        assertEquals(0, server.connectionCount(), "未通过鉴权不得建立连接");
    }

    @Test
    @DisplayName("路径校验：非放行路径 → 404（只有 /ws/quote 与 /ws/alert）")
    void unknownPathIs404() throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port())) {
            s.setSoTimeout(3000);
            s.getOutputStream().write(WsTestSupport.upgradeRequest("/ws/other",
                    "S-" + USER_WITH_GRANTS, WsTestSupport.RFC_KEY)
                    .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            assertTrue(WsTestSupport.readHttpHead(s.getInputStream()).startsWith("HTTP/1.1 404"));
        }
    }

    @Test
    @DisplayName("协议校验：缺少 Upgrade 头 → 400（不升级）")
    void invalidUpgradeIs400() throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port())) {
            s.setSoTimeout(3000);
            String raw = "GET /ws/quote HTTP/1.1\r\nHost: 127.0.0.1\r\nX-HG-Session: S-7\r\n"
                    + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + WsTestSupport.RFC_KEY
                    + "\r\n\r\n";
            s.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String head = WsTestSupport.readHttpHead(s.getInputStream());
            assertTrue(head.startsWith("HTTP/1.1 400 Bad Request"), "实际=" + head.trim());
        }
    }

    // ---------------------------------------------------------------- 订阅与推送

    @Test
    @DisplayName("文本帧往返：subscribe 后行情可送达（覆盖式节流 ≤5 条/秒/合约）")
    void subscribeThenReceiveQuote() throws IOException {
        try (Socket s = WsTestSupport.connectAndUpgrade(port(), "/ws/quote", "S-" + USER_WITH_GRANTS)) {
            WsTestSupport.readFrame(s);                                  // subscribed
            WsTestSupport.sendText(s, "{\"type\":\"subscribe\",\"channels\":[\"quote\"],"
                    + "\"instruments\":[\"ru2601\"],\"subAccounts\":[1]}");
            WsFrame ack = WsTestSupport.readFrame(s);
            assertTrue(ack.text().contains("\"type\":\"subscribed\""));
            assertTrue(ack.text().contains("\"subAccounts\":[1]"), "应回已授权交集，实际=" + ack.text());

            server.publishQuote("ru2601", "14500.000000", "14499.000000", "14501.000000");
            WsFrame quote = readUntilType(s, "quote", 3000);
            assertTrue(quote.text().contains("ru2601"));
            assertTrue(quote.text().contains("14500.000000"), "数值须字符串化十进制");
        }
    }

    @Test
    @DisplayName("🔴 W-2：越权子账户订阅 → 先回 10004 错误帧，再 close(1008)，并写入留痕")
    void unauthorizedSubscriptionIsRejectedAndRecorded() throws IOException {
        try (Socket s = WsTestSupport.connectAndUpgrade(port(), "/ws/alert", "S-" + USER_WITH_GRANTS)) {
            WsTestSupport.readFrame(s);                                   // subscribed
            WsTestSupport.sendText(s, "{\"type\":\"subscribe\",\"channels\":[\"alert\"],"
                    + "\"subAccounts\":[1,99]}");
            WsFrame err = WsTestSupport.readFrame(s);
            assertTrue(err.text().contains("\"type\":\"error\""), "应先收到错误帧，实际=" + err.text());
            assertTrue(err.text().contains("10004"), "错误帧须含 10004，实际=" + err.text());

            WsFrame close = WsTestSupport.readFrame(s);
            assertEquals(WsFrame.Opcode.CLOSE, close.opcode());
            assertEquals(WsCloseCode.POLICY_VIOLATION, WsFrameCodec.closeCodeOf(close));
        }
        assertEquals(1, violations.size(), "越权必须留痕");
        assertEquals(USER_WITH_GRANTS, violations.get(0).userId);
        assertEquals(Integer.valueOf(99), violations.get(0).subAccountId);
    }

    @Test
    @DisplayName("🔴 W-2 安全默认：无授权用户的连接**一条告警都不下发**（订阅生效集为空）")
    void noGrantMeansNoAlertDelivery() throws IOException {
        try (Socket s = WsTestSupport.connectAndUpgrade(port(), "/ws/alert",
                "S-" + USER_WITHOUT_GRANTS)) {
            WsFrame first = WsTestSupport.readFrame(s);
            assertTrue(first.text().contains("\"subAccounts\":[]"),
                    "无授权用户生效子账户必须为空集，实际=" + first.text());
            WsTestSupport.sendText(s, "{\"type\":\"subscribe\",\"channels\":[\"alert\"]}");
            WsTestSupport.readFrame(s);

            server.publishAlert(1, "警告", "margin_ratio", "0.910000", "0.900000");
            s.setSoTimeout(400);
            assertThrows(SocketTimeoutException.class, () -> WsTestSupport.readFrame(s),
                    "无授权连接不得收到任何告警（安全默认 = 空集）");
        }
        assertTrue(violations.isEmpty(), "「没授权」不是越权尝试，不得留痕");
    }

    @Test
    @DisplayName("告警只投给已授权子账户：授权 [1] 的连接收 1 的告警，不收 99 的")
    void alertDeliveredOnlyForAuthorizedSubAccount() throws IOException {
        try (Socket s = WsTestSupport.connectAndUpgrade(port(), "/ws/alert", "S-" + USER_WITH_GRANTS)) {
            WsTestSupport.readFrame(s);
            WsTestSupport.sendText(s, "{\"type\":\"subscribe\",\"channels\":[\"alert\"],"
                    + "\"subAccounts\":[1]}");
            WsTestSupport.readFrame(s);

            server.publishAlert(1, "严重", "fund_available", "0.000000", "1000.000000");
            WsFrame alert = WsTestSupport.readFrame(s);
            assertTrue(alert.text().contains("\"type\":\"alert\""), "实际=" + alert.text());
            assertTrue(alert.text().contains("\"subAccount\":1"));

            server.publishAlert(99, "严重", "x", "1", "0");
            s.setSoTimeout(400);
            assertThrows(SocketTimeoutException.class, () -> WsTestSupport.readFrame(s),
                    "未授权子账户（99）的告警不得下发");
        }
        assertEquals(0, violations.size(), "越权的是发布方，不是订阅方；订阅方合法");
    }

    @Test
    @DisplayName("端点即通道：在 /ws/alert 上请求 quote 通道被拒（不得跨端点越权）")
    void channelMustMatchEndpoint() throws IOException {
        try (Socket s = WsTestSupport.connectAndUpgrade(port(), "/ws/alert", "S-" + USER_WITH_GRANTS)) {
            WsTestSupport.readFrame(s);
            WsTestSupport.sendText(s, "{\"type\":\"subscribe\",\"channels\":[\"quote\"]}");
            WsFrame err = WsTestSupport.readFrame(s);
            assertTrue(err.text().contains("\"type\":\"error\""), "实际=" + err.text());
            assertTrue(err.text().contains("10004"));
        }
    }

    @Test
    @DisplayName("W-5 首帧快照：连接后立即收到最新行情快照（避免前端空白）")
    void sendsSnapshotOnConnect() throws IOException {
        WsServer withSnapshot = new WsServer(0, 30000, 2,
                new SessionAuthFilter(store, "HGSESSION"),
                new WsAuthorizer(this::grants, violations::add), snapshotSource());
        withSnapshot.start();
        try (Socket s = WsTestSupport.connectAndUpgrade(withSnapshot.boundPort(), "/ws/quote",
                "S-" + USER_WITH_GRANTS)) {
            WsFrame subscribed = WsTestSupport.readFrame(s);
            assertTrue(subscribed.text().contains("subscribed"));
            WsFrame snap = WsTestSupport.readFrame(s);
            assertTrue(snap.text().contains("\"type\":\"quote\""), "应收到首帧快照，实际=" + snap.text());
            assertTrue(snap.text().contains("ru2601"));
        } finally {
            withSnapshot.close();
        }
    }

    @Test
    @DisplayName("连接数上限：达 max_connections 时新连接收到 HTTP 503（Retry-After），不挤占既有连接")
    void rejectsWhenAtMaxConnections() throws IOException {
        WsServer tiny = new WsServer(0, 30000, 1,
                new SessionAuthFilter(store, "HGSESSION"),
                new WsAuthorizer(this::grants, violations::add), null);
        tiny.start();
        try (Socket first = WsTestSupport.connectAndUpgrade(tiny.boundPort(), "/ws/quote",
                "S-" + USER_WITH_GRANTS)) {
            WsTestSupport.readFrame(first);
            long deadline = System.currentTimeMillis() + 2000;
            while (tiny.connectionCount() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            try (Socket second = new Socket(InetAddress.getLoopbackAddress(), tiny.boundPort())) {
                second.setSoTimeout(3000);
                second.getOutputStream().write(WsTestSupport.upgradeRequest("/ws/quote",
                        "S-" + USER_WITH_GRANTS, WsTestSupport.RFC_KEY)
                        .getBytes(StandardCharsets.US_ASCII));
                second.getOutputStream().flush();
                String head = WsTestSupport.readHttpHead(second.getInputStream());
                assertTrue(head.startsWith("HTTP/1.1 503"), "实际=" + head.trim());
                assertTrue(head.contains("Retry-After"));
            }
            assertEquals(1, tiny.stats().rejectedOverload());
            assertTrue(first.isConnected(), "既有连接不得被新连接挤掉");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } finally {
            tiny.close();
        }
    }

    @Test
    @DisplayName("未知消息类型 → 回 10001 错误帧（不关闭连接，容忍前端版本差异）")
    void unknownMessageTypeIsTolerated() throws IOException {
        try (Socket s = WsTestSupport.connectAndUpgrade(port(), "/ws/quote", "S-" + USER_WITH_GRANTS)) {
            WsTestSupport.readFrame(s);
            WsTestSupport.sendText(s, "{\"type\":\"whatever\"}");
            WsFrame err = WsTestSupport.readFrame(s);
            assertTrue(err.text().contains("10001"), "实际=" + err.text());
            assertTrue(s.isConnected(), "不得因未知类型断开连接");
        }
    }

    @Test
    @DisplayName("心跳：服务端按 RFC 6455 原生 ping 保活（可被客户端 pong 响应）")
    void serverSendsProtocolPing() throws IOException {
        WsServer fast = new WsServer(0, 1000, 2, new SessionAuthFilter(store, "HGSESSION"),
                new WsAuthorizer(this::grants, violations::add), null);
        fast.start();
        try (Socket s = WsTestSupport.connectAndUpgrade(fast.boundPort(), "/ws/quote",
                "S-" + USER_WITH_GRANTS)) {
            fast.publishQuote("ru2601", "1", "1", "1");
            long deadline = System.currentTimeMillis() + 4000;
            boolean sawPing = false;
            while (System.currentTimeMillis() < deadline && !sawPing) {
                WsFrame f = WsTestSupport.readFrame(s);
                if (f != null && f.opcode() == WsFrame.Opcode.PING) {
                    sawPing = true;
                    s.getOutputStream().write(WsTestSupport.clientControl(
                            WsFrame.Opcode.PONG, true, f.payload()));
                    s.getOutputStream().flush();
                }
            }
            assertTrue(sawPing, "4 秒内应至少收到一次协议 ping（间隔 1000ms）");
            assertTrue(s.isConnected(), "响应 pong 后连接应保持");
        } finally {
            fast.close();
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 读到指定 type 的文本帧为止（用于跳过 subscribed / ping 等）。 */
    private static WsFrame readUntilType(Socket s, String type, int millis) throws IOException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            WsFrame f = WsTestSupport.readFrame(s);
            if (f == null) {
                throw new IOException("连接在读到达前关闭");
            }
            if (f.opcode() == WsFrame.Opcode.TEXT && f.text().contains("\"" + type + "\"")) {
                return f;
            }
        }
        throw new IOException("超时未收到 type=" + type + " 的帧");
    }
}
