package com.hedgeguard.risk_intraday;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成交活动信号（异常下单行为监控的输入）。
 *
 * <p>由事件消费侧（J3）在收到 {@code EVT_TRADE_FILL} 时投喂给
 * {@link AbnormalOrderTracker}；本模块<b>只消费、不落库</b>（落库由 T-065 承接）。
 *
 * <p>本类为纯数据载体，时间取 {@code Asia/Shanghai}（{@code §4.1.1}）。
 */
public final class TradeActivity {

    /** 子账户（行级归属）。 */
    public int subAccountId;

    /** 品种 ID。 */
    public long productId;

    /** 合约代码。 */
    public String instrumentId;

    /** 开 / 平仓方向。 */
    public TradeAction action;

    /** 成交量（手），字符串化十进制输入。 */
    public BigDecimal quantity = BigDecimal.ZERO;

    /** 成交时刻。 */
    public LocalDateTime occurredAt;

    /** 无参构造。 */
    public TradeActivity() {
    }

    /**
     * 构造。
     *
     * @param subAccountId 子账户
     * @param productId    品种
     * @param instrumentId 合约
     * @param action       开 / 平
     * @param quantity     成交量
     * @param occurredAt   成交时刻
     */
    public TradeActivity(int subAccountId, long productId, String instrumentId, TradeAction action,
                         BigDecimal quantity, LocalDateTime occurredAt) {
        this.subAccountId = subAccountId;
        this.productId = productId;
        this.instrumentId = instrumentId;
        this.action = action;
        this.quantity = quantity == null ? BigDecimal.ZERO : quantity;
        this.occurredAt = occurredAt;
    }
}
