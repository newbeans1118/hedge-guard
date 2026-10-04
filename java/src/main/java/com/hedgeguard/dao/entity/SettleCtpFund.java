package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SETTLE-3 t_ctp_settle_fund} CTP 资金明细（账户级合并资金，外部核对基准，只读）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleCtpFund extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 所属批次。 */
    public Long settleBatchId;

    /** 客户权益。 */
    public BigDecimal equity;

    /** 可用资金。 */
    public BigDecimal availableFund;

    /** 占用保证金。 */
    public BigDecimal marginOccupied;

    /** 冻结保证金。 */
    public BigDecimal frozenMargin;

    /** 当日手续费。 */
    public BigDecimal commissionToday;

    /** 当日入金。 */
    public BigDecimal depositToday;

    /** 当日出金。 */
    public BigDecimal withdrawToday;

    /** 平仓盈亏。 */
    public BigDecimal closePnl;

    /** 持仓浮动盈亏。 */
    public BigDecimal floatPnl;
}
