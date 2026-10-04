package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SETTLE-6 t_settle_position} 系统内结算持仓（按子账户；σ 本表 = CTP 合并持仓）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettlePosition extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（拆分结果维度；必填，可直接求和）。 */
    public Integer subAccountId;

    /** 所属结算单。 */
    public Long settleCycleId;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 合约。 */
    public String instrumentId;

    /** 品种。 */
    public Long productId;

    /** 合约类型。 */
    public String instrumentType;

    /** 方向。 */
    public String direction;

    /** 仓型。 */
    public String positionType;

    /** 持仓量。 */
    public BigDecimal positionQty;

    /** 归属来源。 */
    public String attributionSource;

    /** 无法归属的数量。 */
    public BigDecimal unattributedQty;
}
