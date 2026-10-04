package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-1 t_trade_order} 交易指令（拟发往 CTP 的下单请求；必须绑定交易计划 ID 与子账户）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class TradeOrder extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户标识（必填；随指令显式携带，不得间接推导），R-TRADE-1。 */
    public Integer subAccountId;

    /** Java 侧幂等键（HG&lt;yyyyMMdd&gt;&lt;seq&gt;）。 */
    public String clientOrderRef;

    /** C++ 侧指令号（未受理为 null）。 */
    public Long orderId;

    /** 交易计划 ID（必填）。 */
    public Long tradePlanId;

    /** 套保计划（冗余）。 */
    public Long hedgePlanId;

    /** 实货敞口 ID。 */
    public Long exposureId;

    /** 品种。 */
    public Long productId;

    /** 合约代码。 */
    public String instrumentId;

    /** FUTURES / OPTION。 */
    public String instrumentType;

    /** BUY / SELL。 */
    public String direction;

    /** OPEN / CLOSE / CLOSE_TODAY。 */
    public String offsetFlag;

    /** LIMIT / MARKET。 */
    public String priceType;

    /** 限价（price_type=LIMIT 时必填）。 */
    public BigDecimal limitPrice;

    /** 手数（必须 &gt; 0）。 */
    public Integer volume;

    /** 提交人（交易岗）。 */
    public Long operatorId;

    /** 期权策略。 */
    public Long strategyId;

    /** 期权策略腿序号。 */
    public Integer legNo;

    /** 交易理由。 */
    public Long tradeReasonId;

    /** 指令状态（9 值，见 ck_trade_order_status）。 */
    public String status;

    /** 事前风控拒绝的规则编号。 */
    public String rejectRuleId;

    /** 拒绝错误码（0x10xx）。 */
    public String rejectCode;

    /** 拒绝原因。 */
    public String rejectReason;

    /** 人工确认人（必须与 operator_id 一致）。 */
    public Long confirmOperatorId;

    /** 人工确认时间。 */
    public LocalDateTime confirmTime;

    /** C++ 受理时间。 */
    public LocalDateTime acceptTime;

    /** 提交时间。 */
    public LocalDateTime submitTime;

    /** 提交毫秒时间戳。 */
    public Long clientTsMs;

    /** 待确认失效时间。 */
    public LocalDateTime expireTime;

    /** 撤单原因（status=CANCELLED 时必填，服务层校验）。 */
    public String cancelReason;

    /** 撤单操作人。 */
    public Long cancelOperatorId;

    /** 撤单时间。 */
    public LocalDateTime cancelTime;
}
