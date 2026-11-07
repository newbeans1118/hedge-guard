package com.hedgeguard.job;

/**
 * 任务 6／7：<b>报告周期提醒</b>（{@code ARCHITECTURE.md} §11.3 / §6.14 / {@code Q-14}）。
 *
 * <p>按 {@code t_report_period_config} 六类报告（日结算 / 日风险 / 月核对 / 季报 / 年报 /
 * 重大事项）的 {@code period_unit / period_value / remind_advance_days / remind_role_codes}
 * 计算截止日并提醒签署角色；{@code isEnabled='Y'} 才参与。
 *
 * <p>业务动作经 {@link JobPorts#remindReportPeriod} 委托 L3 服务；调度层不做周期计算，
 * 只负责盘前到点触发与可靠性。
 */
public final class ReportPeriodReminderJob extends AbstractScheduledJob {

    /**
     * 构造。
     *
     * @param trigger 触发时点（默认 {@link JobSchedules#REPORT_PERIOD_REMINDER_AT}）
     */
    public ReportPeriodReminderJob(JobTrigger trigger) {
        super(JobType.REPORT_PERIOD_REMINDER, trigger);
    }

    @Override
    public JobOutcome.Summary execute(JobContext ctx, JobPorts ports) {
        return ports.remindReportPeriod(ctx.businessDate(), ctx.operatorId());
    }
}
