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

    /**
     * 标记评估记录「已被更正」（{@code is_corrected = 'Y'}；{@code T-077} 新增，
     * 承接 FR-ACCT-2 验收 3「更正须新增记录并留痕」的更正链）。
     *
     * <p><b>为何不用 {@code updateById}</b>：通用更新通道已显式拒绝（R-ACCT-2「评估记录不得事后
     * 修改」，防借编辑入口静默改写判定结果）。本方法是<b>唯一</b>可改动 {@code is_corrected}
     * 标记位的专用通道——SQL 固定形如 {@code SET is_corrected = 'Y'}，<b>不携带</b>任何业务列
     * （结论 / 观察点 / 明细均不可触碰）；带乐观锁（{@code row_version}），并发更正第二次必 0 行。
     *
     * @param id         评估记录
     * @param operatorId 操作人（{@code updated_by} 留痕）
     * @return 影响行数；{@code 0} = 已被更正或并发冲突
     */
    int markCorrected(long id, long operatorId);

    /**
     * 回写更正来源（{@code corrected_from_id}；仅更正链新增记录需要，{@code T-077}）。
     *
     * <p>与 {@link #markCorrected} 同理为专用窄口通道：仅 SET {@code corrected_from_id}，
     * 不触碰业务列。插入时无法一并写入（候选记录 ID 由自增回填后才可知原记录 ID 已知，
     * 但更正时序=先释放原记录唯一槽位再插入新记录，原记录 ID 在插入前即已知——
     * 为保持「插入零特殊分支」与 Dm 实现最小增量，拆为插入后窄口回写）。
     *
     * @param id              新评估记录
     * @param correctedFromId 被更正的原记录
     * @param operatorId      操作人
     * @return 影响行数（正常为 1）
     */
    int updateCorrectedFrom(long id, long correctedFromId, long operatorId);
}
