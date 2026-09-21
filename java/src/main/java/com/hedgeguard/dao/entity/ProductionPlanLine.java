package com.hedgeguard.dao.entity;

import java.math.BigDecimal;

/**
 * {@code TB-SPOT-4 t_production_plan_line} 生产计划行（产成品/原料明细，冗余子账户标识）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class ProductionPlanLine extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户归属（冗余自计划头，便于直接求和与权限过滤）。 */
    public Integer subAccountId;

    /** 所属计划。 */
    public Long productionPlanId;

    /** FINISHED（产成品）/ RAW_MATERIAL（所需原料）。 */
    public String lineType;

    /** 品种（必须已建立映射，否则不参与计算）。 */
    public Long productId;

    /** 数量；必须 > 0。 */
    public BigDecimal quantity;

    /** 单位。 */
    public String unit;

    /** 推导出的敞口方向：FINISHED → SELL；RAW_MATERIAL → BUY。 */
    public String exposureDirection;
}
