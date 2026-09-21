package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SPOT-6 t_inventory_ledger} 库存台账（按记录日期成序列，不覆盖历史）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class InventoryLedger extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户归属（必填）。 */
    public Integer subAccountId;

    /** 品种。 */
    public Long productId;

    /** RAW_MATERIAL（原材料）/ FINISHED（产成品）/ WIP（在制品）。 */
    public String inventoryType;

    /** 库存数量；允许为负但须告警并要求二次确认（FR-SPOT-9 验收 2）。 */
    public BigDecimal quantity;

    /** 负值是否已经二次确认；quantity < 0 时必须为 'Y'（ck_inventory_negative_confirmed）。 */
    public String negativeConfirmed;

    /** 单位。 */
    public String unit;

    /** 计价（可选，单价）。 */
    public BigDecimal valuation;

    /** 记录日期（形成序列，不覆盖历史）。 */
    public LocalDate recordDate;

    /** 推导方向：RAW_MATERIAL/WIP → BUY；FINISHED → SELL。 */
    public String exposureDirection;

    /** 备注（如账实不符说明）。 */
    public String remark;
}
