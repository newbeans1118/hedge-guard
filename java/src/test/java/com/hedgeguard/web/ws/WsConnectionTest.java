package com.hedgeguard.web.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接写侧与背压单测（`T-028` 验收 6 后半句：**慢客户端有界队列丢弃旧帧且不阻塞其他连接**；完全离线）。
 *
 * <p>两通道语义分别验证（`ARCHITECTURE.md` §4.1.2）：
 * <ul>
 *   <li>{@code /ws/quote}：**允许丢帧** ⇒ 队列满时丢**最旧**（覆盖式），计数递增，**容量不变**；</li>
 *   <li>{@code /ws/alert}：**不允许丢** ⇒ 队列满时**不静默丢**，而是关闭该连接（`1013`）由前端 REST 补拉。</li>
 * </ul>
 *
 * <p>为保证确定性，丢帧类用例**不启动写线程**（否则队列会被立刻抽干），只测入队语义；
 * 送达类用例才启动写线程并从真实回环对端读帧。
 */
class WsConnectionTest {

    private ServerSocket listener;
    private Socket serverSide;
    private Socket clientSide;

    @BeforeEach
    void openLoopbackPair() throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        clientSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        serverSide = listener.accept();
        serverSide.setSoTimeout(3000);
        clientSide.setSoTimeout(3000);
    }

    @AfterEach
    void closePair() throws IOException {
        clientSide.close();
        serverSide.close();
        listener.close();
    }

    private WsConnection connection(int capacity) throws IOException {
        return new WsConnection(1L, WsTestSupport.session(7L, "u7"), serverSide,
                serverSide.getOutputStream(), 30000, capacity);
    }

    @Test
    @DisplayName("行情通道：队列满时丢**最旧**（覆盖式），容量不变，droppedQuote 计数递增")
    void quoteDropsOldestWhenFull() throws IOException {
        WsConnection c = connection(4);
        for (int i = 0; i < 4; i++) {
            c.sendQuote("{\"type\":\"quote\",\"n\":" + i + "}");
        }
        assertEquals(4, c.pendingCount());
        assertEquals(0, c.droppedQuoteCount());

        for (int i = 4; i < 10; i++) {
            c.sendQuote("{\"type\":\"quote\",\"n\":" + i + "}");
        }
        assertEquals(4, c.pendingCount(), "队列容量必须恒定（有界）");
        assertEquals(6, c.droppedQuoteCount(), "每次满队入队应丢一条最旧并计数");
        assertTrue(c.isOpen(), "行情丢帧**不得**关闭连接（§4.1.2 允许丢帧）");
    }

    @Test
    @DisplayName("告警通道：队列满时**不丢**——关闭连接 1013（前端重连后经 REST 补拉）")
    void alertNeverDropsButClosesOnOverflow() throws IOException, InterruptedException {
        WsConnection c = connection(3);
        for (int i = 0; i < 3; i++) {
            c.sendAlert("{\"type\":\"alert\",\"n\":" + i + "}");
        }
        assertEquals(3, c.pendingCount());
        assertEquals(0, c.droppedQuoteCount(), "告警不得计入行情丢帧计数");

        c.sendAlert("{\"type\":\"alert\",\"n\":\"overflow\"}");
        // 溢出后连接应被关闭（关闭动作在独立线程中完成，稍等）
        for (int i = 0; i < 30 && c.isOpen(); i++) {
            Thread.sleep(20);
        }
        assertFalse(c.isOpen(), "告警队列溢出必须关闭连接（绝不静默丢帧）");

        WsFrame close = WsTestSupport.readFrame(clientSide);
        assertEquals(WsFrame.Opcode.CLOSE, close.opcode(), "对端应收到关闭帧");
        assertEquals(WsCloseCode.TRY_AGAIN_LATER, WsFrameCodec.closeCodeOf(close));
    }

    @Test
    @DisplayName("入队**非阻塞**：满队列下大量发送的耗时应在毫秒级（不等待慢客户端）")
    void enqueueNeverBlocks() throws IOException {
        WsConnection c = connection(8);
        long start = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            c.sendQuote("{\"type\":\"quote\",\"n\":" + i + "}");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 1000,
                "5000 次满队入队耗时 " + elapsedMs + " ms，应远小于 1 s（非阻塞）");
        assertEquals(8, c.pendingCount());
    }

    @Test
    @DisplayName("启动写线程后：行情帧能真实送达对端（往返）")
    void deliversQuoteToPeer() throws IOException, InterruptedException {
        WsConnection c = connection(8);
        c.start();
        c.sendQuote("{\"type\":\"quote\",\"instrument\":\"ru2601\"}");
        WsFrame f = WsTestSupport.readFrame(clientSide);
        assertEquals(WsFrame.Opcode.TEXT, f.opcode());
        assertTrue(f.text().contains("ru2601"));
        // ⚠️ 计数在写线程 `flush()` **之后**自增，与"对端已收到"之间存在跨线程可见性窗口 ⇒
        // 不能"读完立刻断言"，否则偶发失败（本用例首轮即因此 flaky）。改为有界轮询。
        long deadline = System.currentTimeMillis() + 1000;
        while (c.sentFrameCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, c.sentFrameCount(), "写出计数应在 1 s 内收敛为 1");
    }

    @Test
    @DisplayName("控制帧与数据帧走同一队列（不得交错破坏线格式）：pong 可送达对端")
    void controlFramesGoThroughSameQueue() throws IOException {
        WsConnection c = connection(8);
        c.start();
        c.sendPong("hb".getBytes(StandardCharsets.US_ASCII));
        WsFrame f = WsTestSupport.readFrame(clientSide);
        assertEquals(WsFrame.Opcode.PONG, f.opcode());
        assertEquals("hb", new String(f.payload(), StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("心跳：onPong 重置存活标记；容忍上限常量符合设计（3×间隔）")
    void heartbeatAccounting() throws IOException {
        WsConnection c = connection(8);
        c.onPong();
        assertFalse(c.heartbeatExpired(), "刚收到 pong 不应判定超时");
        assertEquals(3, WsConnection.HEARTBEAT_MISS_LIMIT);
    }

    @Test
    @DisplayName("关闭幂等：重复 close 不抛异常，且关闭后入队被忽略（不产生写后异常）")
    void closeIsIdempotent() throws IOException {
        WsConnection c = connection(8);
        c.close(WsCloseCode.NORMAL, "bye");
        c.close(WsCloseCode.NORMAL, "bye");
        assertFalse(c.isOpen());
        c.sendQuote("{\"type\":\"quote\"}");
        assertEquals(0, c.pendingCount(), "关闭后不得再入队");
    }

    @Test
    @DisplayName("订阅默认态：空订阅不含任何子账户与通道（安全默认的起点）")
    void defaultSubscriptionIsEmpty() throws IOException {
        WsConnection c = connection(8);
        assertTrue(c.subscription().subAccounts().isEmpty());
        assertFalse(c.subscription().wantsQuote());
        assertFalse(c.subscription().wantsAlert());
    }
}
