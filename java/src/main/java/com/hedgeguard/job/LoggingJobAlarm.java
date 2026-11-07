package com.hedgeguard.job;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 生产用运维告警 sink（{@link JobAlarm} 的日志实现）。
 *
 * <p>失败类告警按 {@code SEVERE} 落运维日志（含异常栈），非失败类告警（如事件流水位滞后）
 * 按 {@code WARNING}；实现只记日志、绝不抛异常，避免掩盖任务原失败或中断其余任务。
 *
 * <p>不引入任何第三方依赖，也不写文件/网络，符合「最小依赖」与「敏感字段零落盘」纪律。
 */
public final class LoggingJobAlarm implements JobAlarm {

    private static final Logger LOG = Logger.getLogger(LoggingJobAlarm.class.getName());

    @Override
    public void alert(String jobId, String message, Throwable cause) {
        String line = "[hg-job] job=" + jobId + " " + message;
        try {
            if (cause == null) {
                LOG.warning(line);
            } else {
                LOG.log(Level.SEVERE, line, cause);
            }
        } catch (RuntimeException ignored) {
            // 告警 sink 自身异常不得影响调度主流程（JobAlarm 契约）
        }
    }
}
