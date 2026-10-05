package com.hedgeguard.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事后风险报表生成结果（{@code FR-RISK-17}/{@code FR-RISK-18}；供任务回执与测试断言）。
 */
public final class PostRiskReportResult {

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 与结算数据的勾稽键。 */
    public String settleReconcileKey;

    /** 报表落库行数（当日、全账户口径；应 {@code == 3}，三类各一行）。 */
    public int reportRows;

    /** 违规事项总条数。 */
    public int violationTotal;

    /** 违规事项按类型计数（{@code violation_type -> count}）。 */
    public Map<String, Integer> violationByType = new LinkedHashMap<>();

    /** 违规事项按严重级别计数（{@code severity -> count}）。 */
    public Map<String, Integer> violationBySeverity = new LinkedHashMap<>();

    /** 限额使用行数。 */
    public int limitUsageRows;

    /** 使用率 {@code >= 80%}（提示）的行数。 */
    public int hintCount;

    /** 使用率 {@code >= 95%}（警告）的行数。 */
    public int warningCount;

    /** 使用率 {@code >= 100%}（超限）的行数。 */
    public int exceededCount;

    /** 与 {@code SETTLE} 结算数据的差异（{@code |镜像浮动盈亏 − 结算浮动盈亏|}；{@code 0} = 一致）。 */
    public BigDecimal reconcileDiff = BigDecimal.ZERO;
}
