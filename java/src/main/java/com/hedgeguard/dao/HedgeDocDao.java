package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.HedgeDoc;

/**
 * {@code TB-PLAN-4 t_hedge_doc} 数据访问（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface HedgeDocDao extends BaseDao<HedgeDoc, Long> {

    /**
     * 取计划当前生效的书面文件版本（uk_hedge_doc_current 条件唯一）。
     *
     * @param hedgePlanId 查询条件
     * @return 命中的实体；不存在返回 {@code null}
     */
    HedgeDoc findCurrent(long hedgePlanId);
}
