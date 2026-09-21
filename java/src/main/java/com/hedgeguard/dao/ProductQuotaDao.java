package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ProductQuota;

import java.util.List;

/**
 * {@code TB-PRODUCT-6 t_product_quota} 数据访问（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface ProductQuotaDao extends BaseDao<ProductQuota, Long> {

    /**
     * 按层级与作用域取额度行（层① 传真实子账户；层③ 传哨兵值 0）。
     *
     * @param limitLayer 查询条件
     * @param subAccountId 查询条件
     * @return 命中列表（无命中返回空列表）
     */
    List<ProductQuota> findByLayerAndScope(String limitLayer, int subAccountId);
}
