package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradePlan;
import java.util.List;

/**
 * {@code TB-PLAN-6 t_trade_plan} 数据访问（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface TradePlanDao extends BaseDao<TradePlan, Long> {

    /**
     * 按套保计划取交易计划（{@code FR-PLAN-5} 详情、{@code FR-AUDIT-1} 追溯）。
     *
     * @param hedgePlanId 套保计划
     * @return 列表（{@code exec_deadline} 升序，交易岗"待执行"默认序）
     */
    List<TradePlan> findByHedgePlan(long hedgePlanId);

    /**
     * 按套保计划 + 子账户权限过滤取交易计划（{@code NFR-SEC-7}／{@code FR-PLAN-15} 验收 4；
     * {@code ARCHITECTURE.md} §8.2「DAO 层统一注入」）。
     *
     * <p>由服务层构造 {@link SubAccountScope}（含哨兵判定与空集恒假）后传入；本层<b>不解析权限语义</b>，
     * 只把 {@code scope.appendTo("hedge_plan_id = ?")} 的条件与既有条件合并。空集合 ⇒ 恒假条件
     * （{@code sub_account_id IN (-1)}，返回空集），<b>绝不</b>退化为不过滤（{@code NFR-SEC-7} 安全默认）。
     *
     * @param hedgePlanId 套保计划
     * @param scope       子账户过滤（不得为跨账户只读哨兵——该场景服务层走不加过滤路径）
     * @return 命中的交易计划列表（{@code exec_deadline} 升序，与 {@link #findByHedgePlan(long)} 同序）
     */
    List<TradePlan> findByHedgePlanScoped(long hedgePlanId, SubAccountScope scope);

    /**
     * 按子账户权限过滤后的分页查询（{@code NFR-SEC-7}／{@code FR-PLAN-15} 验收 4；
     * {@code ARCHITECTURE.md} §8.2「DAO 层统一注入」）。
     *
     * <p>由服务层构造 {@link SubAccountScope}（含哨兵判定与空集恒假）后传入；本层<b>不解析权限语义</b>，
     * 只把 scope 的条件与既有条件合并（经 {@code AbstractDmDao.findPageScoped}，与
     * {@code HedgePlanDao.findPageScoped} 同构）。空集合 ⇒ 恒假条件（{@code sub_account_id IN (-1)}，
     * 返回空集），<b>绝不</b>退化为不过滤（{@code NFR-SEC-7} 安全默认）。
     *
     * <p><b>计数口径</b>：{@code total} 为<b>叠加 scope 与 {@code extraWhere} 过滤后</b>的总行数
     * （分页实现独立执行 {@code COUNT}），【不得】为全表行数——这是分页 × 隔离组合最易错处。
     *
     * @param query      分页 / 排序
     * @param scope      子账户过滤（不得为跨账户只读哨兵——该场景服务层走 <b>不加过滤</b> 路径）
     * @param extraWhere 附加条件（可为 {@code null}；调用方须以占位符传值，禁止拼接取值）
     * @param extraArgs  附加条件参数
     * @return 分页结果
     */
    PageResult<TradePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                         String extraWhere, List<Object> extraArgs);

    /**
     * 按交易计划编号查交易计划（uk_trade_plan_no）。
     *
     * @param tradePlanNo 查询条件
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradePlan findByTradePlanNo(String tradePlanNo);
}
