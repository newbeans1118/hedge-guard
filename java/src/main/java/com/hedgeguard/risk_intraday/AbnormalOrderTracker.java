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
import java.util.List;
import java.util.Map;

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
                    long days = Duration.between(openAt, activity.occurredAt).toDays();
                    holdings.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new HoldingSample(activity.occurredAt, BigDecimal.valueOf(days)));
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

        HoldingSample(java.time.LocalDateTime closedAt, BigDecimal days) {
            this.closedAt = closedAt;
            this.days = days;
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
