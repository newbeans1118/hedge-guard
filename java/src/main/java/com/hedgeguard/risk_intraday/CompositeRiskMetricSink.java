package com.hedgeguard.risk_intraday;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 组合式指标落库端口（先例：{@code CompositeHgcpEventListener}，T-172）。
 *
 * <p>事件消费线程经 {@link AsyncRiskMetricSink} 到达本类；本类把一次评估产物
 * <b>依次</b>分发给全部下游 sink（如 {@code T-065} 持久化 sink、{@code T-188} 重大事项触发器）。
 * <b>单个下游抛 {@link RuntimeException} 只记日志、不影响其余下游，也不中断事件消费</b>
 * （{@code R-RISK-10}：事件消费路径零阻塞；{@code NFR-REL-5}）。
 *
 * <p>本类自身不含 DB 访问，只做分发与失败隔离；SQL 全部落在下游（{@code dao/impl}）。
 */
public final class CompositeRiskMetricSink implements RiskMetricSink {

    private static final Logger LOG = Logger.getLogger(CompositeRiskMetricSink.class.getName());

    private final RiskMetricSink[] downstreams;

    /**
     * 构造。
     *
     * @param downstreams 下游 sink（至少一个；逐个非 {@code null}）
     */
    public CompositeRiskMetricSink(RiskMetricSink... downstreams) {
        if (downstreams == null || downstreams.length == 0) {
            throw new IllegalArgumentException("组合 sink 至少需要一个下游");
        }
        for (RiskMetricSink d : downstreams) {
            if (d == null) {
                throw new IllegalArgumentException("下游 sink 不得为 null");
            }
        }
        this.downstreams = downstreams.clone();
    }

    @Override
    public void publish(RiskEvaluation evaluation) {
        for (RiskMetricSink d : downstreams) {
            try {
                d.publish(evaluation);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "组合指标 sink：下游 " + d.getClass().getSimpleName()
                        + " 处理失败（已隔离，不影响其余下游与事件消费）："
                        + e.getClass().getSimpleName() + "：" + e.getMessage(), e);
            }
        }
    }
}
