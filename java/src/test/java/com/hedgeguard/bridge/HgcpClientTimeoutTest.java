package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * {@code T-165} —— {@link HgcpClient#scheduleTimeout} 超时路径的<b>直接测试覆盖</b>。
 *
 * <p>背景（编排者实测）：超时路径此前零直接测试覆盖（{@code grep COMMAND_TIMEOUT|scheduleTimeout java/src/test/} = 0 命中），
 * 唯一驱动为 {@code HgcpClientGuardTest} 的间接路径。本类补三例：
 * <ol>
 *   <li><b>无回执 ⇒ 标记未生效</b>（{@code CFG-28}）：超时触发后 {@code acknowledged=false}、
 *       message 含「标记未生效」；</li>
 *   <li><b>回执及时 ⇒ 不误判超时</b>：ACK 先到完成 future 并移除在途项 ⇒ 超时触发时
 *       {@code pending.remove} 短路（{@code cur == null ⇒ return}），无二次改写；</li>
 *   <li><b>close 后不再触发</b>：池已 {@code shutdownNow} ⇒ 迟到注册的请求不 complete。</li>
 * </ol>
 *
 * <p><b>测试确定性</b>：经包内可见字段 {@code commandTimeoutMs} 注入 50ms（生产缺省
 * {@value HgcpConstants#COMMAND_TIMEOUT_MS} ms 不变，{@code HgcpConstants} 未改）。
 *
 * <p><b>诚实披露</b>：经公开接缝（{@link HgcpClient#onFrame} / 记录型 {@link HgcpTransport}）驱动，
 * 反射仅用于 ① 注入 {@code transport}（同 {@code HgcpClientGuardTest} 先例）② 只读断言
 * {@code pending} 表状态（用例 2 判「回执已移除在途项」所必需——future 完成语义是
 * first-wins，超时二次 complete 无法从 future 侧观察）。
 */
class HgcpClientTimeoutTest {

    /** 测试注入超时（ms）：远小于 3s，确定性等待上限远大于它。 */
    private static final long TIMEOUT_MS = 50L;

    // ==================== 接缝（同 HgcpClientGuardTest 形态） ====================

    private static final class RecordingTransport implements HgcpTransport {
        final java.util.List<byte[]> sent = new java.util.ArrayList<>();

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
        public int read(byte[] buf) throws java.io.IOException {
            throw new java.io.IOException("记录桩不读取");
        }

        @Override
        public void close() {
        }
    }

    private static void injectTransport(HgcpClient client, HgcpTransport transport) {
        try {
            java.lang.reflect.Field f = HgcpClient.class.getDeclaredField("transport");
            f.setAccessible(true);
            f.set(client, transport);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入传输接缝 transport（字段被改名？）", e);
        }
    }

    /** 只读断言在途表状态（用例 2 必需，理由见类注）。 */
    @SuppressWarnings("unchecked")
    private static java.util.Map<Long, ?> pendingMap(HgcpClient client) {
        try {
            java.lang.reflect.Field f = HgcpClient.class.getDeclaredField("pending");
            f.setAccessible(true);
            return (java.util.Map<Long, ?>) f.get(client);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 pending（字段被改名？）", e);
        }
    }

    /** 注入短超时 + 建「已鉴权」客户端（经公开接缝喂成功 HELLO_ACK）。 */
    private static HgcpClient authenticatedShortTimeoutClient(RecordingTransport t)
            throws Exception {
        HgcpClient c = new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> t);
        java.lang.reflect.Field tf = HgcpClient.class.getDeclaredField("commandTimeoutMs");
        tf.setAccessible(true);
        tf.setLong(c, TIMEOUT_MS);
        injectTransport(c, t);
        Hgcp.MsgHelloAck ack = Hgcp.MsgHelloAck.newBuilder()
                .setAccepted(true)
                .setServerProtoVersion(HgcpFrame.WIRE_VERSION)
                .setSessionId("sess-1")
                .setServerState(Hgcp.ServerState.SERVER_STATE_READY)
                .setParamVersion(3L)
                .build();
        feed(c, HgcpConstants.MSG_HELLO_ACK, HgcpFrame.FLAG_IS_REPLY, 1L, 1L, ack.toByteArray());
        assertEquals(HgcpClient.AuthState.AUTHENTICATED, c.authState(),
                "夹具：须为 AUTHENTICATED（否则命令无法下发，本类无守卫力）");
        return c;
    }

    private static HgcpFrame.Decoded feed(HgcpClient client, int type, int flags, long seq,
                                          long msgId, byte[] payload) {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = type;
        h.flags = flags;
        h.seq = seq;
        h.msgId = msgId;
        byte[] frame = HgcpFrame.encode(h, payload);
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        client.onFrame(d);
        return d;
    }

    // ==================== 用例 1：无回执 ⇒ 标记未生效 ====================

    @Test
    @DisplayName("T-165/用例1：confirmOrder 无回执 ⇒ 超时后 acknowledged=false 且 message 含『标记未生效』")
    void noAckTimesOutAndMarksNotEffective() throws Exception {
        RecordingTransport t = new RecordingTransport();
        HgcpClient c = authenticatedShortTimeoutClient(t);

        java.util.concurrent.CompletableFuture<AckResult> f = c.confirmOrder(555L, 42L, "tok-1");
        assertFalse(f.isDone(), "回执未到前 future 不得完成");

        AckResult r = f.get(5, TimeUnit.SECONDS);
        assertFalse(r.acknowledged, "超时路径须标记未生效（acknowledged=false）");
        assertTrue(r.message.contains("标记未生效"),
                "message 须含『标记未生效』（CFG-28），实际：" + r.message);
        assertTrue(r.message.contains(String.valueOf(HgcpConstants.COMMAND_TIMEOUT_MS)),
                "message 须含命令超时值 3000（语义未改），实际：" + r.message);
        assertTrue(pendingMap(c).isEmpty(), "超时移除后在途表须为空");
    }

    // ==================== 用例 2：回执及时 ⇒ 不误判超时 ====================

    @Test
    @DisplayName("T-165/用例2：ACK 及时到达 ⇒ future 为 ACK 结果；超时触发时 remove 短路（无二次改写）")
    void timelyAckWinsAndTimeoutShortCircuits() throws Exception {
        RecordingTransport t = new RecordingTransport();
        HgcpClient c = authenticatedShortTimeoutClient(t);

        java.util.concurrent.CompletableFuture<AckResult> f = c.cancelOrder(4242L, "客户撤单", 7L);
        assertEquals(1, t.sent.size(), "cancelOrder 须恰好下发一帧命令");
        HgcpFrame.Decoded out = HgcpFrame.decode(t.sent.get(0), 0, t.sent.get(0).length);
        long msgId = out.header.msgId;

        // 立即回 ACK（同 msgId、result=0）⇒ 回执路径先赢
        Hgcp.MsgAck ack = Hgcp.MsgAck.newBuilder()
                .setAckMsgId(msgId)
                .setResult(0)
                .setDetail("已受理")
                .build();
        feed(c, HgcpConstants.MSG_ACK, HgcpFrame.FLAG_IS_REPLY, 9L, msgId, ack.toByteArray());

        AckResult r = f.get(5, TimeUnit.SECONDS);
        assertTrue(r.acknowledged, "及时回执须 acknowledged=true（不得被超时误判为 false）");
        assertEquals("已受理", r.message, "message 须为 ACK detail（非超时文案）");
        assertTrue(pendingMap(c).isEmpty(), "回执须已移除在途项");

        // 等过超时点：超时触发时 pending.remove 返回 null ⇒ 短路，不得二次改写
        Thread.sleep(TIMEOUT_MS * 4);
        assertTrue(r.acknowledged, "超时触发后 ACK 结果不得被改写（first-wins + remove 短路）");
        assertEquals("已受理", r.message, "超时触发后 message 不得被改为超时文案");
        assertTrue(pendingMap(c).isEmpty(), "超时触发不得重新放入在途项");
    }

    // ==================== 用例 3（加分）：close 后不再触发 ====================

    @Test
    @DisplayName("T-165/用例3：close() 关停超时池 ⇒ close 后注册的请求不因超时 complete")
    void afterCloseTimeoutPoolIsDownAndDoesNotComplete() throws Exception {
        RecordingTransport t = new RecordingTransport();
        HgcpClient c = authenticatedShortTimeoutClient(t);
        c.close();

        java.util.concurrent.CompletableFuture<AckResult> f = c.confirmOrder(556L, 43L, "tok-2");
        assertFalse(f.isDone(),
                "close 后迟到注册的请求不得由超时池 complete（池已 shutdownNow）；"
                        + "也不得由 sendCommand 误标完成");

        Thread.sleep(TIMEOUT_MS * 4);
        assertFalse(f.isDone(), "等过超时点后仍不得完成（无悬挂超时任务）");
        assertFalse(pendingMap(c).isEmpty(),
                "close 后迟到注册的在途项保留在表中（无超时触发移除）——挂起语义由 close 时点决定");
    }
}
