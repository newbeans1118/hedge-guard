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
     * 取指定编号前缀下<b>字典序最大</b>的计划编号（{@code DATA_MODEL.md} §3.5「当日最大序号 + 1」取数）。
     *
     * <p><b>为何需要专用方法</b>：{@code t_hedge_plan} 无独立业务日列，业务日仅隐含于 {@code plan_no}
     * 前缀（{@code HP<yyyyMMdd>}）；通用 WHERE 原子（{@code =}/{@code IN}/{@code IS NULL}）表达不了
     * 前缀匹配，故在本方法内以 {@code plan_no LIKE ?}（前缀区间）直查 —— 走 {@code uk_hedge_plan_no}
     * 唯一索引区间扫描。序号<b>零填充定长</b> ⇒ 前缀内字典序最大即序号最大。
     *
     * <p><b>不使用</b> {@code SELECT MAX(...) FOR UPDATE}（{@code §3.5} 明令禁止：热点表锁竞争）。
     *
     * @param planNoPrefix 编号前缀（须为 {@code HP<yyyyMMdd>}，由 {@code BizNoGenerator.hedgePlanDayPrefix}
     *                     拼装；本层按占位符绑定，禁止拼接取值）
     * @return 命中的最大编号；当日无计划返回 {@code null}
     */
    String findMaxPlanNoByPrefix(String planNoPrefix);

    /**
     * 按主键 + 子账户权限过滤读取计划（{@code FR-PLAN-5}：无权限或不存在返回统一结果）。
     *
     * @param id    主键
     * @param scope 子账户过滤（空集 ⇒ 恒假）
     * @return 命中且可见的计划；不可见或不存在返回 {@code null}
     */
    HedgePlan findByIdScoped(long id, SubAccountScope scope);

    /**
     * 定向回写当前书面文件版本号（T-078 再平衡承接：TE-T078-001 修复窄口）。
     *
     * <p><b>为何需要专用窄口</b>：{@code current_doc_version} 与 {@code t_hedge_doc} 的版本链是
     * <b>同一事务面状态对</b>——{@code PlanService.generateHedgeDoc}（PLAN 侧再生成）以
     * {@code plan.currentDocVersion + 1} 推导下一版本号，ACCT 再平衡流程生成 v+1 文档后若不
     * 同步回写，PLAN 侧再生成将推导出已占用的版本号撞 {@code uk_hedge_doc_no}（永久失败，
     * {@code requireDocElements} 预校验不拦截、失败在 insert 时点爆出）。
     *
     * <p><b>为何不用全行 {@code updateById}</b>：再平衡流程仅持有主键与版本号语义（不持有计划
     * 行的其余业务列最新值），全行更新会以陈旧快照覆盖并发写入的其它列（丢更新）；定向单列
     * UPDATE 只触碰本状态对字段，并发面最小。
     *
     * <p>审计字段（{@code updated_by/updated_time}）随写推进，{@code row_version} +1
     * （{@code AbstractDmDao} 统一乐观锁纪律）。调用方须在<b>文档写入同一事务内</b>调用
     * （同笔提交同笔回滚）。
     *
     * @param planId     计划主键
     * @param version    新版本文档号（与 {@code t_hedge_doc.doc_version} 同值）
     * @param operatorId 操作人（审计字段）
     * @return 受影响行数（0 = 计划不存在）
     */
    int updateCurrentDocVersion(long planId, int version, long operatorId);
}
