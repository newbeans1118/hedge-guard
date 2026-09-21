package com.hedgeguard.dao.entity;

/**
 * {@code TB-PRODUCT-4 t_product_spot_category} 品种-现货品类映射（期现一体唯一入口）（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class ProductSpotCategory extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 现货品类编码。 */
    public String spotCategoryCode;

    /** 现货品类名称。 */
    public String spotCategoryName;

    /** 映射到的品种。 */
    public Long productId;

    /** PURCHASE（采购侧）/ SALES（销售侧）/ BOTH（通用）。 */
    public String mappingDirection;

    /** 是否主映射；同一品类（+方向）至多一个主映射（应用层 + 唯一索引保证）。 */
    public String isPrimary;

    /** ENABLED / DISABLED。 */
    public String status;

    /** 映射变更说明（变更不得影响已成立的套保计划，FR-PRODUCT-10）。 */
    public String changeDesc;
}
