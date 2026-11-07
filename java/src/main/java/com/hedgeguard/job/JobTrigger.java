package com.hedgeguard.job;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * 触发时点描述 + 幂等时间片计算（{@code ARCHITECTURE.md} §3.12 J6）。
 *
 * <p>每个触发器只回答两件事：
 * <ol>
 *   <li>{@link #dueBucket(LocalDateTime)}：当前时刻是否到点？到点则返回<b>所属时间片</b>
 *       （每日任务返回交易日 {@code 2026-09-20}；周期巡检返回对齐片
 *       {@code 2026-09-20#935}）；未到点返回 {@code null}。该时间片与 jobId 拼成幂等键，
 *       是「不产生重复结算」的第一道守卫。</li>
 *   <li>{@link #describe()}：人类可读触发时点，供运维与回报清单使用。</li>
 * </ol>
 *
 * <p><b>节流纪律</b>：高频任务（事件流水位巡检）用 {@link #every(Duration)} 按时间片对齐；
 * 同一片内反复 {@code tick()} 由幂等守卫挡下，不重复执行全量巡检
 * （{@code §5.3}「高频刷新必须节流 / 差量更新、动画由状态变化驱动」的服务端对应形态）。
 * 时间片只由<b>注入的</b> {@link JobClock} 给出的 {@code now} 推导，全程不读墙钟。
 */
public final class JobTrigger {

    /** 触发类型。 */
    public enum Kind {
        /** 每日固定时刻（盘前 / 交易日收盘后）。 */
        DAILY_AT,
        /** 按固定间隔对齐的周期巡检。 */
        INTERVAL
    }

    private final Kind kind;
    private final LocalTime at;
    private final long intervalSeconds;

    private JobTrigger(Kind kind, LocalTime at, long intervalSeconds) {
        this.kind = kind;
        this.at = at;
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * 每日固定时刻触发（当日该时刻到达后即到点）。
     *
     * @param at 触发时刻（业务本地时间）
     * @return 触发器
     */
    public static JobTrigger dailyAt(LocalTime at) {
        return new JobTrigger(Kind.DAILY_AT, Objects.requireNonNull(at, "at"), 0L);
    }

    /**
     * 按固定间隔对齐的周期触发（用于事件流水位巡检等高频任务）。
     *
     * @param interval 间隔，须为正且为整秒
     * @return 触发器
     */
    public static JobTrigger every(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        long seconds = interval.getSeconds();
        if (seconds <= 0L || interval.toMillis() % 1000L != 0L) {
            throw new IllegalArgumentException("巡检间隔须为正的整秒：收到 " + interval);
        }
        return new JobTrigger(Kind.INTERVAL, null, seconds);
    }

    /** 触发类型。 */
    public Kind kind() {
        return kind;
    }

    /**
     * 计算当前时刻所属的时间片；未到触发时点返回 {@code null}。
     *
     * @param now 业务当前时刻（来自注入的 {@link JobClock}）
     * @return 时间片字符串（同一片内恒等），或 {@code null} 表示未到点
     */
    public String dueBucket(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        if (kind == Kind.DAILY_AT) {
            return now.toLocalTime().isBefore(at) ? null : now.toLocalDate().toString();
        }
        long slot = now.toLocalTime().toSecondOfDay() / intervalSeconds;
        return now.toLocalDate() + "#" + slot;
    }

    /**
     * 显式指定业务日期的时间片（供 {@code runNow(jobId, businessDate)} 重入调用使用）。
     *
     * @param businessDate 业务日期
     * @return 时间片字符串
     */
    public String businessDateBucket(LocalDate businessDate) {
        Objects.requireNonNull(businessDate, "businessDate");
        if (kind == Kind.INTERVAL) {
            // 周期任务按「当日第 0 片」显式重入，保证同一业务日期内多次重入命中同一幂等键。
            return businessDate + "#0";
        }
        return businessDate.toString();
    }

    /** 每日触发时刻（周期触发返回 {@code null}）。 */
    public LocalTime at() {
        return at;
    }

    /** 人类可读的触发时点描述。 */
    public String describe() {
        if (kind == Kind.DAILY_AT) {
            return "每日 " + at + "（交易日）";
        }
        return "每 " + intervalSeconds + " 秒（按时间片对齐）";
    }
}
