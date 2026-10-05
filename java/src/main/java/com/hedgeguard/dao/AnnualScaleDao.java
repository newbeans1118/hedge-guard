package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.AnnualScale;

/**
 * {@code TB-RISK-11 t_annual_scale} 数据访问（{@code DATA_MODEL.md} §2.8；{@code Q-1}）。
 *
 * <p>规模红线的分子/分母基数。混合表（A/B 行由 §4.3 具名 CHECK 分派）。
 * 本接口方法集与 {@code dao.impl.DmAnnualScaleDao} 一一对应；如需扩签名请连带实现一并提交。
 */
public interface AnnualScaleDao extends BaseDao<AnnualScale, Long> {

    /**
     * 按 作用域 + 子账户 + 品种 + 年度 取唯一一行（{@code uk_annual_scale}）。
     *
     * @param scopeType    作用域类型（{@code SUB_ACCOUNT} / {@code TOTAL_ACCOUNT}）
     * @param subAccountId 子账户（{@code TOTAL_ACCOUNT} 时为哨兵 0）
     * @param productId    品种
     * @param scaleYear    年度
     * @return 行；无则 {@code null}
     */
    AnnualScale findByScopeAndProduct(String scopeType, int subAccountId, long productId, int scaleYear);
}
