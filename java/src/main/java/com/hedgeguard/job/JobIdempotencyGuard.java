package com.hedgeguard.job;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 幂等键 / 状态守卫（{@code 验收标准 4「任务失败可告警且可重入，不产生重复结算」}）。
 *
 * <p><b>状态机</b>（键 = {@code jobId + "#" + 时间片}）：
 * <pre>
 *   ABSENT ──tryAcquire──▶ IN_PROGRESS ──markSucceeded──▶ SUCCEEDED（终态，永不重跑）
 *      ▲                        │
 *      │                        └──markFailed──▶ FAILED（可重入：下次 tick 允许重试）
 *      └────────────────────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li><b>不产生重复结算</b>：{@code SUCCEEDED} 为终态，同一交易日再次触发直接短路；
 *       {@code IN_PROGRESS} 同时挡住并发重复触发（高频 {@code tick()} 节流）。</li>
 *   <li><b>可重入</b>：失败不写终态，而是落到 {@code FAILED} 并<b>释放占用</b>；
 *       同一时间片的下一次 {@code tick()}（或人工补跑）可重新领取并执行。</li>
 * </ul>
 *
 * <p>线程安全：状态迁移经 {@link ConcurrentHashMap#compute} 原子完成，多个调度线程并发
 * {@code tick()} 也只有一个能领到同一键。
 *
 * <p><b>诚实披露</b>：本守卫为进程内状态守卫；进程重启后内存态清空，此时依赖 L3 服务层
 * 自身的幂等约束（如 {@code SettleService} 结算批次 / {@code PostRiskReportService}
 * 报表唯一键，{@code NFR-REL-5}）作为第二道防线。两层合起来覆盖「不可重复结算」。
 */
public final class JobIdempotencyGuard {

    /** 幂等键状态。 */
    public enum State {
        /** 未执行过。 */
        ABSENT,
        /** 已领取、执行中。 */
        IN_PROGRESS,
        /** 成功（终态，禁止重跑）。 */
        SUCCEEDED,
        /** 失败（可重入，允许重试）。 */
        FAILED
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    /**
     * 尝试领取一个幂等键。
     *
     * @param key 幂等键，不得为 {@code null}
     * @return 领取成功（可立即执行）；已成功 / 执行中 / 已成功终态时返回 {@code false}
     */
    public boolean tryAcquire(String key) {
        Objects.requireNonNull(key, "key");
        AtomicBoolean acquired = new AtomicBoolean(false);
        states.compute(key, (k, prev) -> {
            State current = prev == null ? State.ABSENT : prev;
            if (current == State.SUCCEEDED || current == State.IN_PROGRESS) {
                acquired.set(false);
                return current;
            }
            // ABSENT 或 FAILED ⇒ 允许领取（FAILED 即「可重入」的入口）
            acquired.set(true);
            return State.IN_PROGRESS;
        });
        return acquired.get();
    }

    /** 标记成功（终态）。 */
    public void markSucceeded(String key) {
        Objects.requireNonNull(key, "key");
        states.put(key, State.SUCCEEDED);
    }

    /**
     * 标记失败并释放占用（可重入）。
     *
     * <p>刻意<b>不</b>写 {@code SUCCEEDED}：失败任务必须能重试；告警由 {@link JobScheduler}
     * 统一发出，本方法只管状态。
     *
     * @param key 幂等键
     */
    public void markFailed(String key) {
        Objects.requireNonNull(key, "key");
        states.put(key, State.FAILED);
    }

    /** 读取状态（{@code null} 键返回 {@link State#ABSENT}）。 */
    public State stateOf(String key) {
        State s = states.get(key);
        return s == null ? State.ABSENT : s;
    }

    /** 已成功终态的键数量（供单测 / 运维观测）。 */
    public long succeededCount() {
        return states.values().stream().filter(s -> s == State.SUCCEEDED).count();
    }

    /** 已跟踪的键数量。 */
    public int size() {
        return states.size();
    }
}
