package com.hedgeguard.job;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 单次任务执行的上下文（不可变）。
 *
 * <p>任务<b>不得</b>从此处或任何其它途径取得 DAO / 连接；业务动作一律经 {@link JobPorts} 的
 * service 层端口完成（{@code §3.12} 分层纪律）。业务时间只来自注入的 {@link JobClock}，
 * 由 {@link JobScheduler} 在 {@code tick()} 时构造，任务内不读墙钟。
 */
public final class JobContext {

    private final LocalDateTime now;
    private final LocalDate businessDate;
    private final long operatorId;
    private final JobConfig config;
    private final JobAlarm alarm;

    /**
     * 构造。
     *
     * @param now          业务当前时刻（来自注入时钟）
     * @param businessDate 业务日期
     * @param operatorId   系统操作人 id
     * @param config       L3 配置读取端口
     * @param alarm        告警端口
     */
    public JobContext(LocalDateTime now, LocalDate businessDate, long operatorId,
                      JobConfig config, JobAlarm alarm) {
        this.now = Objects.requireNonNull(now, "now");
        this.businessDate = Objects.requireNonNull(businessDate, "businessDate");
        this.operatorId = operatorId;
        this.config = Objects.requireNonNull(config, "config");
        this.alarm = Objects.requireNonNull(alarm, "alarm");
    }

    /** 业务当前时刻。 */
    public LocalDateTime now() {
        return now;
    }

    /** 业务日期（幂等时间片的日期部分）。 */
    public LocalDate businessDate() {
        return businessDate;
    }

    /** 系统操作人 id。 */
    public long operatorId() {
        return operatorId;
    }

    /** L3 配置读取端口。 */
    public JobConfig config() {
        return config;
    }

    /** 读取整数配置（缺失 / 非法回退缺省，不使调度任务整体失败）。 */
    public int configInt(String key, int defaultValue) {
        return config.intValue(key, defaultValue);
    }

    /**
     * 发出非致命告警（如事件流水位滞后）；不抛异常，不改变任务成功/失败判定。
     *
     * @param jobId   任务 id
     * @param message 告警文案
     */
    public void warn(String jobId, String message) {
        alarm.alert(jobId, message, null);
    }
}
