package com.hedgeguard.web.ws;

import com.hedgeguard.web.Session;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 一条 WebSocket 连接（写侧 + 心跳 + 背压）。
 *
 * <p>线程模型（`CONTRACTS.md` §4.1.1 `J2`）：**每连接一个写线程**——负责
 * ① 从有界出站队列取帧写出；② 按 {@code hg.web.ws.heartbeat_ms} 发 RFC 6455 原生 {@code ping}；
 * ③ 连续 3 次未收到 {@code pong} 即判定心跳超时并关闭。**读侧**由 {@link WsServer} 的读循环驱动。
 *
 * <p><b>背压与丢帧策略（按 §4.1.2 的两通道语义分别处理，这是本类的核心不变量）</b>：
 * <table border="1">
 *   <caption>两通道的队列语义</caption>
 *   <tr><th>通道</th><th>策略</th><th>队列满时</th><th>依据</th></tr>
 *   <tr><td>{@code /ws/quote}</td><td>{@link Policy#DROP_OLDEST}</td>
 *       <td>丢最旧（**覆盖式**，只保最新值），计入 {@code droppedQuote}</td><td>§4.1.2：允许丢帧</td></tr>
 *   <tr><td>{@code /ws/alert}</td><td>{@link Policy#NEVER_DROP}</td>
 *       <td>**不丢**：关闭该连接（{@code 1013}），由前端重连后走 REST `/risk/alert?since=` **补拉**</td>
 *       <td>§4.1.2：不允许丢</td></tr>
 * </table>
 *
 * <p><b>不阻塞其他连接</b>：入队全部为非阻塞操作（有界队列 + 计数），
 * 慢客户端只影响自己（丢帧或断开），**绝不**让发布线程等待。
 */
public final class WsConnection {

    private static final Logger LOG = Logger.getLogger(WsConnection.class.getName());

    /** 心跳未响应容忍倍数（连续 3 次 ping 无 pong 即断）。 */
    public static final int HEARTBEAT_MISS_LIMIT = 3;

    /** 出站队列策略。 */
    public enum Policy {
        /** 丢最旧（行情：覆盖式，允许丢帧）。 */
        DROP_OLDEST,
        /** 不丢（告警：满则断开连接，由前端 REST 补拉）。 */
        NEVER_DROP
    }

    private final long id;
    private final long userId;
    private final String username;
    private final Socket socket;
    private final OutputStream out;
    private final int heartbeatMillis;
    private final int queueCapacity;

    private final Deque<byte[]> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong droppedQuote = new AtomicLong();
    private final AtomicLong sentFrames = new AtomicLong();

    private volatile WsSubscription subscription = WsSubscription.empty();
    private volatile boolean open = true;
    /**
     * 「关闭动作只执行一次」的幂等闸门。
     *
     * <p>与 {@link #open} 的分工：{@code open} 是**停止入队/停写**的快速开关（背压溢出路径会先置假以立刻阻止继续入队），
     * {@code closedOnce} 才是**发送关闭帧并释放资源**的一次性开关。二者若混用，会出现
     * "溢出把 open 置假 → close() 见 open 已假而直接返回 → **关闭帧根本没发出去**" 的静默缺陷。
     */
    private final java.util.concurrent.atomic.AtomicBoolean closedOnce =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long lastPongMillis = System.currentTimeMillis();
    private volatile long lastPingMillis;
    private Thread writer;
    private Thread heartbeat;

    /**
     * 构造。
     *
     * @param id              连接号
     * @param session         已认证会话
     * @param socket          已升级的连接
     * @param out             输出流
     * @param heartbeatMillis 心跳间隔（{@code hg.web.ws.heartbeat_ms}）
     * @param queueCapacity   出站队列容量
     */
    public WsConnection(long id, Session session, Socket socket, OutputStream out,
                        int heartbeatMillis, int queueCapacity) {
        this.id = id;
        this.userId = session.userId;
        this.username = session.username;
        this.socket = socket;
        this.out = out;
        this.heartbeatMillis = heartbeatMillis;
        this.queueCapacity = queueCapacity;
    }

    /** 连接号。 */
    public long id() {
        return id;
    }

    /** 会话用户 ID。 */
    public long userId() {
        return userId;
    }

    /** 会话用户名。 */
    public String username() {
        return username;
    }

    /** 当前订阅（只读快照）。 */
    public WsSubscription subscription() {
        return subscription;
    }

    /** 设置订阅。 */
    void setSubscription(WsSubscription s) {
        this.subscription = s;
    }

    /** 是否仍在连接。 */
    public boolean isOpen() {
        return open;
    }

    /** 累计丢弃的行情帧数（背压证据）。 */
    public long droppedQuoteCount() {
        return droppedQuote.get();
    }

    /** 累计写出帧数。 */
    public long sentFrameCount() {
        return sentFrames.get();
    }

    /** 当前待发送帧数（含控制帧）。 */
    public int pendingCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /**
     * 发送行情帧（{@link Policy#DROP_OLDEST}）。
     *
     * @param json 消息
     */
    public void sendQuote(String json) {
        enqueue(WsFrameCodec.encodeText(json), Policy.DROP_OLDEST);
    }

    /**
     * 发送告警帧（{@link Policy#NEVER_DROP}；队列满 ⇒ 关闭连接 `1013`，由前端补拉）。
     *
     * @param json 消息
     */
    public void sendAlert(String json) {
        enqueue(WsFrameCodec.encodeText(json), Policy.NEVER_DROP);
    }

    /**
     * 发送 pong 帧（回显 ping 载荷）。
     *
     * <p>控制帧**必须**与数据帧走同一个出站队列——否则会与写线程交错、破坏线格式（RFC 6455 帧不得交错）。
     *
     * @param payload 载荷（通常回显对端 ping 的载荷）
     */
    public void sendPong(byte[] payload) {
        enqueue(WsFrameCodec.encodePong(payload), Policy.NEVER_DROP);
    }

    /**
     * 连接输入流（读循环用）。
     *
     * @return 输入流
     * @throws IOException 取流失败
     */
    public java.io.InputStream input() throws IOException {
        return socket.getInputStream();
    }

    /**
     * 发送控制类消息（订阅应答 / 错误 / 首帧快照）——按 {@link Policy#NEVER_DROP} 处理：
     * 这些是"控制面"消息，丢失会让前端状态与后端不一致。
     *
     * @param json 消息
     */
    public void sendControl(String json) {
        enqueue(WsFrameCodec.encodeText(json), Policy.NEVER_DROP);
    }

    /** 记录一次 pong（心跳存活）。 */
    void onPong() {
        lastPongMillis = System.currentTimeMillis();
    }

    /** 心跳是否已超时（连续 {@link #HEARTBEAT_MISS_LIMIT} 次未响应）。 */
    boolean heartbeatExpired() {
        return System.currentTimeMillis() - lastPongMillis > (long) heartbeatMillis * HEARTBEAT_MISS_LIMIT;
    }

    /** 启动写线程与心跳线程（各一个，守护线程）。 */
    void start() {
        writer = new Thread(this::writeLoop, "hg-ws-writer-" + id);
        writer.setDaemon(true);
        writer.start();
        heartbeat = new Thread(this::heartbeatLoop, "hg-ws-heartbeat-" + id);
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    /**
     * 关闭连接（幂等）。
     *
     * @param code   关闭码（见 {@link WsCloseCode}）
     * @param reason 原因（**不得含敏感信息**）
     */
    public void close(int code, String reason) {
        if (!closedOnce.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            open = false;
            try {
                // 关闭帧必须尽力送达：直接写，不排队（队列可能已满）
                out.write(WsFrameCodec.encodeClose(code, reason));
                out.flush();
            } catch (IOException e) {
                LOG.log(Level.FINE, "写出关闭帧失败（连接可能已断）：id=" + id + " " + e.getMessage());
            }
            queue.clear();
            lock.notifyAll();
        }
        closeQuietly();
    }

    private void enqueue(byte[] frame, Policy policy) {
        synchronized (lock) {
            if (!open) {
                return;
            }
            if (queue.size() >= queueCapacity) {
                if (policy == Policy.DROP_OLDEST) {
                    queue.pollFirst();
                    droppedQuote.incrementAndGet();
                } else {
                    // NEVER_DROP：宁可断开也不静默丢（§4.1.2「不允许丢」）
                    LOG.log(Level.WARNING, "WS 出站队列已满且策略为不丢，关闭连接：id=" + id
                            + " userId=" + userId + " capacity=" + queueCapacity
                            + "；前端重连后应经 REST 补拉（REST /risk/alert?since=）");
                    open = false;
                    queue.clear();
                    lock.notifyAll();
                    Thread closer = new Thread(() -> close(WsCloseCode.TRY_AGAIN_LATER,
                            "服务端出站队列已满，请重连并补拉"), "hg-ws-overflow-" + id);
                    closer.setDaemon(true);
                    closer.start();
                    return;
                }
            }
            queue.addLast(frame);
            lock.notifyAll();
        }
    }

    private void writeLoop() {
        try {
            while (open) {
                byte[] frame;
                synchronized (lock) {
                    while (open && queue.isEmpty()) {
                        lock.wait(Math.max(1L, heartbeatMillis / 4L));
                    }
                    if (!open) {
                        return;
                    }
                    frame = queue.pollFirst();
                }
                if (frame != null) {
                    out.write(frame);
                    out.flush();
                    sentFrames.incrementAndGet();
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "WS 写出中断（客户端可能已断开）：id=" + id + " " + e.getMessage());
            close(WsCloseCode.GOING_AWAY, "写出失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(WsCloseCode.GOING_AWAY, "服务端停止");
        }
    }

    private void heartbeatLoop() {
        try {
            while (open) {
                Thread.sleep(heartbeatMillis);
                if (!open) {
                    return;
                }
                if (heartbeatExpired()) {
                    LOG.log(Level.INFO, "WS 心跳超时，关闭连接：id=" + id + " userId=" + userId
                            + " 容忍=" + HEARTBEAT_MISS_LIMIT + "×" + heartbeatMillis + "ms");
                    close(WsCloseCode.GOING_AWAY, "心跳超时");
                    return;
                }
                // RFC 6455 原生 ping（`W-3`：非业务 JSON 心跳）
                lastPingMillis = System.currentTimeMillis();
                synchronized (lock) {
                    if (!open) {
                        return;
                    }
                    out.write(WsFrameCodec.encodePing(("hg" + id).getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII)));
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            close(WsCloseCode.GOING_AWAY, "心跳写出失败");
        }
    }

    /** 最近一次 ping 时刻（诊断用）。 */
    long lastPingMillis() {
        return lastPingMillis;
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "关闭 socket 失败（忽略）：id=" + id + " " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "WsConnection{id=" + id + ", userId=" + userId + ", open=" + open
                + ", pending=" + pendingCount() + ", droppedQuote=" + droppedQuote.get() + '}';
    }
}
