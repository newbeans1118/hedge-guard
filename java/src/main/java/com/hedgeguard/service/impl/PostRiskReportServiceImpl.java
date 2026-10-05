package com.hedgeguard.service.impl;

import com.hedgeguard.common.Config;
import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.LimitUsageStatDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PostRiskReportDao;
import com.hedgeguard.dao.ProductQuotaDao;
import com.hedgeguard.dao.RiskViolationDao;
import com.hedgeguard.dao.SettleCycleDao;
import com.hedgeguard.dao.TradePreTradeRejectDao;
import com.hedgeguard.dao.entity.LimitUsageStat;
import com.hedgeguard.dao.entity.PostRiskReport;
import com.hedgeguard.dao.entity.ProductQuota;
import com.hedgeguard.dao.entity.RiskViolation;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.TradePreTradeReject;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.risk_intraday.RiskAlert;
import com.hedgeguard.risk_intraday.RiskAlertLevel;
import com.hedgeguard.risk_intraday.RiskMetricType;
import com.hedgeguard.risk_intraday.SubAccountRiskMetrics;
import com.hedgeguard.service.LimitUsageThresholds;
import com.hedgeguard.service.PostRiskReportRequest;
import com.hedgeguard.service.PostRiskReportResult;
import com.hedgeguard.service.PostRiskReportService;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.JsonCodec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事后风控报表服务实现（{@code FR-RISK-17}/{@code FR-RISK-18}）。
 *
 * <p><b>三层语义</b>：本类承载<b>事后报表</b>层——事前阻断在 C++（全内存）、事中告警在
 * {@code risk_intraday}，本层于交易日结束后按当日全量数据生成并落库。
 *
 * <ul>
 *   <li><b>风险指标统计</b>：取 {@code risk_intraday} 的日终镜像指标（{@code R-RISK-10} 只读面）；</li>
 *   <li><b>违规事项汇总</b>：当日事前拒绝记录（{@code t_pre_trade_reject}）+ 事中告警
 *       （{@code RiskAlert}）派生，并入他源（{@code POST_AUDIT_SCREEN}/{@code MANUAL}）既有行后按
 *       类型与严重级别汇总（{@code FR-RISK-18} 验收 1）；</li>
 *   <li><b>限额使用情况</b>：按子账户 × 品种（层① {@code SUB_PRODUCT} 额度行）计算使用率并标注
 *       {@code >=80%} 提示 / {@code >=95%} 警告（验收 2、3）。</li>
 * </ul>
 *
 * <p><b>勾稽</b>：以镜像浮动盈亏与当日结算单浮动盈亏之和比对，差异 {@code == 0} 为一致
 * （{@code FR-RISK-17} 验收 3）。生成失败（如当日无结算单）抛 {@code 10015}，由调度层告警（验收 4）。
 *
 * <p><b>口径（本单设计决定，待权威席追认）</b>：{@code ruleId -> 违规类型} 映射表见
 * {@link #mapRuleToType(String)}；{@code R-RISK-8}（价格偏离）不属 {@code FR-RISK-18} 六类，
 * 不入违规汇总。<b>严重级别</b>按类型缺省映射（{@link #defaultSeverity(String)}）。
 */
public final class PostRiskReportServiceImpl implements PostRiskReportService {

    /** 分页读页大小（{@code PageQuery} 白名单允许的最大值）。 */
    private static final int PAGE_SIZE = 100;

    /** 使用率保留 6 位小数（{@code usage_ratio DECIMAL(9,6)}）。 */
    private static final int RATIO_SCALE = 6;

    private final PostRiskReportDao reportDao;
    private final RiskViolationDao violationDao;
    private final LimitUsageStatDao limitUsageDao;
    private final TradePreTradeRejectDao rejectDao;
    private final ProductQuotaDao productQuotaDao;
    private final SettleCycleDao settleCycleDao;
    private final TxTemplate tx;
    private final LimitUsageThresholds thresholds;

    /**
     * 构造。
     *
     * @param reportDao       风险报表 DAO
     * @param violationDao    违规事项 DAO
     * @param limitUsageDao   限额使用 DAO
     * @param rejectDao       事前拒绝记录 DAO
     * @param productQuotaDao 品种额度 DAO
     * @param settleCycleDao  结算单 DAO
     * @param tx              事务模板
     * @param config          配置（读取 {@code CFG-21b}；可空取缺省）
     */
    public PostRiskReportServiceImpl(PostRiskReportDao reportDao, RiskViolationDao violationDao,
                                     LimitUsageStatDao limitUsageDao, TradePreTradeRejectDao rejectDao,
                                     ProductQuotaDao productQuotaDao, SettleCycleDao settleCycleDao,
                                     TxTemplate tx, Config config) {
        this.reportDao = reportDao;
        this.violationDao = violationDao;
        this.limitUsageDao = limitUsageDao;
        this.rejectDao = rejectDao;
        this.productQuotaDao = productQuotaDao;
        this.settleCycleDao = settleCycleDao;
        this.tx = tx;
        this.thresholds = LimitUsageThresholds.from(config);
    }

    @Override
    public PostRiskReportResult generate(PostRiskReportRequest request) {
        if (request == null || request.tradeDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "tradeDate 必填（FR-RISK-17）");
        }
        LocalDate date = request.tradeDate;
        return tx.inTransaction(conn -> {
            // ① 结算数据（勾稽对象）：当日无结算单 ⇒ 拒绝生成（FR-RISK-17 验收 3/4）
            List<SettleCycle> cycles = settleCycles(date);
            if (cycles.isEmpty()) {
                throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                        "当日无结算单，拒绝生成事后风险报表（FR-RISK-17 验收3）：tradeDate=" + date);
            }
            BigDecimal settleFloat = BigDecimal.ZERO;
            for (SettleCycle c : cycles) {
                settleFloat = settleFloat.add(nz(c.floatPnl));
            }
            BigDecimal mirrorFloat = request.metrics == null || request.metrics.aggregate == null
                    ? BigDecimal.ZERO : nz(request.metrics.aggregate.floatPnl);
            BigDecimal reconcileDiff = mirrorFloat.subtract(settleFloat).abs();
            String reconcileKey = date + "#" + cycles.size();

            // ② 违规汇总：派生并落库（幂等），再取当日全量（含他源既有行）
            persistDerivedViolations(request);
            List<RiskViolation> violations = violationDao.findByDate(date);

            // ③ 限额使用：按子账户 × 品种（层①）计算并落库
            List<LimitUsageStat> usages = persistLimitUsage(request);

            // ④ 三类报表落库（幂等）
            upsertReport(date, REPORT_TYPE_RISK_METRIC, riskMetricContent(request, reconcileKey, reconcileDiff),
                    reconcileKey, null, request.operatorId);
            upsertReport(date, REPORT_TYPE_VIOLATION_SUMMARY, violationContent(date, violations),
                    reconcileKey, null, request.operatorId);
            upsertReport(date, REPORT_TYPE_LIMIT_USAGE, limitUsageContent(date, usages),
                    reconcileKey, null, request.operatorId);

            // ⑤ 结果
            PostRiskReportResult result = new PostRiskReportResult();
            result.tradeDate = date;
            result.settleReconcileKey = reconcileKey;
            result.reportRows = 3;
            result.reconcileDiff = reconcileDiff;
            result.violationTotal = violations.size();
            result.violationByType = countBy(violations, true);
            result.violationBySeverity = countBy(violations, false);
            result.limitUsageRows = usages.size();
            for (LimitUsageStat u : usages) {
                if (HINT_HINT.equals(u.hintLevel)) {
                    result.hintCount++;
                } else if (HINT_WARNING.equals(u.hintLevel)) {
                    result.warningCount++;
                } else if (HINT_EXCEEDED.equals(u.hintLevel)) {
                    result.exceededCount++;
                }
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ 结算

    private List<SettleCycle> settleCycles(LocalDate date) {
        return findAll(settleCycleDao, "trade_date = ?", Collections.singletonList((Object) date));
    }

    /** 分页读取全部命中行（{@code PageQuery} 单页上限 100）。 */
    private static <T> List<T> findAll(BaseDao<T, ?> dao, String where, List<Object> args) {
        List<T> all = new ArrayList<>();
        int page = 1;
        while (true) {
            PageQuery query = PageQuery.of(String.valueOf(page), String.valueOf(PAGE_SIZE), "id,asc", "id");
            PageResult<T> result = dao.findPage(query, where, args);
            all.addAll(result.items());
            if (result.items().size() < PAGE_SIZE) {
                return all;
            }
            page++;
        }
    }

    // ------------------------------------------------------------------ 违规事项

    private void persistDerivedViolations(PostRiskReportRequest request) {
        LocalDate date = request.tradeDate;
        // 事前拒绝记录 → 六类中的四类
        for (TradePreTradeReject r : findAll(rejectDao, "trade_date = ?",
                Collections.singletonList((Object) date))) {
            String type = mapRuleToType(r.ruleId);
            if (type == null || r.subAccountId == null) {
                continue;
            }
            if (violationDao.countByNaturalKey(r.subAccountId, date, type, r.ruleId,
                    SRC_PRE_TRADE_REJECT) > 0) {
                continue;
            }
            RiskViolation v = new RiskViolation();
            v.subAccountId = r.subAccountId;
            v.violationDate = date;
            v.violationType = type;
            v.severity = defaultSeverity(type);
            v.ruleId = r.ruleId;
            v.violationDesc = "事前风控拒绝：rule=" + r.ruleId + " code=" + r.rejectCode
                    + " current=" + nzStr(r.currentValue) + " threshold=" + nzStr(r.thresholdValue);
            v.source = SRC_PRE_TRADE_REJECT;
            v.occurTime = r.rejectTime != null ? r.rejectTime : com.hedgeguard.common.Clocks.now();
            violationDao.insert(v, request.operatorId);
        }
        // 事中告警（异常下单行为）→ 频繁开平仓
        if (request.alerts != null) {
            for (RiskAlert a : request.alerts) {
                if (a.metricType != RiskMetricType.ABNORMAL_ORDER || a.subAccountId <= 0) {
                    continue;
                }
                if (violationDao.countByNaturalKey(a.subAccountId, date, V_FREQUENT_TRADE, "R-RISK-14",
                        SRC_INTRADAY_ALERT) > 0) {
                    continue;
                }
                RiskViolation v = new RiskViolation();
                v.subAccountId = a.subAccountId;
                v.violationDate = date;
                v.violationType = V_FREQUENT_TRADE;
                v.severity = severityOfAlert(a.level);
                v.ruleId = "R-RISK-14";
                v.violationDesc = "事中告警-异常下单行为：" + nzStr(a.message);
                v.source = SRC_INTRADAY_ALERT;
                v.occurTime = com.hedgeguard.common.Clocks.now();
                violationDao.insert(v, request.operatorId);
            }
        }
    }

    /**
     * {@code RISK} 规则编号（空间乙）→ 违规类型（{@code FR-RISK-18} 六类）。
     *
     * @param ruleId 规则编号
     * @return 违规类型；不属六类返回 {@code null}（如 {@code R-RISK-8} 价格偏离）
     */
    static String mapRuleToType(String ruleId) {
        if (ruleId == null) {
            return null;
        }
        switch (ruleId) {
            case "R-RISK-1":
                return V_OVER_PRODUCT;
            case "R-RISK-2":
            case "R-RISK-3":
            case "R-RISK-4":
            case "R-RISK-5":
                return V_OVER_SCALE;
            case "R-RISK-6":
                return V_OVER_TERM;
            case "R-RISK-7":
                return V_OVER_AUTH;
            case "R-PLAN-8":
            case "R-PLAN-9":
                return V_DOC_MISSING;
            default:
                return null;
        }
    }

    /** 违规类型 → 缺省严重级别（本单设计决定）。 */
    static String defaultSeverity(String type) {
        if (V_OVER_PRODUCT.equals(type)) {
            return SEV_LOW;
        }
        if (V_OVER_SCALE.equals(type) || V_OVER_AUTH.equals(type)) {
            return SEV_HIGH;
        }
        return SEV_MEDIUM;
    }

    private static String severityOfAlert(RiskAlertLevel level) {
        if (level == RiskAlertLevel.CRITICAL) {
            return SEV_HIGH;
        }
        if (level == RiskAlertLevel.WARNING) {
            return SEV_MEDIUM;
        }
        return SEV_LOW;
    }

    private static Map<String, Integer> countBy(List<RiskViolation> violations, boolean byType) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (RiskViolation v : violations) {
            String key = byType ? v.violationType : v.severity;
            m.merge(key, 1, Integer::sum);
        }
        return m;
    }

    // ------------------------------------------------------------------ 限额使用

    private List<LimitUsageStat> persistLimitUsage(PostRiskReportRequest request) {
        LocalDate date = request.tradeDate;
        if (request.metrics != null) {
            for (SubAccountRiskMetrics sub : request.metrics.subAccounts) {
                if (sub.subAccountId <= 0) {
                    continue;
                }
                for (ProductQuota q : productQuotaDao.findByLayerAndScope("SUB_PRODUCT",
                        sub.subAccountId)) {
                    if (q.quotaLimit == null || q.quotaLimit.signum() <= 0 || q.productId == null) {
                        continue;
                    }
                    long exists = limitUsageDao.count(
                            "stat_date = ? AND sub_account_id = ? AND product_id = ? AND limit_type = ?",
                            Arrays.asList(date, sub.subAccountId, q.productId, LIMIT_PRODUCT_QUOTA));
                    if (exists > 0) {
                        continue;
                    }
                    BigDecimal used = nz(q.usedQty);
                    BigDecimal ratio = used.divide(q.quotaLimit, RATIO_SCALE, RoundingMode.HALF_UP);
                    LimitUsageStat u = new LimitUsageStat();
                    u.subAccountId = sub.subAccountId;
                    u.productId = q.productId;
                    u.statDate = date;
                    u.limitType = LIMIT_PRODUCT_QUOTA;
                    u.limitValue = q.quotaLimit;
                    u.usedValue = used;
                    u.usageRatio = ratio;
                    u.hintLevel = thresholds.level(ratio);
                    u.hintThreshold1 = thresholds.hint;
                    u.hintThreshold2 = thresholds.warn;
                    limitUsageDao.insert(u, request.operatorId);
                }
            }
        }
        return limitUsageDao.findByDate(date);
    }

    // ------------------------------------------------------------------ 报表落库

    private void upsertReport(LocalDate date, String type, Map<String, Object> content,
                              String reconcileKey, Long settleCycleId, long operatorId) {
        PostRiskReport existing = reportDao.findByKey(date, type, null);
        PostRiskReport r = existing == null ? new PostRiskReport() : existing;
        r.subAccountId = null;
        r.reportDate = date;
        r.reportType = type;
        r.contentJson = JsonCodec.toJson(content);
        r.settleReconcileKey = reconcileKey;
        r.settleCycleId = settleCycleId;
        r.generatedBy = operatorId;
        r.generatedTime = com.hedgeguard.common.Clocks.now();
        if (existing == null) {
            reportDao.insert(r, operatorId);
        } else {
            reportDao.updateById(r, operatorId);
        }
    }

    private Map<String, Object> riskMetricContent(PostRiskReportRequest request, String reconcileKey,
                                                  BigDecimal reconcileDiff) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tradeDate", request.tradeDate.toString());
        content.put("settleReconcileKey", reconcileKey);
        content.put("reconcileDiff", reconcileDiff.toPlainString());
        if (request.metrics != null) {
            content.put("readyState", request.metrics.readyState == null ? null
                    : request.metrics.readyState.name());
            content.put("watermarkEventSeq", request.metrics.watermarkEventSeq);
            content.put("paramVersion", request.metrics.paramVersion);
            content.put("aggregate", metricsView(request.metrics.aggregate));
            List<Map<String, Object>> subs = new ArrayList<>();
            for (SubAccountRiskMetrics m : request.metrics.subAccounts) {
                subs.add(metricsView(m));
            }
            content.put("subAccounts", subs);
        }
        return content;
    }

    private static Map<String, Object> metricsView(SubAccountRiskMetrics m) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (m == null) {
            return row;
        }
        row.put("subAccountId", m.subAccountId);
        row.put("positionQty", str(m.positionQty));
        row.put("floatPnl", str(m.floatPnl));
        row.put("marginOccupied", str(m.marginOccupied));
        row.put("riskRatio", str(m.riskRatio));
        row.put("marginUsageRate", str(m.marginUsageRate));
        return row;
    }

    private static Map<String, Object> violationContent(LocalDate date, List<RiskViolation> violations) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tradeDate", date.toString());
        content.put("total", violations.size());
        content.put("byType", countBy(violations, true));
        content.put("bySeverity", countBy(violations, false));
        return content;
    }

    private static Map<String, Object> limitUsageContent(LocalDate date, List<LimitUsageStat> usages) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tradeDate", date.toString());
        content.put("total", usages.size());
        List<Map<String, Object>> items = new ArrayList<>();
        int hint = 0;
        int warning = 0;
        int exceeded = 0;
        for (LimitUsageStat u : usages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subAccountId", u.subAccountId);
            row.put("productId", u.productId);
            row.put("limitType", u.limitType);
            row.put("limitValue", str(u.limitValue));
            row.put("usedValue", str(u.usedValue));
            row.put("usageRatio", str(u.usageRatio));
            row.put("hintLevel", u.hintLevel);
            items.add(row);
            if (HINT_HINT.equals(u.hintLevel)) {
                hint++;
            } else if (HINT_WARNING.equals(u.hintLevel)) {
                warning++;
            } else if (HINT_EXCEEDED.equals(u.hintLevel)) {
                exceeded++;
            }
        }
        content.put("items", items);
        content.put("hintCount", hint);
        content.put("warningCount", warning);
        content.put("exceededCount", exceeded);
        return content;
    }

    // ------------------------------------------------------------------ 工具

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nzStr(String v) {
        return v == null ? "-" : v;
    }

    private static String str(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
