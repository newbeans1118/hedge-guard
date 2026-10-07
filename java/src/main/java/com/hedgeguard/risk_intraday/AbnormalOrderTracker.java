package com.hedgeguard.risk_intraday;

import com.hedgeguard.common.Clocks;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 异常下单行为（开平仓频次 / 最短持仓周期）内存跟踪器（{@code FR-RISK-14}）。
 *
 * <p>量化阈值（{@code CH-001} {@code Q-5} 已确认）：同一子账户 + 同一品种滚动窗口内
 * <b>日内开平合计 ≥6 告警 / ≥10 限制新开仓</b>；<b>7 日滚动开平回合 ≥5 告警</b>；
 * <b>平均持仓 &lt;3 个交易日且回合 ≥5 告警</b>。
 *
 * <p><b>纯内存</b>（{@code R-RISK-10}）：仅由事件流增量驱动，不查库。
 * 线程安全：内部单锁（事件消费为 J3 单线程，REST 读侧并发读取）。
 *
 * <p><b>回合（round）定义</b>：一笔 {@link TradeAction#CLOSE} 与其匹配的最近一笔未匹配
 * {@link TradeAction#OPEN}（同子账户 + 同品种，FIFO）构成一个回合；持仓天数 = 平仓时刻 − 开仓时刻。
 */
public final class AbnormalOrderTracker {

    /** 滚动窗口 = 7 个自然日（{@code Q-5}「7 日滚动」）。 */
    private static final int ROLLING_DAYS = 7;

    private final Object lock = new Object();

    /** 全部成交活动（按时间序追加，超窗裁剪）。 */
    private final List<TradeActivity> events = new ArrayList<>();

    /** 每（子账户|品种）的未匹配开仓时刻 FIFO。 */
    private final Map<String, Deque<java.time.LocalDateTime>> opens = new HashMap<>();

    /** 每（子账户|品种）的已闭合回合样本。 */
    private final Map<String, List<HoldingSample>> holdings = new HashMap<>();

    private final Clock clock;

    /** 缺省构造（取 {@link Clocks#clock()}）。 */
    public AbnormalOrderTracker() {
        this(Clocks.clock());
    }

    /**
     * 构造（可注入时钟，{@code NFR-TEST-4}）。
     *
     * @param clock 时钟
     */
    public AbnormalOrderTracker(Clock clock) {
        this.clock = clock == null ? Clocks.clock() : clock;
    }

    /**
     * 记录一笔成交活动。
     *
     * @param activity 活动（{@code action}/{@code occurredAt} 缺失即忽略）
     */
    public void record(TradeActivity activity) {
        if (activity == null || activity.action == null || activity.occurredAt == null) {
            return;
        }
        synchronized (lock) {
            events.add(activity);
            String key = key(activity.subAccountId, activity.productId);
            if (activity.action == TradeAction.OPEN) {
                opens.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(activity.occurredAt);
            } else {
                Deque<java.time.LocalDateTime> q = opens.get(key);
                if (q != null && !q.isEmpty()) {
                    java.time.LocalDateTime openAt = q.pollFirst();
                    Duration held = Duration.between(openAt, activity.occurredAt);
                    holdings.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new HoldingSample(activity.occurredAt,
                                    BigDecimal.valueOf(held.toDays()), held.toMinutes()));
                }
            }
            prune();
        }
    }

    /**
     * 该子账户当日「开平合计」次数的<b>品种级最大值</b>（{@code Q-5} 口径：同一子账户 + 同一品种）。
     *
     * @param subAccountId 子账户
     * @return 最严品种的日内开平合计
     */
    public int maxIntradayOpenClose(int subAccountId) {
        LocalDate today = LocalDate.now(clock);
        synchronized (lock) {
            Map<Long, Integer> byProduct = new HashMap<>();
            for (TradeActivity e : events) {
                if (e.subAccountId == subAccountId && today.equals(e.occurredAt.toLocalDate())) {
                    byProduct.merge(e.productId, 1, Integer::sum);
                }
            }
            int max = 0;
            for (int c : byProduct.values()) {
                max = Math.max(max, c);
            }
            return max;
        }
    }

    /**
     * 该子账户 7 日滚动开平<b>回合</b>数的品种级最大值（{@code Q-5}）。
     *
     * @param subAccountId 子账户
     * @return 最严品种的滚动回合数
     */
    public int maxRollingRounds(int subAccountId) {
        java.time.LocalDateTime floor = java.time.LocalDateTime.now(clock).minusDays(ROLLING_DAYS);
        synchronized (lock) {
            Map<Long, Integer> byProduct = new HashMap<>();
            for (TradeActivity e : events) {
                if (e.subAccountId == subAccountId && e.action == TradeAction.CLOSE
                        && !e.occurredAt.isBefore(floor)) {
                    byProduct.merge(e.productId, 1, Integer::sum);
                }
            }
            int max = 0;
            for (int c : byProduct.values()) {
                max = Math.max(max, c);
            }
            return max;
        }
    }

    /**
     * 该子账户在滚动窗口内已闭合回合的<b>平均持仓（交易日）</b>。
     *
     * @param subAccountId 子账户
     * @return 平均持仓天数；窗口内无闭合回合时返回 {@code null}
     */
    public BigDecimal averageHoldingDays(int subAccountId) {
        java.time.LocalDateTime floor = java.time.LocalDateTime.now(clock).minusDays(ROLLING_DAYS);
        synchronized (lock) {
            BigDecimal sum = BigDecimal.ZERO;
            int n = 0;
            for (Map.Entry<String, List<HoldingSample>> en : holdings.entrySet()) {
                if (!en.getKey().startsWith(subAccountId + "|")) {
                    continue;
                }
                for (HoldingSample s : en.getValue()) {
                    if (!s.closedAt.isBefore(floor)) {
                        sum = sum.add(s.days);
                        n++;
                    }
                }
            }
            if (n == 0) {
                return null;
            }
            return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        }
    }

    /** 清空（重连后重建时调用）。 */
    public void reset() {
        synchronized (lock) {
            events.clear();
            opens.clear();
            holdings.clear();
        }
    }

    /**
     * 产出「子账户 × 品种 × 时间窗」异常下单统计快照（{@code FR-RISK-14}；落库口径）。
     *
     * <p>对每个已跟踪的（子账户 | 品种）组合产出<b>两行</b>：日内窗（{@code intradayWindowMinutes}，
     * {@code CFG-05} 默认 1440）与滚动窗（{@code rollingWindowMinutes}，7 日默认 10080）
     * —— 对应 {@code DATA_MODEL.md} TB-RISK-6 的 {@code window_minutes} 维度。
     * {@code isAbnormal}/{@code actionTaken} 按 {@code RiskThresholds} 与该窗口径判定。
     *
     * <p><b>纯内存只读</b>：不查库、不改状态（{@code R-RISK-10}），供落库侧异步取用。
     * {@code orderIds} 无来源（{@link TradeActivity} 不含指令号/理由）⇒ 由落库侧置空并披露。
     *
     * @param thresholds           阈值（{@code null} 取缺省）
     * @param intradayWindowMinutes 日内窗（分钟）
     * @param rollingWindowMinutes  滚动窗（分钟）
     * @return 统计快照（每个组合两行；无任何活动时为空）
     */
    public List<ProductAbnormalStat> snapshot(RiskThresholds thresholds,
                                              int intradayWindowMinutes,
                                              int rollingWindowMinutes) {
        RiskThresholds t = thresholds == null ? RiskThresholds.defaults() : thresholds;
        java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
        List<ProductAbnormalStat> out = new ArrayList<>();
        synchronized (lock) {
            Set<String> keys = new LinkedHashSet<>();
            for (TradeActivity e : events) {
                keys.add(key(e.subAccountId, e.productId));
            }
            for (String k : keys) {
                int sep = k.indexOf('|');
                int sub = Integer.parseInt(k.substring(0, sep));
                long product = Long.parseLong(k.substring(sep + 1));
                out.add(build(t, sub, product, intradayWindowMinutes, now, true));
                out.add(build(t, sub, product, rollingWindowMinutes, now, false));
            }
        }
        return out;
    }

    private ProductAbnormalStat build(RiskThresholds t, int sub, long product, int windowMinutes,
                                      java.time.LocalDateTime now, boolean intraday) {
        java.time.LocalDateTime floor = now.minusMinutes(windowMinutes);
        int openClose = 0;
        java.time.LocalDateTime last = null;
        for (TradeActivity e : events) {
            if (e.subAccountId == sub && e.productId == product && !e.occurredAt.isBefore(floor)) {
                openClose++;
                if (last == null || e.occurredAt.isAfter(last)) {
                    last = e.occurredAt;
                }
            }
        }
        int rounds = 0;
        long minMin = Long.MAX_VALUE;
        long sumMin = 0;
        BigDecimal sumDays = BigDecimal.ZERO;
        List<HoldingSample> list = holdings.get(key(sub, product));
        if (list != null) {
            for (HoldingSample s : list) {
                if (!s.closedAt.isBefore(floor)) {
                    rounds++;
                    minMin = Math.min(minMin, s.minutes);
                    sumMin += s.minutes;
                    sumDays = sumDays.add(s.days);
                }
            }
        }
        ProductAbnormalStat st = new ProductAbnormalStat();
        st.subAccountId = sub;
        st.productId = product;
        st.windowMinutes = windowMinutes;
        st.openCloseCount = openClose;
        st.roundTripCount = rounds;
        st.minHoldMinutes = rounds == 0 ? null : (int) minMin;
        st.avgHoldMinutes = rounds == 0 ? null
                : BigDecimal.valueOf(sumMin).divide(BigDecimal.valueOf(rounds), 4, RoundingMode.HALF_UP);
        st.lastOrderTime = last;
        if (intraday) {
            st.abnormal = openClose >= t.intradayAlertCount;
            st.actionTaken = openClose >= t.intradaySuspendCount
                    ? "ALERT_AND_SUSPEND_OPEN" : "ALERT_ONLY";
        } else {
            BigDecimal avgDays = rounds == 0 ? null
                    : sumDays.divide(BigDecimal.valueOf(rounds), 4, RoundingMode.HALF_UP);
            boolean byHold = avgDays != null && avgDays.compareTo(t.minHoldingDays) < 0
                    && rounds >= t.rollingAlertRounds;
            st.abnormal = rounds >= t.rollingAlertRounds || byHold;
            st.actionTaken = "ALERT_ONLY";
        }
        return st;
    }

    /** 裁剪超出滚动窗口的事件与回合样本，防止内存无界增长。 */
    private void prune() {
        java.time.LocalDateTime floor = java.time.LocalDateTime.now(clock).minusDays(ROLLING_DAYS);
        events.removeIf(e -> e.occurredAt.isBefore(floor));
        for (List<HoldingSample> list : holdings.values()) {
            list.removeIf(s -> s.closedAt.isBefore(floor));
        }
    }

    private static String key(int subAccountId, long productId) {
        return subAccountId + "|" + productId;
    }

    /** 已闭合回合样本。 */
    private static final class HoldingSample {
        private final java.time.LocalDateTime closedAt;
        private final BigDecimal days;
        private final long minutes;

        HoldingSample(java.time.LocalDateTime closedAt, BigDecimal days, long minutes) {
            this.closedAt = closedAt;
            this.days = days;
            this.minutes = minutes;
        }
    }

    /** 供诊断：当前跟踪的（子账户|品种）键集合（只读）。 */
    public Map<String, Integer> trackedKeys() {
        synchronized (lock) {
            Map<String, Integer> out = new LinkedHashMap<>();
            out.put("events", events.size());
            out.put("openQueues", opens.size());
            out.put("holdingSamples", holdings.size());
            return out;
        }
    }
}
