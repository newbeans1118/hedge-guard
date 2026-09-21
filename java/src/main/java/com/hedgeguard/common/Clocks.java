package com.hedgeguard.common;

/**
 * 时钟提供者（可注入，{@code NFR-TEST-4}）。
 *
 * <p>纪律：业务代码<b>禁止</b>直接调用 {@code System.currentTimeMillis()} / {@code new Date()} /
 * {@code LocalDateTime.now()}——一律经本类取时钟，以便测试注入固定时钟
 * （依据 {@code ARCHITECTURE.md} §8.4 与 {@code DATA_MODEL.md} §0.2「时间戳由应用层写入」）。
 *
 * <p>时区语义固定为 {@code Asia/Shanghai}（{@code ARCHITECTURE.md} §4.1.1「时间」约定）。
 */
public final class Clocks {

    /** 系统默认时区：全系统时间语义基准（{@code ARCHITECTURE.md} §4.1.1）。 */
    public static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Shanghai");

    private static volatile java.time.Clock current = java.time.Clock.system(ZONE);

    private Clocks() {
    }

    /** 取当前时钟。 */
    public static java.time.Clock clock() {
        return current;
    }

    /**
     * 替换时钟（仅测试与启动装配使用）。
     *
     * @param clock 新时钟，不得为 {@code null}
     */
    public static void set(java.time.Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock 不得为 null");
        }
        current = clock;
    }

    /** 恢复为系统时钟。 */
    public static void reset() {
        current = java.time.Clock.system(ZONE);
    }

    /** 当前本地时间（应用层落库用的 {@code TIMESTAMP}）。 */
    public static java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now(current);
    }

    /** 当前业务日期（应用层落库用的 {@code DATE}）。 */
    public static java.time.LocalDate today() {
        return java.time.LocalDate.now(current);
    }

    /** 当前毫秒时间戳（协议/外部回报用）。 */
    public static long epochMilli() {
        return current.millis();
    }
}
