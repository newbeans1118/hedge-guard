package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-8 t_hedge_effect} 套保效果（双侧盈亏）（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>实货盈亏 + 衍生品盈亏 + 净效果 + 未对冲部分（FR-ACCT-5、FR-ACCT-11）。
 * {@code spot_pnl_*} 与 {@code derivative_pnl_*} <b>成对存在于同一行</b>——
 * 「两侧盈亏必须同时呈现」（R-ACCT-4，17 号文）在表结构层的实现：没有"只有衍生品盈亏"的行。
 */
public final class HedgeEffect extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 会计期间。 */
    public String periodKey;

    /** 套保计划（按计划归集；子账户级汇总时可为 null）。 */
    public Long hedgePlanId;

    /** 实货敞口。 */
    public Long exposureId;

    /** 实货盈亏（已实现）。 */
    public BigDecimal spotPnlRealized;

    /** 实货盈亏（未实现）。 */
    public BigDecimal spotPnlUnrealized;

    /** 实货盈亏合计。 */
    public BigDecimal spotPnlTotal;

    /** 衍生品盈亏（已实现-平仓）。 */
    public BigDecimal derivativePnlRealized;

    /** 衍生品盈亏（未实现-浮动）。 */
    public BigDecimal derivativePnlUnrealized;

    /** 衍生品盈亏合计。 */
    public BigDecimal derivativePnlTotal;

    /** 综合评判净效果（两侧合计）。 */
    public BigDecimal netEffect;

    /** 未完全对冲的敞口数量（须明确展示）。 */
    public BigDecimal unhedgedQty;

    /** 未对冲敞口金额。 */
    public BigDecimal unhedgedAmount;

    /** 实际套保比率。 */
    public BigDecimal hedgedRatioActual;

    /** 实货盈亏因缺价无法计算（'Y' 时实货列必须为 NULL 并填 missingDesc，R-ACCT-9）。 */
    public String spotPriceMissing;

    /** 缺失的品种与日期清单（Q-12）。 */
    public String missingDesc;

    /** 实货盈亏计算所用现货价的实际时点（Q-12：沿用最近一期时必带）。 */
    public LocalDate priceAsOf;

    /** 是否使用了"非当日价"（Q-12：结果须标记，不得无标注静默替代）。 */
    public String nonCurrentPriceFlag;

    /** 计算时间。 */
    public LocalDateTime calcTime;
}
