package com.hedgeguard.job;

import java.util.Objects;

/**
 * 定时任务公共骨架：固化 {@code JobType} 与 {@code JobTrigger}，
 * 子类只实现「调用哪一个 L3 服务端口」。
 *
 * <p>刻意让每个子类只依赖 {@link JobPorts} —— 本包无任何 {@code com.hedgeguard.dao} 引用，
 * 从类型层面兑现 §3.12 的「调度任务不得绕过 service 层直接调 DAO」。
 */
public abstract class AbstractScheduledJob implements ScheduledJob {

    private final JobType type;
    private final JobTrigger trigger;

    /**
     * 构造。
     *
     * @param type    任务类型
     * @param trigger 触发时点
     */
    protected AbstractScheduledJob(JobType type, JobTrigger trigger) {
        this.type = Objects.requireNonNull(type, "type");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
    }

    @Override
    public final JobType type() {
        return type;
    }

    @Override
    public final JobTrigger trigger() {
        return trigger;
    }

    /** 任务 id（幂等键前缀）。 */
    public final String id() {
        return type.id();
    }
}
