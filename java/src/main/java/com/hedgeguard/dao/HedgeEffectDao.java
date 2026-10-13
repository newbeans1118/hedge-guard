package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.HedgeEffect;

/**
 * {@code TB-ACCT-8 t_hedge_effect} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}（幂等键 {@code uk_hedge_effect_key} 供重复生成时的
 * 幂等或明确提示，FR-ACCT-5 口径单一 R-ACCT-12）；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface HedgeEffectDao extends BaseDao<HedgeEffect, Long> {

    /**
     * 按幂等键定位套保效果行（{@code uk_hedge_effect_key}；重复计算时幂等或明确提示）。
     *
     * @param subAccountId 子账户
     * @param periodKey    会计期间
     * @param hedgePlanId  套保计划（可为 {@code null} = 子账户级汇总行）
     * @param exposureId   实货敞口（可为 {@code null}）
     * @return 命中的行；不存在返回 {@code null}
     */
    HedgeEffect findByKey(int subAccountId, String periodKey, Long hedgePlanId, Long exposureId);
}
