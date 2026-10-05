package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.RiskAlert;

import java.util.List;

/**
 * {@code TB-RISK-4 t_risk_alert} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-12}~{@code FR-RISK-14}）。
 *
 * <p>事中风险告警落库。本接口方法集与 {@code dao.impl.DmRiskAlertDao} 一一对应。
 */
public interface RiskAlertDao extends BaseDao<RiskAlert, Long> {

    /**
     * 按子账户查告警（{@code FR-RISK-19}），按告警时间倒序。
     *
     * @param subAccountId 子账户
     * @return 列表
     */
    List<RiskAlert> findBySubAccount(int subAccountId);
}
