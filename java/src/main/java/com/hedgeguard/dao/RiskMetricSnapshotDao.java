package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.RiskMetricSnapshot;

import java.util.List;

/**
 * {@code TB-RISK-10 t_risk_metric_snapshot} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-12}/{@code FR-RISK-13}）。
 *
 * <p>事中指标时点值。本接口方法集与 {@code dao.impl.DmRiskMetricSnapshotDao} 一一对应。
 */
public interface RiskMetricSnapshotDao extends BaseDao<RiskMetricSnapshot, Long> {

    /**
     * 按子账户查指标序列（{@code FR-RISK-19} 趋势分析），按快照时间倒序。
     *
     * @param subAccountId 子账户
     * @return 列表
     */
    List<RiskMetricSnapshot> findBySubAccount(int subAccountId);
}
