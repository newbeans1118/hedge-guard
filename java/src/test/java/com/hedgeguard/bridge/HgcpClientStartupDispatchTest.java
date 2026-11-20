package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * 启动期全量下发钩子 + 重连关闭旧连接的守卫（经公开接缝 {@link HgcpClient#onFrame} 与
 * 记录型 {@link HgcpTransport} 驱动，未改生产码一行）。
 *
 * <p>立意 = 行为断言：钩子须在「非 READY」时触发且「已 READY」时不重复；重连须实际
 * {@code close()} 旧 transport —— 否则服务端单连接治理回 {@code 0x0302} 形成循环。
 */
class HgcpClientStartupDispatchTest {

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

    private static void feedHelloAck(HgcpClient c, Hgcp.ServerState state, long seq) {
        Hgcp.MsgHelloAck ack = Hgcp.MsgHelloAck.newBuilder()
                .setAccepted(true)
                .setServerProtoVersion(HgcpFrame.WIRE_VERSION)
                .setSessionId("sess-1")
                .setServerState(state)
                .build();
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.MSG_HELLO_ACK;
        h.flags = HgcpFrame.FLAG_IS_REPLY;
        h.seq = seq;
        h.msgId = seq;
        byte[] frame = HgcpFrame.encode(h, ack.toByteArray());
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        c.onFrame(d);
    }

    @Test
    @DisplayName("HELLO_ACK 非 READY 触发一次启动下发；READY 不重复触发")
    void hookFiresOnlyWhenNotReady() throws Exception {
        RecordingTransport t = new RecordingTransport();
        HgcpClient c = new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> t);
        injectTransport(c, t);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        c.setStartupDispatchHook(() -> {
            calls.incrementAndGet();
            latch.countDown();
        });

        feedHelloAck(c, Hgcp.ServerState.SERVER_STATE_NOT_READY, 1L);
        assertTrue(latch.await(3, TimeUnit.SECONDS), "非 READY 时须异步触发启动下发钩子");
        assertEquals(1, calls.get(), "同一时刻仅一个启动下发在飞");

        feedHelloAck(c, Hgcp.ServerState.SERVER_STATE_READY, 2L);
        Thread.sleep(200);
        assertEquals(1, calls.get(), "组件已 READY 不得重复下发全量快照");
        c.close();
    }

    @Test
    @DisplayName("重连前须关闭旧 transport（0x0302 循环根因）")
    void reconnectClosesOldTransport() throws Exception {        List<RecordingTransport> created = new ArrayList<>();
        HgcpClient c = new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> {
            RecordingTransport t = new RecordingTransport();
            created.add(t);
            return t;
        });
        java.lang.reflect.Method m = HgcpClient.class.getDeclaredMethod("connectAndHandshake");
        m.setAccessible(true);
        m.invoke(c);   // 首次建连（无旧 transport）
        m.invoke(c);   // 重连（须关闭首次的 transport）

        assertEquals(2, created.size(), "两次建连应产生两个 transport 实例");
        assertTrue(created.get(0).closed,
                "旧 transport 必须 close()：否则服务端仍视旧连接 active，新连接被回 0x0302");
        assertFalse(created.get(1).closed, "当前 transport 须保持打开");
        c.close();
    }

    @Test
    @DisplayName("SO_TIMEOUT 读超时只继续读，不触发重连（周期循环根因）")
    void readTimeoutDoesNotTriggerReconnect() throws Exception {
        java.util.concurrent.atomic.AtomicInteger factoryCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        HgcpTransport t = new HgcpTransport() {
            @Override
            public void connect() {
            }

            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public void send(byte[] frame) {
            }

            @Override
            public int read(byte[] buf) throws IOException {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new java.net.SocketTimeoutException("Read timed out");
            }

            @Override
            public void close() {
            }
        };
        HgcpClient c = new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> {
            factoryCalls.incrementAndGet();
            return t;
        });
        injectTransport(c, t);
        setRunning(c, true);
        java.lang.reflect.Method m = HgcpClient.class.getDeclaredMethod("readLoop", long.class);
        m.setAccessible(true);
        Thread reader = new Thread(() -> {
            try {
                m.invoke(c, 0L);
            } catch (Exception ignored) {
                // 反射调用异常由断言间接覆盖
            }
        }, "test-reader");
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(300);
        setRunning(c, false);
        reader.join(2000);
        assertEquals(0, factoryCalls.get(),
                "SocketTimeoutException 不得触发 connectAndHandshake（重连）："
                        + "SO_TIMEOUT 与服务端心跳间隔同值，超时只是等待窗口");
        c.close();
    }

    private static void setRunning(HgcpClient c, boolean v) {
        try {
            java.lang.reflect.Field f = HgcpClient.class.getDeclaredField("running");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) f.get(c)).set(v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 running", e);
        }
    }
}
