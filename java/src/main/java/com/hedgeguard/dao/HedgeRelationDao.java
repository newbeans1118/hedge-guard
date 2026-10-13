package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.HedgeRelation;

import java.util.List;

/**
 * {@code TB-ACCT-1 t_hedge_relation} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface HedgeRelationDao extends BaseDao<HedgeRelation, Long> {

    /**
     * 按编号取套期关系（{@code uk_hedge_relation_no}；CAS24 自检①正式指定核对，FR-ACCT-14）。
     *
     * @param relationNo 套期关系编号
     * @return 命中的套期关系；不存在返回 {@code null}
     */
    HedgeRelation findByRelationNo(String relationNo);

    /**
     * 按套保计划取全部套期关系（{@code idx_hedge_relation_plan}；FR-ACCT-1、有效性评估入口）。
     *
     * @param hedgePlanId 套保计划
     * @return 列表（{@code designated_time} 升序，{@code id} 升序收官）
     */
    List<HedgeRelation> findByHedgePlan(long hedgePlanId);

    /**
     * 从敞口反查套期关系（{@code idx_hedge_relation_exposure}；追溯链 FR-AUDIT-1 正向终点延伸）。
     *
     * @param exposureId 实货敞口
     * @return 列表（{@code designated_time} 升序，{@code id} 升序收官）
     */
    List<HedgeRelation> findByExposure(long exposureId);

    /**
     * 取指定编号前缀下<b>字典序最大</b>的套期关系编号（{@code T-077} 取号；O-4 教训落地）。
     *
     * <p><b>为何不是「当日最大序号 + 1」</b>：{@code relation_no} 为<b>全系统唯一</b>
     * （{@code uk_hedge_relation_no}；TB-ACCT-1），且 {@code DATA_MODEL.md} §3.5 编号表
     * <b>未规定</b>该编号形态——前缀内取数须<b>跨全系统</b>而非限当日（T-137/T-141「当日最大 +1」
     * 判例的前提是编号按业务日重置；此处无该前提，照搬会产生跨日撞号）。
     * 序号零填充定长 ⇒ 前缀内字典序最大即序号最大；并发兜底靠 {@code uk_hedge_relation_no}
     * 唯一约束 + 服务层最多 3 次重试（{@code BizNoGenerator.retryOnUniqueConflict}）。
     *
     * @param relationNoPrefix 编号前缀（须为 {@code HR}，由 {@code AcctService} 常量拼装；
     *                         本层按占位符绑定，禁止拼接取值）
     * @return 命中的最大编号；无任何套期关系返回 {@code null}
     */
    String findMaxRelationNoByPrefix(String relationNoPrefix);

    /**
     * 按子账户集合取套期关系（{@code idx_hedge_relation_sub_status} 最左前缀；{@code T-077}
     * FR-ACCT-2 验收 4「某期间未评估提醒」的取数入口）。
     *
     * <p>集合语义由服务层判定（NFR-SEC-7）：{@code null} = 跨账户只读不过滤；
     * 空集调用侧已转恒假形态（服务层传 {@code List.of(-1)}）⇒ 返回空。
     *
     * @param subAccountIds 子账户集合（{@code null}=不过滤；其余按集合过滤）
     * @return 列表（{@code designated_time} 升序，{@code id} 升序收官）
     */
    List<HedgeRelation> findBySubAccounts(List<Integer> subAccountIds);
}
