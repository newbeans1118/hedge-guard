package com.hedgeguard.monitor;

import com.hedgeguard.bridge.ComponentAlert;

/**
 * 组件告警出口（阶段 3）。
 *
 * <p>装配层把本接口接到既有 {@code RiskAlertNotifier}（→ {@code WsServer.publishAlert}
 * 的冻结 {@code /ws/alert} 通道）。<b>可空</b>：未装配 WS 时不推送，但告警仍进入
 * {@link ComponentMonitor} 的有界内存历史，可经 {@code GET /monitor/component} 查得。
 *
 * <p><b>不得阻塞</b>：回调在 {@code hgcp-reader} 单线程内触发（{@code R-RISK-10} 同族），
 * 实现方只做内存遍历/入队，禁止 I/O 与 DB 写。
 */
@FunctionalInterface
public interface ComponentAlertSink {

    /**
     * 发布一条告警。
     *
     * @param alert 告警（不可变）
     */
    void onAlert(ComponentAlert alert);
}
