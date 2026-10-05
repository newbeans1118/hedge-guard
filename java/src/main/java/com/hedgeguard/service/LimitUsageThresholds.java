package com.hedgeguard.service;

import com.hedgeguard.common.Config;

import java.math.BigDecimal;

/**
 * 限额使用率提示/警告阈值（{@code FR-RISK-18} 验收 3；{@code CFG-21b}）。
 *
 * <p>键 {@code risk.limit_usage_hint}（缺省 {@code 0.80}）/ {@code risk.limit_usage_warn}
 * （缺省 {@code 0.95}）逐字取自 {@code ARCHITECTURE.md} §11.1 {@code CFG-21b}，并与
 * {@code data-init.sql} 播种值一致；<b>不得硬编码</b>（{@code R-RISK-14}）。
 */
public final class LimitUsageThresholds {

    /** 提示线配置键（{@code CFG-21b}）。 */
    public static final String KEY_HINT = "risk.limit_usage_hint";
    /** 警告线配置键（{@code CFG-21b}）。 */
    public static final String KEY_WARN = "risk.limit_usage_warn";
    /** 缺省提示线。 */
    public static final BigDecimal DEFAULT_HINT = new BigDecimal("0.80");
    /** 缺省警告线。 */
    public static final BigDecimal DEFAULT_WARN = new BigDecimal("0.95");

    /** 提示线（{@code >=} 该值标 {@code HINT}）。 */
    public final BigDecimal hint;
    /** 警告线（{@code >=} 该值标 {@code WARNING}）。 */
    public final BigDecimal warn;

    /**
     * 构造。
     *
     * @param hint 提示线
     * @param warn 警告线
     */
    public LimitUsageThresholds(BigDecimal hint, BigDecimal warn) {
        this.hint = hint;
        this.warn = warn;
    }

    /** 全部取缺省值。 */
    public static LimitUsageThresholds defaults() {
        return new LimitUsageThresholds(DEFAULT_HINT, DEFAULT_WARN);
    }

    /**
     * 从配置读取（{@code R-RISK-14}；缺项取缺省）。
     *
     * @param config 配置（可空）
     * @return 阈值
     */
    public static LimitUsageThresholds from(Config config) {
        if (config == null) {
            return defaults();
        }
        return new LimitUsageThresholds(
                decimal(config, KEY_HINT, DEFAULT_HINT),
                decimal(config, KEY_WARN, DEFAULT_WARN));
    }

    /**
     * 判定提示级别（{@code FR-RISK-18} 验收 3）。
     *
     * @param ratio 使用率（可空按 {@code NORMAL}）
     * @return {@code NORMAL} / {@code HINT} / {@code WARNING} / {@code EXCEEDED}
     */
    public String level(BigDecimal ratio) {
        if (ratio == null) {
            return PostRiskReportService.HINT_NORMAL;
        }
        if (ratio.compareTo(BigDecimal.ONE) >= 0) {
            return PostRiskReportService.HINT_EXCEEDED;
        }
        if (ratio.compareTo(warn) >= 0) {
            return PostRiskReportService.HINT_WARNING;
        }
        if (ratio.compareTo(hint) >= 0) {
            return PostRiskReportService.HINT_HINT;
        }
        return PostRiskReportService.HINT_NORMAL;
    }

    private static BigDecimal decimal(Config config, String key, BigDecimal fallback) {
        String raw = config.get(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "限额使用率阈值配置非数字：key=" + key + " value=" + raw, e);
        }
    }
}
