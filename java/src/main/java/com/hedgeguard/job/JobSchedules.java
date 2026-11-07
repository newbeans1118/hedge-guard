package com.hedgeguard.job;

import java.time.LocalTime;

/**
 * 7 类定时任务的<b>触发时点清单</b>与默认装配（{@code ARCHITECTURE.md} §3.12 / §6.5 / §6.9）。
 *
 * <p>触发时刻按业务依赖排序：
 * <ol>
 *   <li>盘前 08:30 到期提醒、09:00 报告周期提醒（不依赖当日结算数据）；</li>
 *   <li>收盘后 15:30 日终结算 → 16:00 事后风控（§6.9「结算完成后」）
 *       → 16:30 报表生成（§6.5 ⑨）→ 17:00 对账；</li>
 *   <li>事件流水位巡检每 60 秒一次（高频，按时间片对齐节流，§6.8 / R-RISK-10）。</li>
 * </ol>
 *
 * <p>时刻为业务本地时间（{@code Asia/Shanghai}，与 {@code Clocks.ZONE} 一致）；
 * 这些是代码级默认常量，可由 {@link #create(JobPorts, JobTrigger...)} 覆盖以便部署调整，
 * <b>不新增配置键</b>（避免越界改动 {@code CONTRACTS.md} / {@code data-init.sql}）。
 */
public final class JobSchedules {

    private JobSchedules() {
    }

    /** 日终结算触发时刻（收盘后，CTP 日终数据到达窗口）。 */
    public static final LocalTime DAILY_SETTLEMENT_AT = LocalTime.of(15, 30);
    /** 事后风控触发时刻（结算完成后）。 */
    public static final LocalTime POST_RISK_REPORT_AT = LocalTime.of(16, 0);
    /** 报表生成触发时刻。 */
    public static final LocalTime REPORT_GENERATION_AT = LocalTime.of(16, 30);
    /** 对账触发时刻。 */
    public static final LocalTime RECONCILIATION_AT = LocalTime.of(17, 0);
    /** 到期提醒触发时刻（盘前）。 */
    public static final LocalTime PLAN_EXPIRY_REMINDER_AT = LocalTime.of(8, 30);
    /** 报告周期提醒触发时刻（盘前）。 */
    public static final LocalTime REPORT_PERIOD_REMINDER_AT = LocalTime.of(9, 0);
    /** 事件流水位巡检间隔（秒）。 */
    public static final long EVENT_FLOW_WATERMARK_INTERVAL_SECONDS = 60L;

    /**
     * 按代码级默认时点装配 7 类任务。
     *
     * @return 7 个任务（顺序即依赖顺序）
     */
    public static ScheduledJob[] defaults() {
        return create(
                JobTrigger.dailyAt(PLAN_EXPIRY_REMINDER_AT),
                JobTrigger.dailyAt(REPORT_PERIOD_REMINDER_AT),
                JobTrigger.dailyAt(DAILY_SETTLEMENT_AT),
                JobTrigger.dailyAt(POST_RISK_REPORT_AT),
                JobTrigger.dailyAt(REPORT_GENERATION_AT),
                JobTrigger.dailyAt(RECONCILIATION_AT),
                JobTrigger.every(java.time.Duration.ofSeconds(EVENT_FLOW_WATERMARK_INTERVAL_SECONDS)));
    }

    /**
     * 用自定义触发时点装配 7 类任务（部署可调整时刻；任务种类仍固定 7 类）。
     *
     * @param triggers 依次对应 {@link #orderedTypes()} 的 7 个触发器
     * @return 7 个任务
     */
    public static ScheduledJob[] create(JobTrigger... triggers) {
        JobType[] types = orderedTypes();
        if (triggers == null || triggers.length != types.length) {
            throw new IllegalArgumentException("触发时点数量须为 " + types.length + "：收到 "
                    + (triggers == null ? "null" : triggers.length));
        }
        ScheduledJob[] jobs = new ScheduledJob[types.length];
        for (int i = 0; i < types.length; i++) {
            jobs[i] = of(types[i], triggers[i]);
        }
        return jobs;
    }

    /** 依赖顺序下的 7 类任务类型。 */
    public static JobType[] orderedTypes() {
        return new JobType[]{
                JobType.PLAN_EXPIRY_REMINDER,
                JobType.REPORT_PERIOD_REMINDER,
                JobType.DAILY_SETTLEMENT,
                JobType.POST_RISK_REPORT,
                JobType.REPORT_GENERATION,
                JobType.RECONCILIATION,
                JobType.EVENT_FLOW_WATERMARK
        };
    }

    private static ScheduledJob of(JobType type, JobTrigger trigger) {
        switch (type) {
            case PLAN_EXPIRY_REMINDER:
                return new PlanExpiryReminderJob(trigger);
            case REPORT_PERIOD_REMINDER:
                return new ReportPeriodReminderJob(trigger);
            case DAILY_SETTLEMENT:
                return new DailySettlementJob(trigger);
            case POST_RISK_REPORT:
                return new PostRiskReportScheduledJob(trigger);
            case REPORT_GENERATION:
                return new ReportGenerationJob(trigger);
            case RECONCILIATION:
                return new ReconciliationJob(trigger);
            case EVENT_FLOW_WATERMARK:
                return new EventFlowWatermarkJob(trigger);
            default:
                throw new IllegalArgumentException("未登记的任务类型：" + type);
        }
    }
}
