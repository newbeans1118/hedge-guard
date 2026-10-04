package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-5 t_position} 持仓明细（子账户 × 合约 × 方向 × 今昨）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>本表是落库投影；权威持仓在 C++ 内存（{@code ARCHITECTURE.md} §4.4.1）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradePosition extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（拆分维度，必填）。 */
    public Integer subAccountId;

    /** 合约。 */
    public String instrumentId;

    /** 品种。 */
    public Long productId;

    /** LONG / SHORT。 */
    public String direction;

    /** TODAY / YESTERDAY。 */
    public String positionType;

    /** 持仓数量（必须 &ge; 0）。 */
    public BigDecimal positionQty;

    /** 可用持仓（&le; position_qty）。 */
    public BigDecimal availableQty;

    /** 冻结（挂单占用）持仓。 */
    public BigDecimal frozenQty;

    /** 持仓均价。 */
    public BigDecimal avgOpenPrice;

    /** 最近结算价。 */
    public BigDecimal lastSettlementPrice;

    /** 持仓对应的套保计划归属（取主计划）。 */
    public Long hedgePlanId;

    /** 该持仓数据的时点。 */
    public LocalDateTime asOfTime;

    /** 事件流序号。 */
    public Long eventSeq;
}
