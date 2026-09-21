package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SubProductWhitelist;

import java.util.List;

/**
 * {@code TB-PRODUCT-5 t_sub_product_whitelist} 数据访问（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SubProductWhitelistDao extends BaseDao<SubProductWhitelist, Long> {

    /**
     * 按品种取全部白名单行（含已停用，供品种停用时的级联处置与审计）。
     *
     * @param productId 品种
     * @return 列表（{@code status} 升序、{@code sub_account_id} 升序）
     */
    List<SubProductWhitelist> findByProduct(long productId);

    /**
     * 取某子账户的白名单全集（事前风控参数预加载热路径）。
     *
     * @param subAccountId 查询条件
     * @return 命中列表（无命中返回空列表）
     */
    List<SubProductWhitelist> findBySubAccount(int subAccountId);
}
