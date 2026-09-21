package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ProductUnit;

/**
 * {@code TB-PRODUCT-2 t_product_unit} 数据访问（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface ProductUnitDao extends BaseDao<ProductUnit, Long> {

    /**
     * 取<b>当前生效</b>的换算关系（{@code effective_from <= 基准日}、{@code status='ENABLED'}、
     * 同日多份时取 {@code effective_from} 最大者）。
     *
     * <p>换算关系缺失的品种不得启用（{@code R-PRODUCT-6}）；变更不追溯重算历史
     * （{@code FR-PRODUCT-13}），故本方法固定按业务日期取"当时生效"的那一份。
     *
     * @param productId 品种
     * @param bizDate   业务日期（基准日）
     * @return 生效换算关系；无则返回 {@code null}（调用方须按"缺换算关系"拒绝，不得降级为 1:1）
     */
    ProductUnit findEffective(long productId, java.time.LocalDate bizDate);
}
