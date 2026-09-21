package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ProductionPlan;

/**
 * {@code TB-SPOT-3 t_production_plan} 数据访问（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 *
 * <p><b>可物理删除的例外</b>（{@code DATA_MODEL.md} §0.3）：仅 DRAFT 态生产计划 允许物理 DELETE；
 * 其余状态一律经状态流转（{@code R-SPOT-9} / {@code R-PLAN-6}），故删除入口由服务层严控。
 */
public interface ProductionPlanDao extends DeletableDao<ProductionPlan, Long> {
}
