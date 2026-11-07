package com.hedgeguard.job;

/**
 * 任务 2／7：<b>事后风控</b>（{@code ARCHITECTURE.md} §6.9 / {@code FR-RISK-17}）。
 *
 * <p>日终结算完成后，取当日全量：成交 / 持仓 / 结算 / 额度使用 / 告警 / 违规事项，
 * 计算并落库风险指标统计 + 违规事项汇总 + 限额使用情况，与 SETTLE 结算数据勾稽一致。
 *
 * <p>复用既有的 {@link PostRiskReportJob}（{@code T-180}）作为 L3 服务接缝：
 * 生产装配把 {@code postRiskReportJob::run} 适配为本任务的端口，
 * 本类只负责到点触发与调度语义，不重复实现报表生成逻辑。
 *
 * <p><b>命名说明</b>：{@code PostRiskReportJob} 是既有 T-180 任务类（构造签名为
 * {@code (service, metricsSource, alarm)}，走自己的 {@code run(LocalDate, long)}）；
 * 本类是 §3.12 J6 的「可调度」封装，故加 {@code Scheduled} 后缀区分，避免改坏既有类与其单测。
 */
public final class PostRiskReportScheduledJob extends AbstractScheduledJob {

    /**
     * 构造。
     *
     * @param trigger 触发时点（默认 {@link JobSchedules#POST_RISK_REPORT_AT}）
     */
    public PostRiskReportScheduledJob(JobTrigger trigger) {
        super(JobType.POST_RISK_REPORT, trigger);
    }

    @Override
    public JobOutcome.Summary execute(JobContext ctx, JobPorts ports) {
        return ports.runPostRiskReport(ctx.businessDate(), ctx.operatorId());
    }
}
