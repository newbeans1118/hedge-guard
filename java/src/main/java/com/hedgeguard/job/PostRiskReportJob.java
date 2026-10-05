package com.hedgeguard.job;

import com.hedgeguard.risk_intraday.MirrorMetrics;
import com.hedgeguard.risk_intraday.RiskAlert;
import com.hedgeguard.risk_intraday.RiskEvaluation;
import com.hedgeguard.service.PostRiskReportRequest;
import com.hedgeguard.service.PostRiskReportResult;
import com.hedgeguard.service.PostRiskReportService;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 事后风控调度任务（{@code ARCHITECTURE.md} §3.12 Java 模块 J6；{@code FR-RISK-17}）。
 *
 * <p><b>触发时机</b>：交易日结束后（{@code FR-RISK-17} 处理规则）。<b>可注入时钟</b>
 * （{@code NFR-TEST-4}）——交易日由调用方显式传入，本类不取系统时间。
 *
 * <p><b>分层纪律</b>：调度任务<b>不得</b>绕过 L3 直调 DAO（{@code §3.12}）——本类只依赖
 * {@link PostRiskReportService}（L3）与内存镜像接缝 {@link MetricsSource}，不持有任何 DAO。
 *
 * <p>生成失败时经 {@link PostRiskReportAlarm} 告警（{@code FR-RISK-17} 验收 4）后原样上抛，
 * 保证失败不被静默。
 */
public final class PostRiskReportJob {

    /** 日终镜像指标来源（生产装配传 {@code monitor::current}）。 */
    @FunctionalInterface
    public interface MetricsSource {
        /**
         * 取当前评估产物（含日终镜像指标与当日报警）。
         *
         * @return 评估产物；无则返回 {@code null}
         */
        RiskEvaluation current();
    }

    private final PostRiskReportService service;
    private final MetricsSource metricsSource;
    private final PostRiskReportAlarm alarm;

    /**
     * 构造。
     *
     * @param service       事后报表服务（L3）
     * @param metricsSource 镜像指标来源（可空：无镜像时风险指标部分全 {@code 0}）
     * @param alarm         失败告警端口（{@code null} 视为 {@link PostRiskReportAlarm#NOOP}）
     */
    public PostRiskReportJob(PostRiskReportService service, MetricsSource metricsSource,
                             PostRiskReportAlarm alarm) {
        this.service = service;
        this.metricsSource = metricsSource;
        this.alarm = alarm == null ? PostRiskReportAlarm.NOOP : alarm;
    }

    /**
     * 跑一次事后风控（生成并落库当日风险报表）。
     *
     * @param tradeDate  交易日
     * @param operatorId 生成人（系统账号）
     * @return 生成结果
     */
    public PostRiskReportResult run(LocalDate tradeDate, long operatorId) {
        try {
            RiskEvaluation eval = metricsSource == null ? null : metricsSource.current();
            MirrorMetrics metrics = eval == null ? null : eval.metrics;
            List<RiskAlert> alerts = eval == null ? Collections.emptyList() : eval.alerts;
            return service.generate(new PostRiskReportRequest(tradeDate, metrics, alerts, operatorId));
        } catch (RuntimeException e) {
            alarm.alert("事后风险报表生成失败：tradeDate=" + tradeDate + "；原因=" + e.getMessage(), e);
            throw e;
        }
    }
}
