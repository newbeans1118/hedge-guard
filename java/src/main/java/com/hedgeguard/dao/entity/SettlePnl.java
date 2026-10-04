package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SETTLE-8 t_settle_pnl} 结算盈亏（平仓/浮动/手续费/保证金，口径全系统唯一）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettlePnl extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（必填，可直接求和）。 */
    public Integer subAccountId;

    /** 所属结算单。 */
    public Long settleCycleId;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 合约。 */
    public String instrumentId;

    /** 品种。 */
    public Long productId;

    /** 套保计划归属。 */
    public Long hedgePlanId;

    /** 盈亏类型。 */
    public String pnlType;

    /** 金额（可为负）。 */
    public BigDecimal amount;

    /** 对应数量。 */
    public BigDecimal qty;
}
