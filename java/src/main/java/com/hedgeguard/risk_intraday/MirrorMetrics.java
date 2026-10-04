package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.ReplayProgress;
import com.hedgeguard.bridge.TradingBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 镜像状态派生指标集（内存计算产物，{@code FR-RISK-12}/{@code FR-RISK-13}）。
 *
 * <p>由 {@link MirrorMetricsCalculator} 从 {@link TradingBridge#mirror()} 的
 * <b>不可变快照</b>纯函数式算出：<b>不查库、不查 C++</b>（{@code R-RISK-10}、
 * {@code FR-RISK-13} 验收 4）；计算过程<b>不修改</b> C++ 内存状态（只读，{@code FR-RISK-12} 验收 4）。
 *
 * <p>{@link #aggregate} 为汇总行（{@code subAccountId=0}），其持仓 / 浮动盈亏 / 资金
 * <b>等于</b> {@link #subAccounts} 明细之和（{@code FR-RISK-12} 验收 2）。
 */
public final class MirrorMetrics {

    /** 组件就绪状态。 */
    public final ReplayProgress.State readyState;

    /** 事件流水位。 */
    public final long watermarkEventSeq;

    /** 参数版本。 */
    public final long paramVersion;

    /** 快照时间（ISO-8601；可能为 {@code null}）。 */
    public final String takenAt;

    /** 快照滞后毫秒数（{@code takenAt} 不可解析时为 {@code -1}）。 */
    public final long lagMillis;

    /** 是否判定为明显滞后（{@code lagMillis > 阈值}）。 */
    public final boolean lagging;

    /** 各子账户指标（不含汇总行）。 */
    public final List<SubAccountRiskMetrics> subAccounts;

    /** 汇总行（{@code subAccountId=0}）。 */
    public final SubAccountRiskMetrics aggregate;

    /**
     * 构造。
     *
     * @param readyState      组件状态
     * @param watermarkEventSeq 事件流水位
     * @param paramVersion    参数版本
     * @param takenAt         快照时间
     * @param lagMillis       滞后毫秒（不可解析为 {@code -1}）
     * @param lagging         是否明显滞后
     * @param subAccounts     子账户指标
     * @param aggregate       汇总
     */
    public MirrorMetrics(ReplayProgress.State readyState, long watermarkEventSeq, long paramVersion,
                         String takenAt, long lagMillis, boolean lagging,
                         List<SubAccountRiskMetrics> subAccounts, SubAccountRiskMetrics aggregate) {
        this.readyState = readyState;
        this.watermarkEventSeq = watermarkEventSeq;
        this.paramVersion = paramVersion;
        this.takenAt = takenAt;
        this.lagMillis = lagMillis;
        this.lagging = lagging;
        this.subAccounts = Collections.unmodifiableList(new ArrayList<>(subAccounts));
        this.aggregate = aggregate;
    }

    /**
     * 取指定子账户的指标。
     *
     * @param subAccountId 子账户
     * @return 指标；不存在返回 {@code null}
     */
    public SubAccountRiskMetrics find(int subAccountId) {
        for (SubAccountRiskMetrics m : subAccounts) {
            if (m.subAccountId == subAccountId) {
                return m;
            }
        }
        return null;
    }
}
