package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.Exposure;

import java.util.List;

/**
 * {@code TB-SPOT-8 t_exposure} 数据访问（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface ExposureDao extends BaseDao<Exposure, Long> {

    /**
     * 取待套保敞口（计划编制入口，idx_exposure_sub_status）。
     *
     * @param subAccountId 查询条件
     * @return 命中列表（无命中返回空列表）
     */
    List<Exposure> findPendingHedge(int subAccountId);
}
