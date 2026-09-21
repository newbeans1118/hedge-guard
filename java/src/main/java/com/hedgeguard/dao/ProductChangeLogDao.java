package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ProductChangeLog;

import java.util.List;

/**
 * {@code TB-PRODUCT-7 t_product_change_log} 数据访问（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface ProductChangeLogDao extends BaseDao<ProductChangeLog, Long> {

    /**
     * 按品种倒序查变更历史（FR-PRODUCT-14 验收 1）。
     *
     * @param productId 查询条件
     * @return 命中列表（无命中返回空列表）
     */
    List<ProductChangeLog> findByProduct(long productId);
}
