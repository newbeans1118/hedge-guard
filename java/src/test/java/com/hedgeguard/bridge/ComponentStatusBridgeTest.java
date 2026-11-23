package com.hedgeguard.bridge;

import com.google.protobuf.MessageLite;
import hgcp.v1.Hgcp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1 监控面 · bridge 侧行为断言。
 *
 * <p>立意：① {@code MSG_ACK.detail} 的现有 key=value 形态须被宽松解析（不改线格式）；
 * ② {@code CMD_QUERY_STATUS} 须走既有 {@code sendCommand} 单帧路径、不新建连接；
 * ③ {@code EVT_SYSTEM_STATE/CTP_STATE/TRADING_ALERT} 须落进与 MirrorState 隔离的
 * {@link ComponentStatus}，并触发新增的三个 {@code default} 监听器回调。
 */
class ComponentStatusBridgeTest {

    // ==================== detail 解析 ====================

    @Test
    @DisplayName("解析现有 C++ 回执：ready=true;param_version=7;load=1/1")
    void parsesCurrentDetail() {
        StatusDetailParser.Parsed p =
                StatusDetailParser.parse("ready=true;param_version=7;load=1/1");
        assertEquals(Boolean.TRUE, p.ready);
        assertEquals(Long.valueOf(7), p.paramVersion);
        assertEquals(Integer.valueOf(1), p.loadReceived);
        assertEquals(Integer.valueOf(1), p.loadTotal);
        assertTrue(!p.unparsed);
    }

    @Test
    @DisplayName("缺字段 / 未知键 / 单字段格式错误：只丢该字段，整体不抛")
    void toleratesMissingUnknownAndMalformed() {
        StatusDetailParser.Parsed p = StatusDetailParser.parse(
                "ready=false;unknown_key=abc;param_version=not-a-number;load=3/4");
        assertEquals(Boolean.FALSE, p.ready);
        assertNull(p.paramVersion, "格式错误字段应丢弃而非崩掉");
        assertEquals(Integer.valueOf(3), p.loadReceived);
        assertEquals(Integer.valueOf(4), p.loadTotal);
    }

    @Test
    @DisplayName("null / 空串：返回安全空结果，不抛")
    void toleratesNullAndEmpty() {
        assertEquals("", StatusDetailParser.parse(null).raw);
        assertNull(StatusDetailParser.parse(null).ready);
        assertEquals("", StatusDetailParser.parse("").raw);
    }

    @Test
    @DisplayName("阶段 2 JSON 形态：结构化解析就绪/版本/状态细名，原文保留")
    void jsonDetailIsParsed() {
        String json = "{\"v\":2,\"ready\":false,\"paramVersion\":9,\"state\":\"BLOCKED\"}";
        StatusDetailParser.Parsed p = StatusDetailParser.parse(json);
        assertTrue(!p.unparsed);
        assertEquals(json, p.raw);
        assertEquals(Boolean.FALSE, p.ready);
        assertEquals(Long.valueOf(9), p.paramVersion);
        assertEquals("BLOCKED", p.state);
    }

    @Test
    @DisplayName("非法 JSON：标记 unparsed、原文保留、绝不猜值")
    void malformedJsonIsKeptRaw() {
        String json = "{not-json";
        StatusDetailParser.Parsed p = StatusDetailParser.parse(json);
        assertTrue(p.unparsed);
        assertEquals(json, p.raw);
        assertNull(p.ready);
        assertNull(p.counters);
    }

    @Test
    @DisplayName("阶段 2 JSON 全段：replay/load/queues/counters/ctp/uptime 逐项提取")
    void jsonAllSectionsParsed() {
        String json = "{\"v\":2,\"tsMs\":123456,\"what\":15,\"state\":\"READY\","
                + "\"serverState\":\"READY\",\"ready\":true,\"paramVersion\":9,\"uptimeMs\":8888,"
                + "\"replay\":{\"runs\":2,\"startedMs\":1000,\"state\":\"READY\"},"
                + "\"load\":{\"received\":1,\"total\":1},"
                + "\"queues\":{\"event\":0,\"eventCapacity\":16384,\"backpressure\":1},"
                + "\"counters\":{\"framesIn\":42,\"commands\":3},"
                + "\"ctp\":{\"tradeConnected\":true,\"mdConnected\":false,\"front\":\"tcp://x\","
                + "\"loggedIn\":false,\"degraded\":true}}";
        StatusDetailParser.Parsed p = StatusDetailParser.parse(json);
        assertTrue(!p.unparsed);
        assertEquals("READY", p.serverState);
        assertEquals(Long.valueOf(2), p.replayRuns);
        assertEquals(Long.valueOf(1000), p.replayStartedMs);
        assertEquals("READY", p.replayState);
        assertEquals(Integer.valueOf(1), p.loadReceived);
        assertEquals(Integer.valueOf(1), p.loadTotal);
        assertEquals(Long.valueOf(16384), p.eventQueueCapacity);
        assertEquals(Long.valueOf(1), p.backpressure);
        assertEquals(Long.valueOf(42), p.counters.get("framesIn"));
        assertEquals(Boolean.TRUE, p.ctpTradeConnected);
        assertEquals(Boolean.FALSE, p.ctpMdConnected);
        assertEquals("tcp://x", p.ctpFront);
        assertEquals(Boolean.TRUE, p.ctpDegraded);
        assertEquals(Long.valueOf(8888), p.uptimeMs);
        assertEquals(Long.valueOf(123456), p.tsMs);
        assertEquals(Integer.valueOf(15), p.what);
    }

    @Test
    @DisplayName("what 位掩码：仅请求状态段时 C++ 不返回运行期段 ⇒ 缺字段保持 null")
    void partialDetailHasNoRuntimeFields() {
        StatusDetailParser.Parsed p = StatusDetailParser.parse(
                "{\"v\":2,\"what\":1,\"state\":\"NOT_READY\",\"ready\":false,\"paramVersion\":0}");
        assertTrue(!p.unparsed);
        assertNull(p.counters);
        assertNull(p.uptimeMs);
        assertEquals(Integer.valueOf(1), p.what);
        assertEquals("NOT_READY", p.state);
    }

    // ==================== HgcpClient 查询与状态 ====================

    /** 只收不发的内存传输桩（不启动读线程 ⇒ 无后台线程）。 */
    private static final class RecordingTransport implements HgcpTransport {
        final List<byte[]> sent = new ArrayList<>();
        volatile boolean closed;

        @Override
        public void connect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            sent.add(frame.clone());
        }

        @Override
        public int read(byte[] buf) throws IOException {
            throw new IOException("记录桩不读取");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static void injectTransport(HgcpClient c, HgcpTransport t) {
        try {
            java.lang.reflect.Field f = HgcpClient.class.getDeclaredField("transport");
            f.setAccessible(true);
            f.set(c, t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入传输接缝 transport", e);
        }
    }

    private static HgcpFrame.Decoded decoded(int type, int flags, long seq, MessageLite body) {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = type;
        h.flags = flags;
        h.seq = seq;
        h.msgId = seq;
        byte[] raw = HgcpFrame.encode(h, body.toByteArray());
        HgcpFrame.Decoded d = HgcpFrame.decode(raw, 0, raw.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        return d;
    }

    private static void feedHelloAck(HgcpClient c, long seq) {
        Hgcp.MsgHelloAck ack = Hgcp.MsgHelloAck.newBuilder()
                .setAccepted(true)
                .setServerProtoVersion(HgcpFrame.WIRE_VERSION)
                .setSessionId("sess-1")
                .setServerState(Hgcp.ServerState.SERVER_STATE_READY)
                .setParamVersion(7)
                .build();
        c.onFrame(decoded(HgcpConstants.MSG_HELLO_ACK, HgcpFrame.FLAG_IS_REPLY, seq, ack));
    }

    @Test
    @DisplayName("queryStatus 只发一帧 CMD_QUERY_STATUS，what 逐字透传，不新建连接")
    void queryStatusSendsSingleFrame() throws Exception {
        RecordingTransport t = new RecordingTransport();
        HgcpClient c = new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> t);
        injectTransport(c, t);
        feedHelloAck(c, 1L);
        int before = t.sent.size();

        CompletableFuture<AckResult> f = c.queryStatus(0);
        assertNotNull(f);
        assertEquals(before + 1, t.sent.size(), "只发一帧，不得新建连接");
        byte[] raw = t.sent.get(t.sent.size() - 1);
        HgcpFrame.Decoded d = HgcpFrame.decode(raw, 0, raw.length);
        assertEquals(HgcpConstants.CMD_QUERY_STATUS, d.header.type);
        Hgcp.CmdQueryStatus q = Hgcp.CmdQueryStatus.parseFrom(d.payload);
        assertEquals(0, q.getWhat());
        assertTrue(!t.closed, "查询不得关闭当前连接");
        c.close();
    }

    @Test
    @DisplayName("三个事件帧落进 ComponentStatus（与镜像隔离）并触发新增监听器回调")
    void eventsFeedComponentStatusAndCallbacks() {
        RecordingTransport t = new RecordingTransport();
        HgcpClient c = new HgcpClient("java-test", "token-not-logged", "boot-2", 1, () -> t);
        injectTransport(c, t);
        feedHelloAck(c, 1L);

        AtomicInteger system = new AtomicInteger();
        AtomicInteger ctp = new AtomicInteger();
        AtomicInteger alert = new AtomicInteger();
        c.setEventListener(new HgcpEventListener() {
            @Override
            public void onSystemState(SystemState e) {
                system.incrementAndGet();
            }

            @Override
            public void onCtpState(CtpState e) {
                ctp.incrementAndGet();
            }

            @Override
            public void onTradingAlert(TradingAlert e) {
                alert.incrementAndGet();
            }
        });

        Hgcp.EvtSystemState sys = Hgcp.EvtSystemState.newBuilder()
                .setState(Hgcp.ServerState.SERVER_STATE_READY)
                .setParamVersionApplied(9)
                .setLoadedCounts("{\"variety\":3}")
                .setReason("重放一致且门卫通过")
                .setTsMs(12345L)
                .build();
        c.onFrame(decoded(HgcpConstants.EVT_SYSTEM_STATE, 0, 2L, sys));

        Hgcp.EvtCtpState ctpState = Hgcp.EvtCtpState.newBuilder()
                .setTradeConnected(true)
                .setMdConnected(true)
                .setFrontAddr("tcp://182.254.243.31:30001")
                .setStateChangeTsMs(23456L)
                .setReason("login")
                .build();
        c.onFrame(decoded(HgcpConstants.EVT_CTP_STATE, 0, 3L, ctpState));

        Hgcp.EvtTradingAlert ta = Hgcp.EvtTradingAlert.newBuilder()
                .setAlertCode(42)
                .setLevel(Hgcp.AlertLevel.ALERT_LEVEL_WARNING)
                .setDetail("阈值预警")
                .setAlertTsMs(34567L)
                .build();
        c.onFrame(decoded(HgcpConstants.EVT_TRADING_ALERT, 0, 4L, ta));

        assertEquals(1, system.get());
        assertEquals(1, ctp.get());
        assertEquals(1, alert.get());

        ComponentStatus s = c.componentStatus();
        assertTrue(s.bridgeAssembled);
        assertTrue(s.linkEstablished);
        assertEquals("READY", s.state);
        assertTrue(s.ready);
        assertEquals(9, s.paramVersion);
        assertEquals("{\"variety\":3}", s.loadedCounts);
        assertEquals("重放一致且门卫通过", s.stateReason);
        assertEquals(12345L, s.stateTsMs);
        assertTrue(s.ctpTradeConnected);
        assertTrue(s.ctpMdConnected);
        assertEquals("tcp://182.254.243.31:30001", s.ctpFront);
        assertEquals(23456L, s.ctpStateChangeTsMs);
        assertEquals(42, s.lastAlertCode);
        assertEquals("ALERT_LEVEL_WARNING", s.lastAlertLevel);
        assertEquals("阈值预警", s.lastAlertDetail);
        assertEquals(34567L, s.lastAlertTsMs);
        assertTrue(s.watermarkEventSeq > 0, "三个事件帧均须推进水位（与 default 分支原语义一致）");
        assertNotNull(s.takenAt);

        // 快照隔离：改返回值不得影响内部状态
        s.state = "TAMPERED";
        assertEquals("READY", c.componentStatus().state);
        c.close();
    }
}
