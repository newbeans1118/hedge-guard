package com.hedgeguard.web;

import com.hedgeguard.common.Config;
import com.hedgeguard.job.JobPorts;
import com.hedgeguard.job.JobScheduler;
import com.hedgeguard.job.JobType;
import com.hedgeguard.job.ScheduledJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-051} 装配级证据：生产 {@link Bootstrap#assembleWithResources(Config)} 在「已配库」分支
 * 确实装配出 7 类任务齐全的 {@link JobScheduler}，且其端口接到真实 L3 服务（而非 NOOP）。
 *
 * <p>沿用仓内 {@link FakeJdbcDriver} 的离线装配手法（与 {@code BootstrapAssemblyTest} 同姿态），
 * 用后清理 System 属性，避免污染其他用例。
 */
class JobAssemblyTest {

    private static final List<String> DB_PROPS = Arrays.asList(
            Config.KEY_DB_URL, Config.KEY_DB_DRIVER, Config.KEY_DB_USER, Config.KEY_DB_POOL_INITIAL);

    @BeforeEach
    @AfterEach
    void clearDbProps() {
        for (String k : DB_PROPS) {
            System.clearProperty(k);
        }
    }

    private static void configureFakeDb() {
        System.setProperty(Config.KEY_DB_URL, "jdbc:dm://127.0.0.1:5236/HG");
        System.setProperty(Config.KEY_DB_DRIVER, FakeJdbcDriver.class.getName());
        System.setProperty(Config.KEY_DB_USER, "hg_app");
        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");
    }

    @Test
    @DisplayName("已配库装配：Assembly.jobScheduler() 非空，7 类任务全覆盖，触发时点齐全")
    void productionAssemblyWiresSevenJobs() {
        configureFakeDb();
        Bootstrap.Assembly assembly = assertDoesNotThrow(
                () -> Bootstrap.assembleWithResources(Config.empty()));
        try {
            assertTrue(assembly.databaseAssembled());
            JobScheduler scheduler = assembly.jobScheduler();
            assertNotNull(scheduler, "已配库时必须装配 J6 调度器");
            assertEquals(JobType.count(), scheduler.jobs().size());
            Set<JobType> types = new HashSet<>();
            for (ScheduledJob j : scheduler.jobs()) {
                types.add(j.type());
                assertNotNull(j.trigger().describe());
            }
            assertEquals(JobType.count(), types.size());
            assertEquals(0L, scheduler.operatorId(), "调度任务以系统账号执行（审计归属）");
            assertNotNull(scheduler.clock());
            assertNotNull(scheduler.guard());
        } finally {
            Bootstrap.close(assembly);
        }
    }

    @Test
    @DisplayName("已配库装配：调度器的 7 个 JobPorts 逐一接到真实 L3 服务适配（非 NOOP），且路由数不因调度层变化")
    void wiredPortsAreRealServices() {
        configureFakeDb();
        Bootstrap.Assembly assembly = assertDoesNotThrow(
                () -> Bootstrap.assembleWithResources(Config.empty()));
        try {
            JobScheduler scheduler = assembly.jobScheduler();
            assertNotNull(scheduler);
            // 装配登记了 7 类且触发时点各不相同（盘前 / 收盘后 / 高频巡检）
            Set<String> triggers = new HashSet<>();
            for (ScheduledJob j : scheduler.jobs()) {
                triggers.add(j.trigger().describe());
            }
            assertTrue(triggers.size() >= 2, "应同时存在日更与高频两类触发时点");

            // ★端口真实性（D-2 补正）：把生产 jobPorts 换成 JobPorts.NOOP 时，以下断言必红
            JobPorts ports = scheduler.ports();
            assertNotNull(ports, "调度器必须暴露已装配的 JobPorts 供装配级核验");
            assertNotSame(JobPorts.NOOP, ports,
                    "生产装配不得把 JobPorts 退化为 NOOP（7 类任务会静默空转）");
            assertTrue(ports instanceof JobPorts.Composite,
                    "生产端口须由 JobPorts.of 以 7 个真实动作装配，实际类型=" + ports.getClass());
            JobPorts.Composite composite = (JobPorts.Composite) ports;
            // 7 个端口逐一断言：非空、且不是 NoOps 的空动作（即确实接到 L3 服务方法引用）
            assertWiredPort("1/7 日终结算", JobPorts.NoOps.SETTLEMENT, composite.settlement());
            assertWiredPort("2/7 事后风控", JobPorts.NoOps.POST_RISK, composite.postRisk());
            assertWiredPort("3/7 报表生成", JobPorts.NoOps.REPORTS, composite.reports());
            assertWiredPort("4/7 对账", JobPorts.NoOps.RECONCILIATION, composite.reconciliation());
            assertWiredPort("5/7 到期提醒", JobPorts.NoOps.EXPIRY_REMINDER, composite.expiryReminder());
            assertWiredPort("6/7 报告周期提醒", JobPorts.NoOps.PERIOD_REMINDER, composite.periodReminder());
            assertWiredPort("7/7 事件流水位巡检", JobPorts.NoOps.WATERMARK, composite.watermark());

            // 路由基线不因新增调度层而变化（调度层不注册任何 HTTP 端点）
            // 【T-131 增注】基线由 168 订正为 173：PRODUCT 写面 +5 对（见 BootstrapAssemblyTest 同轮注释）。
            assertEquals(173, assembly.routeCount());
        } finally {
            Bootstrap.close(assembly);
        }
    }

    /** 单端口真实性判据：已装配（非空）且未退化为空动作（{@link JobPorts.NoOps} 常量）。 */
    private static void assertWiredPort(String label, Object noOpAction, Object wiredAction) {
        assertNotNull(wiredAction, label + " 端口未装配（null）");
        assertNotSame(noOpAction, wiredAction,
                label + " 端口退化为空实现（NOOP）——该类任务将静默空转");
    }
}
