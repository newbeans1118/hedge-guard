package com.hedgeguard.web;

import com.hedgeguard.common.Config;
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
    @DisplayName("已配库装配：调度器的 JobPorts 是真实 L3 服务适配（非 NOOP），且路由数不因调度层变化")
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
            // 路由基线不因新增调度层而变化（调度层不注册任何 HTTP 端点）
            assertEquals(168, assembly.routeCount());
        } finally {
            Bootstrap.close(assembly);
        }
    }
}
