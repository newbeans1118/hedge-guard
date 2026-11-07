package com.hedgeguard.job;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 记录式 {@link JobPorts} 测试桩（{@code T-051}）。
 *
 * <p>逐个登记 7 个端口的调用次数 / 入参，并可让指定任务抛异常以验证「失败告警 + 可重入」。
 * 刻意<b>不</b>依赖任何 DAO / 数据库：调度层单测只验证编排语义（触发 / 幂等 / 告警 / 重入），
 * 业务正确性由各 L3 服务自身的单测保证。
 */
final class RecordingJobPorts implements JobPorts {

    final Map<JobType, Integer> calls = new EnumMap<>(JobType.class);
    final Set<JobType> failing = EnumSet.noneOf(JobType.class);
    int lastAdvanceDays = -1;
    LocalDate lastRemindAsOf;
    LocalDate lastSettlementDate;

    RecordingJobPorts() {
        for (JobType t : JobType.values()) {
            calls.put(t, 0);
        }
    }

    int calls(JobType t) {
        return calls.get(t);
    }

    private void hit(JobType t) {
        calls.put(t, calls.get(t) + 1);
        if (failing.contains(t)) {
            throw new IllegalStateException("注入失败：" + t.id());
        }
    }

    @Override
    public JobOutcome.Summary runDailySettlement(LocalDate tradeDate, long operatorId) {
        hit(JobType.DAILY_SETTLEMENT);
        lastSettlementDate = tradeDate;
        return new JobOutcome.Summary(1, "日终结算完成 " + tradeDate);
    }

    @Override
    public JobOutcome.Summary runPostRiskReport(LocalDate tradeDate, long operatorId) {
        hit(JobType.POST_RISK_REPORT);
        return new JobOutcome.Summary(3, "事后风险报表三部分");
    }

    @Override
    public JobOutcome.Summary generateDailyReports(LocalDate tradeDate, long operatorId) {
        hit(JobType.REPORT_GENERATION);
        return new JobOutcome.Summary(2, "每日结算报告 + 风控日报");
    }

    @Override
    public JobOutcome.Summary reconcile(LocalDate businessDate, long operatorId) {
        hit(JobType.RECONCILIATION);
        return new JobOutcome.Summary(1, "对账完成");
    }

    @Override
    public JobOutcome.Summary remindPlanExpiry(LocalDate asOf, int advanceDays, long operatorId) {
        hit(JobType.PLAN_EXPIRY_REMINDER);
        lastAdvanceDays = advanceDays;
        lastRemindAsOf = asOf;
        return new JobOutcome.Summary(1, "到期提醒");
    }

    @Override
    public JobOutcome.Summary remindReportPeriod(LocalDate asOf, long operatorId) {
        hit(JobType.REPORT_PERIOD_REMINDER);
        return new JobOutcome.Summary(1, "报告周期提醒");
    }

    @Override
    public JobOutcome.Summary inspectEventFlowWatermark(long operatorId) {
        hit(JobType.EVENT_FLOW_WATERMARK);
        // affected>0 ⇒ 触发任务侧的「状态变化驱动」告警（非每帧无条件重放）
        return new JobOutcome.Summary(2, "镜像事件序号落后 2 帧");
    }
}
