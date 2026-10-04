package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-FUND-1 t_fund_ledger} 资金台账（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class FundLedger extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（独立台账；可加总的关键维度）。 */
    public Integer subAccountId;

    /** 记账日。 */
    public LocalDate ledgerDate;

    /** 期初余额（自上日期末结转，不得手工改写）。 */
    public BigDecimal beginBalance;

    /** 当日入金合计。 */
    public BigDecimal amountIn;

    /** 当日出金合计。 */
    public BigDecimal amountOut;

    /** 当日划拨入合计。 */
    public BigDecimal transferIn;

    /** 当日划拨出合计。 */
    public BigDecimal transferOut;

    /** 当日盈亏结转合计。 */
    public BigDecimal pnlCarryover;

    /** 当日手续费合计。 */
    public BigDecimal commission;

    /** 当日保证金占用变动合计。 */
    public BigDecimal marginChange;

    /** 当日差异调整合计（须双人复核）。 */
    public BigDecimal differenceAdjust;

    /** 期末余额（begin_balance + 发生额合计 = end_balance）。 */
    public BigDecimal endBalance;

    /** 可用资金（新开仓可用）。 */
    public BigDecimal availableFund;

    /** 占用保证金。 */
    public BigDecimal marginOccupied;

    /** 权益。 */
    public BigDecimal equity;

    /** 风险度。 */
    public BigDecimal riskRatio;

    /** 当日台账是否已结账（结账后 end_balance 冻结）。 */
    public String isClosed;

}
