package com.hedgeguard.risk_intraday;

import com.hedgeguard.common.Config;

import java.math.BigDecimal;

/**
 * 事中风控阈值（{@code R-RISK-14}：<b>由 {@code CONFIG} 统一配置，不得硬编码</b>）。
 *
 * <p>全部阈值经 {@link Config} 读取；{@link #from(Config)} 的缺省值仅作<b>兜底</b>
 * 并在本类逐项列明（构建时打印/断言可查）。取值口径：风险度 / 保证金占用率见
 * {@code FR-RISK-13}；频次见 {@code FR-RISK-14}（{@code CH-001} {@code Q-5} 已确认）。
 */
public final class RiskThresholds {

    /** 风险度告警阈值（{@code FR-RISK-13}）。 */
    public static final String KEY_RATIO_WARNING = "risk.ratio.warning";
    /** 风险度严重阈值（触发强制平仓提示，{@code FR-RISK-16}）。 */
    public static final String KEY_RATIO_CRITICAL = "risk.ratio.critical";
    /** 保证金占用率告警阈值。 */
    public static final String KEY_MARGIN_WARNING = "risk.margin.usage.warning";
    /** 保证金占用率严重阈值。 */
    public static final String KEY_MARGIN_CRITICAL = "risk.margin.usage.critical";
    /** 镜像滞后阈值（毫秒；{@code FR-RISK-12} 验收 3）。 */
    public static final String KEY_MIRROR_LAG_MILLIS = "risk.mirror.lag_millis";
    /** 日内开平合计告警次数（{@code Q-5}：≥6）。 */
    public static final String KEY_FREQ_INTRADAY_ALERT = "risk.freq.intraday_alert";
    /** 日内开平合计限制新开仓次数（{@code Q-5}：≥10）。 */
    public static final String KEY_FREQ_INTRADAY_SUSPEND = "risk.freq.intraday_suspend";
    /** 7 日滚动开平回合告警数（{@code Q-5}：≥5）。 */
    public static final String KEY_FREQ_ROLLING_ALERT = "risk.freq.rolling7d_alert";
    /** 最短持仓周期（交易日；{@code Q-5}：<3 且回合 ≥5 告警）。 */
    public static final String KEY_FREQ_MIN_HOLDING_DAYS = "risk.freq.min_holding_days";
    /** 资金可用量提示线（≤ 该值告警；{@code 0} 表示禁用）。 */
    public static final String KEY_AVAILABLE_WARN = "risk.fund.available_warn";
    /** 自动限制开仓开关（{@code FR-RISK-15}：自动限制仅在明确配置下启用）。 */
    public static final String KEY_AUTO_SUSPEND = "risk.auto_suspend.enabled";

    /** 风险度缺省告警阈值。 */
    public static final BigDecimal DEFAULT_RATIO_WARNING = new BigDecimal("0.70");
    /** 风险度缺省严重阈值。 */
    public static final BigDecimal DEFAULT_RATIO_CRITICAL = new BigDecimal("0.90");
    /** 保证金占用率缺省告警阈值。 */
    public static final BigDecimal DEFAULT_MARGIN_WARNING = new BigDecimal("0.80");
    /** 保证金占用率缺省严重阈值。 */
    public static final BigDecimal DEFAULT_MARGIN_CRITICAL = new BigDecimal("0.95");
    /** 镜像滞后缺省阈值（毫秒）。 */
    public static final long DEFAULT_LAG_MILLIS = 30_000L;
    /** 日内开平合计缺省告警次数（{@code Q-5}）。 */
    public static final int DEFAULT_INTRADAY_ALERT = 6;
    /** 日内开平合计缺省限制次数（{@code Q-5}）。 */
    public static final int DEFAULT_INTRADAY_SUSPEND = 10;
    /** 7 日滚动开平回合缺省告警数（{@code Q-5}）。 */
    public static final int DEFAULT_ROLLING_ALERT = 5;
    /** 最短持仓周期缺省值（交易日；{@code Q-5}）。 */
    public static final BigDecimal DEFAULT_MIN_HOLDING_DAYS = new BigDecimal("3");

    /** 风险度告警阈值。 */
    public final BigDecimal ratioWarning;
    /** 风险度严重阈值。 */
    public final BigDecimal ratioCritical;
    /** 保证金占用率告警阈值。 */
    public final BigDecimal marginWarning;
    /** 保证金占用率严重阈值。 */
    public final BigDecimal marginCritical;
    /** 镜像滞后阈值（毫秒）。 */
    public final long mirrorLagMillis;
    /** 日内开平合计告警次数。 */
    public final int intradayAlertCount;
    /** 日内开平合计限制次数。 */
    public final int intradaySuspendCount;
    /** 7 日滚动回合告警数。 */
    public final int rollingAlertRounds;
    /** 最短持仓周期（交易日）。 */
    public final BigDecimal minHoldingDays;
    /** 资金可用量提示线（{@code ≤0} 禁用）。 */
    public final BigDecimal availableWarn;
    /** 自动限制开仓是否启用。 */
    public final boolean autoSuspendEnabled;

    /**
     * 构造。
     */
    public RiskThresholds(BigDecimal ratioWarning, BigDecimal ratioCritical,
                          BigDecimal marginWarning, BigDecimal marginCritical, long mirrorLagMillis,
                          int intradayAlertCount, int intradaySuspendCount, int rollingAlertRounds,
                          BigDecimal minHoldingDays, BigDecimal availableWarn, boolean autoSuspendEnabled) {
        this.ratioWarning = ratioWarning;
        this.ratioCritical = ratioCritical;
        this.marginWarning = marginWarning;
        this.marginCritical = marginCritical;
        this.mirrorLagMillis = mirrorLagMillis;
        this.intradayAlertCount = intradayAlertCount;
        this.intradaySuspendCount = intradaySuspendCount;
        this.rollingAlertRounds = rollingAlertRounds;
        this.minHoldingDays = minHoldingDays;
        this.availableWarn = availableWarn;
        this.autoSuspendEnabled = autoSuspendEnabled;
    }

    /** 全部取缺省值。 */
    public static RiskThresholds defaults() {
        return new RiskThresholds(DEFAULT_RATIO_WARNING, DEFAULT_RATIO_CRITICAL,
                DEFAULT_MARGIN_WARNING, DEFAULT_MARGIN_CRITICAL, DEFAULT_LAG_MILLIS,
                DEFAULT_INTRADAY_ALERT, DEFAULT_INTRADAY_SUSPEND, DEFAULT_ROLLING_ALERT,
                DEFAULT_MIN_HOLDING_DAYS, BigDecimal.ZERO, false);
    }

    /**
     * 从配置读取（{@code R-RISK-14}）；缺项取缺省值。
     *
     * @param config 配置
     * @return 阈值
     */
    public static RiskThresholds from(Config config) {
        if (config == null) {
            return defaults();
        }
        return new RiskThresholds(
                decimal(config, KEY_RATIO_WARNING, DEFAULT_RATIO_WARNING),
                decimal(config, KEY_RATIO_CRITICAL, DEFAULT_RATIO_CRITICAL),
                decimal(config, KEY_MARGIN_WARNING, DEFAULT_MARGIN_WARNING),
                decimal(config, KEY_MARGIN_CRITICAL, DEFAULT_MARGIN_CRITICAL),
                (long) config.getInt(KEY_MIRROR_LAG_MILLIS, (int) DEFAULT_LAG_MILLIS),
                config.getInt(KEY_FREQ_INTRADAY_ALERT, DEFAULT_INTRADAY_ALERT),
                config.getInt(KEY_FREQ_INTRADAY_SUSPEND, DEFAULT_INTRADAY_SUSPEND),
                config.getInt(KEY_FREQ_ROLLING_ALERT, DEFAULT_ROLLING_ALERT),
                decimal(config, KEY_FREQ_MIN_HOLDING_DAYS, DEFAULT_MIN_HOLDING_DAYS),
                decimal(config, KEY_AVAILABLE_WARN, BigDecimal.ZERO),
                Boolean.parseBoolean(config.get(KEY_AUTO_SUSPEND, "false")));
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
                    "风控阈值配置非数字：key=" + key + " value=" + raw
                            + "；复现=按 CFG 配置项提交十进制数值（R-RISK-14）", e);
        }
    }
}
