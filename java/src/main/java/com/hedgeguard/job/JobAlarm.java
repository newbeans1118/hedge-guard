package com.hedgeguard.job;

/**
 * 调度失败告警端口（{@code 验收标准 4「任务失败可告警」}）。
 *
 * <p>泛化自既有的 {@link PostRiskReportAlarm}：增加 {@code jobId} 与 {@code message} 维度，
 * 便于运维定位「哪一类任务、哪一时间片」失败。实现须快速返回、不得抛出异常
 * （告警本身失败不得掩盖原失败，也不得中断其余任务）。
 *
 * <p>生产装配由运维告警 sink 承接；测试用记录式实现断言「失败必告警」。
 */
@FunctionalInterface
public interface JobAlarm {

    /** 空告警（仅用于无需告警的装配 / 单测）。 */
    JobAlarm NOOP = (jobId, message, cause) -> {
    };

    /**
     * 告警。
     *
     * @param jobId   任务 id（{@link JobType#id()}）
     * @param message 告警文案（须说明失败任务与时间片）
     * @param cause   触发告警的异常；非失败类告警（如水位滞后）可为 {@code null}
     */
    void alert(String jobId, String message, Throwable cause);
}
