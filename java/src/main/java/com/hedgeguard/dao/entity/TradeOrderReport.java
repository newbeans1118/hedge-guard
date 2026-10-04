package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-2 t_trade_order_report} 委托（报单）回报（与指令一一对应；幂等键 order_sys_id+order_status+volume_traded）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradeOrderReport extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** CTP 报单编号（幂等键）。 */
    public String orderSysId;

    /** 关联指令。 */
    public Long orderId;

    /** CTP 本地报单编号。 */
    public String orderLocalId;

    /** 交易所。 */
    public String exchangeId;

    /** 合约。 */
    public String instrumentId;

    /** BUY / SELL。 */
    public String direction;

    /** 开平标志。 */
    public String offsetFlag;

    /** CTP 委托状态（保留原文语义）。 */
    public String orderStatus;

    /** 限价。 */
    public BigDecimal limitPrice;

    /** 报单总量。 */
    public Integer volumeTotal;

    /** 已成交量。 */
    public Integer volumeTraded;

    /** 未成交量。 */
    public Integer volumeLeft;

    /** CTP 错误号。 */
    public Integer ctpErrorId;

    /** CTP 错误信息原文。 */
    public String ctpErrorMsg;

    /** 回报毫秒时间戳。 */
    public Long reportTsMs;

    /** 回报落库时间。 */
    public LocalDateTime reportTime;

    /** 事件流序号。 */
    public Long eventSeq;
}
