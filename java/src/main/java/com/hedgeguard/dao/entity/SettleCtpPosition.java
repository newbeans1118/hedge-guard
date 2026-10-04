package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SETTLE-2 t_ctp_settle_position} CTP 持仓明细（合并持仓，外部核对基准，只读）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleCtpPosition extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 所属批次。 */
    public Long settleBatchId;

    /** 合约。 */
    public String instrumentId;

    /** 方向。 */
    public String direction;

    /** 仓型。 */
    public String positionType;

    /** 持仓量（合并，不含子账户维度）。 */
    public BigDecimal positionQty;

    /** 持仓均价。 */
    public BigDecimal avgPrice;

    /** 结算价。 */
    public BigDecimal settlementPrice;

    /** 占用保证金。 */
    public BigDecimal margin;
}
