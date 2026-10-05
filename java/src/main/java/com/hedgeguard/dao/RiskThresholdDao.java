package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.RiskThreshold;

/**
 * {@code TB-RISK-1 t_risk_threshold} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-CONFIG-5}/{@code R-RISK-14}）。
 *
 * <p>集中配置的风控阈值。本接口方法集与 {@code dao.impl.DmRiskThresholdDao} 一一对应。
 */
public interface RiskThresholdDao extends BaseDao<RiskThreshold, Long> {

    /**
     * 按 Key + 作用域 + 子账户 取唯一一行（{@code uk_risk_threshold_key_scope}）。
     *
     * @param thresholdKey 阈值 Key
     * @param scopeType    作用域类型（{@code GLOBAL} / {@code SUB_ACCOUNT}）
     * @param subAccountId 子账户（{@code GLOBAL} 时为哨兵 0）
     * @return 行；无则 {@code null}
     */
    RiskThreshold findByKey(String thresholdKey, String scopeType, int subAccountId);
}
