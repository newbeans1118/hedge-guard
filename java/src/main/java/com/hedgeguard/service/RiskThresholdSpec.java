package com.hedgeguard.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 事前风控阈值**合规登记表**（{@code T-063}；{@code FR-CONFIG-5}/{@code FR-RISK-10}/{@code R-CONFIG-8}）。
 *
 * <p>本表把「{@code ARCHITECTURE.md} §11.1 的配置键」↔「{@code §4.2.8} {@code RiskThresholds}
 * 的 16 个线格式字段」↔「{@code INDEX.md} §9.1 的确认口径（默认值 / 取值范围 / 放宽方向）」
 * 三者固定为**唯一映射**，供 {@link RiskService} 与 {@link com.hedgeguard.bridge.ParamChangeEncoder}
 * 共用，避免键名/默认值在多处漂移。
 *
 * <h2>为何默认值/范围「写在本类」不算违反「不得硬编码」</h2>
 * {@code FR-CONFIG-5} 的「不得硬编码」约束的是**可调阈值本身**（其取值存 {@code t_risk_threshold}，
 * 由本类承载的运行期配置读取）；本类固化的是**合规下限口径**（{@code INDEX.md} §9.1 用户确认值 +
 * {@code ARCHITECTURE.md} §11.1 取值范围）——那是**监管常数**，不是业务可调项；若把它也配置化，
 * 等于「把合规红线交给配置」，与 {@code R-CONFIG-8} 立法本意相悖（{@code CH-003} 同旨）。
 * 故默认值在此固化、运行期取值仍读库。
 *
 * <h2>放宽方向（{@code R-CONFIG-8}「不得静默放宽」）</h2>
 * 每项 {@code isRelaxed(candidate)} 判定候选值相对 §9.1 默认值是否**放宽**（更宽松）：
 * 数值型「越大越松」（比例上限、月数、宽限、偏离幅度、频次阈值、风险度阈值）；
 * 枚举型以 {@link #relaxedValues} 显式列出比默认更松的取值；{@link #changeAlwaysRelax}
 * 用于「任何偏离默认即属方法论放宽」的项（规模基数口径）。放宽须双人复核 + 留痕，否则拒绝。
 */
public enum RiskThresholdSpec {

    // ---- CFG-02b：规模红线两档比例（Q-2；数值越大越松） ----
    /** 商品类规模红线比例（默认 90%）。 */
    SCALE_RATIO_COMMODITY("hedge.scale.ratio_commodity", "scale_ratio_commodity", "DECIMAL",
            "0.90", true, new BigDecimal("0"), true, new BigDecimal("1"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-2"),

    /** 贸易类规模红线比例（默认 80%）。 */
    SCALE_RATIO_TRADE("hedge.scale.ratio_trade", "scale_ratio_trade", "DECIMAL",
            "0.80", true, new BigDecimal("0"), true, new BigDecimal("1"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-2"),

    // ---- CFG-01/02：规模基数与范围（Q-1） ----
    /** 规模红线基数口径（默认 {@code EXPOSURE_QTY}；任何偏离默认即放宽）。 */
    SCALE_BASE("hedge.scale.base", "scale_base", "ENUM",
            "EXPOSURE_QTY", true, null, false, null,
            ordered("EXPOSURE_QTY", "REVENUE", "PURCHASE_QTY"), false, Collections.emptySet(),
            true, "Q-1"),

    /** 规模红线计算范围（默认 {@code BOTH} 双算取严；{@code SUB_ACCOUNT}/{@code TOTAL_ACCOUNT} 为放宽）。 */
    SCALE_SCOPE("hedge.scale.scope", "scale_scope", "ENUM",
            "BOTH", true, null, false, null,
            ordered("SUB_ACCOUNT", "TOTAL_ACCOUNT", "BOTH"), false,
            ordered("SUB_ACCOUNT", "TOTAL_ACCOUNT"), false, "Q-1"),

    // ---- CFG-03/03b：期限红线（Q-4；数值越大越松） ----
    /** 期限上限月数（建仓日 + N 个月，默认 12）。 */
    TERM_MAX_MONTHS("risk.term.max_months", "term_max_months", "INT",
            "12", true, new BigDecimal("1"), false, new BigDecimal("120"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-4"),

    /** 实货合同到期宽限天数（默认 30）。 */
    TERM_GRACE_DAYS("risk.term.grace_days", "term_grace_days", "INT",
            "30", true, new BigDecimal("0"), false, new BigDecimal("180"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-4"),

    /** 超期限处置方式（默认 {@code WARN_THEN_BLOCK}；{@code WARN_AND_APPROVE} 为放宽）。 */
    TERM_ACTION("risk.term.action", "term_action", "ENUM",
            "WARN_THEN_BLOCK", true, null, false, null,
            ordered("WARN_THEN_BLOCK", "BLOCK", "WARN_AND_APPROVE"), false,
            ordered("WARN_AND_APPROVE"), false, "Q-4"),

    // ---- CFG-15：价格偏离（Q-15；数值越大越松） ----
    /** 价格偏离基准（{@code Q-15} 冻结为昨结算价；协议仅载该一值）。 */
    PRICE_BASE("risk.price.base", "price_base", "ENUM",
            "PREV_SETTLEMENT", true, null, false, null,
            ordered("PREV_SETTLEMENT"), false, Collections.emptySet(), false, "Q-15"),

    /** 价格偏离告警线（默认 2%）。 */
    PRICE_WARN_PCT("risk.price.warn_pct", "price_warn_pct", "DECIMAL",
            "0.02", true, new BigDecimal("0"), true, new BigDecimal("1"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-15"),

    /** 价格偏离阻断线（默认 5%）。 */
    PRICE_BLOCK_PCT("risk.price.block_pct", "price_block_pct", "DECIMAL",
            "0.05", true, new BigDecimal("0"), true, new BigDecimal("1"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-15"),

    // ---- CFG-04：时点净持仓校验时点（Q-3） ----
    /** 校验时点（默认 {@code PRE_TRADE_AND_EOD} 下单瞬间硬阻断 + 日终复核；后二者为放宽）。 */
    POSITION_CHECK_MODE("risk.position.check_mode", "position_check_mode", "ENUM",
            "PRE_TRADE_AND_EOD", true, null, false, null,
            ordered("PRE_TRADE_AND_EOD", "ON_ORDER", "EOD_ONLY"), false,
            ordered("ON_ORDER", "EOD_ONLY"), false, "Q-3"),

    // ---- CFG-05/05b：频繁开平仓（Q-5；数值越大越松） ----
    /** 日内开平合计告警阈值（默认 6）。 */
    FREQ_INTRADAY_WARN("risk.freq.intraday_warn", "freq_intraday_warn", "INT",
            "6", true, new BigDecimal("1"), false, new BigDecimal("100"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-5"),

    /** 日内开平合计限制新开仓阈值（默认 10）。 */
    FREQ_INTRADAY_BLOCK("risk.freq.intraday_block", "freq_intraday_block", "INT",
            "10", true, new BigDecimal("1"), false, new BigDecimal("100"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-5"),

    /** 7 日滚动开平回合告警阈值（默认 5）。 */
    FREQ_7D_ROUNDS_WARN("risk.freq.7d_rounds_warn", "freq_7d_rounds_warn", "INT",
            "5", true, new BigDecimal("1"), false, new BigDecimal("100"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-5"),

    /** 平均持仓天数告警线（默认 3）。 */
    FREQ_MIN_HOLD_DAYS("risk.freq.min_hold_days", "freq_min_hold_days", "INT",
            "3", true, new BigDecimal("1"), false, new BigDecimal("60"),
            Collections.emptySet(), true, Collections.emptySet(), false, "Q-5"),

    // ---- CFG-21：风险度 / 保证金占用率（默认 0.70；数值越大越松） ----
    /**
     * 风险度告警阈值（默认 0.70）。
     *
     * <p>⚠️ {@code §4.2.8 RiskThresholds} 仅有一个 {@code margin_ratio_threshold} 字段，
     * 而 {@code CFG-21} 有 {@code _warn}/{@code _critical} 两键 ⇒ 本项映射 {@code _warn}；
     * {@code _critical} 在 {@code CMD_UPDATE_RISK_THRESHOLD} 下**无承载字段**（见 {@code T-063} 披露）。
     */
    MARGIN_RATIO_THRESHOLD("risk.margin_ratio_warn", "margin_ratio_threshold", "DECIMAL",
            "0.70", true, new BigDecimal("0"), true, new BigDecimal("1"),
            Collections.emptySet(), true, Collections.emptySet(), false, null);

    private final String configKey;
    private final String wireField;
    private final String valueType;
    private final String defaultValue;
    private final boolean complianceRedline;
    private final BigDecimal min;
    private final boolean minExclusive;
    private final BigDecimal max;
    private final Set<String> allowedValues;
    private final boolean higherIsLooser;
    private final Set<String> relaxedValues;
    private final boolean changeAlwaysRelax;
    private final String relatedQuestionNo;

    RiskThresholdSpec(String configKey, String wireField, String valueType, String defaultValue,
                      boolean complianceRedline, BigDecimal min, boolean minExclusive,
                      BigDecimal max, Set<String> allowedValues, boolean higherIsLooser,
                      Set<String> relaxedValues, boolean changeAlwaysRelax,
                      String relatedQuestionNo) {
        this.configKey = configKey;
        this.wireField = wireField;
        this.valueType = valueType;
        this.defaultValue = defaultValue;
        this.complianceRedline = complianceRedline;
        this.min = min;
        this.minExclusive = minExclusive;
        this.max = max;
        this.allowedValues = allowedValues;
        this.higherIsLooser = higherIsLooser;
        this.relaxedValues = relaxedValues;
        this.changeAlwaysRelax = changeAlwaysRelax;
        this.relatedQuestionNo = relatedQuestionNo;
    }

    /** 有序集合（保序，供错误信息稳定展示）。 */
    private static Set<String> ordered(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    /** 配置键（{@code ARCHITECTURE.md} §11.1；同时作 {@code t_risk_threshold.threshold_key}）。 */
    public String configKey() {
        return configKey;
    }

    /** 线格式字段名（{@code §4.2.8 RiskThresholds}；作 {@code ParamChange.payload} 键）。 */
    public String wireField() {
        return wireField;
    }

    /** 值类型（{@code t_risk_threshold.value_type}）。 */
    public String valueType() {
        return valueType;
    }

    /** 默认值（{@code INDEX.md} §9.1 确认口径；{@code R-CONFIG-8} 合规下限锚点）。 */
    public String defaultValue() {
        return defaultValue;
    }

    /** 是否合规红线（{@code Y} 时放宽须双人复核）。 */
    public boolean complianceRedline() {
        return complianceRedline;
    }

    /** 取值范围下界（{@code null} 表示无界；数值型）。 */
    public BigDecimal min() {
        return min;
    }

    /** 下界是否开区间。 */
    public boolean minExclusive() {
        return minExclusive;
    }

    /** 取值范围上界（{@code null} 表示无界；数值型）。 */
    public BigDecimal max() {
        return max;
    }

    /** 值域（枚举型；空集表示无数值域约束）。 */
    public Set<String> allowedValues() {
        return allowedValues;
    }

    /** 关联待澄清编号（{@code Q-*}）。 */
    public String relatedQuestionNo() {
        return relatedQuestionNo;
    }

    /**
     * 校验候选取值（类型 / 值域 / 范围），不合法即抛 {@link IllegalArgumentException}
     * （由 {@link RiskService} 转业务码 {@code 10001}）。
     *
     * @param candidate 候选取值
     */
    public void requireValid(String candidate) {
        String v = candidate == null ? null : candidate.trim();
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("阈值取值不得为空");
        }
        if ("ENUM".equals(valueType)) {
            if (!allowedValues.isEmpty() && !allowedValues.contains(v)) {
                throw new IllegalArgumentException(
                        "取值不在值域内：允许 " + allowedValues + "，实际=" + v);
            }
            return;
        }
        final BigDecimal d;
        try {
            d = new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("取值须为十进制数：实际=" + v);
        }
        if ("INT".equals(valueType) && d.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("取值须为整数：实际=" + v);
        }
        if (min != null) {
            int c = d.compareTo(min);
            if (minExclusive ? c <= 0 : c < 0) {
                throw new IllegalArgumentException("取值越界：须" + (minExclusive ? "大于 " : "不小于 ")
                        + min.toPlainString() + "，实际=" + v);
            }
        }
        if (max != null && d.compareTo(max) > 0) {
            throw new IllegalArgumentException("取值越界：须不大于 " + max.toPlainString()
                    + "，实际=" + v);
        }
    }

    /**
     * 候选值相对 {@code INDEX.md} §9.1 默认值是否**放宽**（{@code R-CONFIG-8}：放宽不得静默）。
     *
     * @param candidate 候选取值
     * @return 放宽返回 {@code true}
     */
    public boolean isRelaxed(String candidate) {
        String v = candidate == null ? null : candidate.trim();
        if (v == null) {
            return false;
        }
        if (changeAlwaysRelax) {
            return !defaultValue.equals(v);
        }
        if ("ENUM".equals(valueType)) {
            return relaxedValues.contains(v);
        }
        try {
            return new BigDecimal(v).compareTo(new BigDecimal(defaultValue)) > 0 && higherIsLooser;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 按配置键查登记项。
     *
     * @param configKey 配置键
     * @return 登记项
     * @throws IllegalArgumentException 未登记的键
     */
    public static RiskThresholdSpec byKey(String configKey) {
        for (RiskThresholdSpec spec : values()) {
            if (spec.configKey.equals(configKey)) {
                return spec;
            }
        }
        throw new IllegalArgumentException(
                "未登记的风控阈值键：" + configKey + "；复现=使用 §11.1 配置键（本服务仅承载 "
                        + "CMD_UPDATE_RISK_THRESHOLD 线格式可表达的 " + values().length + " 项）");
    }
}
