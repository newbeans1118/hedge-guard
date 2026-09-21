package com.hedgeguard.web.ws;

import com.hedgeguard.web.AuthFilter;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.TraceIds;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Java 端 WebSocket 服务端（`ARCHITECTURE.md` §7.1.11 **A′** / `T-028` / `T-040` 的基座）。
 *
 * <p><b>形态（A′）</b>：**独立 {@link ServerSocket} 监听 {@code CFG-32} 的 {@code hg.web.ws.port}**
 * + 手工 HTTP Upgrade 握手（{@link WsHandshake}）+ 自实现 RFC 6455（{@link WsFrameCodec}）。
 * 之所以不是"在 {@code com.sun.net.httpserver} 上升级"：该 API **无协议升级能力**
 * （`CONTRACTS.md` §4.2.1(1) 的 {@code javap} 证据）。
 *
 * <p><b>安全（不可协商）</b>：
 * <ul>
 *   <li>仅绑定回环 {@code 127.0.0.1}（非回环即启动失败，同 HTTP 侧红线 9）；</li>
 *   <li>握手期鉴权**复用** {@link AuthFilter}（`HGSESSION` / `X-HG-Session`），失败 → **HTTP 401**（不升级）；</li>
 *   <li>订阅授权经 {@link WsAuthorizer}（`W-2`）：**安全默认 = 空集**，越权 → `10004` + 留痕 + `close(1008)`；</li>
 *   <li>仅接受 {@code /ws/quote} 与 {@code /ws/alert} 两个升级路径，其余 404。</li>
 * </ul>
 *
 * <p><b>容量</b>：{@code hg.web.ws.max_connections} 达上限时，新连接返回 **HTTP 503**（`Retry-After`）而不做握手；
 * 单连接出站队列满时按通道语义处理（行情丢最旧 / 告警断开 `1013`，见 {@link WsConnection}）。
 */
public final class WsServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WsServer.class.getName());

    /** 行情节流上界（`ARCHITECTURE.md` §4.1.2：≤ 5 条/秒/合约）。 */
    public static final int QUOTE_MAX_PER_SECOND = 5;

    /** 行情刷新节拍（毫秒）= 1000 / 上限 ⇒ 天然满足"≤5 条/秒/合约"且为**覆盖式**。 */
    public static final int QUOTE_TICK_MILLIS = 1000 / QUOTE_MAX_PER_SECOND;

    /** 握手阶段等待上限（毫秒）。 */
    public static final int HANDSHAKE_WAIT_MILLIS = 5000;

    /** 单连接出站队列容量。 */
    public static final int QUEUE_CAPACITY = 64;

    /** 允许的升级路径。 */
    public static final Set<String> ALLOWED_PATHS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList("/ws/quote", "/ws/alert")));

    /**
     * 首帧快照来源（`CONTRACTS.md` §4.1.2 `W-5`：连接/订阅后**立即下发一次最新值**，避免前端空白）。
     *
     * <p>由 `risk_intraday` 提供实现（镜像状态 → 最新行情 / 未处置告警）；
     * 未装配时**不发送快照**（前端会等到下一次推送），不影响正确性。
     */
    public interface SnapshotSource {

        /**
         * 最新行情帧（已由 {@link WsMessages#quote} 构造）。
         *
         * @param instruments 订阅的合约集（空集 = 全部）
         * @return JSON 列表（可为空）
         */
        List<String> latestQuoteFrames(Set<String> instruments);

        /**
         * 未处置告警帧（**实现方须已按传入的已授权子账户集过滤**）。
         *
         * @param subAccounts 已授权子账户集（**空集 ⇒ 返回空列表**）
         * @return JSON 列表（可为空）
         */
        List<String> pendingAlertFrames(Set<Integer> subAccounts);
    }

    private final int port;
    private final int heartbeatMillis;
    private final int maxConnections;
    private final AuthFilter authFilter;
    private final WsAuthorizer authorizer;
    private final SnapshotSource snapshotSource;

    private final Map<Long, WsConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, String> pendingQuotes = new ConcurrentHashMap<>();
    private final AtomicLong connectionSeq = new AtomicLong();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong rejectedOverload = new AtomicLong();
    private final AtomicLong authFailures = new AtomicLong();
    private final AtomicLong protocolErrors = new AtomicLong();
    private final AtomicLong alertsSent = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile boolean closed;
    private Thread acceptThread;
    private ScheduledExecutorService quoteTicker;

    /**
     * 构造。
     *
     * @param port           监听端口（{@code hg.web.ws.port}）
     * @param heartbeatMillis 心跳间隔（{@code hg.web.ws.heartbeat_ms}，≥1000）
     * @param maxConnections 最大并发连接数（{@code hg.web.ws.max_connections}）
     * @param authFilter     握手鉴权（与 REST 同口径）
     * @param authorizer     `W-2` 子账户授权裁决
     * @param snapshotSource 首帧快照来源（可为 {@code null}）
     */
    public WsServer(int port, int heartbeatMillis, int maxConnections, AuthFilter authFilter,
                    WsAuthorizer authorizer, SnapshotSource snapshotSource) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("WS 端口须在 0..65535（0 = 由系统分配，仅供单测）：值=" + port
                    + "；复现=核对 CFG-32 hg.web.ws.port");
        }
        if (heartbeatMillis < 1000) {
            throw new IllegalArgumentException("WS 心跳须 ≥1000 ms：值=" + heartbeatMillis
                    + "；复现=核对 CFG-32 hg.web.ws.heartbeat_ms（§11.2 下限）");
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("WS 最大连接数须 ≥1：值=" + maxConnections);
        }
        this.port = port;
        this.heartbeatMillis = heartbeatMillis;
        this.maxConnections = maxConnections;
        this.authFilter = authFilter;
        this.authorizer = authorizer;
        this.snapshotSource = snapshotSource;
    }

    /**
     * 启动监听（**仅回环**）。
     *
     * @throws IOException 绑定失败
     */
    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        // 仅回环绑定（红线 9 / NFR-SEC-3）：显式指定 127.0.0.1，绝不 0.0.0.0
        ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        this.serverSocket = ss;
        acceptThread = new Thread(this::acceptLoop, "hg-ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        quoteTicker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hg-ws-quote-tick");
            t.setDaemon(true);
            return t;
        });
        quoteTicker.scheduleWithFixedDelay(this::flushQuotes, QUOTE_TICK_MILLIS, QUOTE_TICK_MILLIS,
                TimeUnit.MILLISECONDS);
        LOG.log(Level.INFO, "WS 服务已启动：ws://127.0.0.1:" + port + "（路径 /ws/quote、/ws/alert；"
                + "心跳 " + heartbeatMillis + "ms；上限 " + maxConnections + " 连接）");
    }

    /** @return 实际绑定端口（测试用临时端口时便于回读）。 */
    public int boundPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    /**
     * @return 实际绑定地址（供单测断言**仅回环**：{@code isLoopbackAddress()} 必须为真）
     */
    public InetSocketAddress boundAddress() {
        ServerSocket ss = serverSocket;
        return ss == null ? null : (InetSocketAddress) ss.getLocalSocketAddress();
    }

    /** 当前连接数。 */
    public int connectionCount() {
        return connections.size();
    }

    /**
     * 发布行情（**覆盖式 + 节流**）。
     *
     * <p>语义：**只保留最新值**，由 {@link #QUOTE_TICK_MILLIS} 节拍统一推送 ⇒
     * 天然满足"≤5 条/秒/合约"且"允许丢帧（仅推最新值）"（`ARCHITECTURE.md` §4.1.2 / §6.13）。
     * 本方法**非阻塞**、**不等待**任何连接。
     *
     * @param instrument 合约
     * @param last       最新价（字符串化十进制）
     * @param bid        买一价
     * @param ask        卖一价
     */
    public void publishQuote(String instrument, String last, String bid, String ask) {
        if (instrument == null || instrument.isEmpty()) {
            throw new IllegalArgumentException("合约不得为空；复现=检查行情事件来源");
        }
        // 覆盖式：同合约只保最新（旧值被覆盖 = 丢帧，属预期）
        pendingQuotes.put(instrument.toUpperCase(Locale.ROOT),
                WsMessages.quote(instrument, last, bid, ask));
    }

    /**
     * 发布告警（**事件驱动、不节流、不允许丢**）。
     *
     * <p>{@code subAccountId} 会在下发前再次与各连接的**已授权子账户集**求交
     * （即 {@link WsSubscription#matchesAlert}），因此**即使调用方传了越权子账户也不会泄露**。
     *
     * @param subAccountId 子账户（账户级为 0）
     * @param level        提示 / 警告 / 严重
     * @param metric       指标
     * @param value        当前值（字符串化十进制）
     * @param threshold    阈值（字符串化十进制）
     */
    public void publishAlert(int subAccountId, String level, String metric, String value,
                             String threshold) {
        String json = WsMessages.alert(level, metric, value, threshold, subAccountId);
        int delivered = 0;
        for (WsConnection c : connections.values()) {
            if (c.subscription().matchesAlert(subAccountId)) {
                c.sendAlert(json);
                delivered++;
            }
        }
        if (delivered > 0) {
            alertsSent.addAndGet(delivered);
        }
    }

    /** 运行时计数（运维/压测取证）。 */
    public Stats stats() {
        return new Stats(totalConnections.get(), connections.size(), rejectedOverload.get(),
                authFailures.get(), protocolErrors.get(), alertsSent.get(), droppedQuoteTotal());
    }

    @Override
    public void close() {
        closed = true;
        if (quoteTicker != null) {
            quoteTicker.shutdownNow();
        }
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "关闭 WS ServerSocket 失败（忽略）：" + e.getMessage());
            }
        }
        for (WsConnection c : connections.values()) {
            c.close(WsCloseCode.GOING_AWAY, "服务端停机");
        }
        connections.clear();
    }

    // ------------------------------------------------------------------ 内部

    private long droppedQuoteTotal() {
        long sum = 0;
        for (WsConnection c : connections.values()) {
            sum += c.droppedQuoteCount();
        }
        return sum;
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(Level.WARNING, "WS accept 失败：" + e.getMessage());
                }
                return;
            }
            if (connections.size() >= maxConnections) {
                rejectedOverload.incrementAndGet();
                rejectOverloaded(socket);
                continue;
            }
            Thread t = new Thread(() -> handleConnection(socket), "hg-ws-conn-" + connectionSeq.get());
            t.setDaemon(true);
            t.start();
        }
    }

    private void rejectOverloaded(Socket socket) {
        try (Socket s = socket) {
            byte[] body = WsHandshake.errorResponse(503,
                    "WebSocket 连接数已达上限 " + maxConnections + "，请稍后重连", TraceIds.next());
            String head = "HTTP/1.1 503 Service Unavailable\r\n"
                    + "Retry-After: 5\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().write(body);
            s.getOutputStream().flush();
        } catch (IOException e) {
            LOG.log(Level.FINE, "回写 503 失败（忽略）：" + e.getMessage());
        }
    }

    private void handleConnection(Socket socket) {
        String traceId = TraceIds.next();
        String clientIp = String.valueOf(socket.getInetAddress());
        WsConnection connection = null;
        long connId = connectionSeq.incrementAndGet();
        try {
            socket.setSoTimeout(HANDSHAKE_WAIT_MILLIS);
            RequestContext ctx = WsHandshake.readRequest(socket.getInputStream(), traceId, clientIp);
            if (ctx == null) {
                return;
            }
            OutputStream out = socket.getOutputStream();
            if (!ALLOWED_PATHS.contains(ctx.path())) {
                out.write(WsHandshake.errorResponse(404,
                        "未放行的 WebSocket 路径：" + ctx.path(), traceId));
                out.flush();
                return;
            }
            String invalid = WsHandshake.validateUpgrade(ctx);
            if (invalid != null) {
                out.write(WsHandshake.errorResponse(400, invalid, traceId));
                out.flush();
                return;
            }
            try {
                authFilter.authenticate(ctx);
            } catch (RuntimeException e) {
                authFailures.incrementAndGet();
                LOG.log(Level.INFO, "WS 握手鉴权失败：traceId=" + traceId + " path=" + ctx.path()
                        + " ip=" + clientIp);
                out.write(WsHandshake.errorResponse(401, "未提供有效会话凭据：请先登录", traceId));
                out.flush();
                return;
            }
            Session session = ctx.session();
            if (session == null) {
                authFailures.incrementAndGet();
                out.write(WsHandshake.errorResponse(401, "会话无效", traceId));
                out.flush();
                return;
            }
            out.write(WsHandshake.upgradeResponse(
                    WsHandshake.acceptKey(ctx.header("Sec-WebSocket-Key"))));
            out.flush();
            socket.setSoTimeout(0);

            connection = new WsConnection(connId, session, socket, out, heartbeatMillis,
                    QUEUE_CAPACITY);
            String channel = ctx.path().equals("/ws/alert")
                    ? WsSubscription.CHANNEL_ALERT : WsSubscription.CHANNEL_QUOTE;
            Set<Integer> effective = authorizer.authorize(session.userId, null,
                    "建立连接 " + ctx.path());
            connection.setSubscription(new WsSubscription(
                    Collections.singleton(channel), Collections.emptySet(), effective));
            connections.put(connId, connection);
            totalConnections.incrementAndGet();
            connection.start();
            LOG.log(Level.INFO, "WS 连接建立：id=" + connId + " userId=" + session.userId
                    + " path=" + ctx.path() + " 授权子账户数=" + effective.size() + " traceId=" + traceId);

            connection.sendControl(WsMessages.subscribed(connection.subscription().channels(),
                    connection.subscription().subAccounts()));
            sendSnapshot(connection);
            readLoop(connection, channel);
        } catch (WsProtocolException e) {
            protocolErrors.incrementAndGet();
            LOG.log(Level.INFO, "WS 协议违规：id=" + connId + " code=" + e.closeCode()
                    + " reason=" + e.getMessage());
            if (connection != null) {
                connection.close(e.closeCode(), e.getMessage());
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "WS 连接异常结束：id=" + connId + " " + e.getMessage());
        } finally {
            connections.remove(connId);
            if (connection != null) {
                connection.close(WsCloseCode.NORMAL, "bye");
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    private void readLoop(WsConnection connection, String defaultChannel) throws IOException {
        while (connection.isOpen()) {
            WsFrame frame = WsFrameCodec.readFrame(connection.input());
            if (frame == null) {
                return;
            }
            switch (frame.opcode()) {
                case PING:
                    connection.sendPong(frame.payload());
                    break;
                case PONG:
                    connection.onPong();
                    break;
                case CLOSE:
                    connection.close(WsFrameCodec.closeCodeOf(frame), "对端关闭");
                    return;
                case TEXT:
                    handleClientText(connection, defaultChannel, frame.text());
                    break;
                default:
                    throw new WsProtocolException(WsCloseCode.UNSUPPORTED_DATA,
                            "不支持的操作码 " + frame.opcode());
            }
        }
    }

    @SuppressWarnings("unchecked")   // JsonCodec 基于 Class<T>，Map.class 只能得到原始 Map；此处立即按 Map<String,Object> 使用
    private void handleClientText(WsConnection connection, String defaultChannel, String text) {
        Map<String, Object> msg;
        try {
            msg = JsonCodec.fromJson(text, Map.class);
        } catch (RuntimeException e) {
            connection.sendControl(WsMessages.error(10001, "订阅消息不是合法 JSON"));
            return;
        }
        Object type = msg == null ? null : msg.get("type");
        if (!(type instanceof String)) {
            connection.sendControl(WsMessages.error(10001, "订阅消息缺少 type"));
            return;
        }
        String t = (String) type;
        if ("subscribe".equals(t)) {
            doSubscribe(connection, defaultChannel, msg);
        } else if ("unsubscribe".equals(t)) {
            Set<String> remaining = new LinkedHashSet<>(connection.subscription().channels());
            for (String ch : channelsOf(msg)) {
                remaining.remove(ch);
            }
            connection.setSubscription(connection.subscription().withChannels(remaining));
            connection.sendControl(WsMessages.subscribed(connection.subscription().channels(),
                    connection.subscription().subAccounts()));
        } else {
            connection.sendControl(WsMessages.error(10001, "未知消息类型：" + t));
        }
    }

    private void doSubscribe(WsConnection connection, String defaultChannel, Map<String, Object> msg) {
        // ① 通道：**只允许本连接路径对应的通道**（端点即通道，避免跨端点越权）
        Set<String> channels = new LinkedHashSet<>();
        channels.add(defaultChannel);
        for (String ch : channelsOf(msg)) {
            if (!ch.equals(defaultChannel)) {
                connection.sendControl(WsMessages.error(10004,
                        "本连接端点仅支持通道 " + defaultChannel));
                return;
            }
            channels.add(ch);
        }
        // ② 子账户：与授权集求交（W-2，越权即拒 + 留痕）
        Set<Integer> requested = intsOf(msg.get("subAccounts"));
        Set<Integer> effective;
        try {
            effective = authorizer.authorize(connection.userId(), requested,
                    "订阅 " + defaultChannel + "（连接 " + connection.id() + "）");
        } catch (WsProtocolException e) {
            // 越权：先告知前端业务码，再按策略关闭
            connection.sendControl(WsMessages.error(10004, "子账户权限不足，订阅被拒绝"));
            throw e;
        }
        // ③ 合约白名单
        Set<String> instruments = new LinkedHashSet<>();
        for (Object o : listOf(msg.get("instruments"))) {
            if (o != null) {
                instruments.add(String.valueOf(o).toUpperCase(Locale.ROOT));
            }
        }
        connection.setSubscription(new WsSubscription(channels, instruments, effective));
        connection.sendControl(WsMessages.subscribed(channels, effective));
        sendSnapshot(connection);
    }

    private void sendSnapshot(WsConnection connection) {
        if (snapshotSource == null) {
            return;
        }
        WsSubscription sub = connection.subscription();
        if (sub.wantsQuote()) {
            for (String json : nullSafe(snapshotSource.latestQuoteFrames(sub.instruments()))) {
                connection.sendControl(json);
            }
        }
        if (sub.wantsAlert() && !sub.subAccounts().isEmpty()) {
            for (String json : nullSafe(snapshotSource.pendingAlertFrames(sub.subAccounts()))) {
                connection.sendControl(json);
            }
        }
    }

    private void flushQuotes() {
        if (closed || pendingQuotes.isEmpty()) {
            return;
        }
        List<String> drained = new ArrayList<>();
        for (String key : new ArrayList<>(pendingQuotes.keySet())) {
            String json = pendingQuotes.remove(key);
            if (json != null) {
                drained.add(json);
            }
        }
        for (String json : drained) {
            for (WsConnection c : connections.values()) {
                if (c.subscription().wantsQuote()) {
                    c.sendQuote(json);
                }
            }
        }
    }

    private static List<?> listOf(Object o) {
        return o instanceof List ? (List<?>) o : Collections.emptyList();
    }

    private static Set<String> channelsOf(Map<String, Object> msg) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : listOf(msg.get("channels"))) {
            if (o != null) {
                out.add(String.valueOf(o).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static Set<Integer> intsOf(Object o) {
        Set<Integer> out = new LinkedHashSet<>();
        for (Object e : listOf(o)) {
            if (e instanceof Number) {
                out.add(((Number) e).intValue());
            } else if (e != null) {
                try {
                    out.add(Integer.valueOf(String.valueOf(e).trim()));
                } catch (NumberFormatException ignored) {
                    // 非法数字忽略（不越权：忽略只会减少可见范围）
                }
            }
        }
        return out;
    }

    private static List<String> nullSafe(List<String> l) {
        return l == null ? Collections.emptyList() : l;
    }

    /** 运行时计数。 */
    public static final class Stats {

        private final long totalConnections;
        private final int currentConnections;
        private final long rejectedOverload;
        private final long authFailures;
        private final long protocolErrors;
        private final long alertsSent;
        private final long droppedQuote;

        Stats(long totalConnections, int currentConnections, long rejectedOverload,
              long authFailures, long protocolErrors, long alertsSent, long droppedQuote) {
            this.totalConnections = totalConnections;
            this.currentConnections = currentConnections;
            this.rejectedOverload = rejectedOverload;
            this.authFailures = authFailures;
            this.protocolErrors = protocolErrors;
            this.alertsSent = alertsSent;
            this.droppedQuote = droppedQuote;
        }

        /** 累计接受连接数。 */
        public long totalConnections() {
            return totalConnections;
        }

        /** 当前连接数。 */
        public int currentConnections() {
            return currentConnections;
        }

        /** 因超上限被拒的连接数。 */
        public long rejectedOverload() {
            return rejectedOverload;
        }

        /** 握手鉴权失败数。 */
        public long authFailures() {
            return authFailures;
        }

        /** 协议违规数。 */
        public long protocolErrors() {
            return protocolErrors;
        }

        /** 累计下发告警条数。 */
        public long alertsSent() {
            return alertsSent;
        }

        /** 累计丢弃的行情帧（背压证据；`ARCHITECTURE.md` §4.1.2 允许）。 */
        public long droppedQuote() {
            return droppedQuote;
        }

        @Override
        public String toString() {
            return "WsStats{current=" + currentConnections + "/total=" + totalConnections
                    + ", overloadRejected=" + rejectedOverload + ", authFailures=" + authFailures
                    + ", protocolErrors=" + protocolErrors + ", alertsSent=" + alertsSent
                    + ", droppedQuote=" + droppedQuote + '}';
        }
    }
}
