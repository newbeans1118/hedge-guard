package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SubInstrumentQuota;

import java.util.List;

/**
 * {@code TB-PRODUCT-8 t_sub_instrument_quota} 数据访问（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SubInstrumentQuotaDao extends BaseDao<SubInstrumentQuota, Long> {

    /**
     * 取某子账户的合约级限额全集（层② 判定与三层限额全景展示）。
     *
     * @param subAccountId 查询条件
     * @return 命中列表（无命中返回空列表）
     */
    List<SubInstrumentQuota> findBySubAccount(int subAccountId);
}
