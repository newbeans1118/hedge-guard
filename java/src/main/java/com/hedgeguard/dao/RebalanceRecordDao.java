package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.RebalanceRecord;

import java.util.List;

/**
 * {@code TB-ACCT-3 t_rebalance_record} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 *
 * <p>再平衡记录不得事后修改（R-ACCT-3）⇒ 实现侧 {@code updateById} 显式拒绝。
 */
public interface RebalanceRecordDao extends BaseDao<RebalanceRecord, Long> {

    /**
     * 按编号取再平衡记录（{@code uk_rebalance_no}）。
     *
     * @param rebalanceNo 再平衡编号
     * @return 命中的记录；不存在返回 {@code null}
     */
    RebalanceRecord findByRebalanceNo(String rebalanceNo);

    /**
     * 按套期关系取再平衡史（{@code idx_rebalance_relation_time}；FR-ACCT-3、CAS24 自检④）。
     *
     * @param hedgeRelationId 套期关系
     * @return 列表（{@code rebalance_time} 升序，{@code id} 升序收官）
     */
    List<RebalanceRecord> findByHedgeRelation(long hedgeRelationId);

    /**
     * 按触发类型 + 所在侧统计取数（{@code idx_rebalance_trigger}；
     * Q-8：核验"敞口侧与工具侧各设一项"是否落实）。
     *
     * @param triggerType 触发类型
     * @param triggerSide 触发条件所在侧
     * @return 列表（{@code rebalance_time} 升序，{@code id} 升序收官）
     */
    List<RebalanceRecord> findByTrigger(String triggerType, String triggerSide);

    /**
     * 取指定编号前缀下<b>字典序最大</b>的再平衡编号（{@code T-078} 取号；O-4 教训同法）。
     *
     * <p><b>为何不是「当日最大序号 + 1」</b>：{@code rebalance_no} 为<b>全系统唯一</b>
     * （{@code uk_rebalance_no}；TB-ACCT-3），且 {@code DATA_MODEL.md} §3.5 编号表<b>未列</b>
     * 该编号——前缀内取数须<b>跨全系统</b>而非限当日（T-137/T-141「当日最大 +1」判例的前提是
     * 编号按业务日重置；此处无该前提，照搬会产生跨日撞号）。序号零填充定长 ⇒ 前缀内字典序最大
     * 即序号最大；并发兜底靠唯一约束 + 服务层最多 3 次重试
     * （{@code BizNoGenerator.retryOnUniqueConflict}）。
     *
     * @param rebalanceNoPrefix 编号前缀（须为 {@code RB}，由 {@code AcctService} 常量拼装；
     *                          本层按占位符绑定，禁止拼接取值）
     * @return 命中的最大编号；无任何再平衡记录返回 {@code null}
     */
    String findMaxRebalanceNoByPrefix(String rebalanceNoPrefix);
}
