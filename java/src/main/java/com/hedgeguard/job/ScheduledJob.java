package com.hedgeguard.job;

/**
 * 一个定时任务（{@code ARCHITECTURE.md} §3.12 J6）。
 *
 * <p><b>分层纪律</b>：实现只可经 {@link JobPorts}（L3 服务端口）完成业务动作，
 * 不得持有或调用任何 {@code com.hedgeguard.dao.*} 类型（验收标准 2）。
 *
 * <p><b>可注入时钟</b>：业务时间只取自 {@link JobContext#now()} / {@link JobContext#businessDate()}，
 * 由 {@link JobScheduler} 从注入的 {@link JobClock} 构造；实现内禁止读取系统时间
 * （{@code NFR-TEST-4}）。
 */
public interface ScheduledJob {

    /** 任务类型（7 类之一）。 */
    JobType type();

    /** 触发时点。 */
    JobTrigger trigger();

    /**
     * 执行一次业务动作。
     *
     * @param ctx   执行上下文（业务时间来自注入时钟）
     * @param ports L3 服务端口
     * @return 业务结果摘要
     */
    JobOutcome.Summary execute(JobContext ctx, JobPorts ports);
}
