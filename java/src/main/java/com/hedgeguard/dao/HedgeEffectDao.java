package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.HedgeEffect;

import java.util.List;

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

    /**
     * 按子账户 + 期间取套保效果行（{@code idx_hedge_effect_sub_period} 承载；
     * FR-ACCT-5 同屏展示与「汇总 = 明细之和」勾稽的取数面，{@code NFR-USE-5}；
     * {@code T-080} 交付——T-076 复核 O-3「有索引无 DAO 方法」的点名承接）。
     *
     * @param subAccountId 子账户
     * @param periodKey    会计期间
     * @return 行（{@code calc_time} 升序，{@code id} 升序收官；可能为空）
     */
    List<HedgeEffect> findBySubAccountAndPeriod(int subAccountId, String periodKey);
}
