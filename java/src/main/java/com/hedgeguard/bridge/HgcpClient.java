package com.hedgeguard.bridge;

import com.hedgeguard.common.Clocks;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import hgcp.v1.Hgcp;

/**
 * HGCP 客户端（{@code ARCHITECTURE.md} §3.10 / §4.2.6；承载 {@code T-042}）。
 *
 * <p>职责：连接 / 鉴权 / 心跳 / 重连 / 补数 / protobuf 编解码 / 命令回执关联 /
 * 事件流按序消费 + 幂等去重 / 镜像状态构建。
 *
 * <p><b>不提供 {@code preCheck}</b> —— 事前风控判定只在 C++ 侧（§3.0.3）。
 *
 * <p><b>事件驱动、不轮询</b>：不主动查库、不主动查 C++（{@code PROJECT.md} §4.1 ⑥-d）。
 *
 * <p>线程模型：
 * <ul>
 *   <li><b>读线程</b>（{@code hgcp-reader}）：唯一消费 socket 的线程，
 *       解帧 → 分派（回执帧完成 future／事件帧更新镜像）。</li>
 *   <li><b>心跳线程</b>（{@code hgcp-heartbeat}）：按 {@value HgcpConstants#HEARTBEAT_INTERVAL_MS} ms
 *       发心跳；超时未收到 ack 达 {@value HgcpConstants#HEARTBEAT_MISS_THRESHOLD} 次 ⇒ 判定断连。</li>
 *   <li><b>调用方线程</b>：经 {@link #placeOrder} 等提交命令，拿 {@link CompletableFuture}。</li>
 * </ul>
 *
 * <p>⚠️ <b>诚实披露</b>：本类的验证形态为「单测 + 内存 {@link HgcpTransport} 驱动」。
 * <b>跨进程与 C++ 侧的联调不在本类验证范围</b>（{@code T-057}）。
 */
public final class HgcpClient implements TradingBridge, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HgcpClient.class.getName());

    /** 认证结果。 */
    public enum AuthState {
        /** 未握手。 */
        NOT_HANDSHAKEN,
        /** 握手已发，等 ack。 */
        HELLO_SENT,
        /** 已鉴权。 */
        AUTHENTICATED,
        /** 鉴权失败（终态，不重连）。 */
        AUTH_FAILED
    }

    private final String componentId;
    private final String authToken;
    private final String clientBootId;
    private final int tokenVersion;
    private final Supplier<HgcpTransport> transportFactory;

    private final AtomicLong msgIdSeq = new AtomicLong(1);
    private final AtomicLong nextSeq = new AtomicLong(1);
    private final AtomicLong lastReceivedSeq = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final IdempotencyCache idempotency = new IdempotencyCache();
    private final MirrorState mirror = new MirrorState();
    private final FrameStreamReader reader = new FrameStreamReader();

    private volatile HgcpTransport transport;
    private volatile AuthState authState = AuthState.NOT_HANDSHAKEN;
    private volatile String sessionId;
    private volatile Thread readerThread;
    private volatile Thread heartbeatThread;
    private volatile int heartbeatMisses;
    private volatile ReplayProgress replayProgress = new ReplayProgress();

    /**
     * 事件流消费监听接缝（{@code T-172}）。{@code null} = 未接线（行为与接线前一致）。
     * 由 {@link #setEventListener} 注入；读线程逐事件回调。
     */
    private volatile HgcpEventListener eventListener;

    /** 启动期全量参数下发钩子：HELLO_ACK 且组件非 READY 时异步触发（读线程不得阻塞）。 */
    private volatile Runnable startupDispatchHook;
    private final AtomicBoolean startupHookInFlight = new AtomicBoolean(false);
    /** 重连串行化：readLoop / heartbeatLoop 可能同时判定断连，只允许一个线程执行重连。 */
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    /** 连接代际：每次建连递增；过期读线程的迟到断连不得再触发重连（连锁循环根因）。 */
    private final AtomicLong connectionEpoch = new AtomicLong(0);

    /** 组件运行状态快照（阶段 1 监控面；与 MirrorState 物理隔离，绝不回写镜像）。 */
    private final ComponentStatus componentState = ComponentStatus.empty();
    private final Object componentStateLock = new Object();
    /** [链路] 最后收到任意 HGCP 帧的时点（epoch ms）。 */
    private volatile long lastFrameAtMs;
    /** [链路] 累计重连成功次数。 */
    private final AtomicLong reconnectCount = new AtomicLong(0);

    /**
     * 注册启动期全量参数下发钩子（可在 {@link #start()} 前后设置）。
     *
     * <p>仅当 {@code HELLO_ACK.accepted=true} 且 {@code server_state != SERVER_STATE_READY}
     * 时触发：C++ 未就绪 ⇒ 需要 {@code CMD_LOAD_RISK_PARAMS} 点亮；已 READY ⇒ 不重复下发。
     * 钩子在独立守护线程执行（DB 读 + 命令下发，不得占用读线程），且同一时刻仅一个在飞。
     */
    public void setStartupDispatchHook(Runnable hook) {
        this.startupDispatchHook = hook;
    }

    /**
     * 命令回执超时调度池（共享单线程、守护线程工厂）。每命令一线程改为共享池（{@code T-165}）；
     * {@code close()} 经 {@link #timeoutPool} 关停，close 后不再有悬挂超时任务。
     */
    private final ScheduledExecutorService timeoutPool =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hgcp-cmd-timeout-pool");
                t.setDaemon(true);
                return t;
            });

    /**
     * 命令回执超时值（ms）。缺省 {@value HgcpConstants#COMMAND_TIMEOUT_MS}；包内可注入仅为测试确定性（{@code T-165}），
     * 生产一律用缺省值，{@code HgcpConstants.COMMAND_TIMEOUT_MS} 本身不改。
     */
    volatile long commandTimeoutMs = HgcpConstants.COMMAND_TIMEOUT_MS;

    /**
     * 构造。
     *
     * @param componentId      组件标识
     * @param authToken        鉴权令牌（<b>不落日志</b>，{@code NFR-SEC-1}）
     * @param clientBootId     客户端启动标识（重连时保持稳定）
     * @param tokenVersion     令牌版本（{@code CH-013} {@code I-1}）
     * @param transportFactory 传输接缝工厂
     */
    public HgcpClient(String componentId, String authToken, String clientBootId,
                      int tokenVersion, Supplier<HgcpTransport> transportFactory) {
        this.componentId = componentId;
        this.authToken = authToken;
        this.clientBootId = clientBootId;
        this.tokenVersion = tokenVersion;
        this.transportFactory = transportFactory;
    }

    /**
     * 注册事件流消费监听器（{@code T-172} 接缝）。
     *
     * <p><b>nullable</b>：设 {@code null} 即注销，回到「仅推进水位」的接线前形态。
     * 可在 {@link #start()} 前后任意时刻调用；切换对读线程立即可见（{@code volatile}）。
     * 回调在 {@code hgcp-reader} 单线程内触发，实现方<b>不得阻塞</b>。
     *
     * @param listener 监听器；{@code null} 表示不接线
     */
    public void setEventListener(HgcpEventListener listener) {
        this.eventListener = listener;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动（建连 + 握手 + 起读线程与心跳线程）。
     *
     * @throws IOException         连接失败
     * @throws HgcpProtocolException 协议失败
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        connectAndHandshake();
        readerThread = new Thread(() -> readLoop(connectionEpoch.get()), "hgcp-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        heartbeatThread = new Thread(this::heartbeatLoop, "hgcp-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void connectAndHandshake() throws IOException {
        HgcpTransport old = transport;
        if (old != null) {
            // 重连前必须关闭旧连接：否则服务端单连接治理仍视旧连接为 active，新连接被回
            // 0x0302 ERR_DUPLICATE_CONNECTION 后立即关闭 ⇒ 读线程 EOF ⇒ 再次重连（实测循环根因）。
            try {
                old.close();
            } catch (Exception ignored) {
                // 旧连接已失效时关闭失败不影响新连接建立
            }
        }
        transport = transportFactory.get();
        transport.connect();
        reader.reset();
        authState = AuthState.HELLO_SENT;
        connectionEpoch.incrementAndGet();
        sendHello();
    }

    private void sendHello() throws IOException {
        Hgcp.MsgHello hello = Hgcp.MsgHello.newBuilder()
                .setProtoVersion(HgcpFrame.WIRE_VERSION)
                .setComponentId(componentId)
                .setComponentRole(Hgcp.ComponentRole.ROLE_JAVA)
                .setAuthToken(authToken)
                .setClientBootId(clientBootId)
                .setLastReceivedSeq(lastReceivedSeq.get())
                .setTokenVersion(tokenVersion)
                .build();
        writeFrame(HgcpConstants.MSG_HELLO, HgcpFrame.FLAG_NEED_ACK, hello.toByteArray());
    }

    /** 关闭。 */
    @Override
    public void close() {
        running.set(false);
        if (transport != null) {
            transport.close();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        timeoutPool.shutdownNow();
        failAllPending(new HgcpProtocolException("客户端已关闭"));
    }

    // ==================== 读循环 ====================

    private void readLoop(long epoch) {
        byte[] buf = new byte[65536];
        while (running.get() && connectionEpoch.get() == epoch) {
            try {
                int n = transport.read(buf);
                if (n < 0) {
                    // 仅当前代际的读线程可触发重连：重连完成后旧读线程的迟到 EOF/异常
                    // 若再次调用 onDisconnected()，会形成「重连→关闭旧连接→旧线程报错→再重连」循环。
                    if (connectionEpoch.get() == epoch) {
                        onDisconnected();
                    }
                    return;
                }
                reader.feed(buf, n);
                for (HgcpFrame.Decoded d : reader.drain()) {
                    onFrame(d);
                }
            } catch (java.net.SocketTimeoutException e) {
                // SO_TIMEOUT(1000ms) 只是读线程的可中断等待窗口，**不是断连**：
                // 与服务端心跳间隔 hg.tcp.heartbeat_ms=1000 同值，心跳帧稍晚即超时。
                // 误判为断连 ⇒ 重连 ⇒ 新连接再次超时 ⇒ 每约 1.5s 一次的周期循环（实测根因）。
                // 继续读即可；借每次超时检查代际，过期读线程静默退出。
                if (!running.get() || connectionEpoch.get() != epoch) {
                    return;
                }
            } catch (IOException e) {
                if (running.get() && connectionEpoch.get() == epoch) {
                    onDisconnected();
                }
                return;
            } catch (HgcpProtocolException e) {
                // 成帧失败：帧边界不可信，与 C++ 侧同姿态——关闭并重连补数，不尝试重同步
                LOG.log(Level.WARNING, "HGCP 成帧失败，关闭连接：" + e.getMessage());
                if (connectionEpoch.get() == epoch) {
                    onDisconnected();
                }
                return;
            }
        }
    }

    /**
     * 处理一帧（供测试直接驱动）。
     *
     * <p>等价于 {@link #onFrame(HgcpFrame.Decoded, boolean) onFrame(d, false)}——本帧
     * <b>不来自</b>补数批次。
     *
     * @param d 已解码帧
     */
    public void onFrame(HgcpFrame.Decoded d) {
        onFrame(d, false);
    }

    /**
     * 处理一帧，并携带「本帧是否来自补数批次」的进程内来源标识（{@code T-174}）。
     *
     * <p><b>不改线格式</b>：来源由调用方在进程内传递（{@link #onResumeData} 对
     * {@code MSG_RESUME_DATA} 批内帧置 {@code true}），不写入 {@code proto/hgcp.proto}、
     * 不动 32B 帧头。
     *
     * <p><b>{@code CH-007} 消费侧规则</b>（{@code ARCHITECTURE.md} §4.2.6）：补数批次中的
     * {@code EVT_MARKET_QUOTE}（{@code 0x020C}）<b>接收侧一律丢弃</b>——不消费、不推进镜像水位、
     * 不透传事件监听器、不推前端、不进入行情缓存（行情为「最近值」语义，重放陈旧价会误导）。
     * 其余事件帧（含全部关键事件）不受影响，同批照常消费。
     *
     * @param d               已解码帧
     * @param fromResumeBatch 是否来自 {@code MSG_RESUME_DATA} 批次
     */
    private void onFrame(HgcpFrame.Decoded d, boolean fromResumeBatch) {
        lastReceivedSeq.set(Math.max(lastReceivedSeq.get(), d.header.seq));
        heartbeatMisses = 0;
        lastFrameAtMs = Clocks.epochMilli();
        try {
            switch (d.header.type) {
                case HgcpConstants.MSG_HELLO_ACK:
                    onHelloAck(d);
                    break;
                case HgcpConstants.MSG_AUTH_FAIL:
                    authState = AuthState.AUTH_FAILED;
                    LOG.warning("HGCP 鉴权失败（不重连）");
                    running.set(false);
                    break;
                case HgcpConstants.MSG_HEARTBEAT_ACK:
                    break;
                case HgcpConstants.MSG_HEARTBEAT:
                    // W-11：心跳为双向契约（ARCHITECTURE.md §4.2.8.5 / §4.2.6 :978）——
                    // C++ 服务端定时发 MSG_HEARTBEAT，本端必须回 MSG_HEARTBEAT_ACK；
                    // 否则服务端连续 3 次未收 ACK ⇒ 0x0301 ERR_HEARTBEAT_TIMEOUT ⇒ 关连接重连。
                    onHeartbeat(d);
                    break;
                case HgcpConstants.MSG_ACK:
                    onAck(d);
                    break;
                case HgcpConstants.MSG_ERROR:
                    onError(d);
                    break;
                case HgcpConstants.MSG_RESUME_DATA:
                    onResumeData(d);
                    break;
                default:
                    if (isEventType(d.header.type)) {
                        // CH-007 消费侧规则：补数批次中的行情帧接收侧一律丢弃（不消费、不推进水位、
                        // 不透传监听器、不推前端、不进入行情缓存）。只拦 EVT_MARKET_QUOTE——
                        // 其余事件类型（含全部关键事件）同批照常消费，不得误伤。
                        if (fromResumeBatch
                                && d.header.type == HgcpConstants.EVT_MARKET_QUOTE) {
                            LOG.fine("补数批次内行情帧（0x" + Integer.toHexString(d.header.type)
                                    + "）按 CH-007 丢弃：seq=" + d.header.seq);
                            return;
                        }
                        onEvent(d);
                    } else {
                        LOG.fine("忽略未知类型帧：0x" + Integer.toHexString(d.header.type));
                    }
                    break;
            }
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new HgcpProtocolException("payload 解析失败：" + e.getMessage());
        }
    }

    /**
     * W-11：应答对端（C++ 服务端）的主动心跳。
     *
     * <p>按 {@code ARCHITECTURE.md §4.2.8.5 / §4.2.6 :978}「心跳双向」契约，收到
     * {@code MSG_HEARTBEAT} 须回 {@code MSG_HEARTBEAT_ACK}：{@code echo_ts_ms} 原样回显
     * 对端 {@code sent_ts_ms}，{@code server_ts_ms} 填本端时间（{@code proto/hgcp.proto:345-348}）。
     *
     * <p><b>写帧失败只告警、不主动重连</b>：本方法在读线程内执行，读循环自身会因
     * {@code read()} 失败进入 {@link #onDisconnected()} 重连流程；此处再触发会与读线程重入。
     */
    private void onHeartbeat(HgcpFrame.Decoded d)
            throws com.google.protobuf.InvalidProtocolBufferException {
        Hgcp.MsgHeartbeat hb = Hgcp.MsgHeartbeat.parseFrom(d.payload);
        Hgcp.MsgHeartbeatAck ack = Hgcp.MsgHeartbeatAck.newBuilder()
                .setEchoTsMs(hb.getSentTsMs())
                .setServerTsMs(Clocks.epochMilli())
                .build();
        try {
            writeFrame(HgcpConstants.MSG_HEARTBEAT_ACK, 0, ack.toByteArray());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "回 MSG_HEARTBEAT_ACK 失败（交由读线程重连）："
                    + e.getClass().getSimpleName());
        }
    }

    private void onHelloAck(HgcpFrame.Decoded d)
            throws com.google.protobuf.InvalidProtocolBufferException {
        Hgcp.MsgHelloAck ack = Hgcp.MsgHelloAck.parseFrom(d.payload);
        if (ack.getAccepted()) {
            sessionId = ack.getSessionId();
            authState = AuthState.AUTHENTICATED;
            mirror.setReadyState(mapServerState(ack.getServerState()));
            if (ack.getParamVersion() > 0) {
                mirror.setParamVersion(ack.getParamVersion());
            }
            // 阶段 1 监控面：握手回执即一次状态基线（[事件] 之外的链路侧基线）。
            synchronized (componentStateLock) {
                componentState.bridgeAssembled = true;
                componentState.linkEstablished = true;
                componentState.state = shortServerState(ack.getServerState());
                componentState.ready = (ack.getServerState() == Hgcp.ServerState.SERVER_STATE_READY);
                componentState.paramVersion = ack.getParamVersion();
                componentState.stateTsMs = Clocks.epochMilli();
            }
            // 组件未 READY ⇒ 需 CMD_LOAD_RISK_PARAMS 全量预加载点亮；已 READY ⇒ 不重复下发。
            if (ack.getServerState() != Hgcp.ServerState.SERVER_STATE_READY) {
                fireStartupDispatch();
            }
        } else {
            authState = AuthState.AUTH_FAILED;
            LOG.warning("HGCP 握手被拒（reason_code=" + ack.getReasonCode() + "）");
        }
    }

    /** 异步触发启动期全量参数下发（读线程不得阻塞；同一时刻仅一个在飞）。 */
    private void fireStartupDispatch() {
        Runnable hook = startupDispatchHook;
        if (hook == null || !startupHookInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                hook.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "启动全量参数下发失败（组件可能仍 NOT_READY）："
                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            } finally {
                startupHookInFlight.set(false);
            }
        }, "hgcp-startup-dispatch");
        t.setDaemon(true);
        t.start();
    }

    private void onAck(HgcpFrame.Decoded d)
            throws com.google.protobuf.InvalidProtocolBufferException {
        Hgcp.MsgAck ack = Hgcp.MsgAck.parseFrom(d.payload);
        PendingRequest p = pending.remove(ack.getAckMsgId());
        if (p == null) {
            return;
        }
        AckResult r = new AckResult();
        r.acknowledged = (ack.getResult() == 0);
        r.componentCode = ack.getReasonCode();
        r.message = ack.getDetail();
        r.paramVersion = ack.getAppliedVersion();
        r.ackTime = Clocks.now().toString();
        p.future.complete(r);
    }

    private void onError(HgcpFrame.Decoded d)
            throws com.google.protobuf.InvalidProtocolBufferException {
        Hgcp.MsgError err = Hgcp.MsgError.parseFrom(d.payload);
        PendingRequest p = (err.getRelatedMsgId() == 0)
                ? null : pending.remove(err.getRelatedMsgId());
        if (p == null) {
            LOG.warning("HGCP 错误帧 0x" + Integer.toHexString(err.getErrCode())
                    + "：" + err.getErrMsg());
            return;
        }
        AckResult r = new AckResult();
        r.acknowledged = false;
        r.componentCode = err.getErrCode();
        r.message = err.getErrMsg();
        r.ruleId = err.getRuleId();
        r.current = err.getCurrentValue();
        r.threshold = err.getThresholdValue();
        r.ackTime = Clocks.now().toString();
        p.future.complete(r);
    }

    private void onResumeData(HgcpFrame.Decoded d)
            throws com.google.protobuf.InvalidProtocolBufferException {
        Hgcp.MsgResumeData rd = Hgcp.MsgResumeData.parseFrom(d.payload);
        // CH-017 裁定：from/to = 本批首/末帧 SEQ；空批合法（不得据 from/to 断言 frames 非空）
        for (com.google.protobuf.ByteString raw : rd.getFramesList()) {
            HgcpFrame.Decoded inner = HgcpFrame.decode(raw.toByteArray(), 0, raw.size());
            if (inner.status != HgcpFrame.Status.OK) {
                throw new HgcpProtocolException(inner.status);
            }
            // T-174：携带来源标识——本帧来自补数批次，供 CH-007 消费侧规则判别。
            onFrame(inner, true);
        }
    }

    private void onEvent(HgcpFrame.Decoded d)
            throws com.google.protobuf.InvalidProtocolBufferException {
        long seq = d.header.seq;
        int type = d.header.type;
        switch (type) {
            case HgcpConstants.EVT_ORDER_ACCEPTED: {
                Hgcp.EvtOrderAccepted e = Hgcp.EvtOrderAccepted.parseFrom(d.payload);
                mirror.advanceWatermark(seq);
                HgcpEventListener.OrderAccepted out = new HgcpEventListener.OrderAccepted();
                out.orderId = e.getOrderId();
                out.clientOrderRef = e.getClientOrderRef();
                out.riskPassed = e.getRiskPassed();
                out.effectiveTimeoutMs = e.getEffectiveTimeoutMs();
                out.acceptedTsMs = e.getAcceptedTsMs();
                notifyListener(l -> l.onOrderAccepted(out));
                break;
            }
            case HgcpConstants.EVT_PRE_TRADE_REJECT: {
                Hgcp.EvtPreTradeReject e = Hgcp.EvtPreTradeReject.parseFrom(d.payload);
                mirror.advanceWatermark(seq);
                HgcpEventListener.PreTradeReject out = new HgcpEventListener.PreTradeReject();
                out.clientOrderRef = e.getClientOrderRef();
                out.subAccountId = e.getSubAccountId();
                out.productId = e.getProductId();
                out.instrumentId = e.getInstrumentId();
                out.ruleId = e.getRuleId();
                out.reasonCode = e.getReasonCode();
                out.limitLayer = e.getLimitLayer().name();
                out.currentValue = e.getCurrentValue();
                out.thresholdValue = e.getThresholdValue();
                out.orderDigest = e.getOrderDigest();
                out.operatorId = e.getOperatorId();
                out.rejectTsMs = e.getRejectTsMs();
                notifyListener(l -> l.onPreTradeReject(out));
                break;
            }
            case HgcpConstants.EVT_ORDER_REPORT: {
                Hgcp.EvtOrderReport e = Hgcp.EvtOrderReport.parseFrom(d.payload);
                mirror.advanceWatermark(seq);
                HgcpEventListener.OrderReport out = new HgcpEventListener.OrderReport();
                out.orderId = e.getOrderId();
                out.orderSysId = e.getOrderSysId();
                out.orderLocalId = e.getOrderLocalId();
                out.status = e.getStatus().name();
                out.volumeTotal = e.getVolumeTotal();
                out.volumeTraded = e.getVolumeTraded();
                out.volumeLeft = e.getVolumeLeft();
                out.ctpErrorId = e.getCtpErrorId();
                out.ctpErrorMsg = e.getCtpErrorMsg();
                out.reportTsMs = e.getReportTsMs();
                notifyListener(l -> l.onOrderReport(out));
                break;
            }
            case HgcpConstants.EVT_TRADE_FILL: {
                Hgcp.EvtTradeFill e = Hgcp.EvtTradeFill.parseFrom(d.payload);
                if (idempotency.first(e.getTradeId(), seq)) {
                    mirror.advanceWatermark(seq);
                    HgcpEventListener.TradeFill out = new HgcpEventListener.TradeFill();
                    out.tradeId = e.getTradeId();
                    out.orderId = e.getOrderId();
                    out.orderSysId = e.getOrderSysId();
                    out.instrumentId = e.getInstrumentId();
                    out.direction = e.getDirection().name();
                    out.offsetFlag = e.getOffsetFlag().name();
                    out.price = e.getPrice();
                    out.volume = e.getVolume();
                    out.tradeTsMs = e.getTradeTsMs();
                    out.subAccountId = e.getSubAccountId();
                    out.tradePlanId = e.getTradePlanId();
                    out.exposureId = e.getExposureId();
                    notifyListener(l -> l.onTradeFill(out));
                }
                break;
            }
            case HgcpConstants.EVT_POSITION_CHANGE: {
                Hgcp.EvtPositionChange e = Hgcp.EvtPositionChange.parseFrom(d.payload);
                if (idempotency.first(e.getInstrumentId() + "#" + e.getSubAccountId(), seq)) {
                    PositionView v = new PositionView();
                    v.subAccountId = e.getSubAccountId();
                    v.instrumentId = e.getInstrumentId();
                    v.positionQty = String.valueOf(e.getPositionQty());
                    v.availableQty = String.valueOf(e.getAvailableQty());
                    mirror.applyPosition(v);
                    mirror.advanceWatermark(seq);
                    HgcpEventListener.PositionChange out = new HgcpEventListener.PositionChange();
                    out.subAccountId = e.getSubAccountId();
                    out.instrumentId = e.getInstrumentId();
                    out.direction = e.getDirection().name();
                    out.positionType = e.getPositionType().name();
                    out.positionQty = e.getPositionQty();
                    out.availableQty = e.getAvailableQty();
                    out.frozenQty = e.getFrozenQty();
                    out.changeReason = e.getChangeReason();
                    out.asOfTsMs = e.getAsOfTsMs();
                    notifyListener(l -> l.onPositionChange(out));
                }
                break;
            }
            case HgcpConstants.EVT_ORDER_CANCEL_RESULT: {
                Hgcp.EvtOrderCancelResult e = Hgcp.EvtOrderCancelResult.parseFrom(d.payload);
                mirror.advanceWatermark(seq);
                HgcpEventListener.OrderCancelResult out = new HgcpEventListener.OrderCancelResult();
                out.orderId = e.getOrderId();
                out.cancelledVolume = e.getCancelledVolume();
                out.releasedQuota = e.getReleasedQuota();
                out.restoredPlanQty = e.getRestoredPlanQty();
                out.result = e.getResult();
                out.reasonCode = e.getReasonCode();
                notifyListener(l -> l.onOrderCancelResult(out));
                break;
            }
            case HgcpConstants.EVT_ORDER_EXPIRED: {
                Hgcp.EvtOrderExpired e = Hgcp.EvtOrderExpired.parseFrom(d.payload);
                mirror.advanceWatermark(seq);
                HgcpEventListener.OrderExpired out = new HgcpEventListener.OrderExpired();
                out.orderId = e.getOrderId();
                out.clientOrderRef = e.getClientOrderRef();
                out.expiredTsMs = e.getExpiredTsMs();
                out.timeoutMs = e.getTimeoutMs();
                notifyListener(l -> l.onOrderExpired(out));
                break;
            }
            case HgcpConstants.EVT_COMMAND_FLOW: {
                Hgcp.EvtCommandFlow e = Hgcp.EvtCommandFlow.parseFrom(d.payload);
                mirror.advanceWatermark(seq);
                HgcpEventListener.CommandFlow out = new HgcpEventListener.CommandFlow();
                out.orderId = e.getOrderId();
                out.flowType = e.getFlowType().name();
                out.flowTsMs = e.getFlowTsMs();
                out.detail = e.getDetail();
                notifyListener(l -> l.onCommandFlow(out));
                break;
            }
            case HgcpConstants.EVT_MARKET_QUOTE: {
                // T-040b 行情桥接：解析并透传监听器，但【不写镜像水位】——0x020C 为可丢例外，
                // 生产 C++ 侧 send_frame(type, 0u, body) 不占 SEQ（event_emitter.cc emit_quote）；
                // 若此处 advanceWatermark，会把 0 之外的无序行情帧计入水位、破坏
                // 「水位 = 已消费可靠事件流连续性判据」口径（t_mirror_watermark 复现依据）。
                // CH-007 拦截分支（补数批次内丢弃）在 onFrame 层先行处置，到不了这里。
                Hgcp.EvtMarketQuote e = Hgcp.EvtMarketQuote.parseFrom(d.payload);
                HgcpEventListener.MarketQuote out = new HgcpEventListener.MarketQuote();
                out.instrumentId = e.getInstrumentId();
                out.lastPrice = e.getLastPrice();
                out.bidPrice1 = e.getBidPrice1();
                out.askPrice1 = e.getAskPrice1();
                out.bidVolume1 = e.getBidVolume1();
                out.askVolume1 = e.getAskVolume1();
                out.preSettlement = e.getPreSettlement();
                out.quoteTsMs = e.getQuoteTsMs();
                out.sourceTsMs = e.getSourceTsMs();
                notifyListener(l -> l.onMarketQuote(out));
                break;
            }
            case HgcpConstants.EVT_FUND_RECEIPT: {
                Hgcp.EvtFundReceipt e = Hgcp.EvtFundReceipt.parseFrom(d.payload);
                if (idempotency.first(e.getApplyId(), seq)) {
                    FundView v = new FundView();
                    v.available = e.getAmount();
                    mirror.applyFund(v);
                    mirror.advanceWatermark(seq);
                }
                break;
            }
            case HgcpConstants.EVT_REPLAY_PROGRESS: {
                Hgcp.EvtReplayProgress e = Hgcp.EvtReplayProgress.parseFrom(d.payload);
                replayProgress = new ReplayProgress();
                replayProgress.state = mapServerState(e.getState());
                replayProgress.replayedCount = e.getReplayedRecords();
                replayProgress.totalCount = e.getTotalRecords();
                replayProgress.lastSeq = seq;
                mirror.setReadyState(replayProgress.state);
                mirror.advanceWatermark(seq);
                break;
            }
            case HgcpConstants.EVT_SYSTEM_STATE: {
                Hgcp.EvtSystemState e = Hgcp.EvtSystemState.parseFrom(d.payload);
                mirror.setReadyState(mapServerState(e.getState()));
                mirror.setParamVersion(e.getParamVersionApplied());
                mirror.advanceWatermark(seq);
                HgcpEventListener.SystemState out = new HgcpEventListener.SystemState();
                out.state = e.getState().name();
                out.paramVersionApplied = e.getParamVersionApplied();
                out.loadedCounts = e.getLoadedCounts();
                out.reason = e.getReason();
                out.tsMs = e.getTsMs();
                synchronized (componentStateLock) {
                    componentState.state = shortServerState(e.getState());
                    componentState.ready = (e.getState() == Hgcp.ServerState.SERVER_STATE_READY);
                    componentState.paramVersion = e.getParamVersionApplied();
                    componentState.loadedCounts = out.loadedCounts;
                    componentState.stateReason = out.reason;
                    componentState.stateTsMs = out.tsMs;
                }
                notifyListener(l -> l.onSystemState(out));
                break;
            }
            case HgcpConstants.EVT_CTP_STATE: {
                Hgcp.EvtCtpState e = Hgcp.EvtCtpState.parseFrom(d.payload);
                mirror.advanceWatermark(seq);
                HgcpEventListener.CtpState out = new HgcpEventListener.CtpState();
                out.tradeConnected = e.getTradeConnected();
                out.mdConnected = e.getMdConnected();
                out.frontAddr = e.getFrontAddr();
                out.stateChangeTsMs = e.getStateChangeTsMs();
                out.reason = e.getReason();
                synchronized (componentStateLock) {
                    componentState.ctpTradeConnected = out.tradeConnected;
                    componentState.ctpMdConnected = out.mdConnected;
                    componentState.ctpFront = out.frontAddr;
                    componentState.ctpStateChangeTsMs = out.stateChangeTsMs;
                    componentState.ctpReason = out.reason;
                }
                notifyListener(l -> l.onCtpState(out));
                break;
            }
            case HgcpConstants.EVT_TRADING_ALERT: {
                Hgcp.EvtTradingAlert e = Hgcp.EvtTradingAlert.parseFrom(d.payload);
                mirror.advanceWatermark(seq);
                HgcpEventListener.TradingAlert out = new HgcpEventListener.TradingAlert();
                out.alertCode = e.getAlertCode();
                out.level = e.getLevel().name();
                out.detail = e.getDetail();
                out.alertTsMs = e.getAlertTsMs();
                synchronized (componentStateLock) {
                    componentState.lastAlertCode = out.alertCode;
                    componentState.lastAlertLevel = out.level;
                    componentState.lastAlertDetail = out.detail;
                    componentState.lastAlertTsMs = out.alertTsMs;
                }
                notifyListener(l -> l.onTradingAlert(out));
                break;
            }
            default:
                // 其余事件（行情/CTP 状态/告警）：本里程碑不落镜像，
                // 仅推进水位（水位 = 已消费事件流的连续性判据）
                mirror.advanceWatermark(seq);
                break;
        }
        notifyConsumed(type, seq);
    }

    /**
     * 通知监听器的具体事件回调。
     *
     * <p><b>异常安全</b>（{@code NFR-REL-5}）：监听器抛出的 {@link RuntimeException}
     * 在此被捕获并记日志，<b>不得中断事件消费</b>。
     */
    private void notifyListener(java.util.function.Consumer<HgcpEventListener> cb) {
        HgcpEventListener l = eventListener;
        if (l == null) {
            return;
        }
        try {
            cb.accept(l);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "HGCP 事件监听器异常（已忽略，不中断消费）：" + ex.getMessage(), ex);
        }
    }

    /**
     * 通知监听器「每消费一事件」的评估泵回调（含镜像与水位处理之后）。
     *
     * <p>异常安全同 {@link #notifyListener}。
     */
    private void notifyConsumed(int type, long seq) {
        HgcpEventListener l = eventListener;
        if (l == null) {
            return;
        }
        try {
            l.onEventConsumed(type, seq);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "HGCP 事件消费泵异常（已忽略，不中断消费）：" + ex.getMessage(), ex);
        }
    }

    private static boolean isEventType(int type) {
        return type >= HgcpConstants.EVT_ORDER_ACCEPTED
                && type <= HgcpConstants.EVT_TRADING_ALERT;
    }

    private static ReplayProgress.State mapServerState(Hgcp.ServerState s) {
        switch (s) {
            case SERVER_STATE_NOT_READY:
                return ReplayProgress.State.NOT_READY;
            case SERVER_STATE_REPLAYING:
                return ReplayProgress.State.REPLAYING;
            case SERVER_STATE_READY:
                return ReplayProgress.State.READY;
            default:
                return ReplayProgress.State.NOT_READY;
        }
    }

    // ==================== 心跳与重连 ====================

    private void heartbeatLoop() {
        while (running.get()) {
            try {
                Thread.sleep(HgcpConstants.HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get() || authState != AuthState.AUTHENTICATED) {
                continue;
            }
            heartbeatMisses++;
            if (heartbeatMisses > HgcpConstants.HEARTBEAT_MISS_THRESHOLD) {
                LOG.warning("HGCP 心跳超时，判定断连");
                onDisconnected();
                return;
            }
            try {
                Hgcp.MsgHeartbeat hb = Hgcp.MsgHeartbeat.newBuilder()
                        .setSentTsMs(Clocks.epochMilli())
                        .setLastReceivedSeq(lastReceivedSeq.get())
                        .build();
                writeFrame(HgcpConstants.MSG_HEARTBEAT, 0, hb.toByteArray());
            } catch (IOException e) {
                onDisconnected();
                return;
            }
        }
    }

    private void onDisconnected() {
        // readLoop / heartbeatLoop / writeFrame 可能同时判定断连：只允许一个线程重连，
        // 否则会并发建连并启动多个 reader 线程（0x0302 / 0x0301 周期循环的第二成因）。
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }
        try {
            failAllPending(new HgcpProtocolException("连接中断"));
            if (!running.get()) {
                return;
            }
            // 指数退避重连：500ms 起、8000ms 封顶（CFG-28 / timing.h）
            long backoff = HgcpConstants.RECONNECT_BASE_MS;
            while (running.get()) {
                try {
                    Thread.sleep(backoff);
                    connectAndHandshake();
                    sendResumeRequest();
                    restartReader();
                    reconnectCount.incrementAndGet();
                    LOG.info("HGCP 重连成功");
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    backoff = Math.min(backoff * 2, HgcpConstants.RECONNECT_CAP_MS);
                }
            }
        } finally {
            reconnecting.set(false);
        }
    }

    private void sendResumeRequest() throws IOException {
        Hgcp.MsgResumeReq req = Hgcp.MsgResumeReq.newBuilder()
                .setLastReceivedSeq(lastReceivedSeq.get())
                .setSessionId(sessionId == null ? "" : sessionId)
                .build();
        writeFrame(HgcpConstants.MSG_RESUME_REQ, HgcpFrame.FLAG_NEED_ACK, req.toByteArray());
    }

    private void restartReader() {
        Thread old = readerThread;
        if (old != null && old.isAlive()) {
            // 旧读线程须先退出：避免两个线程同时消费同一 transport（半包/帧序错乱）。
            old.interrupt();
        }
        long epoch = connectionEpoch.get();
        Thread t = new Thread(() -> readLoop(epoch), "hgcp-reader");
        t.setDaemon(true);
        readerThread = t;
        t.start();
    }

    private void failAllPending(HgcpProtocolException ex) {
        for (PendingRequest p : pending.values()) {
            pending.remove(p.msgId);
            AckResult r = new AckResult();
            r.acknowledged = false;
            r.componentCode = ex.componentCode();
            r.message = ex.getMessage();
            p.future.complete(r);
        }
    }

    // ==================== 命令下发 ====================

    /**
     * 下发交易指令（<b>本调用不写库</b>）。
     *
     * @param cmd 指令
     * @return 受理结果
     */
    @Override
    public CompletableFuture<PlaceOrderResult> placeOrder(PlaceOrderCmd cmd) {
        CompletableFuture<PlaceOrderResult> f = new CompletableFuture<>();
        if (!isReady()) {
            PlaceOrderResult r = new PlaceOrderResult();
            r.accepted = false;
            r.systemNotReady = true;
            r.componentCode = HgcpConstants.ERR_SYSTEM_NOT_READY;
            r.clientOrderRef = cmd.clientOrderRef;
            f.complete(r);
            return f;
        }
        long msgId = msgIdSeq.getAndIncrement();
        pending.put(msgId, new PendingRequest(msgId, null));
        try {
            Hgcp.CmdPlaceOrder.Builder b = Hgcp.CmdPlaceOrder.newBuilder()
                    .setClientOrderRef(cmd.clientOrderRef)
                    .setTradePlanId(cmd.tradePlanId)
                    .setExposureId(cmd.exposureId)
                    .setSubAccountId(cmd.subAccountId)
                    .setProductId((int) cmd.productId)
                    .setInstrumentId(cmd.instrumentId)
                    .setInstrumentType(Hgcp.InstrumentType.INSTRUMENT_TYPE_FUTURES)
                    .setDirection(mapDirection(cmd.side))
                    .setOffsetFlag(mapOffsetFlag(cmd.offsetFlag))
                    .setPriceType(mapPriceType(cmd.priceType))
                    .setVolume(Integer.parseInt(cmd.volume))
                    .setOperatorId(cmd.operatorId)
                    .setTradeReasonCode(mapTradeReasonCode(cmd.tradeReasonCode))
                    .setTradeReasonTimeDesc(mapTradeReasonTimeDesc(cmd.tradeReasonTimeDesc))
                    .setTradeReasonText(cmd.tradeReasonText == null ? "" : cmd.tradeReasonText)
                    .setRelatedPlanId(cmd.relatedPlanId)
                    .setRelatedExposureId(cmd.relatedExposureId)
                    .setClientTsMs(Clocks.epochMilli());
            if (cmd.price != null && !cmd.price.isEmpty()) {
                b.setLimitPrice(cmd.price);
            }
            PendingRequest pr = pending.get(msgId);
            pr.placeOrderFuture = f;
            pr.clientOrderRef = cmd.clientOrderRef;
            writeFrame(HgcpConstants.CMD_PLACE_ORDER,
                    HgcpFrame.FLAG_NEED_ACK, b.build().toByteArray(), msgId);
            scheduleTimeout(msgId);
        } catch (IOException | RuntimeException e) {
            pending.remove(msgId);
            PlaceOrderResult r = new PlaceOrderResult();
            r.accepted = false;
            r.clientOrderRef = cmd.clientOrderRef;
            r.message = String.valueOf(e.getMessage());
            f.complete(r);
        }
        return f;
    }

    /**
     * 人工确认指令（人工确认是强制环节，§2.2）。
     *
     * <p>{@code operatorId} 直接传入线格式必填字段，<b>不再</b>经字符串解析——
     * 消除"非数字操作人串静默退化为 0（= 系统）"的鉴权降级路径（{@code CH-018}）。
     *
     * @param orderId      C++ 指令号
     * @param operatorId   操作人 ID（须与提交人一致，否则 {@code 0x0305}）
     * @param confirmToken 确认令牌
     * @return 回执
     */
    @Override
    public CompletableFuture<AckResult> confirmOrder(long orderId, long operatorId,
                                                     String confirmToken) {
        Hgcp.CmdConfirmOrder body = Hgcp.CmdConfirmOrder.newBuilder()
                .setOrderId(orderId)
                .setOperatorId(operatorId)
                .setConfirmTsMs(Clocks.epochMilli())
                .setConfirmToken(confirmToken == null ? "" : confirmToken)
                .build();
        return sendCommand(HgcpConstants.CMD_CONFIRM_ORDER, body.toByteArray());
    }

    /**
     * 撤单。
     *
     * @param orderId    C++ 指令号
     * @param reason     撤单原因
     * @param operatorId 操作人
     * @return 回执
     */
    @Override
    public CompletableFuture<AckResult> cancelOrder(long orderId, String reason, long operatorId) {
        Hgcp.CmdCancelOrder body = Hgcp.CmdCancelOrder.newBuilder()
                .setOrderId(orderId)
                .setOperatorId(operatorId)
                .setReason(reason == null ? "" : reason)
                .build();
        return sendCommand(HgcpConstants.CMD_CANCEL_ORDER, body.toByteArray());
    }

    /**
     * 参数下发（改 Java 侧 → 下发 C++ → 等回执 → 留痕的统一入口，§3.8）。
     *
     * @param change 参数变更
     * @return 回执（{@code 10014 参数下发未生效} 的判定依据）
     */
    @Override
    public CompletableFuture<AckResult> dispatch(ParamChange change) {
        int type = mapParamChangeType(change.type);
        byte[] body;
        try {
            body = ParamChangeEncoder.encode(change);
        } catch (IllegalArgumentException e) {
            CompletableFuture<AckResult> f = new CompletableFuture<>();
            AckResult r = new AckResult();
            r.acknowledged = false;
            r.message = e.getMessage();
            f.complete(r);
            return f;
        }
        return sendCommand(type, body);
    }

    private CompletableFuture<AckResult> sendCommand(int type, byte[] body) {
        CompletableFuture<AckResult> f = new CompletableFuture<>();
        if (authState != AuthState.AUTHENTICATED) {
            AckResult r = new AckResult();
            r.acknowledged = false;
            r.componentCode = HgcpConstants.ERR_SYSTEM_NOT_READY;
            r.message = "组件未就绪或未鉴权";
            f.complete(r);
            return f;
        }
        long msgId = msgIdSeq.getAndIncrement();
        pending.put(msgId, new PendingRequest(msgId, f));
        try {
            writeFrame(type, HgcpFrame.FLAG_NEED_ACK, body, msgId);
            scheduleTimeout(msgId);
        } catch (IOException e) {
            pending.remove(msgId);
            AckResult r = new AckResult();
            r.acknowledged = false;
            r.message = String.valueOf(e.getMessage());
            f.complete(r);
        }
        return f;
    }

    /**
     * 命令回执超时：{@value HgcpConstants#COMMAND_TIMEOUT_MS} ms 未回执 ⇒ 标记未生效（{@code CFG-28}）。
     */
    private void scheduleTimeout(long msgId) {
        PendingRequest p = pending.get(msgId);
        if (p == null) {
            return;
        }
        // 共享单线程调度池（T-165，替换原「每命令一线程」）：守护属性由线程工厂保持；
        // 超时值 / pending.remove 短路 / AckResult 与 PlaceOrderResult 内容一律未改。
        // close() 已关停池 ⇒ 拒绝调度且不 complete（close 后不再有超时触发）。
        try {
            timeoutPool.schedule(() -> {
                PendingRequest cur = pending.remove(msgId);
                if (cur == null) {
                    return;
                }
                AckResult r = new AckResult();
                r.acknowledged = false;
                r.message = "命令回执超时（" + HgcpConstants.COMMAND_TIMEOUT_MS + " ms），标记未生效";
                if (cur.future != null) {
                    cur.future.complete(r);
                }
                if (cur.placeOrderFuture != null) {
                    PlaceOrderResult pr = new PlaceOrderResult();
                    pr.accepted = false;
                    pr.clientOrderRef = cur.clientOrderRef;
                    pr.message = r.message;
                    cur.placeOrderFuture.complete(pr);
                }
            }, commandTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // 池已关停（close 后迟到注册）：不调度、不 complete，静默返回
        }
    }

    // ==================== 状态查询 ====================

    /** 组件是否 READY（启动重放完成、风控参数已加载）。 */
    @Override
    public boolean isReady() {
        return authState == AuthState.AUTHENTICATED
                && mirror.readyState() == ReplayProgress.State.READY;
    }

    /** 启动重放进度。 */
    @Override
    public ReplayProgress replayProgress() {
        return replayProgress;
    }

    /** 只读镜像状态（事中风控的数据来源；不可变快照）。 */
    @Override
    public MirrorSnapshot mirror() {
        return mirror.snapshot();
    }

    /**
     * 主动查询 C++ 组件状态（{@code CMD_QUERY_STATUS 0x011C}，阶段 1 监控面）。
     *
     * <p><b>纪律</b>：仅供「按需核验 / 对账 / 健康探针」使用，<b>不得</b>定时轮询并把
     * 结果同步回 {@link MirrorState}（{@code TradingBridge} §3.10 冻结纪律）。
     * 本方法只发一帧、复用 {@link #sendCommand} 的 ACK 关联与超时，不新建连接、不改线格式；
     * {@code what} 位语义由阶段 2 补全，阶段 1 传 {@code 0}（C++ 侧现忽略该字段并返回全量摘要）。
     *
     * @param what {@code CmdQueryStatus.what} bitmask（阶段 1 传 {@code 0}）
     * @return 回执；{@link AckResult#message} 即 {@code MSG_ACK.detail}
     */
        public CompletableFuture<AckResult> queryStatus(int what) {
            byte[] body = Hgcp.CmdQueryStatus.newBuilder().setWhat(what).build().toByteArray();
            return sendCommand(HgcpConstants.CMD_QUERY_STATUS, body);
        }

        /**
         * CH-027：动态行情订阅（CMD_SET_QUOTE_SUBSCRIPTION 0x011F）。
         * 合约集完整替换；空集 = 退订全部。交易所原生大小写原样传给 C++。
         */
        @Override
        public CompletableFuture<AckResult> setQuoteSubscription(List<String> instruments) {
            Hgcp.CmdSetQuoteSubscription.Builder b = Hgcp.CmdSetQuoteSubscription.newBuilder();
            if (instruments != null) {
                for (String s : instruments) {
                    if (s != null && !s.isEmpty()) {
                        b.addInstruments(s);
                    }
                }
            }
            return sendCommand(HgcpConstants.CMD_SET_QUOTE_SUBSCRIPTION, b.build().toByteArray());
        }

    /**
     * 组件运行状态只读快照（阶段 1 监控面）。
     *
     * <p><b>不查 C++、不查库</b>：只由 {@code [事件]} 推送与 {@code [链路]} 自身观测构建，
     * 与 {@link #mirror()} 同为内存快照（{@code R-RISK-10}）。需要主动核验时由
     * {@code ComponentMonitor.refresh()} 显式调 {@link #queryStatus(int)}，本方法绝不主动发帧。
     */
    public ComponentStatus componentStatus() {
        synchronized (componentStateLock) {
            componentState.bridgeAssembled = true;
            componentState.linkEstablished = (authState == AuthState.AUTHENTICATED);
            componentState.lastFrameAtMs = lastFrameAtMs;
            componentState.connectionEpoch = connectionEpoch.get();
            componentState.reconnectCount = reconnectCount.get();
            componentState.watermarkEventSeq = mirror.snapshot().watermarkEventSeq;
            componentState.takenAt = Clocks.now().toString();
            return componentState.copy();
        }
    }

    private static String shortServerState(Hgcp.ServerState s) {
        String name = s.name();
        final String prefix = "SERVER_STATE_";
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    /** 当前鉴权状态（诊断/测试用）。 */
    public AuthState authState() {
        return authState;
    }

    /** 会话 id（握手成功后有效）。 */
    public String sessionId() {
        return sessionId;
    }

    /** 幂等缓存（测试/诊断用）。 */
    public IdempotencyCache idempotencyCache() {
        return idempotency;
    }

    /** 镜像构建器（测试/诊断用）。 */
    public MirrorState mirrorState() {
        return mirror;
    }

    // ==================== 帧写出 ====================

    private void writeFrame(int type, int flags, byte[] payload) throws IOException {
        writeFrame(type, flags, payload, msgIdSeq.getAndIncrement());
    }

    private void writeFrame(int type, int flags, byte[] payload, long msgId) throws IOException {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = type;
        h.flags = flags;
        h.seq = nextSeq.getAndIncrement();
        h.msgId = msgId;
        transport.send(HgcpFrame.encode(h, payload));
    }

    // ==================== 映射（Java 字面量 → protobuf 枚举）====================

    /**
     * 买卖方向映射。接受 {@code BUY}/{@code SELL}（含 {@code DIRECTION_} 前缀形态）。
     *
     * @param side 方向字面量
     * @return 枚举
     */
    public static Hgcp.Direction mapDirection(String side) {
        if (side == null) {
            return Hgcp.Direction.DIRECTION_UNSET;
        }
        String s = side.trim().toUpperCase(java.util.Locale.ROOT);
        if (s.endsWith("BUY")) {
            return Hgcp.Direction.DIRECTION_BUY;
        }
        if (s.endsWith("SELL")) {
            return Hgcp.Direction.DIRECTION_SELL;
        }
        return Hgcp.Direction.DIRECTION_UNSET;
    }

    /**
     * 开平标志映射。接受 {@code OPEN}/{@code CLOSE}/{@code CLOSE_TODAY}。
     *
     * @param offsetFlag 开平字面量
     * @return 枚举
     */
    public static Hgcp.OffsetFlag mapOffsetFlag(String offsetFlag) {
        if (offsetFlag == null) {
            return Hgcp.OffsetFlag.OFFSET_FLAG_UNSET;
        }
        String s = offsetFlag.trim().toUpperCase(java.util.Locale.ROOT);
        if (s.endsWith("CLOSE_TODAY")) {
            return Hgcp.OffsetFlag.OFFSET_FLAG_CLOSE_TODAY;
        }
        if (s.endsWith("CLOSE")) {
            return Hgcp.OffsetFlag.OFFSET_FLAG_CLOSE;
        }
        if (s.endsWith("OPEN")) {
            return Hgcp.OffsetFlag.OFFSET_FLAG_OPEN;
        }
        return Hgcp.OffsetFlag.OFFSET_FLAG_UNSET;
    }

    /**
     * 价格类型映射。接受 {@code LIMIT}/{@code MARKET}。
     *
     * @param priceType 价格类型字面量
     * @return 枚举
     */
    public static Hgcp.PriceType mapPriceType(String priceType) {
        if (priceType == null) {
            return Hgcp.PriceType.PRICE_TYPE_UNSET;
        }
        String s = priceType.trim().toUpperCase(java.util.Locale.ROOT);
        if (s.endsWith("LIMIT")) {
            return Hgcp.PriceType.PRICE_TYPE_LIMIT;
        }
        if (s.endsWith("MARKET")) {
            return Hgcp.PriceType.PRICE_TYPE_MARKET;
        }
        return Hgcp.PriceType.PRICE_TYPE_UNSET;
    }

    /**
     * 交易理由关联类型映射（{@code T-173}/{@code CH-020}）。接受 {@code ReasonInput.relatedType} 的
     * 8 值字面量；其中 {@code FORCE_CLOSE}/{@code DELIVERY} 为 CTP 直生成交专用，
     * 协议枚举 {@code TradeReasonCode} <b>无</b>对应值 ⇒ 映射为 {@code ..._UNSET}（见 T-173 披露项）。
     *
     * @param code 关联类型字面量
     * @return 线格式枚举
     */
    public static Hgcp.TradeReasonCode mapTradeReasonCode(String code) {
        if (code == null) {
            return Hgcp.TradeReasonCode.TRADE_REASON_CODE_UNSET;
        }
        String s = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (s.endsWith("NEW_EXPOSURE")) {
            return Hgcp.TradeReasonCode.TRADE_REASON_CODE_NEW_EXPOSURE;
        }
        if (s.endsWith("EXPOSURE_DECREASE")) {
            return Hgcp.TradeReasonCode.TRADE_REASON_CODE_EXPOSURE_DECREASE;
        }
        if (s.endsWith("PLAN_EXECUTION")) {
            return Hgcp.TradeReasonCode.TRADE_REASON_CODE_PLAN_EXECUTION;
        }
        if (s.endsWith("ROLLOVER")) {
            return Hgcp.TradeReasonCode.TRADE_REASON_CODE_ROLLOVER;
        }
        if (s.endsWith("RISK_DISPOSAL")) {
            return Hgcp.TradeReasonCode.TRADE_REASON_CODE_RISK_DISPOSAL;
        }
        if (s.endsWith("ERROR_CORRECTION")) {
            return Hgcp.TradeReasonCode.TRADE_REASON_CODE_ERROR_CORRECTION;
        }
        return Hgcp.TradeReasonCode.TRADE_REASON_CODE_UNSET;
    }

    /**
     * 交易理由时点说明映射（{@code T-173}/{@code CH-020}）。接受 5 值字面量。
     *
     * @param desc 时点说明字面量
     * @return 线格式枚举
     */
    public static Hgcp.TradeReasonTimeDesc mapTradeReasonTimeDesc(String desc) {
        if (desc == null) {
            return Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_UNSET;
        }
        String s = desc.trim().toUpperCase(java.util.Locale.ROOT);
        if (s.endsWith("CONTRACT_SIGNED")) {
            return Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_CONTRACT_SIGNED;
        }
        if (s.endsWith("PRICE_TRIGGERED")) {
            return Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_PRICE_TRIGGERED;
        }
        if (s.endsWith("MONTHLY_REBALANCE")) {
            return Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_MONTHLY_REBALANCE;
        }
        if (s.endsWith("EXPIRY_ROLLOVER")) {
            return Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_EXPIRY_ROLLOVER;
        }
        if (s.endsWith("RISK_REQUIRED")) {
            return Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_RISK_REQUIRED;
        }
        return Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_UNSET;
    }

    /**
     * {@link ParamChange.Type} → 消息类型码。
     *
     * @param t 类型
     * @return 类型码
     */
    public static int mapParamChangeType(ParamChange.Type t) {
        switch (t) {
            case CMD_UPDATE_WHITELIST:
                return HgcpConstants.CMD_UPDATE_WHITELIST;
            case CMD_UPDATE_QUOTA:
                return HgcpConstants.CMD_UPDATE_QUOTA;
            case CMD_UPDATE_RISK_THRESHOLD:
                return HgcpConstants.CMD_UPDATE_RISK_THRESHOLD;
            case CMD_UPDATE_EXPOSURE:
                return HgcpConstants.CMD_UPDATE_EXPOSURE;
            case CMD_UPDATE_POSITION_LIMIT:
                return HgcpConstants.CMD_UPDATE_POSITION_LIMIT;
            case CMD_UPDATE_PERMISSION:
                return HgcpConstants.CMD_UPDATE_PERMISSION;
            case CMD_SET_TRADING_SUSPEND:
                return HgcpConstants.CMD_SET_TRADING_SUSPEND;
            case CMD_SET_READY_STATE:
                return HgcpConstants.CMD_SET_READY_STATE;
            case CMD_LOAD_RISK_PARAMS:
                return HgcpConstants.CMD_LOAD_RISK_PARAMS;
            case CMD_TRIGGER_REPLAY:
                return HgcpConstants.CMD_TRIGGER_REPLAY;
            case CMD_RELOAD:
                return HgcpConstants.CMD_RELOAD;
            default:
                throw new IllegalArgumentException("未知参数变更类型：" + t);
        }
    }

    /** 在途请求（回执关联）。 */
    private static final class PendingRequest {
        final long msgId;
        final CompletableFuture<AckResult> future;
        volatile CompletableFuture<PlaceOrderResult> placeOrderFuture;
        volatile String clientOrderRef;

        PendingRequest(long msgId, CompletableFuture<AckResult> future) {
            this.msgId = msgId;
            this.future = future;
        }
    }
}
