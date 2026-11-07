package com.hedgeguard.job;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 幂等键 / 状态守卫（{@code 验收标准 4「任务失败可告警且可重入，不产生重复结算」}）。
 *
 * <p><b>状态机</b>（键 = {@code jobId + "#" + 时间片}）：
 * <pre>
 *   ABSENT ──acquire──▶ IN_PROGRESS ──markSucceeded──▶ SUCCEEDED（终态，永不重跑）
 *      ▲                     │
 *      │                     └──markFailed──▶ FAILED（可重入：连续失败次数 &lt; 上限时允许重试）
 *      │                                          │
 *      └──────────────────────────────────────────┴──连续失败 ≥ 上限 ⇒ 拒绝领取（等待人工介入）
 * </pre>
 *
 * <ul>
 *   <li><b>不产生重复结算</b>：{@code SUCCEEDED} 为终态，同一交易日再次触发直接短路；
 *       {@code IN_PROGRESS} 同时挡住并发重复触发（高频 {@code tick()} 节流）。</li>
 *   <li><b>可重入</b>：失败不写终态，而是落到 {@code FAILED} 并<b>释放占用</b>；
 *       同一时间片的下一次 {@code tick()}（或人工补跑）可重新领取并执行。</li>
 *   <li><b>重试上限</b>（{@link #DEFAULT_MAX_ATTEMPTS}）：每个幂等键维护<b>连续失败计数</b>，
 *       与状态在同一个 {@link ConcurrentHashMap#compute} 内原子更新。连续失败达上限后
 *       {@link #acquire(String)} 返回 {@link AcquireOutcome#RETRY_LIMIT_EXHAUSTED}，
 *       调度器据此<b>不再调用 L3 端口</b>——避免「60s 节拍 + 持续故障」下每拍重跑一次
 *       （生产日更任务时间片为整天，故障一天可达数百次）与告警风暴。</li>
 *   <li><b>告警去重</b>：{@link #markFailed(String)} 回执 {@link FailureRecord#alertDue()}
 *       仅在「本次连续失败的第 1 次」与「达上限那一次」为 {@code true}；同一轮失败被重复
 *       上报（{@code FAILED → markFailed}）不重复计数、不再告警。成功则清零计数。</li>
 * </ul>
 *
 * <p>线程安全：状态与计数迁移经 {@link ConcurrentHashMap#compute} 原子完成，多个调度线程并发
 * {@code tick()} 也只有一个能领到同一键；计数与状态不会出现「状态已改、计数未改」的中间态。
 *
 * <p><b>诚实披露</b>：本守卫为进程内状态守卫；进程重启后内存态清空，此时依赖 L3 服务层
 * 自身的幂等约束（如 {@code SettleService} 结算批次 / {@code PostRiskReportService}
 * 报表唯一键，{@code NFR-REL-5}）作为第二道防线。两层合起来覆盖「不可重复结算」。
 */
public final class JobIdempotencyGuard {

    /** 同一幂等键允许的<b>连续失败</b>次数上限（达上限即停止自动重试，等待人工介入）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 幂等键状态。 */
    public enum State {
        /** 未执行过。 */
        ABSENT,
        /** 已领取、执行中。 */
        IN_PROGRESS,
        /** 成功（终态，禁止重跑）。 */
        SUCCEEDED,
        /** 失败（连续失败未达上限时可重入，允许重试）。 */
        FAILED
    }

    /** 领取幂等键的结果（区分「领取成功 / 已处理 / 重试次数已耗尽」）。 */
    public enum AcquireOutcome {
        /** 领取成功，可立即执行。 */
        ACQUIRED,
        /** 已成功（终态）或正在执行中，本次不执行。 */
        ALREADY_HANDLED,
        /** 已处于失败态且连续失败次数达上限，停止自动重试（本次不执行、不调 L3 端口、不再告警）。 */
        RETRY_LIMIT_EXHAUSTED
    }

    /**
     * 一次失败登记的回执（{@link #markFailed(String)} 返回值）。
     *
     * <p>调度器据此决定「是否告警」，避免失败告警在 60s 节拍下每拍重放。
     */
    public static final class FailureRecord {

        private final int attempt;
        private final boolean alertDue;
        private final boolean retryExhausted;

        FailureRecord(int attempt, boolean alertDue, boolean retryExhausted) {
            this.attempt = attempt;
            this.alertDue = alertDue;
            this.retryExhausted = retryExhausted;
        }

        /** 本次失败是该键<b>连续失败</b>的第几次（从 1 起）。 */
        public int attempt() {
            return attempt;
        }

        /** 本次是否应发出失败告警（首次失败 或 达重试上限时为 {@code true}，其余为 {@code false}）。 */
        public boolean alertDue() {
            return alertDue;
        }

        /** 本次失败后是否已达重试上限（后续 {@link #acquire(String)} 将拒绝领取）。 */
        public boolean retryExhausted() {
            return retryExhausted;
        }
    }

    /** 单键状态 + 连续失败计数（不可变，整体替换保证原子性）。 */
    private static final class KeyState {

        private static final KeyState ABSENT_STATE = new KeyState(State.ABSENT, 0);
        private static final KeyState SUCCEEDED_STATE = new KeyState(State.SUCCEEDED, 0);

        private final State state;
        private final int consecutiveFailures;

        KeyState(State state, int consecutiveFailures) {
            this.state = state;
            this.consecutiveFailures = consecutiveFailures;
        }
    }

    private final int maxAttempts;
    private final ConcurrentHashMap<String, KeyState> states = new ConcurrentHashMap<>();

    /** 默认装配（连续失败上限 {@value #DEFAULT_MAX_ATTEMPTS} 次）。 */
    public JobIdempotencyGuard() {
        this(DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * 指定连续失败上限（运维/单测可调）。
     *
     * @param maxAttempts 连续失败上限，须 ≥ 1
     */
    public JobIdempotencyGuard(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("重试上限须 ≥ 1：收到 " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
    }

    /** 连续失败上限。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 尝试领取一个幂等键（含重试上限判定）。
     *
     * @param key 幂等键，不得为 {@code null}
     * @return 领取结果：{@link AcquireOutcome#ACQUIRED} / {@link AcquireOutcome#ALREADY_HANDLED}
     *         / {@link AcquireOutcome#RETRY_LIMIT_EXHAUSTED}
     */
    public AcquireOutcome acquire(String key) {
        Objects.requireNonNull(key, "key");
        AtomicReference<AcquireOutcome> outcome = new AtomicReference<>();
        states.compute(key, (k, prev) -> {
            KeyState current = prev == null ? KeyState.ABSENT_STATE : prev;
            if (current.state == State.SUCCEEDED || current.state == State.IN_PROGRESS) {
                outcome.set(AcquireOutcome.ALREADY_HANDLED);
                return current;
            }
            if (current.state == State.FAILED && current.consecutiveFailures >= maxAttempts) {
                // 重试上限已耗尽：拒绝领取，调度器不得再调用 L3 端口
                outcome.set(AcquireOutcome.RETRY_LIMIT_EXHAUSTED);
                return current;
            }
            // ABSENT 或 FAILED（计数未达上限）⇒ 允许领取（FAILED 即「可重入」的入口）
            outcome.set(AcquireOutcome.ACQUIRED);
            return new KeyState(State.IN_PROGRESS, current.consecutiveFailures);
        });
        return outcome.get();
    }

    /**
     * 尝试领取一个幂等键（布尔简化形式，等价于 {@code acquire(key) == ACQUIRED}）。
     *
     * @param key 幂等键，不得为 {@code null}
     * @return 领取成功（可立即执行）；已成功 / 执行中 / 重试上限已耗尽时返回 {@code false}
     */
    public boolean tryAcquire(String key) {
        return acquire(key) == AcquireOutcome.ACQUIRED;
    }

    /**
     * 标记成功（终态），并清零连续失败计数。
     *
     * @param key 幂等键
     */
    public void markSucceeded(String key) {
        Objects.requireNonNull(key, "key");
        states.compute(key, (k, prev) -> KeyState.SUCCEEDED_STATE);
    }

    /**
     * 标记失败并释放占用（连续失败未达上限时可重入）。
     *
     * <p>刻意<b>不</b>写 {@code SUCCEEDED}：失败任务在阈值内必须能重试；告警由
     * {@link JobScheduler} 依回执 {@link FailureRecord#alertDue()} 统一发出（去重），
     * 本方法只管状态与计数。
     *
     * @param key 幂等键
     * @return 失败回执（第几次连续失败 / 本次是否应告警 / 是否已达上限）
     */
    public FailureRecord markFailed(String key) {
        Objects.requireNonNull(key, "key");
        AtomicReference<FailureRecord> record = new AtomicReference<>();
        states.compute(key, (k, prev) -> {
            KeyState current = prev == null ? KeyState.ABSENT_STATE : prev;
            if (current.state == State.FAILED) {
                // 同一轮失败被重复上报：不重复计数、不再告警（同键同时间片告警去重）
                record.set(new FailureRecord(current.consecutiveFailures, false,
                        current.consecutiveFailures >= maxAttempts));
                return current;
            }
            int attempt = current.consecutiveFailures + 1;
            boolean exhausted = attempt >= maxAttempts;
            record.set(new FailureRecord(attempt, attempt == 1 || exhausted, exhausted));
            return new KeyState(State.FAILED, attempt);
        });
        return record.get();
    }

    /** 读取状态（{@code null} 键返回 {@link State#ABSENT}）。 */
    public State stateOf(String key) {
        KeyState s = states.get(key);
        return s == null ? State.ABSENT : s.state;
    }

    /** 读取某幂等键的<b>连续失败</b>次数（未跟踪 / 已成功均为 0）。 */
    public int failuresOf(String key) {
        KeyState s = states.get(key);
        return s == null ? 0 : s.consecutiveFailures;
    }

    /** 已成功终态的键数量（供单测 / 运维观测）。 */
    public long succeededCount() {
        return states.values().stream().filter(s -> s.state == State.SUCCEEDED).count();
    }

    /** 已跟踪的键数量。 */
    public int size() {
        return states.size();
    }
}
