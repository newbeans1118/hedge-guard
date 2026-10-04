package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SETTLE-7 t_settle_fund} 系统内结算资金（按子账户；σ 本表 = CTP 账户资金）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleFund extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（必填，可直接求和）。 */
    public Integer subAccountId;

    /** 所属结算单。 */
    public Long settleCycleId;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 期初余额。 */
    public BigDecimal beginBalance;

    /** 权益。 */
    public BigDecimal equity;

    /** 可用资金。 */
    public BigDecimal availableFund;

    /** 占用保证金。 */
    public BigDecimal marginOccupied;

    /** 冻结保证金。 */
    public BigDecimal frozenMargin;

    /** 当日入金。 */
    public BigDecimal amountIn;

    /** 当日出金。 */
    public BigDecimal amountOut;

    /** 当日子账户划拨入。 */
    public BigDecimal transferIn;

    /** 当日子账户划拨出。 */
    public BigDecimal transferOut;

    /** 平仓盈亏。 */
    public BigDecimal closePnl;

    /** 浮动盈亏。 */
    public BigDecimal floatPnl;

    /** 手续费。 */
    public BigDecimal commission;

    /** 保证金占用变动。 */
    public BigDecimal marginChange;

    /** 期末余额。 */
    public BigDecimal endBalance;

    /** 关联的资金台账行。 */
    public Long fundLedgerId;

    /** 归属来源。 */
    public String attributionSource;
}
