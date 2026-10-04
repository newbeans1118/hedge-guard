package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-5 t_settle_cycle} 结算单（交易日 × 子账户；每个子账户独立出结算结果）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleCycle extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（独立结算维度）。 */
    public Integer subAccountId;

    /** 结算单号（全系统唯一）。 */
    public String settleNo;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 是否全账户汇总行。 */
    public String isAggregate;

    /** 结算状态。 */
    public String status;

    /** 平仓盈亏。 */
    public BigDecimal closePnl;

    /** 浮动盈亏。 */
    public BigDecimal floatPnl;

    /** 手续费。 */
    public BigDecimal commission;

    /** 保证金占用。 */
    public BigDecimal marginOccupied;

    /** 可用资金。 */
    public BigDecimal availableFund;

    /** 权益。 */
    public BigDecimal equity;

    /** 风险度。 */
    public BigDecimal riskRatio;

    /** 子账户资金是否为负。 */
    public String negativeFundFlag;

    /** 关账确认人。 */
    public Long confirmedBy;

    /** 关账时间。 */
    public LocalDateTime confirmedTime;

    /** 是否已作废。 */
    public String voidedFlag;

    /** 作废原因。 */
    public String voidReason;
}
