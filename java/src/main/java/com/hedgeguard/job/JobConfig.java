package com.hedgeguard.job;

/**
 * 调度任务读取 L3 配置项的接缝（{@code CFG-24} 等；惰性读取，不缓存）。
 *
 * <p><b>分层纪律</b>：调度任务<b>不得</b>绕过 service 层直接调 DAO（§3.12）。故本接口由生产装配
 * 用 {@code ConfigService.getConfig(key)} 适配，本包内不出现任何 {@code com.hedgeguard.dao} 引用；
 * 测试用内存实现注入，可控制配置值。
 *
 * <p><b>惰性</b>：每次任务执行时读取，配置变更（双人复核后）下一次触发即生效，无需重启。
 */
@FunctionalInterface
public interface JobConfig {

    /**
     * 读取整数配置项。
     *
     * @param key          配置键，如 {@code plan.expiry_remind_days}
     * @param defaultValue 键缺失 / 值非法时的回退缺省（不得因单项配置缺失而使调度任务整体失败）
     * @return 配置值
     */
    int intValue(String key, int defaultValue);

    /** 全部取缺省值的实现（仅用于无 DB 的轻量装配 / 单测）。 */
    JobConfig DEFAULTS = (key, defaultValue) -> defaultValue;
}
