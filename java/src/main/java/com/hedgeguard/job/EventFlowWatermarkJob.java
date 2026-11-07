package com.hedgeguard.job;

/**
 * 任务 7／7：<b>事件流水位巡检</b>（{@code ARCHITECTURE.md} §6.8 / §4.5 / {@code R-RISK-10}）。
 *
 * <p>检查镜像状态事件序号水位：若「事件序号落后超阈值」或水位回退，须告警且不得静默。
 * 这是 7 类中唯一的<b>高频</b>任务（默认每 60 秒），故：
 * <ul>
 *   <li>触发器用 {@link JobTrigger#every} 按时间片对齐，同一片内多次 {@code tick()}
 *       由 {@link JobIdempotencyGuard} 短路，<b>不做全量重复巡检</b>（节流 / 差量语义，§5.3）；</li>
 *   <li>告警由「水位状态变化」驱动，非无条件每帧重放。</li>
 * </ul>
 *
 * <p>水位真值由 L3 服务端口 {@link JobPorts#inspectEventFlowWatermark} 提供（生产经
 * {@code TradingBridge.mirror() / mirrorState().watermarkEventSeq()} 与
 * {@code t_mirror_watermark} 落库水位比对）；本类不读 DB、不查 C++。
 */
public final class EventFlowWatermarkJob extends AbstractScheduledJob {

    /**
     * 构造。
     *
     * @param trigger 触发时点（默认 {@link JobTrigger#every} 60 秒）
     */
    public EventFlowWatermarkJob(JobTrigger trigger) {
        super(JobType.EVENT_FLOW_WATERMARK, trigger);
    }

    @Override
    public JobOutcome.Summary execute(JobContext ctx, JobPorts ports) {
        JobOutcome.Summary summary = ports.inspectEventFlowWatermark(ctx.operatorId());
        if (summary != null && summary.affected() > 0) {
            // 状态变化驱动：仅当检测到滞后 / 回退（affected>0）才告警，非无条件重放
            ctx.warn(type().id(), "事件流水位巡检发现滞后／回退：" + summary.detail());
        }
        return summary == null ? JobOutcome.Summary.empty() : summary;
    }
}
