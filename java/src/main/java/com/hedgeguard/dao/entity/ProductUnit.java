package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-PRODUCT-2 t_product_unit} 计量单位与换算关系（换算关系缺失的品种不得启用）（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class ProductUnit extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 品种。 */
    public Long productId;

    /** 现货计量单位（TON / KG / LOT / SHEET / 自定义标签）。 */
    public String spotUnit;

    /** 期货交易单位（如 LOT）。 */
    public String futuresUnit;

    /** 换算系数：1 futures_unit = convert_factor spot_unit；必须 > 0（ck_product_unit_factor_positive）。 */
    public BigDecimal convertFactor;

    /** 换算关系文字说明（如"1 手 = 5 吨"）。 */
    public String convertDesc;

    /** ENABLED / DISABLED。 */
    public String status;

    /** 生效起始日（换算关系变更不追溯重算历史，R-PRODUCT-6）。 */
    public LocalDate effectiveFrom;
}
