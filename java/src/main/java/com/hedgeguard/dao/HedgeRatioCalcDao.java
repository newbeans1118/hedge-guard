package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.HedgeRatioCalc;

/**
 * {@code TB-PLAN-3 t_hedge_ratio_calc} 数据访问（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface HedgeRatioCalcDao extends BaseDao<HedgeRatioCalc, Long> {
}
