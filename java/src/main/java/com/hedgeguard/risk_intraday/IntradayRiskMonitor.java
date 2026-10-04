package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.MirrorSnapshot;
import com.hedgeguard.bridge.PositionView;
import com.hedgeguard.bridge.TradingBridge;
import com.hedgeguard.common.Clocks;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 事中风控引擎（{@code ARCHITECTURE.md} §3.11「Java 模块 J5：事中风控与镜像状态」）。
 *
 * <h2>职责与数据来源</h2>
 * 基于 {@link TradingBridge#mirror()} <b>只读不可变快照</b>实时监控 6 类指标
 * （实时持仓 / 浮动盈亏 / 保证金占用率 / 风险度 / 资金可用量 / 异常下单行为），
 * 超阈值告警与处置；对应 {@code FR-RISK-12}~{@code FR-RISK-16}、{@code FR-RISK-19}。
 *
 * <h2>三条硬纪律</h2>
 * <ol>
 *   <li><b>只读监控</b>（{@code R-RISK-10}）：{@link #evaluate()} 只读快照，不修改 C++ 内存状态，
 *       不查库（全过程零 SQL，{@code FR-RISK-13} 验收 4）；</li>
 *   <li><b>先算后异步落库</b>（{@code FR-RISK-13} 验收 4）：指标在内存算出后经
 *       {@link RiskMetricSink} <b>非阻塞</b>发布，落库不进入事件消费临界路径；</li>
 *   <li><b>只限开仓、不限平仓 / 不自动下单</b>（{@code R-RISK-5}、{@code R-RISK-13}）：
 *       限制经 {@link TradingSuspender}（生产 {@code scan_scope=OPEN_ONLY}）；强制平仓
 *       <b>只提示不自动下单</b>。</li>
 * </ol>
 *
 * <p><b>自动限制仅在明确配置下启用</b>（{@code FR-RISK-15}）：{@link RiskThresholds#autoSuspendEnabled}
 * 为假时只产告警与建议，不下发限制。
 *
 * <p>线程安全：{@link #evaluate()} 由事件消费线程调用，{@link #current()} 由 REST 线程读取；
 * {@code last} 为 {@code volatile}，受限期集合加锁。
 */
public final class IntradayRiskMonitor {

    private static final Logger LOG = Logger.getLogger(IntradayRiskMonitor.class.getName());

    /** 时间口径（{@code §4.1.1} ISO-8601 本地时区，秒精度）。 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final TradingBridge bridge;
    private final RiskThresholds thresholds;
    private final AbnormalOrderTracker tracker;
    private final RiskMetricSink sink;
    private final TradingSuspender suspender;
    private final long systemOperatorId;

    private final Object suspendLock = new Object();
    private final Set<Integer> suspended = new LinkedHashSet<>();

    private volatile RiskEvaluation last;

    /**
     * 构造。
     *
     * @param bridge           组件桥（{@code null} 视为空快照）
     * @param thresholds       阈值
     * @param tracker          异常下单跟踪器
     * @param sink             异步落库端口
     * @param suspender        限制开仓端口；{@code null} 表示不自动限制
     * @param systemOperatorId 自动处置的操作人（留痕）
     */
    public IntradayRiskMonitor(TradingBridge bridge, RiskThresholds thresholds,
                               AbnormalOrderTracker tracker, RiskMetricSink sink,
                               TradingSuspender suspender, long systemOperatorId) {
        this.bridge = bridge;
        this.thresholds = thresholds == null ? RiskThresholds.defaults() : thresholds;
        this.tracker = tracker == null ? new AbnormalOrderTracker() : tracker;
        this.sink = sink == null ? new NoopRiskMetricSink() : sink;
        this.suspender = suspender;
        this.systemOperatorId = systemOperatorId;
    }

    /** 阈值（只读）。 */
    public RiskThresholds thresholds() {
        return thresholds;
    }

    /** 异常下单跟踪器（供事件消费侧投喂）。 */
    public AbnormalOrderTracker tracker() {
        return tracker;
    }

    /**
     * 记录一笔成交活动（事件消费侧调用；仅内存、不落库）。
     *
     * @param activity 成交活动
     */
    public void recordTrade(TradeActivity activity) {
        tracker.record(activity);
    }

    /**
     * 执行一次完整评估（含处置）。
     *
     * <p>由事件消费线程在每次消费事件后调用；结果同时经 {@link RiskMetricSink} 异步发布。
     *
     * @return 评估产物
     */
    public RiskEvaluation evaluate() {
        RiskEvaluation evaluation = compute(true);
        last = evaluation;
        sink.publish(evaluation);
        return evaluation;
    }

    /**
     * 取最近一次评估；尚无评估时做一次<b>只读、不处置</b>的计算（REST 首次读取用）。
     *
     * @return 评估产物
     */
    public RiskEvaluation current() {
        RiskEvaluation e = last;
        return e != null ? e : compute(false);
    }

    /** 当前受限子账户集合（只读副本）。 */
    public Set<Integer> suspendedSubAccounts() {
        synchronized (suspendLock) {
            return new LinkedHashSet<>(suspended);
        }
    }

    private RiskEvaluation compute(boolean applyDisposal) {
        MirrorSnapshot snap = bridge == null ? MirrorSnapshot.empty() : bridge.mirror();
        long nowMillis = Clocks.epochMilli();
        String nowIso = Clocks.now().format(TIME_FMT);
        MirrorMetrics metrics = MirrorMetricsCalculator.compute(snap, thresholds, tracker, nowMillis);

        List<RiskAlert> alerts = new ArrayList<>();
        List<ForceCloseHint> hints = new ArrayList<>();
        Set<Integer> toSuspend = new LinkedHashSet<>();

        evaluateOne(metrics.aggregate, false, snap, nowIso, alerts, hints, toSuspend);
        for (SubAccountRiskMetrics m : metrics.subAccounts) {
            evaluateOne(m, true, snap, nowIso, alerts, hints, toSuspend);
        }
        if (metrics.lagging) {
            alerts.add(new RiskAlert(0, RiskMetricType.POSITION, RiskAlertLevel.WARNING,
                    String.valueOf(metrics.lagMillis), String.valueOf(thresholds.mirrorLagMillis),
                    nowIso, "镜像状态明显滞后：滞后 " + metrics.lagMillis + "ms 超阈值 "
                    + thresholds.mirrorLagMillis + "ms（FR-RISK-12 验收 3）"));
        }

        if (applyDisposal) {
            applySuspend(toSuspend, alerts, nowIso);
        }

        Set<Integer> suspendedSnapshot;
        synchronized (suspendLock) {
            suspendedSnapshot = new LinkedHashSet<>(suspended);
        }
        return new RiskEvaluation(metrics, alerts, hints, suspendedSnapshot, nowMillis);
    }

    private void evaluateOne(SubAccountRiskMetrics m, boolean real, MirrorSnapshot snap,
                             String nowIso, List<RiskAlert> alerts, List<ForceCloseHint> hints,
                             Set<Integer> toSuspend) {
        if (m.riskRatio != null) {
            if (m.riskRatio.compareTo(thresholds.ratioCritical) >= 0) {
                alerts.add(alert(m, RiskMetricType.RISK_RATIO, RiskAlertLevel.CRITICAL,
                        m.riskRatio, thresholds.ratioCritical, nowIso, "风险度"));
                if (real) {
                    hints.addAll(forceCloseHints(snap, m, m.riskRatio));
                }
            } else if (m.riskRatio.compareTo(thresholds.ratioWarning) >= 0) {
                alerts.add(alert(m, RiskMetricType.RISK_RATIO, RiskAlertLevel.WARNING,
                        m.riskRatio, thresholds.ratioWarning, nowIso, "风险度"));
            }
        }
        if (m.marginUsageRate != null) {
            if (m.marginUsageRate.compareTo(thresholds.marginCritical) >= 0) {
                alerts.add(alert(m, RiskMetricType.MARGIN_USAGE_RATE, RiskAlertLevel.CRITICAL,
                        m.marginUsageRate, thresholds.marginCritical, nowIso, "保证金占用率"));
            } else if (m.marginUsageRate.compareTo(thresholds.marginWarning) >= 0) {
                alerts.add(alert(m, RiskMetricType.MARGIN_USAGE_RATE, RiskAlertLevel.WARNING,
                        m.marginUsageRate, thresholds.marginWarning, nowIso, "保证金占用率"));
            }
        }
        if (thresholds.availableWarn != null && thresholds.availableWarn.signum() > 0
                && m.available.compareTo(thresholds.availableWarn) <= 0) {
            alerts.add(alert(m, RiskMetricType.AVAILABLE_FUND, RiskAlertLevel.WARNING,
                    m.available, thresholds.availableWarn, nowIso, "资金可用量"));
        }
        if (!real) {
            return;
        }
        // ⑥ 异常下单行为（Q-5）：日内开平合计 ≥10 限制新开仓；≥6 告警。
        if (m.abnormalIntradayCount >= thresholds.intradaySuspendCount) {
            RiskAlert a = alert(m, RiskMetricType.ABNORMAL_ORDER, RiskAlertLevel.CRITICAL,
                    BigDecimal.valueOf(m.abnormalIntradayCount),
                    BigDecimal.valueOf(thresholds.intradaySuspendCount), nowIso, "日内开平合计");
            a.disposalAction = DisposalAction.SUSPEND_OPEN;
            alerts.add(a);
            toSuspend.add(m.subAccountId);
        } else if (m.abnormalIntradayCount >= thresholds.intradayAlertCount) {
            alerts.add(alert(m, RiskMetricType.ABNORMAL_ORDER, RiskAlertLevel.WARNING,
                    BigDecimal.valueOf(m.abnormalIntradayCount),
                    BigDecimal.valueOf(thresholds.intradayAlertCount), nowIso, "日内开平合计"));
        }
        if (m.abnormalRollingRounds >= thresholds.rollingAlertRounds) {
            alerts.add(alert(m, RiskMetricType.ABNORMAL_ORDER, RiskAlertLevel.WARNING,
                    BigDecimal.valueOf(m.abnormalRollingRounds),
                    BigDecimal.valueOf(thresholds.rollingAlertRounds), nowIso, "7 日滚动开平回合"));
        }
        if (m.averageHoldingDays != null
                && m.averageHoldingDays.compareTo(thresholds.minHoldingDays) < 0
                && m.abnormalRollingRounds >= thresholds.rollingAlertRounds) {
            alerts.add(alert(m, RiskMetricType.ABNORMAL_ORDER, RiskAlertLevel.WARNING,
                    m.averageHoldingDays, thresholds.minHoldingDays, nowIso, "平均持仓（交易日）"));
        }
    }

    private void applySuspend(Set<Integer> toSuspend, List<RiskAlert> alerts, String nowIso) {
        if (toSuspend.isEmpty()) {
            return;
        }
        if (!thresholds.autoSuspendEnabled || suspender == null) {
            // FR-RISK-15：自动限制仅在明确配置下启用；未启用则保留为「建议」。
            for (RiskAlert a : alerts) {
                if (a.disposalAction == DisposalAction.SUSPEND_OPEN) {
                    a.disposalAction = DisposalAction.ALERT_ONLY;
                    a.message = a.message + "；建议限制新开仓（自动限制未启用，须风控岗人工处置）";
                }
            }
            return;
        }
        for (Integer sub : toSuspend) {
            synchronized (suspendLock) {
                if (suspended.contains(sub)) {
                    continue;
                }
                try {
                    suspender.suspendOpenOnly(sub, "事中风控自动限制新开仓：日内开平合计超严重阈值"
                            + "（scope=OPEN_ONLY，平仓放行，R-RISK-5；R-RISK-13 不自动下单）",
                            systemOperatorId);
                    suspended.add(sub);
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "自动限制新开仓下发失败（不视为生效）：subAccountId=" + sub
                            + "；原因=" + e.getMessage(), e);
                    alerts.add(new RiskAlert(sub, RiskMetricType.ABNORMAL_ORDER,
                            RiskAlertLevel.CRITICAL, "下发失败", "已生效",
                            nowIso, "限制新开仓下发失败，不视为生效（FR-RISK-15）：" + e.getMessage()));
                }
            }
        }
    }

    private static List<ForceCloseHint> forceCloseHints(MirrorSnapshot snap,
                                                        SubAccountRiskMetrics m, BigDecimal riskRatio) {
        List<ForceCloseHint> out = new ArrayList<>();
        for (PositionView p : snap.positions()) {
            if (p.subAccountId != m.subAccountId) {
                continue;
            }
            BigDecimal positionQty = MirrorMetricsCalculator.decimal(p.positionQty);
            BigDecimal closeable = MirrorMetricsCalculator.decimal(p.availableQty);
            if (closeable.signum() == 0) {
                closeable = positionQty.abs();
            }
            if (closeable.signum() == 0) {
                continue;
            }
            String direction = positionQty.signum() >= 0 ? "SELL" : "BUY";
            out.add(new ForceCloseHint(m.subAccountId, p.instrumentId, direction, closeable.abs(),
                    riskRatio, "风险度达严重阈值，建议人工确认后平仓（系统不自动下单，R-RISK-13）"));
        }
        return out;
    }

    private RiskAlert alert(SubAccountRiskMetrics m, RiskMetricType type, RiskAlertLevel level,
                            BigDecimal value, BigDecimal threshold, String nowIso, String label) {
        String message = label + " " + value.toPlainString() + (level == RiskAlertLevel.CRITICAL
                ? " ≥ 严重阈值 " : " ≥ 告警阈值 ") + threshold.toPlainString()
                + "（子账户 " + m.subAccountId + "，时间 " + nowIso + "）";
        return new RiskAlert(m.subAccountId, type, level, value.toPlainString(),
                threshold.toPlainString(), nowIso, message);
    }
}
