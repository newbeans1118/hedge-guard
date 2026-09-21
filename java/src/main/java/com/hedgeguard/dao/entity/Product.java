package com.hedgeguard.dao.entity;

/**
 * {@code TB-PRODUCT-1 t_product} 品种（全局共享档案；编码一经使用不得修改）（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class Product extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 品种编码，全系统唯一、一经使用不得修改。 */
    public String productCode;

    /** 品种名称（同一子账户内不得重复由 TB-PRODUCT-5 + 应用层保证）。 */
    public String productName;

    /** 品种分类：COMMODITY（商品类 → 90% 红线）/ TRADE（贸易类 → 80% 红线）。 */
    public String category;

    /** 是否纳入分类管理（8 号文）；'Y' 时 category 必须非空（ck_product_category_managed）。 */
    public String categoryManaged;

    /** ENABLED / DISABLED（无 DELETED：品种不允许物理删除）。 */
    public String status;

    /** 是否境内场内（必须恒为 'Y'：境外/场外永久排除，PROJECT.md §4.2）。 */
    public String isDomesticExchange;

    /** 计价币种（本项目仅 CNY）。 */
    public String currency;

    /** 备注。 */
    public String remark;

    /** 停用原因（停用时必填）。 */
    public String disabledReason;
}
