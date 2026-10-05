package com.hedgeguard.service;

import com.hedgeguard.risk_intraday.MirrorMetrics;
import com.hedgeguard.risk_intraday.RiskAlert;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 事后风险报表生成入参（{@code FR-RISK-17}）。
 *
 * <p><b>数据源对齐镜像面</b>：{@code metrics} 为 {@code MirrorMetricsCalculator} 的日终产物，
 * {@code alerts} 为同日事中告警（{@code RiskAlert}）；当日事前拒绝记录与限额配置由服务层经
 * DAO 取数（调度任务不得直调 DAO，{@code ARCHITECTURE.md} §3.12）。
 */
public final class PostRiskReportRequest {

    /** 交易日。 */
    public final LocalDate tradeDate;

    /** 日终镜像指标（可空；空则风险指标部分全 {@code 0}）。 */
    public final MirrorMetrics metrics;

    /** 当日事中告警（可空）。 */
    public final List<RiskAlert> alerts;

    /** 生成人。 */
    public final long operatorId;

    /**
     * 构造。
     *
     * @param tradeDate  交易日
     * @param metrics    日终镜像指标
     * @param alerts     当日报警
     * @param operatorId 生成人
     */
    public PostRiskReportRequest(LocalDate tradeDate, MirrorMetrics metrics,
                                 List<RiskAlert> alerts, long operatorId) {
        this.tradeDate = tradeDate;
        this.metrics = metrics;
        this.alerts = alerts == null ? Collections.emptyList() : new ArrayList<>(alerts);
        this.operatorId = operatorId;
    }
}
