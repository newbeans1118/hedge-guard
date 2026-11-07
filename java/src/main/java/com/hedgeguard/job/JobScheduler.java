package com.hedgeguard.job;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * J6 调度器（{@code ARCHITECTURE.md} §3.12）：7 类定时任务的统一编排。
 *
 * <p><b>验收标准映射</b>：
 * <ol>
 *   <li><b>7 类任务</b>：构造时登记 {@link JobSchedules#defaults(JobPorts)} 的 7 个任务，
 *       类型去重且固定为 {@link JobType#count()}；{@link #jobs()} 可观测。</li>
 *   <li><b>分层纪律</b>：本类与全部任务只依赖 {@link JobPorts}（L3 服务端口），
 *       包内零 {@code com.hedgeguard.dao} 引用、零 SQL。</li>
 *   <li><b>可注入时钟</b>（{@code NFR-TEST-4}）：时间只取自构造注入的 {@link JobClock}；
 *       {@link #tick()} 不读墙钟，单测注入可变时钟即可逐帧推进业务日期。</li>
 *   <li><b>可靠性</b>：{@link JobIdempotencyGuard} 保证「成功终态不重跑（不重复结算）」、
 *       「失败释放可重入」；{@link JobAlarm} 保证失败必告警；单个任务失败不影响其余任务。</li>
 * </ol>
 *
 * <p><b>单次 tick 语义</b>：取一次 {@code now}（同一帧内所有任务看到同一业务时间），
 * 按登记顺序逐个判定触发时点 → 幂等键 {@code jobId#时间片} → 领取 → 执行 → 提交状态。
 * 高频调用下同一时间片的重复 tick 由守卫短路，不做全量重复执行（§5.3 节流纪律）。
 *
 * <p>线程安全：状态迁移在 {@link JobIdempotencyGuard} 内原子完成；本类无可变共享状态，
 * 允许由 {@code ScheduledExecutorService} 多线程驱动（生产装配见 {@code Bootstrap}）。
 */
public final class JobScheduler {

    private static final Logger LOG = Logger.getLogger(JobScheduler.class.getName());

    private final JobClock clock;
    private final JobPorts ports;
    private final JobConfig config;
    private final JobAlarm alarm;
    private final JobIdempotencyGuard guard;
    private final long operatorId;
    private final List<ScheduledJob> jobs;
    private final Map<JobType, ScheduledJob> byType;
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * 构造（生产装配用）。
     *
     * @param clock     注入时钟（生产传 {@link JobClock#system()}）
     * @param ports     L3 服务端口
     * @param config    L3 配置读取端口
     * @param alarm     失败告警端口
     * @param guard     幂等状态守卫
     * @param operatorId 系统操作人 id
     * @param jobs      任务清单（须恰好 7 类且不重复）
     */
    public JobScheduler(JobClock clock, JobPorts ports, JobConfig config, JobAlarm alarm,
                        JobIdempotencyGuard guard, long operatorId, List<ScheduledJob> jobs) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.config = Objects.requireNonNull(config, "config");
        this.alarm = Objects.requireNonNull(alarm, "alarm");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.operatorId = operatorId;
        Objects.requireNonNull(jobs, "jobs");
        Map<JobType, ScheduledJob> index = new LinkedHashMap<>();
        for (ScheduledJob job : jobs) {
            Objects.requireNonNull(job, "job");
            if (index.putIfAbsent(job.type(), job) != null) {
                throw new IllegalArgumentException("任务类型重复登记：" + job.type());
            }
        }
        if (index.size() != JobType.count()) {
            throw new IllegalArgumentException("须登记全部 " + JobType.count()
                    + " 类定时任务，实际=" + index.size());
        }
        this.byType = Collections.unmodifiableMap(index);
        this.jobs = Collections.unmodifiableList(new ArrayList<>(jobs));
    }

    /**
     * 便捷构造：默认 7 类任务 + 独立守卫 + 空告警（单测 / 轻量装配用）。
     *
     * @param clock      注入时钟
     * @param ports      L3 服务端口
     * @param config     L3 配置读取端口
     * @param alarm      失败告警端口
     * @param operatorId 系统操作人 id
     */
    public JobScheduler(JobClock clock, JobPorts ports, JobConfig config, JobAlarm alarm,
                        long operatorId) {
        this(clock, ports, config, alarm, new JobIdempotencyGuard(), operatorId,
                Arrays.asList(JobSchedules.defaults()));
    }

    /**
     * 推进一帧：按注入时钟取业务时刻，逐个处置 7 类任务。
     *
     * @return 7 个处置结果（顺序与 {@link #jobs()} 一致）
     */
    public List<JobOutcome.Result> tick() {
        LocalDateTime now = clock.now();
        Objects.requireNonNull(now, "clock.now() 不得为 null");
        LocalDate businessDate = now.toLocalDate();
        List<JobOutcome.Result> results = new ArrayList<>(jobs.size());
        for (ScheduledJob job : jobs) {
            results.add(runOne(job, now, businessDate));
        }
        return results;
    }

    /**
     * 显式指定业务时刻推进一帧（补跑 / 单测可控时间推进；仍不读墙钟）。
     *
     * @param now 业务时刻
     * @return 处置结果
     */
    public List<JobOutcome.Result> tickAt(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        List<JobOutcome.Result> results = new ArrayList<>(jobs.size());
        for (ScheduledJob job : jobs) {
            results.add(runOne(job, now, now.toLocalDate()));
        }
        return results;
    }

    /**
     * 人工补跑指定任务与业务日期（重入入口）。
     *
     * <p>刻意<b>绕过</b>「是否到点」判定，但仍<b>保留</b>幂等守卫：
     * 若该业务日期已成功执行，补跑仍会被短路，绝不产生重复结算。
     *
     * @param type         任务类型
     * @param businessDate 业务日期
     * @return 处置结果
     */
    public JobOutcome.Result runNow(JobType type, LocalDate businessDate) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(businessDate, "businessDate");
        ScheduledJob job = byType.get(type);
        if (job == null) {
            throw new IllegalArgumentException("未登记的任务类型：" + type);
        }
        String bucket = job.trigger().businessDateBucket(businessDate);
        return executeGuarded(job, businessDate.atStartOfDay(), businessDate, bucket);
    }

    private JobOutcome.Result runOne(ScheduledJob job, LocalDateTime now, LocalDate businessDate) {
        String bucket = job.trigger().dueBucket(now);
        if (bucket == null) {
            return JobOutcome.Result.notDue(job.type());
        }
        return executeGuarded(job, now, businessDate, bucket);
    }

    private JobOutcome.Result executeGuarded(ScheduledJob job, LocalDateTime now,
                                             LocalDate businessDate, String bucket) {
        String key = job.type().id() + "#" + bucket;
        if (!guard.tryAcquire(key)) {
            return JobOutcome.Result.duplicate(job.type(), bucket);
        }
        JobContext ctx = new JobContext(now, businessDate, operatorId, config, alarm);
        try {
            JobOutcome.Summary summary = job.execute(ctx, ports);
            guard.markSucceeded(key);
            return JobOutcome.Result.success(job.type(), bucket, summary);
        } catch (RuntimeException e) {
            // 失败：释放为可重入状态（不写成功终态），并告警；其余任务不受影响
            guard.markFailed(key);
            alert(job.type(), key, e);
            return JobOutcome.Result.failed(job.type(), bucket, e);
        }
    }

    private void alert(JobType type, String key, Throwable cause) {
        String message = "定时任务失败：job=" + type.id() + "，幂等键=" + key
                + "，原因=" + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        try {
            alarm.alert(type.id(), message, cause);
        } catch (RuntimeException alarmFailure) {
            // 告警 sink 自身失败不得掩盖原失败、不得中断其余任务（JobAlarm 契约）
            // 此处刻意吞掉：原失败已体现在 FAILED 结果中，且不可再抛影响后续任务。
        }
    }

    /**
     * 挂到生产调度线程池（{@code ARCHITECTURE.md} §3.12「J6 调度线程 ScheduledExecutorService」）。
     *
     * <p><b>生产只负责节拍</b>：真正的「是否到点 / 业务日期」仍由注入的 {@link JobClock} 决定，
     * 故替换时钟即可在单测中完整控制时间推进，不依赖真实墙钟。线程池由调用方持有与关闭。
     *
     * <p>重复调用返回既有句柄（幂等，不重复注册节拍）。
     *
     * @param executor  外部持有的调度线程池（守护线程由调用方设定）
     * @param periodSec 节拍周期（秒）
     * @return 调度句柄
     */
    public synchronized ScheduledFuture<?> start(ScheduledExecutorService executor, long periodSec) {
        Objects.requireNonNull(executor, "executor");
        if (periodSec <= 0L) {
            throw new IllegalArgumentException("调度节拍周期须为正：收到 " + periodSec);
        }
        if (scheduledFuture != null) {
            return scheduledFuture;
        }
        scheduledFuture = executor.scheduleAtFixedRate(this::safeTick, periodSec, periodSec,
                TimeUnit.SECONDS);
        return scheduledFuture;
    }

    /** 取消已注册的调度节拍（幂等；不关闭外部线程池）。 */
    public synchronized void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            // 单个节拍异常不得终止整个调度线程（失败已在 executeGuarded 内告警）
            LOG.log(Level.SEVERE, "[hg-job] 调度节拍异常（不中断后续节拍）", e);
        }
    }

    /** 已登记任务（不可变，顺序即执行顺序）。 */
    public List<ScheduledJob> jobs() {
        return jobs;
    }

    /** 幂等状态守卫（可观测）。 */
    public JobIdempotencyGuard guard() {
        return guard;
    }

    /** 注入的时钟（可观测）。 */
    public JobClock clock() {
        return clock;
    }

    /** 系统操作人 id。 */
    public long operatorId() {
        return operatorId;
    }
}
