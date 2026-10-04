package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.HgcpEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * HGCP 事件流 → 事中风控投喂接缝（{@code T-172}）。
 *
 * <p>把 {@link HgcpEventListener} 的回调映射为 {@link IntradayRiskMonitor} 的两个消费点：
 * <ul>
 *   <li><b>成交投喂</b>：{@code EVT_TRADE_FILL} → {@link IntradayRiskMonitor#recordTrade}；</li>
 *   <li><b>评估泵</b>：每消费一事件 → {@link IntradayRiskMonitor#evaluate()}（内存计算，
 *       不落库；异步落库由既有 {@code AsyncRiskMetricSink} 承接，{@code R-RISK-10}）。</li>
 * </ul>
 *
 * <p><b>不阻塞</b>：两处调用均为纯内存，运行于 {@code hgcp-reader} 单线程；
 * 监听器异常由 {@link com.hedgeguard.bridge.HgcpClient} 吞掉记日志（{@code NFR-REL-5}）。
 *
 * <p><b>⚠️ 线格式缺口（据实披露，{@code T-172} 遗留）</b>：{@code EvtTradeFill}
 * （{@code §4.2.8}）<b>不含 {@code product_id}</b>，而异常下单行为按「同一子账户 + 同一品种」
 * 分组（{@code Q-5}）。故 {@link ProductIdResolver} 为可注入接缝：缺省返回 {@code 0}
 * ⇒ 品种级分组退化为「子账户级聚合」（会跨品种误触发）。真实品种归属须经订单提交上下文
 * （{@code order_id → product_id}）非阻塞解析——见交付回执待裁定项。
 */
public final class IntradayEventFeed implements HgcpEventListener {

    /**
     * {@code order_id → product_id} 解析接缝（非阻塞、纯内存）。
     *
     * <p>缺省实现返回 {@code 0}（见类注释的线格式缺口披露）。
     */
    @FunctionalInterface
    public interface ProductIdResolver {
        /**
         * @param orderId C++ 指令号（{@code EvtTradeFill.order_id}）
         * @return 品种 ID；未知返回 {@code 0}
         */
        long productIdForOrder(long orderId);
    }

    private final IntradayRiskMonitor monitor;
    private final ProductIdResolver productIdResolver;

    /**
     * 构造（缺省品种解析：恒 {@code 0}）。
     *
     * @param monitor 事中风控引擎（非空）
     */
    public IntradayEventFeed(IntradayRiskMonitor monitor) {
        this(monitor, orderId -> 0L);
    }

    /**
     * 构造。
     *
     * @param monitor            事中风控引擎（非空）
     * @param productIdResolver  品种解析接缝；{@code null} 时退化为恒 {@code 0}
     */
    public IntradayEventFeed(IntradayRiskMonitor monitor, ProductIdResolver productIdResolver) {
        if (monitor == null) {
            throw new IllegalArgumentException("IntradayEventFeed 需要非空 monitor");
        }
        this.monitor = monitor;
        this.productIdResolver = productIdResolver == null ? orderId -> 0L : productIdResolver;
    }

    /** {@code EVT_TRADE_FILL} → {@link IntradayRiskMonitor#recordTrade}。 */
    @Override
    public void onTradeFill(TradeFill e) {
        if (e == null || e.tradeId == null || e.tradeId.isEmpty()) {
            return;
        }
        TradeActivity a = new TradeActivity();
        a.subAccountId = e.subAccountId;
        a.productId = productIdResolver.productIdForOrder(e.orderId);
        a.instrumentId = e.instrumentId;
        a.action = actionOf(e.offsetFlag);
        a.quantity = BigDecimal.valueOf(e.volume);
        a.occurredAt = toLocalDateTime(e.tradeTsMs);
        monitor.recordTrade(a);
    }

    /** 每消费一事件 → {@link IntradayRiskMonitor#evaluate()}（评估泵）。 */
    @Override
    public void onEventConsumed(int eventType, long seq) {
        monitor.evaluate();
    }

    private static TradeAction actionOf(String offsetFlag) {
        // 线格式枚举名：OFFSET_FLAG_OPEN / OFFSET_FLAG_CLOSE / OFFSET_FLAG_CLOSE_TODAY
        return "OFFSET_FLAG_OPEN".equals(offsetFlag) ? TradeAction.OPEN : TradeAction.CLOSE;
    }

    private static LocalDateTime toLocalDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), com.hedgeguard.common.Clocks.ZONE);
    }
}
