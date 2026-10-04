package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-3 t_trade_fill} 成交记录（CTP 成交明细；结算与审计原始依据，不可改删；幂等键 trade_id）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradeFill extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户标识（加总对账的关键维度）。 */
    public Integer subAccountId;

    /** CTP 成交编号（幂等键）。 */
    public String tradeId;

    /** 关联指令。 */
    public Long orderId;

    /** CTP 报单编号。 */
    public String orderSysId;

    /** 交易计划（追溯链：成交 → 计划）。 */
    public Long tradePlanId;

    /** 套保计划（冗余）。 */
    public Long hedgePlanId;

    /** 实货敞口。 */
    public Long exposureId;

    /** 品种。 */
    public Long productId;

    /** 合约。 */
    public String instrumentId;

    /** FUTURES / OPTION。 */
    public String instrumentType;

    /** BUY / SELL。 */
    public String direction;

    /** OPEN / CLOSE / CLOSE_TODAY。 */
    public String offsetFlag;

    /** 成交价。 */
    public BigDecimal fillPrice;

    /** 成交手数（必须 &gt; 0）。 */
    public Integer fillVolume;

    /** 合约乘数。 */
    public BigDecimal contractMultiplier;

    /** 手续费。 */
    public BigDecimal commission;

    /** CTP 成交毫秒时间戳。 */
    public Long tradeTsMs;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 落库时间。 */
    public LocalDateTime fillTime;

    /** 事件流序号。 */
    public Long eventSeq;

    /** 是否已纳入日终结算（Y/N）。 */
    public String settledFlag;
}
