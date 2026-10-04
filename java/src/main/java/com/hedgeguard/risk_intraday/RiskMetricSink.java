package com.hedgeguard.risk_intraday;

/**
 * 指标 / 告警异步落库端口（{@code FR-RISK-13} 验收 4「先算后异步落库」）。
 *
 * <p><b>实现必须不阻塞</b>：{@link #publish(RiskEvaluation)} 在事件消费线程上被调用，
 * 不得内含同步 DB 查询（{@code R-RISK-10}；{@code FR-RISK-13} 验收 4）。真正的落库由
 * {@code T-065}（风险告警 / 处置 / 指标快照）以真实 DAO 实现本端口承接；本里程碑提供
 * {@link NoopRiskMetricSink}（不落库）作为生产兜底、并为单测提供记录式实现。
 */
public interface RiskMetricSink {

    /**
     * 发布一次评估产物（指标 + 告警）。<b>须快速返回</b>，不得阻塞事件消费。
     *
     * @param evaluation 评估产物
     */
    void publish(RiskEvaluation evaluation);
}
