package com.hedgeguard.web;

import com.hedgeguard.bridge.HgcpClient;
import com.hedgeguard.bridge.HgcpEventListener;
import com.hedgeguard.common.Config;
import com.hedgeguard.web.ws.WsConnection;
import com.hedgeguard.web.ws.WsServer;
import com.hedgeguard.web.ws.WsFrame;
import com.hedgeguard.web.ws.WsSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-040} <b>生产装配级</b>取证（离线，不接触 DM8 / C++）：{@code Bootstrap} 装配链
 * 「{@code CFG-32} 开关 → WsServer（握手鉴权/授权）→ 行情桥（HgcpClient 组合器）→
 * 告警 notifier（RiskIntradayPersistenceSink 端口）」四段全链。
 *
 * <p><b>判据（变异锚；T-040c delta 逐条亲证，与实测判别力一致——TE-T040-V-001 订正）</b>：
 * <ul>
 *   <li>摘行情桥接缝（{@code Bootstrap.buildEventConsumers} 内删 {@code WsQuoteBridge} 并联，
 *       即原 {@code Bootstrap:834} {@code consumers.add} 行）⇒
 *       {@link #buildEventConsumersSeamContainsQuoteBridgeExactlyWhenWsAssembled()} 与
 *       {@link #endToEndQuoteFrameReachesWsClient()} 必红（接缝产物缺桥 + 客户端收不到 quote 帧）。
 *       ——<b>勘误</b>：本轮原声明（指摘装配行）经 te-verify-t040 实测<b>失实</b>：测试环境
 *       {@code hgcpClient=null}，e2e 原以 fallback 自建桥喂帧，恰绕过被摘行（全量恒绿）；
 *       T-040c 提取包内接缝并让 e2e 改喂接缝产物后，该声明对「接缝等价点」成立（本轮变异亲证红）；</li>
 *   <li>摘告警 notifier 产出（{@code registerRiskIntradayRoute} 传回 null、槽为 null）⇒
 *       {@link #alertNotifierPublishesToSubscribedAlertClient()} 正证①必红；<b>摘 lambda 内
 *       {@code server.publishAlert} 委托行（槽仍非 null）</b> ⇒
 *       {@link #alertLambdaDeliversToAuthorizedAlertClient()} 必红——te-verify-t040 实测
 *       该委托行原全量无击杀器，T-040c 补齐（本轮变异亲证红）；</li>
 *   <li>删 CFG-32 开关分支 ⇒ {@link #wsPortUnconfiguredServerNotAssembled()} 必红
 *       （未配置也装配 ⇒ 编造默认端口，违 CONTRACTS §4.3）。</li>
 * </ul>
 *
 * <p><b>装配形态</b>：与 {@code ParamDispatchAssemblyTest} 同法——
 * {@code FakeJdbcDriver} 使 {@code DataSourcePool} 预建连接成功、把装配跑到底；
 * HGCP 桥凭据未设置（环境变量不可注入）⇒ {@code hgcpClient=null}，行情桥接线分支
 * （{@code bridge instanceof HgcpClient}）不触发——故行情推送全链经
 * <b>生产接缝产物直取</b>：调用 {@code Bootstrap.buildEventConsumers}（与生产装配行
 * 同一构造代码）从产物清单取出 {@code WsQuoteBridge}，喂 {@code MarketQuote} 载荷驱动
 * {@code WsServer.publishQuote} 节拍器（与 {@code HgcpClient.onEvent → notifyListener}
 * 到桥之后的路径逐字节同一）。
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
            assertNotNull(bridge, "行情桥须可达（生产组合器或生产接缝产物；接缝内删桥 ⇒ 必红）");

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

    /**
     * <b>M1 接缝直证</b>（te-verify-t040 TE-T040-V-001 判别力补齐）：组合器清单构造已提取为
     * 包内可见接缝 {@code Bootstrap.buildEventConsumers}——生产路径（WS 已装配）与
     * 关态路径（WS 未装配）分别取证，防「测试环境 fallback 绕行」再现。
     */
    @Test
    @DisplayName("M1 接缝直证：buildEventConsumers 产物——WS 已装配含 WsQuoteBridge（生产路径）、未装配不含（关态路径）")
    void buildEventConsumersSeamContainsQuoteBridgeExactlyWhenWsAssembled() throws Exception {
        Bootstrap.Assembly a = assembleWithWs();
        HgcpEventListener feed = new HgcpEventListener() { };
        HgcpEventListener sink = new HgcpEventListener() { };

        // 生产路径：WS 已装配 ⇒ 产物恰三席，第三席为 WsQuoteBridge（持有装配产物 server，
        // 与原 Bootstrap:834 装配行逐参同一——WsQuoteBridge 包内唯一构造器即 server）。
        List<HgcpEventListener> withWs = Bootstrap.buildEventConsumers(feed, sink, a.wsServer());
        assertEquals(3, withWs.size(), "WS 已装配：feed + sink + 行情桥 恰三席");
        assertTrue(withWs.get(0) == feed && withWs.get(1) == sink,
                "基底两席及其次序保持（feed、sink）");
        assertTrue(withWs.get(2) instanceof Bootstrap.WsQuoteBridge,
                "第三席须为 WsQuoteBridge（M1 变异：接缝内删 consumers.add(WsQuoteBridge) ⇒ 本断言必红）");

        // 关态路径：WS 未装配（null）⇒ 恰两席、不混入行情桥（既有形态：WS 缺席不接桥）。
        List<HgcpEventListener> withoutWs = Bootstrap.buildEventConsumers(feed, sink, null);
        assertEquals(2, withoutWs.size(), "WS 未装配：feed + sink 恰两席");
        for (HgcpEventListener l : withoutWs) {
            assertFalse(l instanceof Bootstrap.WsQuoteBridge,
                    "关态路径不得混入行情桥（既有形态回归锚）");
        }
    }

    /**
     * <b>M2 判别器</b>（te-verify-t040 TE-T040-V-001 判别力补齐）：授权客户端经
     * <b>生产 notifier lambda</b>（{@code Bootstrap.assembleWsServer} 返回的
     * {@code WsServerHolder} 委托行 {@code server.publishAlert(...)}）收到 alert 帧。
     *
     * <p><b>授权行使形态披露</b>：装配环境 {@code FakeJdbcDriver} 下
     * {@code t_user_sub_account_perm} 读取 fail-closed 恒空集（W-2 安全默认，无法经
     * {@code WsAuthorizer.authorize} 求交出非空有效集）——「申请集 × 授权集」求交语义由
     * {@code WsAuthorizerTest} 与 {@code WsServerTest.alertDeliveredOnlyForAuthorizedSubAccount}
     * 覆盖；本用例把该连接的订阅置为<b>已授权形态</b>（{@code subAccounts={7}}，即授权行使
     * 之后的等价状态），其后全链保持生产路径：{@code notifier.notify → 生产 lambda 委托行 →
     * server.publishAlert → matchesAlert → sendAlert → 真实 socket 帧}。
     * <b>判别对象</b>：摘 lambda 内 {@code publishAlert} 委托行（槽仍非 null）⇒ 客户端收不到
     * alert 帧 ⇒ 必红（原全量对该变异无击杀器）。
     */
    @Test
    @DisplayName("告警推送生产链：授权形态客户端经生产 notifier lambda 委托行收到 alert 帧（M2 判别器）")
    void alertLambdaDeliversToAuthorizedAlertClient() throws Exception {
        Bootstrap.Assembly a = assembleWithWs();
        a.sessionStore().save(new Session("S-T040c", 7L, "lisi", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        Socket client = connectAndUpgrade(a.wsServer().server().boundPort(), "/ws/alert", "S-T040c");
        try {
            WsFrame subscribed = readFrame(client);
            assertNotNull(subscribed, "升级后首帧须为 subscribed 应答（端点即通道）");

            // 把唯一连接的订阅置为「已授权形态」（见方法 javadoc 授权行使形态披露）：
            // 通道=alert、有效子账户集={7}（装配环境 fail-closed 空授权的等价行使后状态）。
            WsConnection conn = soleAlertConnection(a.wsServer().server());
            java.lang.reflect.Method setSub = WsConnection.class
                    .getDeclaredMethod("setSubscription", WsSubscription.class);
            setSub.setAccessible(true);
            setSub.invoke(conn, new WsSubscription(
                    Collections.singleton(WsSubscription.CHANNEL_ALERT),
                    Collections.emptySet(),
                    Collections.singleton(7)));

            long sentBefore = a.wsServer().server().stats().alertsSent();
            // 经生产 lambda 真实注入告警（Bootstrap.assembleWsServer 返回的 notifier 槽）
            a.wsServer().alertNotifier().notify(7, "WARN", "MARGIN_RATIO", "0.910000", "0.900000");

            WsFrame alert = readFrameOfType(client, "alert", 2000);
            assertNotNull(alert, "2s 内未收到 alert 帧 —— 摘 Bootstrap 告警 lambda 内"
                    + " server.publishAlert 委托行（槽仍非 null）后本断言必红（TE-T040-V-001 M2 判别器）");
            String json = new String(alert.payload(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"alert\"") && json.contains("\"MARGIN_RATIO\"")
                            && json.contains("\"0.910000\"") && json.contains("\"0.900000\""),
                    "alert 帧载荷须逐字段透传（type/metric/value/threshold），实际=" + json);
            assertEquals(sentBefore + 1, a.wsServer().server().stats().alertsSent(),
                    "alertsSent 计数须恰 +1（推送面正向计数互证）");
        } finally {
            client.close();
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 取装配 WS 服务端的唯一活跃连接（经 {@code connections} 映射反射；本测试恰建一条）。 */
    private static WsConnection soleAlertConnection(WsServer server) throws Exception {
        Field f = WsServer.class.getDeclaredField("connections");
        f.setAccessible(true);
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) f.get(server);
        assertEquals(1, map.size(), "本用例应恰建立一条 WS 连接");
        return (WsConnection) map.values().iterator().next();
    }

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
        // HgcpClient 未装配（凭据只读进程环境、测试不可注入）：以**生产接缝**路径喂桥——
        // 经 Bootstrap.buildEventConsumers（与生产装配同一构造代码）取产物内的 WsQuoteBridge。
        // （TE-T040-V-001 M1 修复：原「测试侧自建 new WsQuoteBridge(server)」恰绕过被测行
        // ⇒ 摘行全量恒绿；改喂接缝产物后，接缝内删桥 ⇒ 本方法返回 null ⇒ e2e 必红。）
        // （HgcpClient 侧到桥为止的路径由 HgcpMarketQuoteBridgeTest 覆盖，两段合成全链。）
        java.util.List<HgcpEventListener> consumers = Bootstrap.buildEventConsumers(
                new HgcpEventListener() { }, new HgcpEventListener() { }, a.wsServer());
        return extractQuoteBridge(consumers);
    }

    /** 从组合器（或单监听器、或接缝产物清单）中拆出 {@code WsQuoteBridge}。 */
    private static HgcpEventListener extractQuoteBridge(Object listener) throws Exception {
        if (listener instanceof java.util.List) {
            for (Object l : (java.util.List<?>) listener) {
                if (l instanceof Bootstrap.WsQuoteBridge) {
                    return (HgcpEventListener) l;
                }
            }
            return null;
        }
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
