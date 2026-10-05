package com.hedgeguard.service;

/**
 * 事后风控报表服务（{@code FR-RISK-17}/{@code FR-RISK-18}；{@code R-RISK-12 @ REQUIREMENTS/RISK.md §4}）。
 *
 * <p>交易日结束后由调度层（{@code com.hedgeguard.job}）触发，基于当日全量数据生成并<b>落库</b>
 * 风险报表三部分：风险指标统计 / 违规事项汇总 / 限额使用情况（{@code FR-RISK-17} 验收 2），
 * 并与 {@code SETTLE} 结算数据勾稽（验收 3）。调度任务不得绕过本层直调 DAO（{@code §3.12}）。
 */
public interface PostRiskReportService {

    // ── 报表类型（{@code t_post_risk_report.report_type}）──
    /** 风险指标统计。 */
    String REPORT_TYPE_RISK_METRIC = "RISK_METRIC";
    /** 违规事项汇总。 */
    String REPORT_TYPE_VIOLATION_SUMMARY = "VIOLATION_SUMMARY";
    /** 限额使用情况。 */
    String REPORT_TYPE_LIMIT_USAGE = "LIMIT_USAGE";

    // ── 违规类型（{@code t_risk_violation.violation_type}；{@code DATA_MODEL.md} §4.3）──
    /** 超品种。 */
    String V_OVER_PRODUCT = "OVER_PRODUCT";
    /** 超规模。 */
    String V_OVER_SCALE = "OVER_SCALE";
    /** 超期限。 */
    String V_OVER_TERM = "OVER_TERM";
    /** 超授权。 */
    String V_OVER_AUTH = "OVER_AUTH";
    /** 频繁开平仓。 */
    String V_FREQUENT_TRADE = "FREQUENT_TRADE";
    /** 文档缺失。 */
    String V_DOC_MISSING = "DOC_MISSING";

    // ── 违规来源（{@code t_risk_violation.source}）──
    /** 事前拒绝记录。 */
    String SRC_PRE_TRADE_REJECT = "PRE_TRADE_REJECT";
    /** 事中告警。 */
    String SRC_INTRADAY_ALERT = "INTRADAY_ALERT";

    // ── 严重级别（{@code t_risk_violation.severity}）──
    /** 低。 */
    String SEV_LOW = "LOW";
    /** 中。 */
    String SEV_MEDIUM = "MEDIUM";
    /** 高。 */
    String SEV_HIGH = "HIGH";

    // ── 限额使用提示级别（{@code t_limit_usage_stat.hint_level}）──
    /** 正常。 */
    String HINT_NORMAL = "NORMAL";
    /** 提示（{@code >=80%}）。 */
    String HINT_HINT = "HINT";
    /** 警告（{@code >=95%}）。 */
    String HINT_WARNING = "WARNING";
    /** 超限（{@code >=100%}）。 */
    String HINT_EXCEEDED = "EXCEEDED";

    /** 品种额度限额类型（{@code t_limit_usage_stat.limit_type}）。 */
    String LIMIT_PRODUCT_QUOTA = "PRODUCT_QUOTA";

    /**
     * 生成并落库当日风险报表（幂等：同 {@code (交易日, 报表类型, 全账户)} 唯一）。
     *
     * @param request 入参（交易日 + 日终镜像指标 + 当日报警 + 生成人）
     * @return 生成结果（计数/勾稽差异）
     */
    PostRiskReportResult generate(PostRiskReportRequest request);
}
