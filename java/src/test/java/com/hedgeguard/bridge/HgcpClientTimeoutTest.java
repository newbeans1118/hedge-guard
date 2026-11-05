package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * {@code T-165} —— {@link HgcpClient#scheduleTimeout} 超时路径的<b>直接测试覆盖</b>；
 * {@code T-209} 起改以<b>可控逻辑序</b>驱动（超时「何时发生」由测试给定，不再抢真实时钟窗口）。
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
 * <p><b>测试确定性（{@code T-209}，{@code NFR-TEST-4}）</b>：原实现经包内字段 {@code commandTimeoutMs} 注入
 * {@code 50 ms} 后，用例 2 依赖「测试线程在 50 ms 内把 ACK 喂进 {@code onFrame}」跑赢超时调度线程；
 * 全量门禁（与其它测试/构建争 2 核）下测试线程可被调度出去 &gt; 50 ms ⇒ 超时先赢 ⇒
 * {@code acknowledged=false} 假红。现改为把生产代码的<b>同一个</b>超时任务登记到
 * {@link ManualTimeoutScheduler}（可控调度器），由测试显式 {@code fireAll()} 决定「超时任务发生在哪一刻」：
 * 「ACK 先到」「超时后到」由<b>构造</b>保证，与墙钟负载无关。
 *
 * <p><b>为何这不是放宽断言</b>（对照 {@code T-159} 先例）：① 原有断言一条未减（另加 3 条更强的）；
 * ② 被执行的仍是生产代码里同一个超时 lambda（{@code pending.remove} 短路、{@code complete}、文案构造全照跑），
 * 只是<b>触发时刻</b>由「真实时钟到点」改为「测试显式推进」；③ 时序判别力仍在：注入「ACK 不移除在途项」
 * ⇒ 用例 2 必红；注入「超时任务延迟不取 {@code commandTimeoutMs}」⇒ 新增的透传断言必红（见 {@code T-209} 回执）。
 *
 * <p><b>顺带修正（判别力缺口）</b>：原用例 2 在等过超时点后断言的是<b>事先捕获</b>的 {@code AckResult r} 对象，
 * 而超时任务写入的是<b>新建</b>的 {@code AckResult} —— 那两条断言恒真、无判别力。现改为
 * 从 future <b>重新取值</b>（{@code f.join()}）后再断言，语义仍是「future 的值不得被超时改写」，但已可失败。
 *
 * <p><b>诚实披露</b>：经公开接缝（{@link HgcpClient#onFrame} / 记录型 {@link HgcpTransport}）驱动，
 * 反射仅用于 ① 注入 {@code transport}（同 {@code HgcpClientGuardTest} 先例）② 注入 {@code timeoutPool}
 * （{@code T-209}：可控调度器）③ 只读断言 {@code pending} 表状态（用例 2 判「回执已移除在途项」所必需）④
 * 注入 {@code commandTimeoutMs}（{@code T-165} 既有，生产缺省
 * {@value HgcpConstants#COMMAND_TIMEOUT_MS} ms 不变，{@code HgcpConstants} 未改）。
 * 注入是否生效由「可控调度器收到任务数 == 1」自校验（未生效 ⇒ 立即红，不会静默退回真实时钟）。
 */
class HgcpClientTimeoutTest {

    /**
     * 注入超时（ms）。{@code T-209} 后它<b>不再决定时序</b>（超时由 {@link ManualTimeoutScheduler} 显式触发），
     * 仅用于断言「客户端把配置值透传给调度器」——故取小值仅保持「短超时」字面。
     */
    private static final long TIMEOUT_MS = 50L;

    // ==================== 接缝（同 HgcpClientGuardTest 形态） ====================

    private static final class RecordingTransport implements HgcpTransport {
        final List<byte[]> sent = new ArrayList<>();

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

    /**
     * 可控逻辑序调度器（{@code T-209} / {@code NFR-TEST-4}）：{@link #schedule} 只登记、<b>不</b>按真实时钟执行，
     * 由测试 {@link #fireAll()} 显式推进时序 ⇒ 超时任务与回执之间的先后由构造决定。
     *
     * <p>其余语义沿用真实 {@link ScheduledThreadPoolExecutor}（{@code shutdownNow()} ⇒ {@code isShutdown()}），
     * 故 {@code close()} 关停池后「迟到注册被拒绝调度」的真实路径仍被执行。
     */
    private static final class ManualTimeoutScheduler extends ScheduledThreadPoolExecutor {

        private final List<Runnable> queued = new ArrayList<>();
        private final List<Long> delaysMs = new ArrayList<>();
        private int total;

        ManualTimeoutScheduler() {
            super(1, r -> {
                Thread th = new Thread(r, "hgcp-timeout-manual");
                th.setDaemon(true);
                return th;
            });
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (isShutdown()) {
                throw new RejectedExecutionException("可控调度器已关停（与真实池 shutdownNow 后一致）");
            }
            queued.add(command);
            delaysMs.add(unit.toMillis(delay));
            total++;
            return new InertScheduledFuture();
        }

        /** 推进逻辑时钟：在调用线程（测试线程）上执行全部已登记的超时任务。 */
        synchronized void fireAll() {
            List<Runnable> batch = new ArrayList<>(queued);
            queued.clear();
            for (Runnable task : batch) {
                task.run();
            }
        }

        /** 累计登记数（清空队列后仍可断言「登记过/未登记过」）。 */
        synchronized int scheduledTotal() {
            return total;
        }

        /** 首个超时任务的延迟（ms），用于断言配置值透传。 */
        synchronized long firstDelayMs() {
            return delaysMs.isEmpty() ? -1L : delaysMs.get(0);
        }

        /** 客户端只丢弃 {@code schedule} 的返回值，故给惰性替身即可（真延迟由 {@link #fireAll()} 表达）。 */
        private static final class InertScheduledFuture implements ScheduledFuture<Object> {
            @Override
            public long getDelay(TimeUnit unit) {
                return 0L;
            }

            @Override
            public int compareTo(Delayed other) {
                return 0;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public Object get() {
                return null;
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                return null;
            }
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
    private static Map<Long, ?> pendingMap(HgcpClient client) {
        try {
            java.lang.reflect.Field f = HgcpClient.class.getDeclaredField("pending");
            f.setAccessible(true);
            return (Map<Long, ?>) f.get(client);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 pending（字段被改名？）", e);
        }
    }

    /**
     * 建「已鉴权」客户端（经公开接缝喂成功 HELLO_ACK），并注入短超时值。
     *
     * @param scheduler 可控调度器；{@code null} = 保留生产真实调度池（用于「close 关停真实池」的端到端覆盖）
     */
    private static HgcpClient authenticatedClient(RecordingTransport t, ManualTimeoutScheduler scheduler)
            throws Exception {
        HgcpClient c = new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> t);
        java.lang.reflect.Field tf = HgcpClient.class.getDeclaredField("commandTimeoutMs");
        tf.setAccessible(true);
        tf.setLong(c, TIMEOUT_MS);
        if (scheduler != null) {
            java.lang.reflect.Field pf = HgcpClient.class.getDeclaredField("timeoutPool");
            pf.setAccessible(true);
            pf.set(c, scheduler);
        }
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
    @DisplayName("T-165/用例1：confirmOrder 无回执 ⇒ 超时触发后 acknowledged=false 且 message 含『标记未生效』")
    void noAckTimesOutAndMarksNotEffective() throws Exception {
        RecordingTransport t = new RecordingTransport();
        ManualTimeoutScheduler clock = new ManualTimeoutScheduler();
        HgcpClient c = authenticatedClient(t, clock);

        CompletableFuture<AckResult> f = c.confirmOrder(555L, 42L, "tok-1");
        assertFalse(f.isDone(), "回执未到前 future 不得完成");
        assertEquals(1, clock.scheduledTotal(), "无回执的命令须登记恰好 1 个超时任务"
                + "（0 ⇒ timeoutPool 注入未生效，本用例已静默退回真实时钟）");
        assertEquals(TIMEOUT_MS, clock.firstDelayMs(),
                "超时任务延迟须取注入的 commandTimeoutMs（未透传 ⇒ 配置项失守仍会假绿）");

        clock.fireAll();  // 逻辑时钟推进：唯一超时任务在此刻发生
        assertTrue(f.isDone(), "超时任务执行后 future 须已完成");
        AckResult r = f.join();
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
        ManualTimeoutScheduler clock = new ManualTimeoutScheduler();
        HgcpClient c = authenticatedClient(t, clock);

        CompletableFuture<AckResult> f = c.cancelOrder(4242L, "客户撤单", 7L);
        assertEquals(1, t.sent.size(), "cancelOrder 须恰好下发一帧命令");
        HgcpFrame.Decoded out = HgcpFrame.decode(t.sent.get(0), 0, t.sent.get(0).length);
        long msgId = out.header.msgId;

        // 回 ACK（同 msgId、result=0）⇒ 回执路径先赢（时序由构造保证：此刻超时任务尚未被推进）
        Hgcp.MsgAck ack = Hgcp.MsgAck.newBuilder()
                .setAckMsgId(msgId)
                .setResult(0)
                .setDetail("已受理")
                .build();
        feed(c, HgcpConstants.MSG_ACK, HgcpFrame.FLAG_IS_REPLY, 9L, msgId, ack.toByteArray());

        assertEquals(1, clock.scheduledTotal(), "命令须登记恰好 1 个超时任务"
                + "（0 ⇒ timeoutPool 注入未生效，本用例已静默退回真实时钟）");
        assertTrue(f.isDone(), "回执先到 ⇒ future 须已完成");
        AckResult r = f.join();
        assertTrue(r.acknowledged, "及时回执须 acknowledged=true（不得被超时误判为 false）");
        assertEquals("已受理", r.message, "message 须为 ACK detail（非超时文案）");
        assertTrue(pendingMap(c).isEmpty(), "回执须已移除在途项");

        // 逻辑时钟推进 ⇒ 超时任务在此刻才发生：pending.remove 返回 null ⇒ 短路，不得二次改写
        clock.fireAll();
        AckResult after = f.join();
        assertTrue(after.acknowledged, "超时触发后 ACK 结果不得被改写（first-wins + remove 短路）");
        assertEquals("已受理", after.message, "超时触发后 message 不得被改为超时文案");
        assertTrue(pendingMap(c).isEmpty(), "超时触发不得重新放入在途项");
    }

    // ==================== 用例 3（加分）：close 后不再触发 ====================

    @Test
    @DisplayName("T-165/用例3：close() 关停超时池 ⇒ close 后注册的请求不因超时 complete")
    void afterCloseTimeoutPoolIsDownAndDoesNotComplete() throws Exception {
        RecordingTransport t = new RecordingTransport();
        ManualTimeoutScheduler clock = new ManualTimeoutScheduler();
        HgcpClient c = authenticatedClient(t, clock);
        c.close();
        assertTrue(clock.isShutdown(), "close() 须关停超时调度池（shutdownNow）");

        CompletableFuture<AckResult> f = c.confirmOrder(556L, 43L, "tok-2");
        assertFalse(f.isDone(),
                "close 后迟到注册的请求不得由超时池 complete（池已 shutdownNow）；"
                        + "也不得由 sendCommand 误标完成");
        assertEquals(0, clock.scheduledTotal(), "池已关停 ⇒ 不得登记任何超时任务（无悬挂超时）");
        clock.fireAll();
        assertFalse(f.isDone(), "等过超时点后仍不得完成（无悬挂超时任务）");
        assertFalse(pendingMap(c).isEmpty(),
                "close 后迟到注册的在途项保留在表中（无超时触发移除）——挂起语义由 close 时点决定");

        // 真实调度池的同族端到端覆盖：close() 关停真实池 ⇒ 迟到注册被同步拒绝（无需 sleep：拒绝是同步的）
        RecordingTransport t2 = new RecordingTransport();
        HgcpClient c2 = authenticatedClient(t2, null);
        c2.close();
        CompletableFuture<AckResult> f2 = c2.confirmOrder(557L, 44L, "tok-3");
        assertFalse(f2.isDone(), "真实池已 shutdownNow ⇒ 迟到注册的超时任务被同步拒绝，future 不得完成");
        assertFalse(pendingMap(c2).isEmpty(), "真实池路径：close 后迟到注册的在途项同样保留（无超时触发移除）");
    }
}
