package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SettleCycle;

/**
 * {@code TB-SETTLE-5 t_settle_cycle} 数据访问（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link com.hedgeguard.dao.SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SettleCycleDao extends BaseDao<SettleCycle, Long> {

    // 本表仅需基类通用增删改查（{@link BaseDao}）。
}
