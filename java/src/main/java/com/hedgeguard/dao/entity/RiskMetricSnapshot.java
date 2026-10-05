package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-RISK-10 t_risk_metric_snapshot} 风险指标快照（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-12}/{@code FR-RISK-13}）。
 *
 * <p>事中指标的时点值，用于趋势分析与审计复现。实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class RiskMetricSnapshot extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 快照时间。 */
    public LocalDateTime snapshotTime;

    /** 快照日期。 */
    public LocalDate snapshotDate;

    /** 净持仓量。 */
    public BigDecimal positionQtyNet;

    /** 浮动盈亏。 */
    public BigDecimal floatPnl;

    /** 保证金占用。 */
    public BigDecimal marginOccupied;

    /** 保证金占用率。 */
    public BigDecimal marginRatio;

    /** 风险度。 */
    public BigDecimal riskRatio;

    /** 可用资金。 */
    public BigDecimal availableFund;

    /** 权益。 */
    public BigDecimal equity;

    /** 该快照对应的镜像事件序号（可复现）。 */
    public long mirrorSeq;

    /** 快照来源：{@code PERIODIC}（周期）/ {@code EVENT}（事件触发）/ {@code EOD}（日终）。 */
    public String snapshotSource;
}
