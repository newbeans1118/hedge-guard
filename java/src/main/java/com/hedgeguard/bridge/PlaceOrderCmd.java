package com.hedgeguard.bridge;

/**
 * 下单指令（{@code ARCHITECTURE.md} §3.10 {@code PlaceOrderCmd}）。
 *
 * <p>金额 / 价格 / 数量一律以<b>字符串</b>承载（避免 JS 与 {@code double} 精度丢失，
 * {@code ARCHITECTURE.md} §4.1.1「金额 / 数量」、{@code DATA_MODEL.md} §0.2）。
 */
public final class PlaceOrderCmd {

    /** 子账户（贯穿字段，协议级强制携带）。 */
    public int subAccountId;

    /** 交易计划 ID（{@code t_trade_plan.id}）——额度与数量门禁的依据。 */
    public long tradePlanId;

    /** 品种 ID。 */
    public long productId;

    /** 合约代码（如 {@code ru2601}）。 */
    public String instrumentId;

    /** 方向：{@code BUY} / {@code SELL}。 */
    public String side;

    /** 开平标志：{@code OPEN} / {@code CLOSE}。 */
    public String offsetFlag;

    /** 价格类型：{@code LIMIT} / {@code MARKET}。 */
    public String priceType;

    /** 委托价（字符串化十进制；市价单可为 {@code null}）。 */
    public String price;

    /** 数量（手，字符串化十进制）。 */
    public String volume;

    /** 幂等键（{@code client_order_ref}，{@code HG<yyyyMMdd><seq(6)>}，应用层生成）。 */
    public String clientOrderRef;

    /** 交易理由 ID（{@code t_trade_reason.id}；结构化必填，{@code Q-13}）。 */
    public long tradeReasonId;

    /** 操作人用户 ID（事前风控的授权校验输入）。 */
    public long operatorId;

    /** 二次确认令牌（价格偏离告警区间时必填，{@code 0x1018}）。 */
    public String confirmToken;
}
