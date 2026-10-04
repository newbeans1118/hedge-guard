package com.hedgeguard.risk_intraday;

/**
 * 空实现（生产兜底）：丢弃评估产物、恒不阻塞。
 *
 * <p>真实落库（{@code t_risk_alert} / {@code t_risk_metric_snapshot}）由 {@code T-065} 承接；
 * 在其落地前，本模块以本类避免「伪落库」并保持事件消费路径零 DB 查询（{@code FR-RISK-13} 验收 4）。
 */
public final class NoopRiskMetricSink implements RiskMetricSink {

    @Override
    public void publish(RiskEvaluation evaluation) {
        // 有意为空：T-065 以真实 DAO 实现本端口后替换。
    }
}
