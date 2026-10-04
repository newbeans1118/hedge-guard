package com.hedgeguard.risk_intraday;

import com.hedgeguard.common.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-050} 验收 2（阈值可配置）：{@code R-RISK-14}「阈值由 CONFIG 统一配置，不得硬编码」。
 */
class RiskThresholdsTest {

    @Test
    @DisplayName("缺省阈值：Q-5 频次（6/10/5/3）与风险度/占用率缺省值")
    void defaults() {
        RiskThresholds t = RiskThresholds.defaults();
        assertEquals(new BigDecimal("0.70"), t.ratioWarning);
        assertEquals(new BigDecimal("0.90"), t.ratioCritical);
        assertEquals(30_000L, t.mirrorLagMillis);
        assertEquals(6, t.intradayAlertCount);
        assertEquals(10, t.intradaySuspendCount);
        assertEquals(5, t.rollingAlertRounds);
        assertEquals(new BigDecimal("3"), t.minHoldingDays);
        assertFalse(t.autoSuspendEnabled, "自动限制缺省关闭（FR-RISK-15）");
    }

    @Test
    @DisplayName("从配置读取：显式覆盖生效、变更后立即生效")
    void fromConfigOverrides() {
        System.setProperty(RiskThresholds.KEY_RATIO_CRITICAL, "0.95");
        System.setProperty(RiskThresholds.KEY_FREQ_INTRADAY_SUSPEND, "12");
        System.setProperty(RiskThresholds.KEY_AUTO_SUSPEND, "true");
        try {
            RiskThresholds t = RiskThresholds.from(Config.empty());
            assertEquals(new BigDecimal("0.95"), t.ratioCritical);
            assertEquals(12, t.intradaySuspendCount);
            assertTrue(t.autoSuspendEnabled);
        } finally {
            System.clearProperty(RiskThresholds.KEY_RATIO_CRITICAL);
            System.clearProperty(RiskThresholds.KEY_FREQ_INTRADAY_SUSPEND);
            System.clearProperty(RiskThresholds.KEY_AUTO_SUSPEND);
        }
    }
}
