package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.EffectivenessAssess;

import java.util.List;

/**
 * {@code TB-ACCT-2 t_effectiveness_assess} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 *
 * <p>评估记录不得事后修改（R-ACCT-2）⇒ 实现侧 {@code updateById} 显式拒绝；更正须新增记录
 * 并经 {@code correctedFromId} 引用原记录。
 */
public interface EffectivenessAssessDao extends BaseDao<EffectivenessAssess, Long> {

    /**
     * 取某套期关系某期间的有效评估记录（{@code uk_eff_assess_rel_period} 前两键 + 未被更正）；
     * 「每期至少一次评估」（FR-ACCT-2）与关账门禁③「有效性评估已完成」（R-ACCT-8）的取数入口。
     *
     * @param hedgeRelationId 套期关系
     * @param periodKey       会计期间
     * @return 命中的记录；不存在返回 {@code null}
     */
    EffectivenessAssess findActiveByRelationAndPeriod(long hedgeRelationId, String periodKey);

    /**
     * 按子账户 + 期间取评估记录（{@code idx_eff_assess_sub_period}；FR-ACCT-2、关账门禁 R-ACCT-8）。
     *
     * @param subAccountId 子账户
     * @param periodKey    会计期间
     * @return 列表（{@code assess_time} 升序，{@code id} 升序收官）
     */
    List<EffectivenessAssess> findBySubAccountAndPeriod(int subAccountId, String periodKey);

    /**
     * 按期间筛无效/需再平衡的结论（{@code idx_eff_assess_conclusion}；
     * FR-ACCT-2 验收 2、再平衡触发检查）。
     *
     * @param periodKey  会计期间
     * @param conclusion 结论（{@code EFFECTIVE} / {@code INEFFECTIVE} / {@code NEED_REBALANCE}）
     * @return 列表（{@code assess_time} 升序，{@code id} 升序收官）
     */
    List<EffectivenessAssess> findByPeriodAndConclusion(String periodKey, String conclusion);

    /**
     * 按期间筛"量化监测超阈值需复核"的记录（{@code idx_eff_assess_monitor}；
     * Q-7：仅提示，不判定有效性）。
     *
     * @param periodKey 会计期间
     * @return 列表（{@code assess_time} 升序，{@code id} 升序收官）
     */
    List<EffectivenessAssess> findMonitorFlaggedByPeriod(String periodKey);
}
