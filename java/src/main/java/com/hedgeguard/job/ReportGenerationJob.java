package com.hedgeguard.job;

/**
 * 任务 3／7：<b>报表生成</b>（{@code ARCHITECTURE.md} §6.5 ⑨ / §6.11）。
 *
 * <p>关账后生成每日结算报告 + 风控日报（不一致时须显式标注「不一致／已阻断」），
 * 供 AUDIT 模块导出；全链路追溯端点显式标记。业务动作经
 * {@link JobPorts#generateDailyReports} 委托 L3 服务。
 */
public final class ReportGenerationJob extends AbstractScheduledJob {

    /**
     * 构造。
     *
     * @param trigger 触发时点（默认 {@link JobSchedules#REPORT_GENERATION_AT}）
     */
    public ReportGenerationJob(JobTrigger trigger) {
        super(JobType.REPORT_GENERATION, trigger);
    }

    @Override
    public JobOutcome.Summary execute(JobContext ctx, JobPorts ports) {
        return ports.generateDailyReports(ctx.businessDate(), ctx.operatorId());
    }
}
