package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SPOT-5 t_wip_record} 在制品记录（敞口 = 数量 × 完工比例）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class WipRecord extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户归属（必填）。 */
    public Integer subAccountId;

    /** 品种（必填）。 */
    public Long productId;

    /** 关联的生产计划（可空）。 */
    public Long productionPlanId;

    /** 在制品数量；必须 ≥ 0。 */
    public BigDecimal wipQty;

    /** 完工比例（0–1）；敞口按 wip_qty × completion_ratio 折算（R-SPOT-4）。 */
    public BigDecimal completionRatio;

    /** 推导出的方向（默认 BUY，因在制品已锁定原料价格风险）。 */
    public String exposureDirection;

    /** 记录日期。 */
    public LocalDate recordDate;

    /** 单位。 */
    public String unit;
}
