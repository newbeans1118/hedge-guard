package com.hedgeguard.risk_intraday;

/**
 * 告警实时推送端口（{@code FR-RISK-12}~{@code FR-RISK-16}；{@code T-065}）。
 *
 * <p>本端口把「告警落库」与「实时下发」解耦：落库后由实现方（装配层）把告警帧推给 WebSocket
 * 通道 {@code /ws/alert}（{@code ARCHITECTURE.md §4.1.2}）。<b>不丢</b>语义由通道实现负责
 * （告警队列满时关闭连接 {@code 1013}，客户端重连后经 REST {@code /risk/alert?since=} 补拉）。
 *
 * <p><b>诚实披露</b>：生产 {@code WsServer} 的装配属 {@code T-040}（{@code BLOCKED}）
 * ⇒ 本端口在 T-040 落地前无生产实现（装配层传 {@code null} 即仅落库不推送）；推送侧以
 * {@code T-040} 为承接方，补拉侧 REST 由本任务实现。
 */
@FunctionalInterface
public interface RiskAlertNotifier {

    /**
     * 发布一条告警（实现方须快速返回，不得阻塞落库链路）。
     *
     * @param subAccountId 子账户（账户级为 0）
     * @param level        级别（{@code INFO} / {@code WARNING} / {@code CRITICAL}）
     * @param metric       指标名
     * @param value        当前值（字符串化十进制或计数）
     * @param threshold    阈值（字符串化十进制或计数）
     */
    void notify(int subAccountId, String level, String metric, String value, String threshold);
}
