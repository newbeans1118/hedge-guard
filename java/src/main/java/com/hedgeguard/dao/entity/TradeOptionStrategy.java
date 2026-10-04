package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-8 t_option_strategy} 期权策略（多腿策略头，同链同额）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradeOptionStrategy extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 策略编号（全系统唯一）。 */
    public String strategyNo;

    /** 策略类型（5 值）。 */
    public String strategyType;

    /** 同一套保计划（同链同额），R-TRADE-12。 */
    public Long hedgePlanId;

    /** 交易计划。 */
    public Long tradePlanId;

    /** 腿数。 */
    public Integer legCount;

    /** 各腿合计占用的套保额度。 */
    public BigDecimal totalQuotaOccupied;

    /** PENDING / PARTIAL / COMPLETED / CANCELLED。 */
    public String status;

    /** 提交人。 */
    public Long operatorId;

    /** 提交时间。 */
    public LocalDateTime submitTime;
}
