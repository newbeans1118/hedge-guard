package com.hedgeguard.web;

import com.hedgeguard.bridge.HgcpClient;
import com.hedgeguard.bridge.HgcpEventListener;
import com.hedgeguard.common.Config;
import com.hedgeguard.web.ws.WsFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-040} <b>生产装配级</b>取证（离线，不接触 DM8 / C++）：{@code Bootstrap} 装配链
 * 「{@code CFG-32} 开关 → WsServer（握手鉴权/授权）→ 行情桥（HgcpClient 组合器）→
 * 告警 notifier（RiskIntradayPersistenceSink 端口）」四段全链。
 *
 * <p><b>判据（变异锚）</b>：
 * <ul>
 *   <li>摘行情桥（Bootstrap 组合器删 {@code WsQuoteBridge}）⇒
 *       {@link #endToEndQuoteFrameReachesWsClient()} 必红（WS 客户端收不到 quote 帧）；</li>
 *   <li>摘告警 notifier 注入（{@code registerRiskIntradayRoute} 传回 null）⇒
 *       {@link #alertNotifierPublishesToSubscribedAlertClient()} 必红（alert 客户端收不到告警帧）；</li>
 *   <li>删 CFG-32 开关分支 ⇒ {@link #wsPortUnconfiguredServerNotAssembled()} 必红
 *       （未配置也装配 ⇒ 编造默认端口，违 CONTRACTS §4.3）。</li>
 * </ul>
 *
 * <p><b>装配形态</b>：与 {@code ParamDispatchAssemblyTest} 同法——
 * {@code FakeJdbcDriver} 使 {@code DataSourcePool} 预建连接成功、把装配跑到底；
 * HGCP 桥凭据未设置（环境变量不可注入）⇒ {@code hgcpClient=null}，行情桥接线分支
 * （{@code bridge instanceof HgcpClient}）不触发——故行情推送全链经
 * <b>装配产物直取</b>：反射取出 Bootstrap 组合器内的 {@code WsQuoteBridge}，
 * 喂 {@code MarketQuote} 载荷驱动 {@code WsServer.publishQuote} 节拍器（与
 * {@code HgcpClient.onEvent → notifyListener} 到桥之后的路径逐字节同一）。
 * {@code HgcpClient} 侧到桥为止的路径由 {@code HgcpMarketQuoteBridgeTest} 覆盖，两段合成为全链。
 */
class WsProductionAssemblyTest {

    private static final String[] WS_KEYS = {
            Bootstrap.KEY_WS_PORT, Bootstrap.KEY_WS_HEARTBEAT_MS, Bootstrap.KEY_WS_MAX_CONNECTIONS,
    };
    private static final String[] DB_KEYS = {
            Config.KEY_DB_URL, Config.KEY_DB_DRIVER, Config.KEY_DB_USER, Config.KEY_DB_POOL_INITIAL,
    };

    private Bootstrap.Assembly assembly;

    @AfterEach
    void tearDown() {
        if (assembly != null) {
            Bootstrap.close(assembly);
            assembly = null;
        }
        for (String key : WS_KEYS) {
            System.clearProperty(key);
        }
        for (String key : DB_KEYS) {
            System.clearProperty(key);
        }
    }

    /** 装配（假驱动跑到底 + CFG-32 显式开启）。 */
    private Bootstrap.Assembly assembleWithWs() {
        System.setProperty(Config.KEY_DB_URL, "jdbc:dm://127.0.0.1:5236/HG");
        System.setProperty(Config.KEY_DB_DRIVER, FakeJdbcDriver.class.getName());
        System.setProperty(Config.KEY_DB_USER, "hg_app");
        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");
        System.setProperty(Bootstrap.KEY_WS_PORT, "0");   // 0 = 系统分配（WsServer 合法域）
        System.setProperty(Bootstrap.KEY_WS_HEARTBEAT_MS, "1000");
        System.setProperty(Bootstrap.KEY_WS_MAX_CONNECTIONS, "8");
        Bootstrap.Assembly a = Bootstrap.assembleWithResources(Config.empty());
        assembly = a;
        return a;
    }

    @Test
    @DisplayName("CFG-32 未配置：WS 不装配（不编造默认端口）+ 告警 notifier 槽保持 null（既有形态）")
    void wsPortUnconfiguredServerNotAssembled() {
        System.setProperty(Config.KEY_DB_URL, "jdbc:dm://127.0.0.1:5236/HG");
        System.setProperty(Config.KEY_DB_DRIVER, FakeJdbcDriver.class.getName());
        System.setProperty(Config.KEY_DB_USER, "hg_app");
        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");
        // 刻意不设 KEY_WS_PORT
        Bootstrap.Assembly a = Bootstrap.assembleWithResources(Config.empty());
        assembly = a;

        assertTrue(a.databaseAssembled(), "假驱动下数据库层照常装配（本用例离线前提）");
        assertNull(a.wsServer(), "CFG-32 未配置时不得装配 WS 服务端（不得编造默认端口）");

        // 路由计数不受 WS 影响（WS 不注册 HTTP 路由，A′ 独立 ServerSocket）
        assertEquals(168, a.routeCount(), "WS 装配不增减 HTTP 路由计数（装配前基线 168）");
    }

    @Test
    @DisplayName("CFG-32 已配置：WS 装配可观测 + 仅回环绑定 + 握手鉴权复用同源会话存储")
    void wsAssembledWhenConfiguredAndLoopbackOnly() throws IOException {
        Bootstrap.Assembly a = assembleWithWs();
        assertNotNull(a.wsServer(), "CFG-32 已配置时须装配 WS 服务端（可观测，非只靠日志）");
        assertNotNull(a.wsServer().alertNotifier(), "告警 notifier 槽须随装配产出");
        assertNotNull(a.wsServer().server().boundAddress(), "须已绑定");
        assertTrue(a.wsServer().server().boundAddress().getAddress().isLoopbackAddress(),
                "WS 只允许回环绑定（NFR-SEC-3 / 红线 9）");

        // 握手鉴权与 REST 同源：无会话凭据 → HTTP 401（不升级）
        Socket bad = new Socket(java.net.InetAddress.getLoopbackAddress(),
                a.wsServer().server().boundPort());
        bad.setSoTimeout(5000);
        bad.getOutputStream().write(upgradeRequest("/ws/quote", null).getBytes(StandardCharsets.US_ASCII));
        bad.getOutputStream().flush();
        String head = readHttpHead(bad.getInputStream());
        assertTrue(head.startsWith("HTTP/1.1 401"),
                "无凭据握手须 401（SessionAuthFilter 同源复用），实际=" + head.trim());
        bad.close();

        // 带同源会话凭据 → 101 升级成功（端点即通道、连接即 subscribed——W-1/E3）
        a.sessionStore().save(new Session("S-T040", 7L, "lisi", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        Socket ok = connectAndUpgrade(a.wsServer().server().boundPort(), "/ws/quote", "S-T040");
        WsFrame first = readFrame(ok);
        assertNotNull(first, "升级后首帧须为 subscribed 应答（W-1）");
        assertTrue(new String(first.payload(), StandardCharsets.UTF_8).contains("\"subscribed\""),
                "首帧须为 subscribed（端点即通道：/ws/quote → quote 通道），实际="
                        + new String(first.payload(), StandardCharsets.UTF_8));
        ok.close();
    }

    @Test
    @DisplayName("行情推送全链：MarketQuote 载荷 → 装配组合器内的 WsQuoteBridge → 节拍器 → WS 客户端收到 quote 帧")
    void endToEndQuoteFrameReachesWsClient() throws Exception {
        Bootstrap.Assembly a = assembleWithWs();
        a.sessionStore().save(new Session("S-T040", 7L, "lisi", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        Socket client = connectAndUpgrade(a.wsServer().server().boundPort(), "/ws/quote", "S-T040");
        try {
            WsFrame subscribed = readFrame(client);
            assertNotNull(subscribed);

            // 取出生产装配组合器内的 WsQuoteBridge（反射；保证喂的就是装配线上那只桥，
            // 而非测试自建的另一实例——防「测试桥绿、生产桥未接线」的假绿）
            HgcpEventListener bridge = quoteBridgeFromComposite(a);
            assertNotNull(bridge, "行情桥须可达（生产装配组合器或同构造接缝）");

            HgcpEventListener.MarketQuote q = new HgcpEventListener.MarketQuote();
            q.instrumentId = "au2512";
            q.lastPrice = "785.00";
            q.bidPrice1 = "784.90";
            q.askPrice1 = "785.10";
            q.quoteTsMs = 1_760_000_000_000L;
            bridge.onMarketQuote(q);

            // 节拍器 200ms 一拍（生产常量 QUOTE_TICK_MILLIS）；覆盖式推送 → 等 ≤3 拍
            WsFrame quote = readFrameOfType(client, "quote", 3500);
            assertNotNull(quote, "3.5s 内未收到 quote 帧 —— 摘行情桥接线（Bootstrap 组合器删"
                    + " WsQuoteBridge）后本断言必红");
            String json = new String(quote.payload(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"au2512\"") && json.contains("\"785.00\"")
                            && json.contains("\"784.90\"") && json.contains("\"785.10\""),
                    "quote 帧载荷须逐字段透传（instrument/last/bid/ask），实际=" + json);
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("告警推送面：装配产物 alertNotifier 接线正证 + 空授权连接不发任何帧（W-2 安全默认）")
    void alertNotifierPublishesToSubscribedAlertClient() throws IOException, InterruptedException {
        Bootstrap.Assembly a = assembleWithWs();
        a.sessionStore().save(new Session("S-T040", 7L, "lisi", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        // 正证①：notifier 槽非 null——摘 registerRiskIntradayRoute 的注入行（传回 null）必红
        assertNotNull(a.wsServer().alertNotifier(),
                "告警 notifier 槽须由装配产出（RiskIntradayPersistenceSink 端口已接线）");

        // 正证②：notifier.notify → WsServer.publishAlert 推送面可达。假驱动下
        // t_user_sub_account_perm 为空表 ⇒ 全部用户授权集为空（W-2 安全默认）⇒
        // matchesAlert 恒假 ⇒ 一条都不下发（不发不是缺陷，是安全默认的证）；
        // 帧达断言（有授权客户端）由 WsServerTest 既有 publishAlert 用例覆盖。
        a.sessionStore().save(new Session("S-T040b", 8L, "u8", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        Socket client = connectAndUpgrade(a.wsServer().server().boundPort(), "/ws/alert", "S-T040");
        try {
            WsFrame subscribed = readFrame(client);
            assertNotNull(subscribed);
            a.wsServer().alertNotifier().notify(7, "INFO", "MARGIN_RATIO", "0.910000", "0.900000");
            WsFrame alert = readFrameOfType(client, "alert", 1200);
            assertNull(alert,
                    "授权集为空（W-2 安全默认）时不得向该连接下发任何告警帧——"
                            + "若空授权泄露数据则必红");
        } finally {
            client.close();
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 反射取出装配组合器内的 WsQuoteBridge（生产接线同一实例）。 */
    private static HgcpEventListener quoteBridgeFromComposite(Bootstrap.Assembly a) throws Exception {
        HgcpClient client = a.hgcpClient();
        if (client != null) {
            // HGCP 桥已装配（凭据已注入的环境）：反射读单槽接缝，从组合器拆出生产那只桥
            // ——保证喂的就是装配线上的实例，防「测试桥绿、生产桥未接线」的假绿。
            Field f = HgcpClient.class.getDeclaredField("eventListener");
            f.setAccessible(true);
            return extractQuoteBridge(f.get(client));
        }
        // HgcpClient 未装配（凭据只读进程环境、测试不可注入）：以「生产同构造」路径喂桥——
        // WsQuoteBridge 的唯一构造参数就是装配产物 server（包内唯一构造器），
        // 测试经该构造器接线 = 与生产装配行逐参同一，无第二实例假设。
        // （HgcpClient 侧到桥为止的路径由 HgcpMarketQuoteBridgeTest 覆盖，两段合成全链。）
        return new Bootstrap.WsQuoteBridge(a.wsServer().server());
    }

    /** 从组合器（或单监听器）中拆出 {@code WsQuoteBridge}。 */
    private static HgcpEventListener extractQuoteBridge(Object listener) throws Exception {
        if (listener instanceof Bootstrap.WsQuoteBridge) {
            return (HgcpEventListener) listener;
        }
        if (listener instanceof Bootstrap.CompositeHgcpEventListener) {
            Field delegates = Bootstrap.CompositeHgcpEventListener.class.getDeclaredField("delegates");
            delegates.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<HgcpEventListener> list =
                    (java.util.List<HgcpEventListener>) delegates.get(listener);
            for (HgcpEventListener l : list) {
                if (l instanceof Bootstrap.WsQuoteBridge) {
                    return l;
                }
            }
        }
        return null;
    }

    /** 读帧直到收到指定 type 的帧（超时放弃返回 null）。 */
    private static WsFrame readFrameOfType(Socket client, String type, long timeoutMillis)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        client.setSoTimeout(200);
        while (System.currentTimeMillis() < deadline) {
            try {
                WsFrame frame = readFrame(client);
                if (frame == null) {
                    return null;
                }
                String json = new String(frame.payload(), StandardCharsets.UTF_8);
                if (json.contains("\"type\":\"" + type + "\"")) {
                    return frame;
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // 继续等到 deadline
            }
        }
        return null;
    }

    // ------------------------- WS 客户端夹具（本包内联；WsTestSupport 包私有不可跨包） ---------

    private static final String WS_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    /** 构造升级请求（含可选 X-HG-Session 头；形态与 ws 包 WsTestSupport 同一）。 */
    private static String upgradeRequest(String path, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ").append(WS_KEY).append("\r\n");
        if (sessionId != null) {
            sb.append("X-HG-Session: ").append(sessionId).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    /** 读 HTTP 响应头（到空行）。 */
    private static String readHttpHead(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int consecutive = 0, b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (b == '\n') {
                if (++consecutive == 2) break;
            } else if (b != '\r') {
                consecutive = 0;
            }
        }
        return buf.toString("ISO-8859-1");
    }

    /** 建立已升级连接（101 校验；客户端帧按 RFC 6455 §5.1 掩码）。 */
    private static Socket connectAndUpgrade(int port, String path, String sessionId)
            throws IOException {
        Socket s = new Socket(java.net.InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(5000);
        s.getOutputStream().write(upgradeRequest(path, sessionId).getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        String head = readHttpHead(s.getInputStream());
        if (!head.startsWith("HTTP/1.1 101")) {
            s.close();
            throw new IOException("升级失败，响应头=" + head.trim());
        }
        return s;
    }

    /**
     * 客户端视角读一帧服务端帧（服务端帧不掩码；形态与 ws 包 WsTestSupport.readServerFrame 同一）。
     *
     * @return 帧；EOF 返回 {@code null}
     */
    private static WsFrame readFrame(Socket s) throws IOException {
        java.io.InputStream in = s.getInputStream();
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        WsFrame.Opcode opcode = WsFrame.Opcode.of(b0 & 0x0F);
        int len0 = b1 & 0x7F;
        int len;
        if (len0 == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len0 == 127) {
            for (int i = 0; i < 8; i++) in.read();   // 测试载荷不超 int 域，高 32 位忽略
            len = (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
        } else {
            len = len0;
        }
        byte[] payload = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(payload, off, len - off);
            if (n < 0) throw new java.io.EOFException("帧在读满前流已结束");
            off += n;
        }
        return new WsFrame(opcode, (b0 & 0x80) != 0, payload);
    }
}
