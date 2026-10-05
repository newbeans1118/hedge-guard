package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.RiskDisposal;

import java.util.List;

/**
 * {@code TB-RISK-5 t_risk_disposal} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-15}/{@code FR-RISK-16}）。
 *
 * <p>告警处置动作。本接口方法集与 {@code dao.impl.DmRiskDisposalDao} 一一对应。
 */
public interface RiskDisposalDao extends BaseDao<RiskDisposal, Long> {

    /**
     * 取该子账户「生效中」的处置（{@code releasedTime IS NULL}）。
     *
     * @param subAccountId 子账户
     * @return 列表（按主键倒序）
     */
    List<RiskDisposal> findActiveBySubAccount(int subAccountId);
}
