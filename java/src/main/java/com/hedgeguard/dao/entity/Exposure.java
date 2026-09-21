package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SPOT-8 t_exposure} 实货敞口（根实体；PLAN 的唯一输入，买卖不抵销）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class Exposure extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户归属（必填）。 */
    public Integer subAccountId;

    /** 品种。已映射（unmapped_flag='N'）时恒为真实品种（> 0），须先完成映射（TB-PRODUCT-4）；未映射（unmapped_flag='Y'）时取未映射哨兵值 0（外键指向 t_product 保留系统行 id=0，见 TB-PRODUCT-1 备注）并进"未映射清单"（FR-SPOT-14 验收 2）。本列恒非空 → 其作为 uk_exposure_key 组成部分时唯一性不依赖 DM8 对 NULL 的处理（与 DC-020 / G-5 同向，DC-041）；表达式见本表备注。 */
    public Long productId;

    /** 现货品类编码（DC-041 补列；经 TB-PRODUCT-4 映射到品种）。本表恒非空——未映射行亦必填，它正是"哪个品类未映射"的载体（FR-PRODUCT-10 验收 2）；取值来自来源单据（TB-SPOT-1 / TB-SPOT-2 的 spot_category_code）。已纳入 uk_exposure_key（使"同一品类未映射行各占一行"）——该取舍存在待确认项，见本表备注。 */
    public String spotCategoryCode;

    /** 买入敞口（BUY）＝未来需买入，担心价格上涨；卖出敞口（SELL）＝未来需卖出，担心价格下跌。 */
    public String direction;

    /** 敞口计算口径（Q-17 已确认默认组合口径、含生产计划）：COMBINED（默认）/ CONTRACT_UNEXECUTED / INVENTORY_HELD / PRODUCTION_PLAN。 */
    public String calcMode;

    /** 分量①已签合同未执行量（Q-17：快照必须存三类分量，不得只存合计）。 */
    public BigDecimal contractUnexecutedQty;

    /** 分量②库存持有量。 */
    public BigDecimal inventoryQty;

    /** 分量③生产计划需求量。 */
    public BigDecimal productionPlanQty;

    /** 本快照启用的分量集合（快照配置：来自 exposure.components，可按子账户/品种覆盖）；被关闭的分量列恒为 0。 */
    public String componentsEnabled;

    /** 快照标识（供事前风控下发引用与审计复现；CMD_UPDATE_EXPOSURE.snapshot_id）。 */
    public String snapshotId;

    /** 快照时点（Q-3 已确认：校验记录须标注所用快照时点）。 */
    public LocalDateTime snapshotTime;

    /** 组合口径的构成（如 CONTRACT_UNEXECUTED+INVENTORY_HELD；calc_mode='COMBINED' 时必填，R-SPOT-4）。 */
    public String combinedComponents;

    /** 计算基准日期（审计复现口径，FR-SPOT-14 验收 4）。 */
    public LocalDate asOfDate;

    /** 计算时点（FR-SPOT-14 验收 4）。 */
    public LocalDateTime calcTime;

    /** 敞口数量（同口径换算后）；必须 ≥ 0。 */
    public BigDecimal exposureQty;

    /** 数量单位（同口径后的单位）。 */
    public String unit;

    /** 敞口金额（只能来自已录入现货价；缺价时为 NULL 并标注，R-SPOT-4）。 */
    public BigDecimal exposureAmount;

    /** 是否因缺价导致金额无法计算（'Y' 时 exposure_amount 必须为 NULL）。 */
    public String priceMissingFlag;

    /** 估值所用现货价的实际时点（Q-12 + R-SPOT-4 + FR-ACCT-5，v1.2 补列，PI-03/DC-026）：敞口估值沿用最近一期价时必填；与 t_hedge_effect.price_as_of 同名同义。 */
    public LocalDate priceAsOf;

    /** 是否使用了"非当日价"（Q-12，v1.2 补列）：'Y' 时 price_as_of < as_of_date，结果必须显式标记"非当日价"；不得无标注静默沿用。 */
    public String nonCurrentPriceFlag;

    /** 三分量沿用最近一期的分量时点（Q-17 / M1 #17，v1.2 补列）：分量缺失而沿用历史分量时必填，与"非当期分量"标记成对出现。 */
    public LocalDateTime snapshotAsOf;

    /** 已被已批准计划引用的数量（驱动状态流转）。 */
    public BigDecimal coveredQty;

    /** PENDING_HEDGE（待套保）/ PARTIALLY_COVERED（部分覆盖）/ COVERED（已覆盖）/ CLOSED（已关闭）。 */
    public String status;

    /** 是否属于"未映射"数据。与 product_id 双向联动（⇔）：'Y' ⇔ product_id = 0（未映射哨兵值；进"未映射清单"）；'N' ⇔ product_id > 0。由具名 CHECK ck_exposure_product_unmapped 保证（表达式见本表备注，DC-041）。 */
    public String unmappedFlag;
}
