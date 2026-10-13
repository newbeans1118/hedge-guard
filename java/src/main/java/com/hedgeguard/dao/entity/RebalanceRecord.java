package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-3 t_rebalance_record} 再平衡记录（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>再平衡记录<b>不得事后修改</b>（{@code ACCT.md} §2 数据字典 / R-ACCT-3）；
 * 须重新生成书面文件与正式指定，不得只改数字。
 */
public final class RebalanceRecord extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 套期关系。 */
    public Long hedgeRelationId;

    /** 再平衡编号。 */
    public String rebalanceNo;

    /** 触发类型：{@code EXPOSURE_DELTA} / {@code RATIO_DEVIATION} / {@code PLAN_REVISED} / {@code EXPOSURE_GONE}。 */
    public String triggerType;

    /** 触发条件所在侧：{@code EXPOSURE_SIDE} / {@code INSTRUMENT_SIDE}。 */
    public String triggerSide;

    /** 触发原因描述（含量化判定值）。 */
    public String triggerReason;

    /** 敞口变化幅度（触发判定值）。 */
    public BigDecimal triggerExposureDelta;

    /** 比率偏离（触发判定值）。 */
    public BigDecimal triggerRatioDeviation;

    /** 触发阈值（快照，来自 CFG-16）。 */
    public BigDecimal triggerThreshold;

    /** 调整前套保比率。 */
    public BigDecimal ratioBefore;

    /** 调整后套保比率。 */
    public BigDecimal ratioAfter;

    /** 调整前数量。 */
    public BigDecimal qtyBefore;

    /** 调整后数量。 */
    public BigDecimal qtyAfter;

    /** 重新生成的书面文件（不得只改数字）。 */
    public Long newHedgeDocId;

    /** 重新指定后的套期关系。 */
    public Long newHedgeRelationId;

    /** 执行人。 */
    public Long executorId;

    /** 执行时间。 */
    public LocalDateTime rebalanceTime;

    /** 是否已反馈至 PLAN（如需调整计划，R-ACCT-3）。 */
    public String planFeedbackFlag;
}
