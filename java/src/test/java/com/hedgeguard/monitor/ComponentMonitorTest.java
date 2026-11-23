package com.hedgeguard.monitor;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.ComponentAlert;
import com.hedgeguard.bridge.ComponentStatus;
import com.hedgeguard.bridge.HgcpClient;
import com.hedgeguard.bridge.HgcpEventListener;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.handler.monitor.MonitorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1 监控面 · L3 服务与 REST 行为断言。
 *
 * <p>核心不变量：① 默认 {@code snapshot()} / 无参 GET <b>绝不查 C++</b>；
 * ② {@code refresh()} 严格单飞，不叠加查询帧；③ 查询失败或阶段 2 JSON 形态
 * 不得以「猜值」覆盖事件侧状态；④ 组件桥未装配须如实披露，不假装可用。
 */
class ComponentMonitorTest {

    private static ComponentStatus base() {
        ComponentStatus s = ComponentStatus.empty();
        s.bridgeAssembled = true;
        s.linkEstablished = true;
        s.state = "READY";
        s.ready = true;
        s.paramVersion = 5;
        return s;
    }

    private static AckResult ok(String detail) {
        AckResult r = new AckResult();
        r.acknowledged = true;
        r.message = detail;
        return r;
    }

    private static AckResult failed(String message) {
        AckResult r = new AckResult();
        r.acknowledged = false;
        r.message = message;
        return r;
    }

    private static final class FakeQuery implements ComponentMonitor.QueryExecutor {
        final AtomicInteger calls = new AtomicInteger();
        volatile CompletableFuture<AckResult> next;
        volatile int lastWhat = -1;

        @Override
        public CompletableFuture<AckResult> query(int what) {
            lastWhat = what;
            calls.incrementAndGet();
            CompletableFuture<AckResult> f = next;
            return f != null ? f
                    : CompletableFuture.completedFuture(ok("ready=true;param_version=7;load=1/1"));
        }
    }

    @Test
    @DisplayName("默认快照不查 C++：只合并事件/链路内存状态")
    void snapshotDoesNotQuery() {
        FakeQuery q = new FakeQuery();
        ComponentMonitor m = new ComponentMonitor(ComponentMonitorTest::base, q);
        ComponentStatus s = m.snapshot();
        assertEquals(0, q.calls.get(), "默认路径绝不允许发 CMD_QUERY_STATUS 帧");
        assertEquals("READY", s.state);
        assertEquals(5, s.paramVersion);
        assertEquals(0L, s.lastQueryTsMs, "从未核验");
        assertNotNull(s.takenAt);
    }

    @Test
    @DisplayName("refresh 解析现有 detail 并覆盖就绪/版本/批次")
    void refreshParsesDetail() throws Exception {
        FakeQuery q = new FakeQuery();
        ComponentMonitor m = new ComponentMonitor(ComponentMonitorTest::base, q);
        ComponentStatus s = m.refresh().get(3, TimeUnit.SECONDS);
        assertEquals(1, q.calls.get());
        assertTrue(s.lastQueryOk);
        assertTrue(s.ready);
        assertEquals(7, s.paramVersion);
        assertEquals(1, s.loadBatchesReceived);
        assertEquals(1, s.loadBatchesTotal);
        assertEquals("ready=true;param_version=7;load=1/1", s.queryDetail);
        assertTrue(s.lastQueryTsMs > 0);
        assertFalse(s.refreshPending);
    }

    @Test
    @DisplayName("并发 refresh 严格单飞：共享同一 future，查询只发一次")
    void refreshIsSingleFlight() throws Exception {
        FakeQuery q = new FakeQuery();
        CompletableFuture<AckResult> pending = new CompletableFuture<>();
        q.next = pending;
        ComponentMonitor m = new ComponentMonitor(ComponentMonitorTest::base, q);

        CompletableFuture<ComponentStatus> f1 = m.refresh();
        CompletableFuture<ComponentStatus> f2 = m.refresh();
        assertEquals(1, q.calls.get(), "在飞期间不得叠加查询帧（避免撑高 SEQ / 背压）");
        assertTrue(f1 == f2, "并发调用须拿到同一个 future");

        pending.complete(ok("ready=true;param_version=8;load=2/2"));
        assertTrue(f1.get(3, TimeUnit.SECONDS).lastQueryOk);
        assertEquals(8, f2.get(3, TimeUnit.SECONDS).paramVersion);
    }

    @Test
    @DisplayName("查询失败：保留事件侧状态，如实标 lastQueryOk=false，不抛")
    void failedAckKeepsEventState() throws Exception {
        FakeQuery q = new FakeQuery();
        q.next = CompletableFuture.completedFuture(failed("命令回执超时"));
        ComponentMonitor m = new ComponentMonitor(ComponentMonitorTest::base, q);
        ComponentStatus s = m.refresh().get(3, TimeUnit.SECONDS);
        assertFalse(s.lastQueryOk);
        assertEquals("命令回执超时", s.lastQueryMessage);
        assertTrue(s.ready, "失败不得把事件侧 ready 猜成 false");
        assertEquals(5, s.paramVersion, "失败不得把事件侧版本猜成 0");
    }

    @Test
    @DisplayName("阶段 2：refresh 按 what=0x0F 显式请求全量段")
    void refreshRequestsAllWhatBits() throws Exception {
        FakeQuery q = new FakeQuery();
        ComponentMonitor m = new ComponentMonitor(ComponentMonitorTest::base, q);
        m.refresh().get(3, TimeUnit.SECONDS);
        assertEquals(0x0F, ComponentMonitor.WHAT_ALL);
        assertEquals(0x0F, q.lastWhat, "阶段 2 位掩码须显式按位请求");
    }

    @Test
    @DisplayName("阶段 2 JSON detail：解析并覆盖/补齐状态与全部运行期指标")
    void jsonDetailIsApplied() throws Exception {
        String json = "{\"v\":2,\"tsMs\":123456,\"what\":15,\"state\":\"READY\","
                + "\"serverState\":\"READY\",\"ready\":true,\"paramVersion\":9,\"uptimeMs\":8888,"
                + "\"replay\":{\"runs\":2,\"startedMs\":1000,\"state\":\"READY\"},"
                + "\"load\":{\"received\":1,\"total\":1},"
                + "\"queues\":{\"event\":0,\"eventCapacity\":16384,\"backpressure\":1},"
                + "\"counters\":{\"framesIn\":42,\"commands\":3},"
                + "\"ctp\":{\"tradeConnected\":true,\"mdConnected\":false,\"front\":\"tcp://x\","
                + "\"loggedIn\":false,\"degraded\":true}}";
        FakeQuery q = new FakeQuery();
        q.next = CompletableFuture.completedFuture(ok(json));
        ComponentMonitor m = new ComponentMonitor(ComponentMonitorTest::base, q);
        ComponentStatus s = m.refresh().get(3, TimeUnit.SECONDS);
        assertTrue(s.lastQueryOk);
        assertEquals(9, s.paramVersion, "阶段 2 JSON 的 paramVersion 须覆盖事件侧");
        assertEquals("READY", s.componentState);
        assertEquals("READY", s.serverState);
        assertEquals(2, s.replayRuns);
        assertEquals(1000, s.replayStartedMs);
        assertEquals("READY", s.replayState);
        assertEquals(1, s.loadBatchesReceived);
        assertEquals(1, s.loadBatchesTotal);
        assertEquals(16384, s.eventQueueCapacity);
        assertEquals(1, s.backpressureCount);
        assertNotNull(s.counters);
        assertEquals(Long.valueOf(42), s.counters.get("framesIn"));
        assertEquals("tcp://x", s.ctpFront);
        assertTrue(s.ctpTradeConnected);
        assertFalse(s.ctpMdConnected);
        assertEquals(Boolean.FALSE, s.ctpLoggedIn);
        assertEquals(Boolean.TRUE, s.ctpDegraded);
        assertEquals(8888, s.uptimeMs);
        assertEquals(123456, s.componentTsMs);
        assertEquals(15, s.queryWhat);
    }

    @Test
    @DisplayName("阶段 2 非法 JSON：保留事件侧状态，不臆造")
    void malformedJsonKeepsEventState() throws Exception {
        FakeQuery q = new FakeQuery();
        q.next = CompletableFuture.completedFuture(ok("{not-json"));
        ComponentMonitor m = new ComponentMonitor(ComponentMonitorTest::base, q);
        ComponentStatus s = m.refresh().get(3, TimeUnit.SECONDS);
        assertTrue(s.lastQueryOk);
        assertTrue(s.ready);
        assertEquals(5, s.paramVersion);
        assertEquals(-1, s.replayRuns);
        assertEquals(-1, s.eventQueueCapacity);
    }

    @Test
    @DisplayName("组件桥未装配：如实披露，refresh 也不抛异常")
    void bridgeMissingIsHonest() throws Exception {
        ComponentMonitor m = new ComponentMonitor((HgcpClient) null);
        ComponentStatus s = m.snapshot();
        assertFalse(s.bridgeAssembled);
        assertFalse(s.linkEstablished);
        assertEquals("UNKNOWN", s.state);
        ComponentStatus after = m.refresh().get(3, TimeUnit.SECONDS);
        assertFalse(after.lastQueryOk);
        assertNotNull(after.lastQueryMessage);
    }

    @Test
    @DisplayName("snapshotPending 显式标记超时，不假装成功")
    void snapshotPendingIsMarked() {
        ComponentMonitor m = new ComponentMonitor(ComponentMonitorTest::base, new FakeQuery());
        assertTrue(m.snapshotPending().refreshPending);
        assertFalse(m.snapshot().refreshPending);
    }

    // ==================== REST 端点 ====================

    private static RequestContext ctx(String rawQuery) {
        return new RequestContext("GET", ApiRoutes.full("/monitor/component"), rawQuery,
                Collections.emptyMap(), "", "HGMON", "127.0.0.1");
    }

    private static String body(com.hedgeguard.web.HttpResult r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("GET 无 refresh 参数：200 且不触发查询")
    void handlerDefaultDoesNotQuery() {
        FakeQuery q = new FakeQuery();
        MonitorHandler h = new MonitorHandler(
                new ComponentMonitor(ComponentMonitorTest::base, q));
        com.hedgeguard.web.HttpResult r = h.handle(ctx(null));
        assertEquals(200, r.status);
        assertEquals(0, q.calls.get());
        assertTrue(body(r).contains("\"ready\""), "响应须含组件状态字段");
    }

    @Test
    @DisplayName("GET ?refresh=1：触发恰好一次查询，回执失败也返回 200 不假装成功")
    void handlerRefreshOnceAndHonestOnFailure() {
        FakeQuery q = new FakeQuery();
        q.next = CompletableFuture.completedFuture(failed("组件回执超时"));
        MonitorHandler h = new MonitorHandler(
                new ComponentMonitor(ComponentMonitorTest::base, q));
        com.hedgeguard.web.HttpResult r = h.handle(ctx("refresh=1"));
        assertEquals(200, r.status);
        assertEquals(1, q.calls.get());
        assertTrue(body(r).contains("\"lastQueryOk\":false"));
    }

    @Test
    @DisplayName("monitor 为 null（测试装配）：如实返回未装配快照，不 NPE")
    void handlerToleratesNullMonitor() {
        com.hedgeguard.web.HttpResult r = new MonitorHandler(null).handle(ctx(null));
        assertEquals(200, r.status);
        assertTrue(body(r).contains("\"bridgeAssembled\":false"));
    }

    // ==================== 阶段 3：阈值告警 / 去重 / 留痕 ====================

    private static ComponentStatus badStatus() {
        ComponentStatus s = ComponentStatus.empty();
        s.bridgeAssembled = true;
        s.linkEstablished = false;
        s.state = "NOT_READY";
        s.ready = false;
        s.ctpDegraded = Boolean.TRUE;
        s.ctpLoggedIn = Boolean.FALSE;
        s.reconnectCount = 2;
        s.eventQueueDepth = 3;
        s.backpressureCount = 1;
        return s;
    }

    private static ComponentStatus healthyStatus() {
        ComponentStatus s = ComponentStatus.empty();
        s.bridgeAssembled = true;
        s.linkEstablished = true;
        s.state = "READY";
        s.ready = true;
        s.ctpDegraded = Boolean.FALSE;
        s.ctpLoggedIn = Boolean.TRUE;
        s.reconnectCount = 0;
        s.eventQueueDepth = 0;
        s.backpressureCount = 0;
        return s;
    }

    /** 让 refresh 不覆盖事件侧状态（失败回执 ⇒ parsed=null）。 */
    private static ComponentMonitor monitored(AtomicReference<ComponentStatus> cur,
                                              List<ComponentAlert> sink) {
        FakeQuery q = new FakeQuery();
        q.next = CompletableFuture.completedFuture(failed("跳过查询"));
        ComponentMonitor m = new ComponentMonitor(cur::get, q);
        m.setAlertSink(sink::add);
        return m;
    }

    @Test
    @DisplayName("阶段 3：5 类阈值告警各触发一次，持续异常不刷屏（去重）")
    void thresholdsFireOnceAndDedup() throws Exception {
        AtomicReference<ComponentStatus> cur = new AtomicReference<>(badStatus());
        List<ComponentAlert> sink = new ArrayList<>();
        ComponentMonitor m = monitored(cur, sink);

        m.refresh().get(3, TimeUnit.SECONDS);
        assertEquals(5, sink.size(), "state/link/ctp/reconnect/queue 五类阈值各一条");
        Set<String> codes = new HashSet<>();
        for (ComponentAlert a : sink) {
            codes.add(a.code);
            assertNotNull(a.level);
            assertNotNull(a.metric);
            assertNotNull(a.threshold);
            assertTrue(a.metric.startsWith("component."));
        }
        assertEquals(5, codes.size(), "去重键须互异");

        // 条件持续成立 ⇒ 不得重复推送
        m.refresh().get(3, TimeUnit.SECONDS);
        m.refresh().get(3, TimeUnit.SECONDS);
        assertEquals(5, sink.size(), "同一条件持续成立期间只发一次");
    }

    @Test
    @DisplayName("阶段 3：条件解除后可再次触发（不漏报）")
    void alertRearmsAfterRecovery() throws Exception {
        AtomicReference<ComponentStatus> cur = new AtomicReference<>(badStatus());
        List<ComponentAlert> sink = new ArrayList<>();
        ComponentMonitor m = monitored(cur, sink);

        m.refresh().get(3, TimeUnit.SECONDS);
        assertEquals(5, sink.size());
        cur.set(healthyStatus());
        m.refresh().get(3, TimeUnit.SECONDS);
        assertEquals(5, sink.size(), "健康态不新增告警");
        cur.set(badStatus());
        m.refresh().get(3, TimeUnit.SECONDS);
        assertEquals(10, sink.size(), "解除后再次异常须重新告警");
    }

    @Test
    @DisplayName("阶段 3：告警进入有界内存历史，随快照返回")
    void alertHistoryIsBoundedAndReturned() throws Exception {
        AtomicReference<ComponentStatus> cur = new AtomicReference<>(badStatus());
        List<ComponentAlert> sink = new ArrayList<>();
        ComponentMonitor m = monitored(cur, sink);
        ComponentStatus s = m.refresh().get(3, TimeUnit.SECONDS);
        assertNotNull(s.alerts);
        assertEquals(5, s.alerts.size(), "本次新增告警须出现在响应快照中");

        // 交替异常/恢复 120 轮 ⇒ 历史须被 MAX_ALERT_HISTORY 截断
        for (int i = 0; i < 120; i++) {
            cur.set(healthyStatus());
            m.refresh().get(3, TimeUnit.SECONDS);
            cur.set(badStatus());
            m.refresh().get(3, TimeUnit.SECONDS);
        }
        ComponentStatus last = m.snapshot();
        assertTrue(last.alerts.size() <= ComponentMonitor.MAX_ALERT_HISTORY,
                "告警历史须有界，实际=" + last.alerts.size());
    }

    @Test
    @DisplayName("阶段 3：告警出口抛异常不得中断监控主链路")
    void sinkExceptionDoesNotBreakMonitor() throws Exception {
        AtomicReference<ComponentStatus> cur = new AtomicReference<>(badStatus());
        FakeQuery q = new FakeQuery();
        q.next = CompletableFuture.completedFuture(failed("跳过查询"));
        ComponentMonitor m = new ComponentMonitor(cur::get, q);
        m.setAlertSink(a -> {
            throw new IllegalStateException("出口故障");
        });
        ComponentStatus s = m.refresh().get(3, TimeUnit.SECONDS);
        assertNotNull(s);
        assertNotNull(s.alerts);
        assertEquals(5, s.alerts.size(), "出口异常不影响留痕与快照");
    }

    @Test
    @DisplayName("阶段 3：三个事件回调均触发阈值评估（事件驱动，非轮询）")
    void eventCallbacksTriggerEvaluation() {
        AtomicReference<ComponentStatus> cur = new AtomicReference<>(badStatus());
        List<ComponentAlert> sink = new ArrayList<>();
        ComponentMonitor m = monitored(cur, sink);

        m.onSystemState(new HgcpEventListener.SystemState());
        assertEquals(5, sink.size(), "onSystemState 须触发评估");
        // 后续回调在活跃集未解除时不新增
        m.onCtpState(new HgcpEventListener.CtpState());
        m.onTradingAlert(new HgcpEventListener.TradingAlert());
        assertEquals(5, sink.size());
    }
}
