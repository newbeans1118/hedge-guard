package com.hedgeguard.job;

import com.hedgeguard.common.Clocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-051} 调度层单测：验收标准 1–4 的可执行判据。
 *
 * <p>覆盖：
 * <ol>
 *   <li><b>7 类定时任务</b>：{@link JobType#count()} == 7、装配去重且全覆盖、触发时点齐全；</li>
 *   <li><b>分层纪律</b>：{@code job} 包零 {@code com.hedgeguard.dao} 引用（由 {@link JobLayeringTest}
 *       静态扫描保证），调度器只持 {@link JobPorts} 端口；</li>
 *   <li><b>可注入时钟</b>（{@code NFR-TEST-4}）：{@link MutableJobClock} 推进时间，
 *       同一用例在固定时钟下结果稳定、不依赖真实墙钟；</li>
 *   <li><b>可靠性</b>：幂等守卫（不重复结算）、失败必告警、失败后可重入、单任务失败隔离、
 *       并发不重复、高频巡检节流。变异测试见 {@code T-051} 回报：摘幂等守卫 ⇒
 *       {@link #successIsTerminalNoDuplicateSettlement} 必红。</li>
 * </ol>
 */
class JobSchedulerTest {

    private static final long SYS = 0L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 20); // 周日：验证「到点判定只看注入时刻」

    @AfterEach
    void resetClocks() {
        Clocks.reset();
    }

    private static JobScheduler scheduler(JobClock clock, JobPorts ports, JobConfig config,
                                          JobAlarm alarm) {
        return new JobScheduler(clock, ports, config, alarm, SYS);
    }

    // ================================================================ 验收 1：7 类任务

    @Test
    @DisplayName("验收1：JobType 恰 7 类且 id 唯一；默认装配 7 个任务全覆盖、顺序即依赖顺序")
    void sevenJobTypesAndCompleteAssembly() {
        assertEquals(7, JobType.count());
        Set<String> ids = new HashSet<>();
        for (JobType t : JobType.values()) {
            assertTrue(ids.add(t.id()), "任务 id 重复：" + t.id());
            assertNotNull(t.displayName());
        }
        JobScheduler s = scheduler(new MutableJobClock(DAY.atTime(9, 0)).asJobClock(),
                new RecordingJobPorts(), JobConfig.DEFAULTS, JobAlarm.NOOP);
        assertEquals(7, s.jobs().size());
        assertEquals(JobType.count(), s.jobs().size());
        for (JobType t : JobType.values()) {
            assertTrue(s.jobs().stream().anyMatch(j -> j.type() == t), "缺少任务：" + t);
        }
        // 依赖顺序：到期/周期提醒（盘前）→ 日终结算 → 事后风控 → 报表 → 对账 → 水位巡检
        List<JobType> expected = Arrays.asList(JobSchedules.orderedTypes());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), s.jobs().get(i).type());
        }
        // 触发时点描述全部非空
        for (com.hedgeguard.job.ScheduledJob j : s.jobs()) {
            assertNotNull(j.trigger());
            assertFalse(j.trigger().describe().isEmpty());
        }
    }

    @Test
    @DisplayName("验收1：JobClock.system() 一律经 Clocks（生产用系统时钟，Clocks.set 可替换）")
    void productionClockGoesThroughClocks() {
        LocalDateTime fixed = DAY.atTime(15, 30);
        Clocks.set(Clock.fixed(fixed.atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        assertEquals(fixed, JobClock.system().now());
        assertEquals(DAY, JobClock.system().today());
    }

    @Test
    @DisplayName("装配自检：任务类型重复 / 缺类 ⇒ 拒绝装配（防静默漏任务）")
    void assemblyRejectsDuplicateOrMissingTypes() {
        JobPorts ports = new RecordingJobPorts();
        JobClock clock = new MutableJobClock(DAY.atTime(9, 0)).asJobClock();
        JobConfig cfg = JobConfig.DEFAULTS;
        // 重复
        com.hedgeguard.job.ScheduledJob dup = new DailySettlementJob(JobTrigger.dailyAt(LocalTime.of(15, 30)));
        assertThrows(IllegalArgumentException.class, () -> new JobScheduler(clock, ports, cfg,
                JobAlarm.NOOP, new JobIdempotencyGuard(), SYS,
                Arrays.asList(dup, dup)));
        // 缺类
        assertThrows(IllegalArgumentException.class, () -> new JobScheduler(clock, ports, cfg,
                JobAlarm.NOOP, new JobIdempotencyGuard(), SYS, Collections.singletonList(dup)));
    }

    // ================================================================ 验收 3：可注入时钟 / 触发时点

    @Test
    @DisplayName("验收3：注入时钟推进到各任务时点才触发；未到点 NOT_DUE，且同用例结果稳定")
    void injectableClockControlsTriggering() {
        MutableJobClock clock = new MutableJobClock(DAY.atStartOfDay());
        RecordingJobPorts ports = new RecordingJobPorts();
        JobScheduler s = scheduler(clock.asJobClock(), ports, JobConfig.DEFAULTS, JobAlarm.NOOP);

        // 00:00：所有日更任务未到点（高频水位巡检按 60s 时间片恒定到点）
        List<JobOutcome.Result> midnight = s.tick();
        for (JobOutcome.Result r : midnight) {
            if (r.type() == JobType.EVENT_FLOW_WATERMARK) {
                assertEquals(JobOutcome.Status.SUCCESS, r.status());
            } else {
                assertEquals(JobOutcome.Status.NOT_DUE, r.status(), "未到点任务不应执行：" + r.type());
            }
        }
        assertEquals(0, ports.calls(JobType.DAILY_SETTLEMENT));

        // 推进到 08:30 → 到期提醒触发；09:00 报告周期提醒仍未到点
        clock.set(DAY.atTime(8, 30));
        s.tick();
        assertEquals(1, ports.calls(JobType.PLAN_EXPIRY_REMINDER));
        assertEquals(0, ports.calls(JobType.REPORT_PERIOD_REMINDER));

        // 推进到 17:00 → 日终结算 / 事后风控 / 报表 / 对账全部触发（各一次）
        clock.set(DAY.atTime(17, 0));
        s.tick();
        assertEquals(1, ports.calls(JobType.REPORT_PERIOD_REMINDER));
        assertEquals(1, ports.calls(JobType.DAILY_SETTLEMENT));
        assertEquals(1, ports.calls(JobType.POST_RISK_REPORT));
        assertEquals(1, ports.calls(JobType.REPORT_GENERATION));
        assertEquals(1, ports.calls(JobType.RECONCILIATION));
        assertEquals(DAY, ports.lastSettlementDate, "业务日期须来自注入时钟");
    }

    @Test
    @DisplayName("验收3：tickAt(explicitNow) 显式推进，同样不读墙钟；不同日期是不同时间片")
    void tickAtIsDeterministicAndDateScoped() {
        MutableJobClock clock = new MutableJobClock(DAY.atStartOfDay());
        RecordingJobPorts ports = new RecordingJobPorts();
        JobScheduler s = scheduler(clock.asJobClock(), ports, JobConfig.DEFAULTS, JobAlarm.NOOP);

        // 同一时刻重复 tickAt ⇒ 结果逐项一致（幂等短路不计，此处仅比对 NOT_DUE/SUCCESS 形状）
        List<JobOutcome.Result> a = s.tickAt(DAY.atTime(18, 0));
        List<JobOutcome.Status> first = statuses(a);
        JobScheduler s2 = scheduler(new MutableJobClock(DAY.atStartOfDay()).asJobClock(),
                new RecordingJobPorts(), JobConfig.DEFAULTS, JobAlarm.NOOP);
        List<JobOutcome.Status> second = statuses(s2.tickAt(DAY.atTime(18, 0)));
        assertEquals(first, second, "固定时钟下同一用例结果必须稳定可重复");

        // 跨业务日期 ⇒ 新时间片 ⇒ 重新执行（不因前一日成功而永久短路）
        s.tickAt(DAY.plusDays(1).atTime(18, 0));
        assertEquals(2, ports.calls(JobType.DAILY_SETTLEMENT), "新交易日应重新结算一次");
    }

    private static List<JobOutcome.Status> statuses(List<JobOutcome.Result> results) {
        List<JobOutcome.Status> out = new ArrayList<>();
        for (JobOutcome.Result r : results) {
            out.add(r.status());
        }
        return out;
    }

    @Test
    @DisplayName("验收3：JobTrigger.dailyAt 的到点判定与 every 的时间片对齐")
    void triggerBucketSemantics() {
        JobTrigger daily = JobTrigger.dailyAt(LocalTime.of(15, 30));
        assertEquals(null, daily.dueBucket(DAY.atTime(15, 29)));
        assertEquals(DAY.toString(), daily.dueBucket(DAY.atTime(15, 30)));
        assertEquals(DAY.toString(), daily.dueBucket(DAY.atTime(23, 59)));
        assertEquals(DAY.plusDays(1).toString(), daily.dueBucket(DAY.plusDays(1).atTime(15, 30)));
        assertEquals(DAY.toString(), daily.businessDateBucket(DAY));

        JobTrigger every = JobTrigger.every(java.time.Duration.ofSeconds(60));
        String b1 = every.dueBucket(DAY.atTime(10, 5, 3));
        String b2 = every.dueBucket(DAY.atTime(10, 5, 59));
        String b3 = every.dueBucket(DAY.atTime(10, 6, 0));
        assertEquals(b1, b2, "同一分钟片内 bucket 必须相同（节流）");
        assertNotEquals(b1, b3, "跨分钟片 bucket 必须不同");

        assertThrows(IllegalArgumentException.class,
                () -> JobTrigger.every(java.time.Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> JobTrigger.every(java.time.Duration.ofMillis(1500)));
    }

    // ================================================================ 验收 4：幂等 / 告警 / 重入

    @Test
    @DisplayName("验收4【变异守卫】：同一交易日重复 tick 不产生重复结算（幂等键终态）")
    void successIsTerminalNoDuplicateSettlement() {
        MutableJobClock clock = new MutableJobClock(DAY.atTime(17, 0));
        RecordingJobPorts ports = new RecordingJobPorts();
        JobScheduler s = scheduler(clock.asJobClock(), ports, JobConfig.DEFAULTS, JobAlarm.NOOP);

        List<JobOutcome.Result> first = s.tick();
        assertEquals(JobOutcome.Status.SUCCESS, result(first, JobType.DAILY_SETTLEMENT).status());
        assertEquals(1, ports.calls(JobType.DAILY_SETTLEMENT), "首次应结算一次");

        // 同一时间片内多次 tick（模拟高频心跳 / 重复投递 / 运维误触）
        for (int i = 0; i < 5; i++) {
            List<JobOutcome.Result> repeat = s.tick();
            assertEquals(JobOutcome.Status.SKIPPED_DUPLICATE,
                    result(repeat, JobType.DAILY_SETTLEMENT).status(),
                    "同一交易日已成功的结算任务必须被幂等守卫短路");
        }
        assertEquals(1, ports.calls(JobType.DAILY_SETTLEMENT),
                "★守卫有牙：摘掉幂等守卫后此处必 > 1，用例必红（不产生重复结算）");
        assertEquals(1, ports.calls(JobType.POST_RISK_REPORT));
        assertEquals(1, ports.calls(JobType.REPORT_GENERATION));
        assertEquals(1, ports.calls(JobType.RECONCILIATION));
        assertEquals(7L, s.guard().succeededCount(), "7 类任务各留一个成功终态幂等键");
    }

    @Test
    @DisplayName("验收4：失败必告警（含 jobId 与时间片），且失败释放为可重入态")
    void failureAlarmsAndIsRetriable() {
        MutableJobClock clock = new MutableJobClock(DAY.atTime(17, 0));
        RecordingJobPorts ports = new RecordingJobPorts();
        List<String> failureAlarms = new ArrayList<>();
        // 只收集「带异常」的失败告警；水位滞后一类的非失败告警 cause 为 null，属正常（不参与本断言）。
        JobAlarm alarm = (jobId, message, cause) -> {
            if (cause != null) {
                failureAlarms.add(jobId + "|" + message);
            }
        };
        JobScheduler s = scheduler(clock.asJobClock(), ports, JobConfig.DEFAULTS, alarm);

        ports.failing.add(JobType.DAILY_SETTLEMENT);
        List<JobOutcome.Result> failed = s.tick();
        assertEquals(JobOutcome.Status.FAILED, result(failed, JobType.DAILY_SETTLEMENT).status());
        assertEquals(1, failureAlarms.size(), "任务失败必须告警一次且带上异常");
        assertTrue(failureAlarms.get(0).contains(JobType.DAILY_SETTLEMENT.id()));
        assertTrue(failureAlarms.get(0).contains(DAY.toString()), "告警须含失败时间片，便于运维定位");
        // 其余任务不受影响（失败隔离）
        assertEquals(JobOutcome.Status.SUCCESS, result(failed, JobType.POST_RISK_REPORT).status());
        assertEquals(JobOutcome.Status.SUCCESS, result(failed, JobType.REPORT_GENERATION).status());

        String key = JobType.DAILY_SETTLEMENT.id() + "#" + DAY;
        assertEquals(JobIdempotencyGuard.State.FAILED, s.guard().stateOf(key), "失败须落 FAILED 而非终态");

        // 修复后重入：同一时间片内再次 tick 应重新执行并成功
        ports.failing.remove(JobType.DAILY_SETTLEMENT);
        List<JobOutcome.Result> retry = s.tick();
        assertEquals(JobOutcome.Status.SUCCESS, result(retry, JobType.DAILY_SETTLEMENT).status(),
                "失败可重入：下一次 tick 必须能重试成功");
        assertEquals(2, ports.calls(JobType.DAILY_SETTLEMENT));
        assertEquals(JobIdempotencyGuard.State.SUCCEEDED, s.guard().stateOf(key));

        // 成功后再次短路（重入完成即回到幂等终态）
        s.tick();
        assertEquals(2, ports.calls(JobType.DAILY_SETTLEMENT));
    }

    @Test
    @DisplayName("验收4：告警 sink 自身抛异常不得掩盖任务失败、不得中断其余任务")
    void alarmSinkFailureIsIsolated() {
        RecordingJobPorts ports = new RecordingJobPorts();
        ports.failing.add(JobType.DAILY_SETTLEMENT);
        JobAlarm throwing = (jobId, message, cause) -> {
            throw new IllegalStateException("告警 sink 故障");
        };
        JobScheduler s = scheduler(new MutableJobClock(DAY.atTime(17, 0)).asJobClock(),
                ports, JobConfig.DEFAULTS, throwing);
        List<JobOutcome.Result> results = assertDoesNotThrow(() -> s.tick());
        assertEquals(JobOutcome.Status.FAILED, result(results, JobType.DAILY_SETTLEMENT).status());
        assertEquals(JobOutcome.Status.SUCCESS, result(results, JobType.RECONCILIATION).status());
    }

    @Test
    @DisplayName("验收4：runNow 人工补跑保留幂等守卫（已成功不可重复结算）；未到点也可显式触发")
    void runNowStillGuarded() {
        MutableJobClock clock = new MutableJobClock(DAY.atTime(6, 0)); // 全未到点
        RecordingJobPorts ports = new RecordingJobPorts();
        JobScheduler s = scheduler(clock.asJobClock(), ports, JobConfig.DEFAULTS, JobAlarm.NOOP);

        JobOutcome.Result manual = s.runNow(JobType.DAILY_SETTLEMENT, DAY);
        assertEquals(JobOutcome.Status.SUCCESS, manual.status());
        assertEquals(1, ports.calls(JobType.DAILY_SETTLEMENT));

        JobOutcome.Result again = s.runNow(JobType.DAILY_SETTLEMENT, DAY);
        assertEquals(JobOutcome.Status.SKIPPED_DUPLICATE, again.status(),
                "人工补跑也不得破坏「不产生重复结算」");
        assertEquals(1, ports.calls(JobType.DAILY_SETTLEMENT));
        assertThrows(NullPointerException.class, () -> s.runNow(null, DAY));
        assertThrows(NullPointerException.class, () -> s.runNow(JobType.DAILY_SETTLEMENT, null));
    }

    @Test
    @DisplayName("验收4：高频水位巡检按时间片节流，片内重复 tick 不重复全量巡检")
    void highFrequencyWatermarkIsThrottled() {
        MutableJobClock clock = new MutableJobClock(DAY.atTime(10, 5, 1));
        RecordingJobPorts ports = new RecordingJobPorts();
        JobScheduler s = scheduler(clock.asJobClock(), ports, JobConfig.DEFAULTS, JobAlarm.NOOP);

        s.tick();
        clock.set(DAY.atTime(10, 5, 20));
        s.tick();
        clock.set(DAY.atTime(10, 5, 59));
        s.tick();
        assertEquals(1, ports.calls(JobType.EVENT_FLOW_WATERMARK), "同一分钟片内只巡检一次");

        clock.set(DAY.atTime(10, 6, 0));
        s.tick();
        assertEquals(2, ports.calls(JobType.EVENT_FLOW_WATERMARK), "跨片后重新巡检一次");
    }

    @Test
    @DisplayName("验收4：水位巡检告警由状态变化驱动（落后才告警，不是无条件重放）")
    void watermarkAlarmIsStateChangeDriven() {
        MutableJobClock clock = new MutableJobClock(DAY.atTime(10, 5, 1));
        RecordingJobPorts ports = new RecordingJobPorts();
        List<String> alarms = new ArrayList<>();
        JobScheduler s = scheduler(clock.asJobClock(), ports,
                JobConfig.DEFAULTS, (jobId, message, cause) -> alarms.add(message));
        s.tick();
        assertEquals(1, alarms.size());
        assertTrue(alarms.get(0).contains("落后"));

        // 无滞后 ⇒ 无新增告警
        JobPorts noLag = JobPorts.of(
                (d, op) -> JobOutcome.Summary.empty(), (d, op) -> JobOutcome.Summary.empty(),
                (d, op) -> JobOutcome.Summary.empty(), (d, op) -> JobOutcome.Summary.empty(),
                (d, days, op) -> JobOutcome.Summary.empty(), (d, op) -> JobOutcome.Summary.empty(),
                op -> JobOutcome.Summary.empty());
        JobScheduler s2 = scheduler(new MutableJobClock(DAY.atTime(10, 5, 1)).asJobClock(),
                noLag, JobConfig.DEFAULTS, (jobId, message, cause) -> alarms.add(message));
        s2.tick();
        assertEquals(1, alarms.size(), "水位齐平时不得告警（禁止无条件循环重放）");
    }

    // ================================================================ CFG-24

    @Test
    @DisplayName("CFG-24：到期提醒提前天数经 JobConfig 惰性读取；越界回退缺省 30")
    void cfg24IsReadLazilyAndClamped() {
        MutableJobClock clock = new MutableJobClock(DAY.atTime(8, 30));
        RecordingJobPorts ports = new RecordingJobPorts();
        AtomicInteger reads = new AtomicInteger();
        JobConfig cfg = (key, def) -> {
            reads.incrementAndGet();
            assertEquals(JobPorts.CFG_PLAN_EXPIRY_REMIND_DAYS, key);
            assertEquals(JobPorts.DEFAULT_PLAN_EXPIRY_REMIND_DAYS, def);
            return 7;
        };
        JobScheduler s = scheduler(clock.asJobClock(), ports, cfg, JobAlarm.NOOP);
        s.tick();
        assertEquals(7, ports.lastAdvanceDays, "CFG-24 值须透传到 L3 端口");
        assertEquals(DAY, ports.lastRemindAsOf);
        assertTrue(reads.get() >= 1, "每次执行都读配置（变更后下一次触发即生效）");

        // 越界 → 回退缺省
        RecordingJobPorts p2 = new RecordingJobPorts();
        JobScheduler s2 = scheduler(new MutableJobClock(DAY.atTime(8, 30)).asJobClock(),
                p2, (key, def) -> 999, JobAlarm.NOOP);
        s2.tick();
        assertEquals(JobPorts.DEFAULT_PLAN_EXPIRY_REMIND_DAYS, p2.lastAdvanceDays);

        // 缺省值（键缺失）
        RecordingJobPorts p3 = new RecordingJobPorts();
        JobScheduler s3 = scheduler(new MutableJobClock(DAY.atTime(8, 30)).asJobClock(),
                p3, JobConfig.DEFAULTS, JobAlarm.NOOP);
        s3.tick();
        assertEquals(30, p3.lastAdvanceDays);
    }

    // ================================================================ 守卫状态机 / 并发

    @Test
    @DisplayName("幂等守卫状态机：ABSENT→IN_PROGRESS→SUCCEEDED/FAILED，FAILED 可重入")
    void guardStateMachine() {
        JobIdempotencyGuard g = new JobIdempotencyGuard();
        assertEquals(JobIdempotencyGuard.State.ABSENT, g.stateOf("k"));
        assertTrue(g.tryAcquire("k"));
        assertEquals(JobIdempotencyGuard.State.IN_PROGRESS, g.stateOf("k"));
        assertFalse(g.tryAcquire("k"), "执行中不得并发重复领取");
        g.markSucceeded("k");
        assertEquals(JobIdempotencyGuard.State.SUCCEEDED, g.stateOf("k"));
        assertFalse(g.tryAcquire("k"), "成功终态不得重跑");
        assertEquals(1L, g.succeededCount());

        g.markFailed("k2");
        assertEquals(JobIdempotencyGuard.State.FAILED, g.stateOf("k2"));
        assertTrue(g.tryAcquire("k2"), "失败须可重入");
    }

    @Test
    @DisplayName("幂等守卫并发：N 线程抢同一幂等键，恰 1 个领取成功（防并发重复结算）")
    void guardIsAtomicUnderConcurrency() throws Exception {
        final int n = 16;
        JobIdempotencyGuard g = new JobIdempotencyGuard();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return g.tryAcquire("settlement#2026-09-20");
            }));
        }
        start.countDown();
        int acquired = 0;
        for (java.util.concurrent.Future<Boolean> f : futures) {
            if (f.get(5, TimeUnit.SECONDS)) {
                acquired++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, acquired, "同一幂等键并发只能有一个执行者");
    }

    // ================================================================ start/stop 与端口工厂

    @Test
    @DisplayName("start 幂等注册节拍、参数校验；stop 取消节拍且不关闭外部线程池")
    void startStopLifecycle() {
        RecordingJobPorts ports = new RecordingJobPorts();
        JobScheduler s = scheduler(new MutableJobClock(DAY.atStartOfDay()).asJobClock(),
                ports, JobConfig.DEFAULTS, JobAlarm.NOOP);
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-test-pool");
            t.setDaemon(true);
            return t;
        });
        try {
            assertThrows(NullPointerException.class, () -> s.start(null, 60L));
            assertThrows(IllegalArgumentException.class, () -> s.start(pool, 0L));
            java.util.concurrent.ScheduledFuture<?> first = s.start(pool, 3600L);
            assertNotNull(first);
            assertSame(first, s.start(pool, 3600L), "start 幂等：重复调用返回既有句柄，不重复注册节拍");
            assertFalse(pool.isShutdown(), "start 不得关闭调用方持有的线程池");
            s.stop();
            s.stop(); // 幂等
            assertFalse(pool.isShutdown(), "stop 只取消节拍，不关闭外部线程池");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("JobPorts.of 参数非空校验；NOOP 7 端口可用")
    void jobPortsFactoryContracts() {
        assertThrows(NullPointerException.class,
                () -> JobPorts.of(null, (d, op) -> JobOutcome.Summary.empty(),
                        (d, op) -> JobOutcome.Summary.empty(), (d, op) -> JobOutcome.Summary.empty(),
                        (d, days, op) -> JobOutcome.Summary.empty(), (d, op) -> JobOutcome.Summary.empty(),
                        op -> JobOutcome.Summary.empty()));
        assertEquals(0, JobPorts.NOOP.runDailySettlement(DAY, SYS).affected());
        assertEquals(0, JobPorts.NOOP.inspectEventFlowWatermark(SYS).affected());
    }

    @Test
    @DisplayName("JobSchedules.create 自定义触发时点数量不符 ⇒ 拒绝")
    void createRequiresSevenTriggers() {
        assertThrows(IllegalArgumentException.class, () -> JobSchedules.create(
                JobTrigger.dailyAt(LocalTime.of(1, 0))));
        assertEquals(7, JobSchedules.defaults().length);
        assertEquals(7, JobSchedules.orderedTypes().length);
    }

    // 装配级证据（已配库 / 未配库两路）由 com.hedgeguard.web.JobAssemblyTest 覆盖：
    // Bootstrap.Assembly / assembleWithResources 为 web 包内可见，故该用例置于 web 包。

    // ================================================================ 工具

    private static JobOutcome.Result result(List<JobOutcome.Result> results, JobType type) {
        for (JobOutcome.Result r : results) {
            if (r.type() == type) {
                return r;
            }
        }
        throw new AssertionError("结果缺少任务：" + type);
    }
}
