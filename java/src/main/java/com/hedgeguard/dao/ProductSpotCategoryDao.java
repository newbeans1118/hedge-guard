package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ProductSpotCategory;
import java.util.List;

/**
 * {@code TB-PRODUCT-4 t_product_spot_category} 数据访问（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface ProductSpotCategoryDao extends BaseDao<ProductSpotCategory, Long> {

    /**
     * 按品种取全部现货品类映射（含停用；品种停用前置校验与页面展示）。
     *
     * @param productId 品种
     * @return 列表（{@code status} 升序、{@code spot_category_code} 升序）
     */
    List<ProductSpotCategory> findByProduct(long productId);

    /**
     * 取现货品类的<b>主映射</b>（{@code is_primary='Y'}）；无主映射时退化为唯一启用映射。
     *
     * <p>{@code FR-PRODUCT-10} 验收 3：一个现货品类映射到两个品种时须指定主映射，
     * 故有主映射即须以主映射为准，<b>不得</b>任取一行。
     *
     * @param spotCategoryCode 现货品类编码
     * @param mappingDirection 方向（{@code PURCHASE} / {@code SALES}；{@code BOTH} 视为通用）
     * @return 映射；无映射返回 {@code null}（该品类进"未映射清单"）
     */
    ProductSpotCategory findPrimaryMapping(String spotCategoryCode, String mappingDirection);
}
