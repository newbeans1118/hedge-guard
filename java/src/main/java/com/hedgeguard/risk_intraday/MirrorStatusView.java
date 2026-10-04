package com.hedgeguard.risk_intraday;

import java.util.List;
import java.util.Set;

/**
 * {@code GET /risk/mirror-status} 的响应数据（{@code FR-RISK-12} / {@code FR-RISK-13} 视图）。
 *
 * <p>按 {@code §4.1.1} 的分页约定：本资源为<b>全局汇总视图</b>（非列表分页），
 * 不适用 {@code items/total/page/size} 信封；明细以 {@link #subAccounts} 数组呈现，
 * 同时提供 {@link #aggregate}（汇总 = 明细之和）。
 */
public final class MirrorStatusView {

    /** 组件就绪状态。 */
    public String readyState;

    /** 事件流水位。 */
    public long watermarkEventSeq;

    /** 参数版本。 */
    public long paramVersion;

    /** 快照时间（ISO-8601）。 */
    public String takenAt;

    /** 滞后毫秒（不可判定为 {@code -1}）。 */
    public long lagMillis;

    /** 是否明显滞后。 */
    public boolean lagging;

    /** 汇总指标。 */
    public SubAccountMetricsView aggregate;

    /** 各子账户指标（按调用者可见范围过滤）。 */
    public List<SubAccountMetricsView> subAccounts;

    /** 当前告警（按可见范围过滤；未处置者 {@code disposed=false}）。 */
    public List<RiskAlert> alerts;

    /** 强制平仓提示（只提示，不自动下单）。 */
    public List<ForceCloseHint> forceCloseHints;

    /** 当前受「限制新开仓」的子账户。 */
    public Set<Integer> suspendedSubAccounts;

    /** 评估时刻（{@code epochMilli}）。 */
    public long evaluatedAtEpochMilli;
}
