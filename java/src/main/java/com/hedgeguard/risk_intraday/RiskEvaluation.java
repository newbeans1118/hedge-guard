package com.hedgeguard.risk_intraday;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次事中评估的完整产物（{@code FR-RISK-12}~{@code FR-RISK-16}）。
 *
 * <p>= 内存指标（{@link #metrics}） + 告警（{@link #alerts}） +
 * 强制平仓提示（{@link #forceCloseHints}） + 当前受「限制新开仓」的子账户集合。
 */
public final class RiskEvaluation {

    /** 内存指标。 */
    public final MirrorMetrics metrics;

    /** 告警（不可静默，{@code R-RISK-11}）。 */
    public final List<RiskAlert> alerts;

    /** 强制平仓提示（只提示，不自动下单）。 */
    public final List<ForceCloseHint> forceCloseHints;

    /** 当前处于「限制新开仓」的子账户集合。 */
    public final Set<Integer> suspendedSubAccounts;

    /** 评估时刻（{@code epochMilli}）。 */
    public final long evaluatedAtEpochMilli;

    /**
     * 构造。
     *
     * @param metrics            指标
     * @param alerts             告警
     * @param forceCloseHints    强制平仓提示
     * @param suspendedSubAccounts 受限子账户
     * @param evaluatedAtEpochMilli 评估时刻
     */
    public RiskEvaluation(MirrorMetrics metrics, List<RiskAlert> alerts,
                          List<ForceCloseHint> forceCloseHints,
                          Set<Integer> suspendedSubAccounts, long evaluatedAtEpochMilli) {
        this.metrics = metrics;
        this.alerts = Collections.unmodifiableList(new ArrayList<>(alerts));
        this.forceCloseHints = Collections.unmodifiableList(new ArrayList<>(forceCloseHints));
        this.suspendedSubAccounts = Collections.unmodifiableSet(new LinkedHashSet<>(suspendedSubAccounts));
        this.evaluatedAtEpochMilli = evaluatedAtEpochMilli;
    }
}
