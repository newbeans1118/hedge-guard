package com.hedgeguard.job;

/**
 * 任务 5／7：<b>到期提醒</b>（{@code ARCHITECTURE.md} §11.2 {@code CFG-24}）。
 *
 * <p>盘前扫描 {@code advanceDays} 天内到期的套保计划并提醒。提前期取自配置项
 * {@code plan.expiry_remind_days}（{@code CFG-24}，缺省 30、范围 1–180，
 * 见 {@code docs/DEPLOY/schema/data-init.sql} 种子），经 {@link JobConfig} 端口
 * <b>惰性读取</b>——配置双人复核变更后下一次触发即生效，无需重启；
 * 读取失败 / 越界一律回退缺省，不使整个调度任务失败。
 *
 * <p><b>不硬编码阈值</b>（{@code R-RISK-14}）：键名、缺省、上下界均为具名常量；
 * 不新增配置键，避免越界改动 {@code CONTRACTS.md} / 配置种子。
 */
public final class PlanExpiryReminderJob extends AbstractScheduledJob {

    /**
     * 构造。
     *
     * @param trigger 触发时点（默认 {@link JobSchedules#PLAN_EXPIRY_REMINDER_AT}）
     */
    public PlanExpiryReminderJob(JobTrigger trigger) {
        super(JobType.PLAN_EXPIRY_REMINDER, trigger);
    }

    @Override
    public JobOutcome.Summary execute(JobContext ctx, JobPorts ports) {
        int days = ctx.configInt(JobPorts.CFG_PLAN_EXPIRY_REMIND_DAYS,
                JobPorts.DEFAULT_PLAN_EXPIRY_REMIND_DAYS);
        if (days < JobPorts.MIN_PLAN_EXPIRY_REMIND_DAYS
                || days > JobPorts.MAX_PLAN_EXPIRY_REMIND_DAYS) {
            days = JobPorts.DEFAULT_PLAN_EXPIRY_REMIND_DAYS;
        }
        return ports.remindPlanExpiry(ctx.businessDate(), days, ctx.operatorId());
    }
}
