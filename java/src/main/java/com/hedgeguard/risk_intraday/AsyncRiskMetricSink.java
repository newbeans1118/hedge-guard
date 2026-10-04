package com.hedgeguard.risk_intraday;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 异步落库装饰器（{@code FR-RISK-13} 验收 4「先算后异步落库」）。
 *
 * <p>把评估产物投递到一个<b>有界队列</b>，由单守护线程顺序交给下游
 * {@link RiskMetricSink}（生产为 {@code T-065} 的真实 DB 写者）。{@link #publish} 只做入队，
 * <b>不等待</b>下游，故事件消费路径不会被 DB I/O 阻塞（{@code R-RISK-10}）。
 *
 * <p>队列满时<b>丢最旧</b>并计数（{@link #dropped()}）：事中监控属可重算的观测数据，
 * 宁可丢观测样本，也不得阻塞事件消费或无限增长内存。
 */
public final class AsyncRiskMetricSink implements RiskMetricSink, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AsyncRiskMetricSink.class.getName());

    /** 缺省队列容量。 */
    public static final int DEFAULT_CAPACITY = 1024;

    private final RiskMetricSink downstream;
    private final int capacity;
    private final Deque<RiskEvaluation> queue = new ArrayDeque<>();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    /**
     * 构造（缺省容量）。
     *
     * @param downstream 下游写者（不得为 {@code null}）
     */
    public AsyncRiskMetricSink(RiskMetricSink downstream) {
        this(downstream, DEFAULT_CAPACITY);
    }

    /**
     * 构造。
     *
     * @param downstream 下游写者
     * @param capacity   队列容量（&gt;0）
     */
    public AsyncRiskMetricSink(RiskMetricSink downstream, int capacity) {
        if (downstream == null) {
            throw new IllegalArgumentException("下游写者不得为 null");
        }
        this.downstream = downstream;
        this.capacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
        this.worker = new Thread(this::drain, "hg-risk-metric-sink");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void publish(RiskEvaluation evaluation) {
        if (evaluation == null) {
            return;
        }
        synchronized (queue) {
            if (!running) {
                return;
            }
            while (queue.size() >= capacity) {
                queue.pollFirst();
                dropped.incrementAndGet();
            }
            queue.addLast(evaluation);
            queue.notifyAll();
        }
    }

    /** 队列满丢弃的样本数（可观测）。 */
    public long dropped() {
        return dropped.get();
    }

    private void drain() {
        while (running) {
            RiskEvaluation next;
            synchronized (queue) {
                while (running && queue.isEmpty()) {
                    try {
                        queue.wait(200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                next = queue.pollFirst();
            }
            if (next == null) {
                continue;
            }
            try {
                downstream.publish(next);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "异步落库下游异常（已丢弃该样本，不阻塞事件消费）："
                        + e.getClass().getSimpleName() + "：" + e.getMessage(), e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        synchronized (queue) {
            queue.notifyAll();
        }
    }
}
