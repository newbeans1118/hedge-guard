package com.hedgeguard.job;

/**
 * 任务 4／7：<b>对账</b>（{@code ARCHITECTURE.md} §6.5 ⑥ / §6.7 / §6.14）。
 *
 * <p>加总对账（Σ 子账户持仓 / 资金 / 盈亏 = 真实账户）、资金日终对账、
 * 核对任务逾期巡检。不一致时由 L3 服务按 {@code Q-11} 告警与差异所属子账户阻断；
 * 调度层不参与判定，只保证到点触发、失败告警、可重入且不重复执行。
 */
public final class ReconciliationJob extends AbstractScheduledJob {

    /**
     * 构造。
     *
     * @param trigger 触发时点（默认 {@link JobSchedules#RECONCILIATION_AT}）
     */
    public ReconciliationJob(JobTrigger trigger) {
        super(JobType.RECONCILIATION, trigger);
    }

    @Override
    public JobOutcome.Summary execute(JobContext ctx, JobPorts ports) {
        return ports.reconcile(ctx.businessDate(), ctx.operatorId());
    }
}
