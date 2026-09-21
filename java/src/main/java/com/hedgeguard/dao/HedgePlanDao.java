package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.HedgePlan;

import java.util.List;

/**
 * {@code TB-PLAN-1 t_hedge_plan} 数据访问（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 *
 * <p><b>可物理删除的例外</b>（{@code DATA_MODEL.md} §0.3）：仅 DRAFT 态套保计划 允许物理 DELETE；
 * 其余状态一律经状态流转（{@code R-SPOT-9} / {@code R-PLAN-6}），故删除入口由服务层严控。
 */
public interface HedgePlanDao extends DeletableDao<HedgePlan, Long> {

    /**
     * 按计划编号查计划（uk_hedge_plan_no；交易指令按编号回溯）。
     *
     * @param planNo 查询条件
     * @return 命中的实体；不存在返回 {@code null}
     */
    HedgePlan findByPlanNo(String planNo);

    /**
     * 按子账户权限过滤后的分页查询（{@code NFR-SEC-7}「DAO 统一注入」；{@code ARCHITECTURE.md} §8.2）。
     *
     * <p>由服务层构造 {@link SubAccountScope}（含哨兵判定与空集恒假）后传入；本层不解析权限语义，
     * 只把 scope 的条件与既有条件合并。空集合 ⇒ 恒假条件（返回空集），<b>绝不</b>退化为不过滤。
     *
     * @param query      分页 / 排序
     * @param scope      子账户过滤（不得为跨账户只读哨兵——该场景服务层走 <b>不加过滤</b> 路径）
     * @param extraWhere 附加条件（可为 {@code null}）
     * @param extraArgs  附加条件参数
     * @return 分页结果
     */
    PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                         String extraWhere, List<Object> extraArgs);

    /**
     * 按主键 + 子账户权限过滤读取计划（{@code FR-PLAN-5}：无权限或不存在返回统一结果）。
     *
     * @param id    主键
     * @param scope 子账户过滤（空集 ⇒ 恒假）
     * @return 命中且可见的计划；不可见或不存在返回 {@code null}
     */
    HedgePlan findByIdScoped(long id, SubAccountScope scope);
}
