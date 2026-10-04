package com.hedgeguard.bridge;

/**
 * HGCP 事件流消费监听接缝（{@code T-172}）。
 *
 * <p><b>职责</b>：把 {@link HgcpClient} 读线程消费到的事件以<b>中立载荷</b>回调给 L3 装配方
 * （事中风控 / 交易执行落库），使协议细节不外泄——本接口的载荷字段与线格式事件
 * <b>逐字对应</b>（{@code §4.2.8}），<b>不包含任何线格式未承载的字段</b>。
 *
 * <p><b>接缝形态</b>（对齐 {@code T-036}/{@code T-037} 先例）：<b>nullable</b>——
 * 未注册监听器时 {@code HgcpClient} 行为与接线前完全一致（仅推进水位）。
 * {@link HgcpClient#setEventListener} 可在 {@code start()} 前后调用。
 *
 * <p><b>异常安全</b>：监听器抛出的 {@link RuntimeException} 由 {@link HgcpClient} 捕获并记日志，
 * <b>绝不中断事件消费</b>（{@code NFR-REL-5}）。
 *
 * <p><b>线程模型</b>：全部回调在 {@code hgcp-reader} 单线程内<b>顺序</b>触发，
 * 与镜像水位推进同线程——实现方无需额外同步，但<b>不得阻塞</b>
 * （长任务须自行转异步，{@code R-RISK-10}）。
 */
public interface HgcpEventListener {

    /**
     * 每消费一个事件帧后回调（该帧的镜像处理与水位推进之后）。
     *
     * <p>供事中风控<b>评估泵</b>等「每事件触发一次」的消费使用。
     *
     * @param eventType 事件类型（{@link HgcpConstants} {@code EVT_*}）
     * @param seq       事件流序号
     */
    default void onEventConsumed(int eventType, long seq) {
    }

    /** {@code EVT_ORDER_ACCEPTED}（{@code 0x0201}）。 */
    default void onOrderAccepted(OrderAccepted e) {
    }

    /** {@code EVT_ORDER_REPORT}（{@code 0x0203}）。 */
    default void onOrderReport(OrderReport e) {
    }

    /** {@code EVT_TRADE_FILL}（{@code 0x0204}）。 */
    default void onTradeFill(TradeFill e) {
    }

    /** {@code EVT_POSITION_CHANGE}（{@code 0x0205}）。 */
    default void onPositionChange(PositionChange e) {
    }

    /** {@code EVT_PRE_TRADE_REJECT}（{@code 0x0202}）。 */
    default void onPreTradeReject(PreTradeReject e) {
    }

    /** {@code EVT_COMMAND_FLOW}（{@code 0x0208}）。 */
    default void onCommandFlow(CommandFlow e) {
    }

    /** {@code EVT_ORDER_CANCEL_RESULT}（{@code 0x0206}）。 */
    default void onOrderCancelResult(OrderCancelResult e) {
    }

    /** {@code EVT_ORDER_EXPIRED}（{@code 0x0207}）。 */
    default void onOrderExpired(OrderExpired e) {
    }

    // ==================== 中立载荷（字段 = 线格式字段，逐字对应；无自造字段） ====================

    /** {@code EvtOrderAccepted} 载荷（{@code §4.2.8}，仅 5 字段）。 */
    final class OrderAccepted {
        public long orderId;
        public String clientOrderRef;
        public boolean riskPassed;
        public long effectiveTimeoutMs;
        public long acceptedTsMs;
    }

    /** {@code EvtOrderReport} 载荷。{@code status} 为线格式枚举名（如 {@code ORDER_STATUS_*}）。 */
    final class OrderReport {
        public long orderId;
        public String orderSysId;
        public String orderLocalId;
        public String status;
        public int volumeTotal;
        public int volumeTraded;
        public int volumeLeft;
        public int ctpErrorId;
        public String ctpErrorMsg;
        public long reportTsMs;
    }

    /** {@code EvtTradeFill} 载荷。{@code direction}/{@code offsetFlag} 为线格式枚举名。 */
    final class TradeFill {
        public String tradeId;
        public long orderId;
        public String orderSysId;
        public String instrumentId;
        public String direction;
        public String offsetFlag;
        public String price;
        public int volume;
        public long tradeTsMs;
        public int subAccountId;
        public long tradePlanId;
        public long exposureId;
    }

    /** {@code EvtPositionChange} 载荷。 */
    final class PositionChange {
        public int subAccountId;
        public String instrumentId;
        public String direction;
        public String positionType;
        public int positionQty;
        public int availableQty;
        public int frozenQty;
        public String changeReason;
        public long asOfTsMs;
    }

    /** {@code EvtPreTradeReject} 载荷。 */
    final class PreTradeReject {
        public String clientOrderRef;
        public int subAccountId;
        public long productId;
        public String instrumentId;
        public String ruleId;
        public int reasonCode;
        public String limitLayer;
        public String currentValue;
        public String thresholdValue;
        public String orderDigest;
        public long operatorId;
        public long rejectTsMs;
    }

    /** {@code EvtCommandFlow} 载荷。 */
    final class CommandFlow {
        public long orderId;
        public String flowType;
        public long flowTsMs;
        public String detail;
    }

    /** {@code EvtOrderCancelResult} 载荷。 */
    final class OrderCancelResult {
        public long orderId;
        public int cancelledVolume;
        public String releasedQuota;
        public long restoredPlanQty;
        public int result;
        public int reasonCode;
    }

    /** {@code EvtOrderExpired} 载荷。 */
    final class OrderExpired {
        public long orderId;
        public String clientOrderRef;
        public long expiredTsMs;
        public long timeoutMs;
    }
}
