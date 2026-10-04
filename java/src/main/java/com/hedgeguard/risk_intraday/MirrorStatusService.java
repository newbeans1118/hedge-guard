package com.hedgeguard.risk_intraday;

import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.service.SubAccountVisibility;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 镜像状态读取服务（{@code GET /risk/mirror-status}）。
 *
 * <p>把 {@link IntradayRiskMonitor} 最近一次评估按<b>调用者可见子账户范围</b>过滤后返回
 * （{@code NFR-SEC-7} 只读面 / {@code FR-AUTH-13}）：可见集含哨兵
 * {@link SubAccountScope#ALL_ACCOUNT_SENTINEL} 表示跨账户只读 ⇒ 不过滤；否则仅返回可见子账户，
 * <b>空集 = 无可见数据</b>（fail-closed，不退化为「不过滤 = 全放行」）。
 *
 * <p>本服务<b>不查库</b>：数据全部来自内存镜像（{@code R-RISK-10}）。
 */
public final class MirrorStatusService {

    private final IntradayRiskMonitor monitor;
    private final SubAccountVisibility visibility;

    /**
     * 构造。
     *
     * @param monitor    事中风控引擎
     * @param visibility 子账户可见性（{@code null} 表示不启用隔离，仅测试装配用）
     */
    public MirrorStatusService(IntradayRiskMonitor monitor, SubAccountVisibility visibility) {
        this.monitor = monitor;
        this.visibility = visibility;
    }

    /**
     * 取镜像状态视图。
     *
     * @param userId 调用者用户 ID
     * @return 视图
     */
    public MirrorStatusView current(long userId) {
        RiskEvaluation e = monitor.current();
        Set<Integer> visible = visibleOrNull(userId);

        MirrorStatusView view = new MirrorStatusView();
        view.readyState = e.metrics.readyState == null ? null : e.metrics.readyState.name();
        view.watermarkEventSeq = e.metrics.watermarkEventSeq;
        view.paramVersion = e.metrics.paramVersion;
        view.takenAt = e.metrics.takenAt;
        view.lagMillis = e.metrics.lagMillis;
        view.lagging = e.metrics.lagging;
        view.aggregate = SubAccountMetricsView.of(e.metrics.aggregate);
        view.evaluatedAtEpochMilli = e.evaluatedAtEpochMilli;

        List<SubAccountMetricsView> subs = new ArrayList<>();
        for (SubAccountRiskMetrics m : e.metrics.subAccounts) {
            if (allowed(visible, m.subAccountId)) {
                subs.add(SubAccountMetricsView.of(m));
            }
        }
        view.subAccounts = subs;

        List<RiskAlert> alerts = new ArrayList<>();
        for (RiskAlert a : e.alerts) {
            if (allowed(visible, a.subAccountId)) {
                alerts.add(a);
            }
        }
        view.alerts = alerts;

        List<ForceCloseHint> hints = new ArrayList<>();
        for (ForceCloseHint h : e.forceCloseHints) {
            if (allowed(visible, h.subAccountId)) {
                hints.add(h);
            }
        }
        view.forceCloseHints = hints;

        Set<Integer> suspended = new LinkedHashSet<>();
        for (Integer sub : e.suspendedSubAccounts) {
            if (allowed(visible, sub)) {
                suspended.add(sub);
            }
        }
        view.suspendedSubAccounts = suspended;
        return view;
    }

    /** 可见集：{@code null} = 不启用隔离（返回 {@code null}）；含哨兵 = 不过滤（返回 {@code null}）。 */
    private Set<Integer> visibleOrNull(long userId) {
        if (visibility == null) {
            return null;
        }
        List<Integer> visible = visibility.visibleSubAccountIds(userId);
        if (visible == null) {
            return null;
        }
        Set<Integer> set = new LinkedHashSet<>(visible);
        if (set.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            return null;   // 跨账户只读：不过滤
        }
        return set;
    }

    private static boolean allowed(Set<Integer> visible, int subAccountId) {
        return visible == null || visible.contains(subAccountId);
    }
}
