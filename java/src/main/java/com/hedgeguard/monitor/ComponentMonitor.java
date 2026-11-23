package com.hedgeguard.monitor;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.ComponentAlert;
import com.hedgeguard.bridge.ComponentStatus;
import com.hedgeguard.bridge.HgcpClient;
import com.hedgeguard.bridge.HgcpEventListener;
import com.hedgeguard.bridge.StatusDetailParser;
import com.hedgeguard.common.Clocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * C++ 组件监控服务（阶段 1）。
 *
 * <p><b>触发纪律（关键）</b>：本服务<b>不设任何定时器</b>。{@link #snapshot()} 只读内存缓存，
 * 绝不主动查 C++；只有 {@link #refresh()}（运维显式 {@code ?refresh=1} / 连接重建后基线核验 /
 * 外部健康探针）才发一帧 {@code CMD_QUERY_STATUS}。这是为了遵守 {@code TradingBridge} §3.10
 * 「不得以定时轮询同步两侧状态」——查询结果是<b>核验</b>，绝不回写 {@code MirrorState}。
 *
 * <p><b>单飞</b>：并发 refresh 共享同一个 {@link CompletableFuture}，不叠加查询帧，
 * 避免撑高 SEQ / 触发背压。超时由 {@code HgcpClient#sendCommand} 的 {@code scheduleTimeout}
 * 兜底（一定 complete），本类不额外计时。
 *
 * <p>数据来源与 C++ 改动：阶段 1 <b>零 proto、零 C++ 改动</b>，只解析现有
 * {@code MSG_ACK.detail}（{@code ready=...;param_version=...;load=x/y}）。
 */
public final class ComponentMonitor implements HgcpEventListener {

    /** 查询执行器接缝（供单测以替身注入，生产为 {@code HgcpClient::queryStatus}）。 */
    @FunctionalInterface
    public interface QueryExecutor {
        /** 发一次查询。 */
        CompletableFuture<AckResult> query(int what);
    }

    /**
     * 阶段 2 的 {@code what} 位掩码全量：
     * {@code 0x01} 状态机/就绪/版本 · {@code 0x02} 重放 · {@code 0x04} 参数批次
     * · {@code 0x08} 队列/运行计数/CTP/uptime。
     *
     * <p>C++ 侧把 {@code what==0} 也视作全量（兼容阶段 1 客户端），故本常量
     * 改为显式按位请求后，新旧服务端都能正确响应。
     */
    public static final int WHAT_ALL = 0x0F;

    private final Supplier<ComponentStatus> statusSource;
    private final QueryExecutor queryExecutor;
    /** 在飞的 refresh（单飞判据；null = 空闲）。 */
    private final AtomicReference<CompletableFuture<ComponentStatus>> inFlight =
            new AtomicReference<>();
    private volatile LastQuery lastQuery = LastQuery.none();

    /** 告警历史上限（有界，防内存增长；仅内存留痕，重启不保留）。 */
    public static final int MAX_ALERT_HISTORY = 100;

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ComponentMonitor.class.getName());

    private volatile ComponentAlertSink alertSink;
    /** 当前活跃告警的去重键（条件持续成立期间只发一次，解除后可再次触发）。 */
    private final Set<String> activeAlerts = ConcurrentHashMap.newKeySet();
    private final Deque<ComponentAlert> alertHistory = new ArrayDeque<>();

    /** 生产装配：组件桥可为 {@code null}（凭据缺失/未配库），此时如实返回「未装配」快照。 */
    public ComponentMonitor(HgcpClient client) {
        this(client == null ? ComponentStatus::empty : client::componentStatus,
                client == null ? ComponentMonitor::bridgeMissing : client::queryStatus);
    }

    /** 包内可见构造：供单测注入状态源与查询替身。 */
    ComponentMonitor(Supplier<ComponentStatus> statusSource, QueryExecutor queryExecutor) {
        this.statusSource = statusSource;
        this.queryExecutor = queryExecutor;
    }

    private static CompletableFuture<AckResult> bridgeMissing(int what) {
        AckResult r = new AckResult();
        r.acknowledged = false;
        r.message = "组件桥未装配（HGCP 凭据缺失或未配库）";
        return CompletableFuture.completedFuture(r);
    }

    /**
     * 注入告警出口（可空）。未装配 WS 时不推送，但告警仍进入有界内存历史，
     * 可经 {@code GET /monitor/component} 的 {@code alerts} 数组查得。
     */
    public void setAlertSink(ComponentAlertSink sink) {
        this.alertSink = sink;
    }

    // ---- 阶段 3：事件驱动告警评估（回调在 hgcp-reader 单线程，禁止阻塞） ----

    @Override
    public void onSystemState(HgcpEventListener.SystemState e) {
        evaluate(snapshot());
    }

    @Override
    public void onCtpState(HgcpEventListener.CtpState e) {
        evaluate(snapshot());
    }

    @Override
    public void onTradingAlert(HgcpEventListener.TradingAlert e) {
        evaluate(snapshot());
    }

    /**
     * 按阈值评估当前快照，返回<b>本次新增</b>的告警（去重后）。
     *
     * <p>阈值口径（阶段 3）：状态非 READY、链路未鉴权、CTP 降级/未登录、
     * 累计重连 &gt; 0、事件队列积压/背压 &gt; 0。同一条件持续成立只发一次，
     * 条件解除后从活跃集移除，允许再次触发（不刷屏、不漏报）。
     */
    private List<ComponentAlert> evaluate(ComponentStatus s) {
        Map<String, ComponentAlert> desired = new LinkedHashMap<>();
        final long now = Clocks.epochMilli();
        final String ts = Clocks.now().toString();
        final String state = s.state == null ? "UNKNOWN" : s.state;

        if (!s.ready || !"READY".equals(state)) {
            desired.put("component.state", new ComponentAlert("component.state", "CRITICAL",
                    "component.state", state, "READY",
                    "组件未就绪，交易指令将被 0x0401 拒绝", now, ts));
        }
        if (!s.linkEstablished) {
            desired.put("component.link", new ComponentAlert("component.link", "CRITICAL",
                    "component.link", "false", "linkEstablished=true",
                    "HGCP 链路未鉴权/组件桥未装配", now, ts));
        }
        if (Boolean.TRUE.equals(s.ctpDegraded) || Boolean.FALSE.equals(s.ctpLoggedIn)) {
            desired.put("component.ctp", new ComponentAlert("component.ctp", "WARNING",
                    "component.ctp",
                    "degraded=" + s.ctpDegraded + ",loggedIn=" + s.ctpLoggedIn,
                    "loggedIn=true,degraded=false",
                    "CTP 前置降级或会话未登录（如实披露，不作真联结论）", now, ts));
        }
        if (s.reconnectCount > 0) {
            desired.put("component.reconnect", new ComponentAlert("component.reconnect", "WARNING",
                    "component.reconnect", String.valueOf(s.reconnectCount), "0",
                    "累计发生 HGCP 重连", now, ts));
        }
        if (s.eventQueueDepth > 0 || s.backpressureCount > 0) {
            desired.put("component.queue", new ComponentAlert("component.queue", "WARNING",
                    "component.queue",
                    "eventQueueDepth=" + s.eventQueueDepth + ",backpressure=" + s.backpressureCount,
                    "0", "事件队列积压或发生背压", now, ts));
        }

        List<ComponentAlert> newly = new ArrayList<>();
        for (ComponentAlert a : desired.values()) {
            if (activeAlerts.add(a.code)) {
                record(a);
                newly.add(a);
            }
        }
        for (String code : new ArrayList<>(activeAlerts)) {
            if (!desired.containsKey(code)) {
                activeAlerts.remove(code);
            }
        }
        return newly;
    }

    private void record(ComponentAlert a) {
        synchronized (alertHistory) {
            alertHistory.addLast(a);
            while (alertHistory.size() > MAX_ALERT_HISTORY) {
                alertHistory.removeFirst();
            }
        }
        ComponentAlertSink sink = alertSink;
        if (sink != null) {
            try {
                sink.onAlert(a);
            } catch (RuntimeException e) {
                // 不带异常对象：读线程内不打印堆栈（避免刷屏），仍留可定位的类别与消息
                LOG.warning("组件告警出口异常（已忽略，不中断监控）："
                        + e.getClass().getSimpleName() + "：" + e.getMessage());
            }
        }
    }

    /**
     * 当前状态快照（<b>默认路径，不查 C++、不查库</b>）。
     *
     * <p>合并：{@code [事件]/[链路]} 内存状态 + 最近一次 {@code [查询]} 核验结果。
     * 两者均标注时点，供调用方判断新鲜度，不得当作实时真值。
     */
    public ComponentStatus snapshot() {
        return merge(lastQuery, false);
    }

    /** 等待 refresh 超时时返回带 {@code refreshPending=true} 标记的缓存快照。 */
    public ComponentStatus snapshotPending() {
        return merge(lastQuery, true);
    }

    /**
     * 主动核验一次（<b>唯一会发帧的入口</b>）。并发调用共享同一 future（严格单飞）。
     *
     * @return 查询完成后的最新快照；永不抛异常（失败也返回带错误说明的快照）
     */
    public CompletableFuture<ComponentStatus> refresh() {
        CompletableFuture<ComponentStatus> existing = inFlight.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<ComponentStatus> created = new CompletableFuture<>();
        if (!inFlight.compareAndSet(null, created)) {
            return inFlight.get();
        }
        try {
            queryExecutor.query(WHAT_ALL).whenComplete((ack, ex) -> {
                LastQuery q = (ex != null)
                        ? LastQuery.failure(String.valueOf(ex.getMessage()))
                        : LastQuery.of(ack);
                lastQuery = q;
                inFlight.compareAndSet(created, null);
                // 查询后的快照最完整（含阶段 2 JSON 指标）⇒ 在此做一次阈值评估。
                ComponentStatus s = merge(q, false);
                List<ComponentAlert> newly = evaluate(s);
                if (!newly.isEmpty()) {
                    List<ComponentAlert> all = new ArrayList<>(s.alerts);
                    all.addAll(newly);
                    s.alerts = all;
                }
                created.complete(s);
            });
        } catch (RuntimeException e) {
            lastQuery = LastQuery.failure(String.valueOf(e.getMessage()));
            inFlight.compareAndSet(created, null);
            ComponentStatus s = merge(lastQuery, false);
            evaluate(s);
            created.complete(s);
        }
        return created;
    }

    private ComponentStatus merge(LastQuery q, boolean refreshPending) {
        ComponentStatus s = statusSource.get().copy();
        s.lastQueryTsMs = q.tsMs;
        s.lastQueryOk = q.ok;
        s.lastQueryMessage = q.message;
        s.queryDetail = q.detail;
        s.refreshPending = refreshPending;
        // 查询成功且解析出字段才覆盖/补齐；解析不出（格式未知/异常）时
        // 一律保留事件侧值，不臆造、不猜值。
        StatusDetailParser.Parsed p = q.parsed;
        if (p != null && !p.unparsed) {
            if (p.ready != null) {
                s.ready = p.ready;
            }
            if (p.paramVersion != null) {
                s.paramVersion = p.paramVersion;
            }
            if (p.loadReceived != null) {
                s.loadBatchesReceived = p.loadReceived;
            }
            if (p.loadTotal != null) {
                s.loadBatchesTotal = p.loadTotal;
            }
            if (p.state != null) {
                s.componentState = p.state;
            }
            if (p.serverState != null) {
                s.serverState = p.serverState;
                if ("UNKNOWN".equals(s.state)) {
                    s.state = p.serverState;  // 事件侧尚无基线时用协议态兜底
                }
            }
            if (p.replayRuns != null) {
                s.replayRuns = p.replayRuns;
            }
            if (p.replayStartedMs != null) {
                s.replayStartedMs = p.replayStartedMs;
            }
            if (p.replayState != null) {
                s.replayState = p.replayState;
            }
            if (p.eventQueueDepth != null) {
                s.eventQueueDepth = p.eventQueueDepth;
            }
            if (p.eventQueueCapacity != null) {
                s.eventQueueCapacity = p.eventQueueCapacity;
            }
            if (p.backpressure != null) {
                s.backpressureCount = p.backpressure;
            }
            if (p.counters != null) {
                s.counters = new LinkedHashMap<>(p.counters);
            }
            if (p.ctpTradeConnected != null) {
                s.ctpTradeConnected = p.ctpTradeConnected;
            }
            if (p.ctpMdConnected != null) {
                s.ctpMdConnected = p.ctpMdConnected;
            }
            if (p.ctpFront != null) {
                s.ctpFront = p.ctpFront;
            }
            s.ctpLoggedIn = p.ctpLoggedIn;
            s.ctpDegraded = p.ctpDegraded;
            if (p.uptimeMs != null) {
                s.uptimeMs = p.uptimeMs;
            }
            if (p.tsMs != null) {
                s.componentTsMs = p.tsMs;
            }
            if (p.what != null) {
                s.queryWhat = p.what;
            }
        }
        s.takenAt = Clocks.now().toString();
        synchronized (alertHistory) {
            s.alerts = new ArrayList<>(alertHistory);
        }
        return s;
    }

    /**
     * 最近一次主动核验结果（不可变值对象）。
     *
     * <p>阶段 2 起直接持有 {@link StatusDetailParser.Parsed}：阶段 1 的 key=value
     * 与阶段 2 的 JSON 走同一套宽容解析，字段扩展不再改本类结构。
     */
    private static final class LastQuery {
        final long tsMs;
        final boolean ok;
        final String message;
        final String detail;
        /** null = 无解析结果（从未核验 / 回执失败）；由 merge 决定是否覆盖事件侧值。 */
        final StatusDetailParser.Parsed parsed;

        private LastQuery(long tsMs, boolean ok, String message, String detail,
                          StatusDetailParser.Parsed parsed) {
            this.tsMs = tsMs;
            this.ok = ok;
            this.message = message;
            this.detail = detail;
            this.parsed = parsed;
        }

        static LastQuery none() {
            return new LastQuery(0L, false, null, null, null);
        }

        static LastQuery failure(String message) {
            return new LastQuery(Clocks.epochMilli(), false, message, null, null);
        }

        static LastQuery of(AckResult ack) {
            String detail = (ack == null) ? null : ack.message;
            boolean ok = ack != null && ack.acknowledged;
            String message = (ack == null) ? "回执为空" : ack.message;
            StatusDetailParser.Parsed p = ok ? StatusDetailParser.parse(detail) : null;
            return new LastQuery(Clocks.epochMilli(), ok, message, detail, p);
        }
    }
}
