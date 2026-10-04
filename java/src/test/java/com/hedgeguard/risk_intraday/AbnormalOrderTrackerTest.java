package com.hedgeguard.risk_intraday;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code T-050} 验收 2（异常下单行为）：日内开平合计 / 7 日滚动回合 / 平均持仓（{@code FR-RISK-14}、{@code Q-5}）。
 */
class AbnormalOrderTrackerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 10, 0);

    private static AbnormalOrderTracker trackerAt(LocalDateTime now) {
        return new AbnormalOrderTracker(Clock.fixed(now.atZone(ZONE).toInstant(), ZONE));
    }

    private static TradeActivity ev(int sub, long product, TradeAction action, LocalDateTime at) {
        return new TradeActivity(sub, product, "inst-" + product, action, BigDecimal.ONE, at);
    }

    @Test
    @DisplayName("日内开平合计取品种级最大值（同一子账户 + 同一品种口径）")
    void intradayCountPerProductMax() {
        AbnormalOrderTracker t = trackerAt(NOW);
        for (int i = 0; i < 3; i++) {
            t.record(ev(1, 100, TradeAction.OPEN, NOW.minusHours(i + 1)));
            t.record(ev(1, 100, TradeAction.CLOSE, NOW.minusMinutes(i + 1)));
        }
        t.record(ev(1, 200, TradeAction.OPEN, NOW.minusHours(1)));
        assertEquals(6, t.maxIntradayOpenClose(1), "品种 100 日内开平 6 次");
        // 昨日的事件不计入日内
        t.record(ev(1, 100, TradeAction.OPEN, NOW.minusDays(1)));
        assertEquals(6, t.maxIntradayOpenClose(1));
    }

    @Test
    @DisplayName("7 日滚动回合：仅计窗口内已闭合回合，超窗剔除")
    void rollingRoundsWindow() {
        AbnormalOrderTracker t = trackerAt(NOW);
        for (int i = 0; i < 5; i++) {
            t.record(ev(1, 100, TradeAction.OPEN, NOW.minusDays(2).minusHours(i)));
            t.record(ev(1, 100, TradeAction.CLOSE, NOW.minusDays(1).minusHours(i)));
        }
        assertEquals(5, t.maxRollingRounds(1));
        // 8 天前的平仓不计入
        t.record(ev(1, 100, TradeAction.CLOSE, NOW.minusDays(8)));
        assertEquals(5, t.maxRollingRounds(1));
    }

    @Test
    @DisplayName("平均持仓天数：FIFO 配对开平，算平仓 − 开仓的交易日")
    void averageHoldingDays() {
        AbnormalOrderTracker t = trackerAt(NOW);
        t.record(ev(1, 100, TradeAction.OPEN, NOW.minusDays(5)));
        t.record(ev(1, 100, TradeAction.CLOSE, NOW.minusDays(3)));   // 2 日
        t.record(ev(1, 100, TradeAction.OPEN, NOW.minusDays(4)));
        t.record(ev(1, 100, TradeAction.CLOSE, NOW.minusDays(0)));   // 4 日
        assertEquals(new BigDecimal("3.0000"), t.averageHoldingDays(1));
    }

    @Test
    @DisplayName("空样本：平均持仓为 null（不可算，不伪造 0）；reset 清空")
    void emptyAndReset() {
        AbnormalOrderTracker t = trackerAt(NOW);
        assertNull(t.averageHoldingDays(9));
        t.record(ev(1, 100, TradeAction.OPEN, NOW.minusHours(1)));
        t.reset();
        assertEquals(0, t.maxIntradayOpenClose(1));
        assertNull(t.averageHoldingDays(1));
    }
}
