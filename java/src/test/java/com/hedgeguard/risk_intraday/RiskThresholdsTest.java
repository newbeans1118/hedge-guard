package com.hedgeguard.risk_intraday;

import com.hedgeguard.common.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-050} 验收 2（阈值可配置）：{@code R-RISK-14}「阈值由 CONFIG 统一配置，不得硬编码」。
 *
 * <p>并承载缺陷 {@code TE-T050-001} 的机械判据：{@link RiskThresholds} 读取的每一个配置键
 * 都必须逐字 ∈ 冻结 CFG 清单（{@code ARCHITECTURE.md} §11.1 {@code CFG-05/05b/21}，
 * 经 {@code data-init.sql} 播种实证）。
 */
class RiskThresholdsTest {

    /**
     * 冻结 CFG 键（{@code CFG-05} / {@code CFG-05b} / {@code CFG-21}；{@code data-init.sql} 播种）。
     * <b>删/改本集合任一键，{@link #configKeysMatchFrozenList()} 必红</b>（自证有牙）。
     */
    private static final Set<String> FROZEN_CFG_KEYS = Set.of(
            "risk.freq.intraday_warn",
            "risk.freq.intraday_block",
            "risk.freq.7d_rounds_warn",
            "risk.freq.min_hold_days",
            "risk.margin_ratio_warn",
            "risk.margin_ratio_critical");

    /**
     * 暂无冻结 CFG 对应物、暂保留现键名的键（待权威席裁定后登记）。
     * <b>新增/改名本集合须先获授权</b>——本测试即门禁。
     */
    private static final Set<String> PENDING_CFG_KEYS = Set.of(
            "risk.mirror.lag_millis",
            "risk.fund.available_warn",
            "risk.auto_suspend.enabled");

    @Test
    @DisplayName("缺省阈值：CFG-05/05b/21 文档值（6/10/5/3/0.70/0.90）")
    void defaults() {
        RiskThresholds t = RiskThresholds.defaults();
        assertEquals(new BigDecimal("0.70"), t.ratioWarning);
        assertEquals(new BigDecimal("0.90"), t.ratioCritical);
        assertEquals(new BigDecimal("0.70"), t.marginWarning, "CFG-21 同一对键承载二者");
        assertEquals(new BigDecimal("0.90"), t.marginCritical, "CFG-21 同一对键承载二者");
        assertEquals(6, t.intradayAlertCount);
        assertEquals(10, t.intradaySuspendCount);
        assertEquals(5, t.rollingAlertRounds);
        assertEquals(new BigDecimal("3"), t.minHoldingDays);
        assertEquals(30_000L, t.mirrorLagMillis);
        assertEquals(BigDecimal.ZERO, t.availableWarn);
        assertFalse(t.autoSuspendEnabled, "自动限制缺省关闭（FR-RISK-15）");
    }

    @Test
    @DisplayName("键名对齐：声明的每个键逐字 ∈ 冻结 CFG 清单 ∪ 待裁定键（TE-T050-001 判据）")
    void configKeysMatchFrozenList() throws Exception {
        Set<String> declared = new TreeSet<>();
        for (Field f : RiskThresholds.class.getDeclaredFields()) {
            if (f.getType() == String.class && Modifier.isStatic(f.getModifiers())
                    && f.getName().startsWith("KEY_")) {
                declared.add((String) f.get(null));
            }
        }
        Set<String> allowed = new TreeSet<>(FROZEN_CFG_KEYS);
        allowed.addAll(PENDING_CFG_KEYS);
        assertEquals(allowed, declared,
                "RiskThresholds 读取的键必须逐字 ∈ 冻结 CFG 清单（CFG-05/05b/21）∪ 待裁定键；"
                        + "多出=擅自发明键名，缺失=漏读冻结键。实际声明=" + declared);
    }

    @Test
    @DisplayName("键名逐字生效：每个配置键写入后即改变对应阈值（无别名、无硬编码兜底）")
    void everyKeyIsConsulted() {
        System.setProperty(RiskThresholds.KEY_FREQ_INTRADAY_ALERT, "7");
        System.setProperty(RiskThresholds.KEY_FREQ_INTRADAY_SUSPEND, "11");
        System.setProperty(RiskThresholds.KEY_FREQ_ROLLING_ALERT, "8");
        System.setProperty(RiskThresholds.KEY_FREQ_MIN_HOLDING_DAYS, "4");
        System.setProperty(RiskThresholds.KEY_RATIO_WARNING, "0.55");
        System.setProperty(RiskThresholds.KEY_RATIO_CRITICAL, "0.88");
        System.setProperty(RiskThresholds.KEY_MIRROR_LAG_MILLIS, "45000");
        System.setProperty(RiskThresholds.KEY_AVAILABLE_WARN, "1234.5");
        System.setProperty(RiskThresholds.KEY_AUTO_SUSPEND, "true");
        try {
            RiskThresholds t = RiskThresholds.from(Config.empty());
            assertEquals(7, t.intradayAlertCount);
            assertEquals(11, t.intradaySuspendCount);
            assertEquals(8, t.rollingAlertRounds);
            assertEquals(new BigDecimal("4"), t.minHoldingDays);
            assertEquals(new BigDecimal("0.55"), t.ratioWarning);
            assertEquals(new BigDecimal("0.88"), t.ratioCritical);
            assertEquals(new BigDecimal("0.55"), t.marginWarning, "同一 CFG-21 键同时改二者");
            assertEquals(new BigDecimal("0.88"), t.marginCritical, "同一 CFG-21 键同时改二者");
            assertEquals(45_000L, t.mirrorLagMillis);
            assertEquals(new BigDecimal("1234.5"), t.availableWarn);
            assertTrue(t.autoSuspendEnabled);
        } finally {
            System.clearProperty(RiskThresholds.KEY_FREQ_INTRADAY_ALERT);
            System.clearProperty(RiskThresholds.KEY_FREQ_INTRADAY_SUSPEND);
            System.clearProperty(RiskThresholds.KEY_FREQ_ROLLING_ALERT);
            System.clearProperty(RiskThresholds.KEY_FREQ_MIN_HOLDING_DAYS);
            System.clearProperty(RiskThresholds.KEY_RATIO_WARNING);
            System.clearProperty(RiskThresholds.KEY_RATIO_CRITICAL);
            System.clearProperty(RiskThresholds.KEY_MIRROR_LAG_MILLIS);
            System.clearProperty(RiskThresholds.KEY_AVAILABLE_WARN);
            System.clearProperty(RiskThresholds.KEY_AUTO_SUSPEND);
        }
    }
}
