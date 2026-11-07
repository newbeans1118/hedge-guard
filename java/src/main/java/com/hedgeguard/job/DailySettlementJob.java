package com.hedgeguard.job;

/**
 * 任务 1／7：<b>日终结算</b>（{@code ARCHITECTURE.md} §6.5）。
 *
 * <p>交易日收盘 → CTP 日终结算数据到达 → 经 {@link JobPorts#runDailySettlement} 完成
 * 入库（批次 + 三类明细）/ 子账户拆分 / 独立结算 / 三类核对 / 加总对账 / 关账门禁。
 *
 * <p>本类<b>不做</b>任何数据访问，也不自行判定「数据是否到达」——由 L3 服务层
 * （{@code SettleService}）按 §6.5「否 → 待重试，不得用推算数据」处理；
 * 调度层只负责到点触发 + 幂等守卫 + 失败告警与重入。
 */
public final class DailySettlementJob extends AbstractScheduledJob {

    /**
     * 构造。
     *
     * @param trigger 触发时点（默认 {@link JobSchedules#DAILY_SETTLEMENT_AT}）
     */
    public DailySettlementJob(JobTrigger trigger) {
        super(JobType.DAILY_SETTLEMENT, trigger);
    }

    @Override
    public JobOutcome.Summary execute(JobContext ctx, JobPorts ports) {
        return ports.runDailySettlement(ctx.businessDate(), ctx.operatorId());
    }
}
