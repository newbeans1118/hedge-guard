package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuditReportDao;
import com.hedgeguard.dao.MajorEventDao;
import com.hedgeguard.dao.OperationLogDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PostRiskReportDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.entity.AuditReport;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.dao.entity.OperationLog;
import com.hedgeguard.dao.entity.PostRiskReport;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.JsonCodec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计报表服务（{@code T-069}；{@code FR-AUDIT-5/6/7/12}、{@code R-AUDIT-2/3/5}）。
 *
 * <p>生成、提交与导出三类审计报表：
 * <ul>
 *   <li><b>季度报表</b>（{@code FR-AUDIT-5}）：品种 / 保值规模 / 盈亏 / 持仓；双侧盈亏；子账户 + 汇总两层；</li>
 *   <li><b>年度专项报告</b>（{@code FR-AUDIT-6}）：五部分 + 季度报告清单 + 核对任务与签署记录全覆盖（{@code Q-14}）；</li>
 *   <li><b>重大事项专项报告</b>（{@code FR-AUDIT-7}）：五节；事项类型缺失即拒绝；触发提醒。</li>
 * </ul>
 *
 * <p><b>留痕（{@code R-AUDIT-3}）</b>：生成 / 提交写入 {@code t_operation_log}（{@code AUDIT_REPORT_GENERATE} /
 * {@code AUDIT_REPORT_SUBMIT}；{@code t_audit_log.action_type} 的封闭枚举不含生成，故操作留痕落操作日志表）；
 * 导出写入 {@code t_audit_log}（{@code EXPORT}，{@code FR-AUDIT-12} 验收 3）。
 *
 * <p><b>导出前防篡改门禁</b>：导出经 {@link AuditLogService#assertChainIntact}；校验未通过 ⇒ {@code 10018} 阻断
 * （{@code FR-AUDIT-10} 验收 2）。
 *
 * <p><b>{@code FR-RISK-18} 验收 4</b>：事后风控汇总（违规事项汇总 / 限额使用）从 {@code t_post_risk_report}
 * 按期间读入报表内容，使「汇总结果可在 {@code AUDIT} 中导出」。
 *
 * <p><b>聚合数据源</b>：{@link ReportDataProvider} 接缝承接（生产装配 {@code DmAuditReportDataProvider}）；
 * 实货盈亏权威源在 {@code ACCT}（{@code T-076+} 未交付）⇒ 标注数据缺口，不伪造数值（{@code R-AUDIT-2}）。
 */
public final class AuditReportService {

    /** 报表类型：季度。 */
    public static final String TYPE_QUARTERLY = "QUARTERLY";
    /** 报表类型：年度专项。 */
    public static final String TYPE_ANNUAL = "ANNUAL_SPECIAL";
    /** 报表类型：重大事项。 */
    public static final String TYPE_MAJOR_EVENT = "MAJOR_EVENT";

    /** 操作留痕：报表生成。 */
    public static final String OP_GENERATE = "AUDIT_REPORT_GENERATE";
    /** 操作留痕：报表提交。 */
    public static final String OP_SUBMIT = "AUDIT_REPORT_SUBMIT";

    /** 完整性：完整。 */
    public static final String COMPLETE = "COMPLETE";
    /** 完整性：无法判定（数据缺口）。 */
    public static final String UNKNOWN = "UNKNOWN";

    /** 数据缺口说明（实货盈亏）。 */
    public static final String GAP_PHYSICAL_PNL = "实货盈亏数据源=ACCT（T-076+）未交付，本期记数据缺口";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final int PAGE_SIZE = 100;

    private final AuditReportDao reportDao;
    private final MajorEventDao majorEventDao;
    private final OperationLogDao operationLogDao;
    private final AuditLogService auditLogService;
    private final CheckTaskQueryService checkTaskQueryService;
    private final PostRiskReportDao postRiskReportDao;
    private final ReportDataProvider dataProvider;
    private final SubAccountVisibility authService;
    private final TxTemplate tx;

    /**
     * 构造。
     *
     * @param reportDao             审计报表 DAO
     * @param majorEventDao         重大事项 DAO
     * @param operationLogDao       操作留痕 DAO
     * @param auditLogService       审计日志服务（导出留痕 + 防篡改门禁）
     * @param checkTaskQueryService 核对任务查询（年度全覆盖）
     * @param postRiskReportDao     事后风险报表 DAO（{@code FR-RISK-18} 验收 4）
     * @param dataProvider          报表聚合数据源接缝
     * @param authService           可见范围取数
     * @param tx                    事务模板
     */
    public AuditReportService(AuditReportDao reportDao, MajorEventDao majorEventDao,
                              OperationLogDao operationLogDao, AuditLogService auditLogService,
                              CheckTaskQueryService checkTaskQueryService,
                              PostRiskReportDao postRiskReportDao,
                              ReportDataProvider dataProvider, SubAccountVisibility authService,
                              TxTemplate tx) {
        this.reportDao = reportDao;
        this.majorEventDao = majorEventDao;
        this.operationLogDao = operationLogDao;
        this.auditLogService = auditLogService;
        this.checkTaskQueryService = checkTaskQueryService;
        this.postRiskReportDao = postRiskReportDao;
        this.dataProvider = dataProvider;
        this.authService = authService;
        this.tx = tx;
    }

    // ------------------------------------------------------------ 生成

    /**
     * 生成季度报表（{@code FR-AUDIT-5}）。
     *
     * @param periodKey   期间键（{@code 2026-Q3}）
     * @param subAccountIds 子账户范围（可空 = 全部可见）
     * @param operatorId  生成人
     * @return 落库后的报表
     */
    public AuditReport generateQuarterly(String periodKey, List<Integer> subAccountIds, long operatorId) {
        LocalDate from = quarterStart(periodKey);
        LocalDate to = from.plusMonths(3).minusDays(1);
        List<Integer> visible = visible(operatorId);
        boolean cross = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        List<ReportRow> rows = rowsOrReject(from, to, visible, cross);
        String content = buildQuarterlyContent(periodKey, from, to, rows, operatorId);
        return persist(TYPE_QUARTERLY, periodKey, subAccountIds, content, COMPLETE, null,
                physicalGap(rows) ? GAP_PHYSICAL_PNL : null, OPERATOR_SYSTEM, operatorId);
    }

    /**
     * 生成年度专项报告（{@code FR-AUDIT-6}）。
     *
     * @param year         报告年度（{@code 2026}）
     * @param subAccountIds 子账户范围（可空 = 全部可见）
     * @param operatorId   生成人
     * @return 落库后的报表
     */
    public AuditReport generateAnnual(String year, List<Integer> subAccountIds, long operatorId) {
        LocalDate from = LocalDate.of(Integer.parseInt(year.trim()), 1, 1);
        LocalDate to = from.plusYears(1).minusDays(1);
        List<Integer> visible = visible(operatorId);
        boolean cross = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        List<ReportRow> rows = rowsOrReject(from, to, visible, cross);

        // 季度报告清单 + 缺口标注（验收 2/4）。
        List<String> quarters = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int q = 1; q <= 4; q++) {
            String key = year.trim() + "-Q" + q;
            quarters.add(key);
            AuditReport r = findByPeriod(TYPE_QUARTERLY, key);
            if (r != null) {
                present.add(key);
            } else {
                missing.add(key);
            }
        }
        // 核对任务与签署记录全覆盖（验收 2；Q-14）。
        CheckTaskQueryService.AnnualCoverage coverage =
                checkTaskQueryService == null ? null
                        : checkTaskQueryService.annualCoverage(year, to, operatorId);

        String content = buildAnnualContent(year, rows, present, missing, coverage, operatorId);
        String integrity = missing.isEmpty() ? COMPLETE : UNKNOWN;
        List<String> breaks = new ArrayList<>();
        for (String m : missing) {
            breaks.add("季报缺失：" + m);
        }
        String gap = missing.isEmpty() ? (physicalGap(rows) ? GAP_PHYSICAL_PNL : null)
                : "缺少季度数据：" + missing + (physicalGap(rows) ? "；" + GAP_PHYSICAL_PNL : "");
        return persist(TYPE_ANNUAL, year.trim(), subAccountIds, content, integrity,
                breaks.isEmpty() ? null : breaks, gap, OPERATOR_SYSTEM, operatorId);
    }

    /**
     * 生成重大事项专项报告（{@code FR-AUDIT-7}）。事项类型缺失 ⇒ 拒绝（验收 3）。
     *
     * @param req        重大事项请求
     * @param operatorId 生成人
     * @return 落库后的报表
     */
    public AuditReport generateMajorEvent(MajorEventRequest req, long operatorId) {
        if (req == null || req.eventType == null || req.eventType.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "重大事项类型必填（FR-AUDIT-7 验收 3）");
        }
        String eventType = req.eventType.trim();
        String periodKey = req.eventNo == null || req.eventNo.trim().isEmpty()
                ? eventType + "-" + STAMP.format(Clocks.now()) : req.eventNo.trim();
        String content = buildMajorEventContent(req, operatorId);
        AuditReport report = persist(TYPE_MAJOR_EVENT, periodKey, singleton(req.subAccountId),
                content, COMPLETE, null, null, OPERATOR_SYSTEM, operatorId);

        // 关联/落重大事项（状态机 TRIGGERED → REPORT_GENERATED）。
        tx.inTransaction(connection -> {
            MajorEvent event = req.eventNo == null || req.eventNo.trim().isEmpty()
                    ? null : findEvent(req.eventNo.trim());
            if (event == null) {
                event = new MajorEvent();
                event.subAccountId = req.subAccountId;
                event.eventNo = req.eventNo == null || req.eventNo.trim().isEmpty()
                        ? "ME" + STAMP.format(Clocks.now()) : req.eventNo.trim();
                event.eventType = eventType;
                event.triggerSource = req.triggerSource == null ? "-" : req.triggerSource;
                event.eventDesc = req.eventDesc == null ? "-" : req.eventDesc;
                event.impactScope = req.impactScope;
                event.handlingMeasures = req.handlingMeasures;
                event.rectificationPlan = req.rectificationPlan;
                event.status = "REPORT_GENERATED";
                event.auditReportId = report.id;
                event.triggeredTime = Clocks.now();
                majorEventDao.insert(event, operatorId);
            } else {
                event.status = "REPORT_GENERATED";
                event.auditReportId = report.id;
                majorEventDao.updateById(event, operatorId);
            }
            return null;
        });
        return report;
    }

    /** 待生成的重大事项提醒（{@code FR-AUDIT-7} 验收 1：命中触发条件即提醒）。 */
    public List<MajorEvent> pendingMajorEvents(long operatorId) {
        List<MajorEvent> out = new ArrayList<>();
        if (majorEventDao == null) {
            return out;
        }
        for (String type : MAJOR_EVENT_TYPES) {
            out.addAll(majorEventDao.findByTypeAndStatus(type, "TRIGGERED"));
        }
        return out;
    }

    /** 提交报表（{@code FR-AUDIT-7} 验收 4：提交留痕）。 */
    public AuditReport submit(String reportNo, String submittedTo, long operatorId) {
        AuditReport report = reportDao.findByReportNo(reportNo);
        if (report == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "报表不存在：reportNo=" + reportNo);
        }
        report.submitted = "Y";
        report.submittedTo = submittedTo;
        report.submittedTime = Clocks.now();
        if (tx.inTransaction(connection -> reportDao.updateById(report, operatorId)) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "报表提交失败：reportNo=" + reportNo);
        }
        writeOperation(OP_SUBMIT, "AUDIT_REPORT", report.id, report.reportNo,
                "submittedTo=" + submittedTo, report.subAccountId, operatorId);
        return report;
    }

    // ------------------------------------------------------------ 导出（FR-AUDIT-12）

    /**
     * 导出审计报表（{@code FR-AUDIT-12}）。无数据 ⇒ {@code 10015} 不生成空文件（验收 4）；
     * 导出动作写入审计日志（验收 3）。
     *
     * @param reportNo   报表编号
     * @param operatorId 导出人
     * @return 导出内容（含类型 / 时间范围 / 生成时间 / 生成人 / 子账户范围）
     */
    public ReportExport export(String reportNo, long operatorId) {
        AuditReport report = reportDao.findByReportNo(reportNo);
        if (report == null) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "报表不存在或无数据，不生成空文件（FR-AUDIT-12 验收 4）：reportNo=" + reportNo);
        }
        // 导出前防篡改门禁（FR-AUDIT-10 验收 2）。
        auditLogService.assertChainIntact(operatorId);
        String fileId = "REPORT-" + report.reportNo;
        StringBuilder sb = new StringBuilder();
        sb.append("# 审计报表导出（FR-AUDIT-12）\n");
        sb.append("报表类型: ").append(report.reportType).append('\n');
        sb.append("期间: ").append(report.periodKey).append('\n');
        sb.append("生成时间: ").append(report.generatedTime).append('\n');
        sb.append("生成人: ").append(report.generatedBy).append('\n');
        sb.append("子账户范围: ").append(report.subAccountId == null ? "汇总" : report.subAccountId).append('\n');
        sb.append("完整性: ").append(report.chainIntegrity).append('\n');
        if (report.chainIntegrity != null && !COMPLETE.equals(report.chainIntegrity)) {
            sb.append(">>> 链条不完整\n");
            if (report.breakList != null) {
                sb.append("断点清单:\n").append(report.breakList).append('\n');
            }
        }
        if (report.dataGapDesc != null) {
            sb.append("数据缺口: ").append(report.dataGapDesc).append('\n');
        }
        sb.append("\n").append(report.contentJson).append('\n');

        AuditLog log = new AuditLog();
        log.subAccountId = report.subAccountId;
        log.auditUserId = operatorId;
        log.actionType = AuditLogService.ACTION_EXPORT;
        log.queryCondition = "report export reportNo=" + report.reportNo;
        log.resultCount = 1;
        log.exportFileId = fileId;
        log.chainIntegrity = report.chainIntegrity;
        auditLogService.append(log, operatorId);

        ReportExport out = new ReportExport();
        out.report = report;
        out.fileId = fileId;
        out.content = sb.toString();
        return out;
    }

    // ------------------------------------------------------------ 内部辅助

    private static final long OPERATOR_SYSTEM = 0L;

    private static final List<String> MAJOR_EVENT_TYPES = java.util.Arrays.asList(
            "AGG_INCONSISTENT_TIMEOUT", "SCALE_REDLINE_HIT", "EXCHANGE_INQUIRY",
            "FORCE_CLOSE_HINT_UNDISPOSED", "LOSS_THRESHOLD");

    private List<ReportRow> rowsOrReject(LocalDate from, LocalDate to, List<Integer> visible,
                                         boolean cross) {
        List<ReportRow> rows = dataProvider == null
                ? new ArrayList<>() : dataProvider.rows(from, to, visible, cross);
        if (rows == null || rows.isEmpty()) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "期间无数据，拒绝生成（FR-AUDIT-5）：from=" + from + " to=" + to);
        }
        return rows;
    }

    private boolean physicalGap(List<ReportRow> rows) {
        for (ReportRow r : rows) {
            if (r.physicalPnl == null) {
                return true;
            }
        }
        return rows.isEmpty();
    }

    private AuditReport persist(String type, String periodKey, List<Integer> subAccountIds,
                                String content, String integrity, List<String> breaks,
                                String gap, long generatedBy, long operatorId) {
        AuditReport r = new AuditReport();
        r.subAccountId = subAccountIds != null && subAccountIds.size() == 1 ? subAccountIds.get(0) : null;
        r.reportNo = uniqueReportNo(type, periodKey);
        r.reportType = type;
        r.periodKey = periodKey;
        r.contentJson = content;
        r.dualPnlIncluded = "Y";
        r.chainIntegrity = integrity;
        r.breakList = breaks == null || breaks.isEmpty() ? null : String.join("\n", breaks);
        r.dataGapDesc = gap;
        r.generatedBy = generatedBy == OPERATOR_SYSTEM ? operatorId : generatedBy;
        r.generatedTime = Clocks.now();
        r.submitted = "N";
        if (tx.inTransaction(connection -> reportDao.insert(r, operatorId)) != 1) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "审计报表落库失败（report_no 唯一约束）：reportNo=" + r.reportNo);
        }
        writeOperation(OP_GENERATE, "AUDIT_REPORT", r.id, r.reportNo,
                "type=" + type + " period=" + periodKey + " integrity=" + integrity,
                r.subAccountId, operatorId);
        return r;
    }

    private void writeOperation(String opType, String objectType, long objectId, String objectNo,
                                String detail, Integer subAccountId, long operatorId) {
        if (operationLogDao == null) {
            return;
        }
        OperationLog log = new OperationLog();
        log.subAccountId = subAccountId;
        log.operatorId = operatorId;
        log.operationType = opType;
        log.objectType = objectType;
        log.objectId = objectId;
        log.objectNo = objectNo;
        log.actionResult = "SUCCESS";
        log.resultDetail = detail;
        log.operationTime = Clocks.now();
        tx.inTransaction(connection -> operationLogDao.insert(log, operatorId));
    }

    private AuditReport findByPeriod(String type, String periodKey) {
        String where = "report_type = ? AND period_key = ?";
        List<Object> args = new ArrayList<>();
        args.add(type);
        args.add(periodKey);
        PageResult<AuditReport> pr = reportDao.findPage(
                PageQuery.of("1", String.valueOf(PAGE_SIZE), null, "id", "asc"), where, args);
        return pr.items().isEmpty() ? null : pr.items().get(0);
    }

    private MajorEvent findEvent(String eventNo) {
        String where = "event_no = ?";
        List<Object> args = new ArrayList<>();
        args.add(eventNo);
        PageResult<MajorEvent> pr = majorEventDao.findPage(
                PageQuery.of("1", String.valueOf(PAGE_SIZE), null, "id", "asc"), where, args);
        return pr.items().isEmpty() ? null : pr.items().get(0);
    }

    private List<PostRiskReport> postRiskSummary(LocalDate from, LocalDate to) {
        List<PostRiskReport> out = new ArrayList<>();
        if (postRiskReportDao == null) {
            return out;
        }
        PageResult<PostRiskReport> pr = postRiskReportDao.findPage(
                PageQuery.of("1", String.valueOf(PAGE_SIZE), null, "id", "asc"),
                "report_date >= ? AND report_date <= ?",
                new ArrayList<Object>(java.util.Arrays.asList(from, to)));
        out.addAll(pr.items());
        return out;
    }

    private String buildQuarterlyContent(String periodKey, LocalDate from, LocalDate to,
                                         List<ReportRow> rows, long operatorId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reportType", TYPE_QUARTERLY);
        content.put("periodKey", periodKey);
        content.put("from", from.toString());
        content.put("to", to.toString());
        content.put("generatedTime", Clocks.now().toString());
        content.put("generatedBy", operatorId);
        content.put("dualPnlIncluded", "Y");
        content.put("rows", rowMaps(rows));
        content.put("summary", summary(rows));
        content.put("dualPnl", dualPnl(rows));
        content.put("riskSummary", riskSummaryMaps(from, to));
        return JsonCodec.toJson(content);
    }

    private String buildAnnualContent(String year, List<ReportRow> rows, List<String> present,
                                      List<String> missing,
                                      CheckTaskQueryService.AnnualCoverage coverage,
                                      long operatorId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reportType", TYPE_ANNUAL);
        content.put("year", year);
        content.put("generatedTime", Clocks.now().toString());
        content.put("generatedBy", operatorId);
        content.put("rows", rowMaps(rows));
        content.put("summary", summary(rows));
        content.put("dualPnl", dualPnl(rows));
        content.put("quarterlyReports", present);
        content.put("missingQuarters", missing);
        if (coverage != null) {
            Map<String, Object> cov = new LinkedHashMap<>();
            for (Map.Entry<String, CheckTaskQueryService.Coverage> e : coverage.byType.entrySet()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("total", e.getValue().total);
                c.put("completed", e.getValue().completed);
                c.put("overdue", e.getValue().overdue);
                c.put("rate", e.getValue().rate);
                cov.put(e.getKey(), c);
            }
            content.put("checkTaskCoverage", cov);
            List<String> overdue = new ArrayList<>();
            for (com.hedgeguard.dao.entity.SettleCheckTask t : coverage.overdue) {
                overdue.add(t.checkType + "/" + t.periodKey + "/sub=" + t.subAccountId);
            }
            content.put("overdueUnsigned", overdue);
        }
        return JsonCodec.toJson(content);
    }

    private String buildMajorEventContent(MajorEventRequest req, long operatorId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reportType", TYPE_MAJOR_EVENT);
        content.put("eventType", req.eventType);
        content.put("eventNo", req.eventNo);
        content.put("generatedTime", Clocks.now().toString());
        content.put("generatedBy", operatorId);
        content.put("eventDesc", req.eventDesc);
        content.put("causeAnalysis", req.causeAnalysis);
        content.put("impactScope", req.impactScope);
        content.put("handlingMeasures", req.handlingMeasures);
        content.put("rectificationPlan", req.rectificationPlan);
        return JsonCodec.toJson(content);
    }

    private List<Map<String, Object>> rowMaps(List<ReportRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReportRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subAccountId", r.subAccountId);
            m.put("productId", r.productId);
            m.put("productName", r.productName);
            m.put("hedgeScale", r.hedgeScale);
            m.put("physicalPnl", r.physicalPnl);
            m.put("derivativePnl", r.derivativePnl);
            m.put("positionQty", r.positionQty);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> summary(List<ReportRow> rows) {
        BigDecimal hedgeScale = BigDecimal.ZERO;
        BigDecimal physical = BigDecimal.ZERO;
        BigDecimal derivative = BigDecimal.ZERO;
        BigDecimal position = BigDecimal.ZERO;
        boolean physicalMissing = false;
        for (ReportRow r : rows) {
            hedgeScale = hedgeScale.add(nz(r.hedgeScale));
            derivative = derivative.add(nz(r.derivativePnl));
            position = position.add(nz(r.positionQty));
            if (r.physicalPnl == null) {
                physicalMissing = true;
            } else {
                physical = physical.add(r.physicalPnl);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hedgeScale", hedgeScale);
        m.put("physicalPnl", physicalMissing ? null : physical);
        m.put("derivativePnl", derivative);
        m.put("positionQty", position);
        return m;
    }

    private Map<String, Object> dualPnl(List<ReportRow> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("physicalPnl", physicalGap(rows) ? null : summary(rows).get("physicalPnl"));
        m.put("derivativePnl", summary(rows).get("derivativePnl"));
        m.put("note", physicalGap(rows) ? GAP_PHYSICAL_PNL : null);
        return m;
    }

    private List<Map<String, Object>> riskSummaryMaps(LocalDate from, LocalDate to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PostRiskReport r : postRiskSummary(from, to)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reportDate", r.reportDate == null ? null : r.reportDate.toString());
            m.put("reportType", r.reportType);
            m.put("subAccountId", r.subAccountId);
            m.put("contentJson", r.contentJson);
            out.add(m);
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static LocalDate quarterStart(String periodKey) {
        if (periodKey == null || !periodKey.matches("\\d{4}-Q[1-4]")) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "报告季度格式须为 yyyy-Qn：值=" + periodKey);
        }
        int year = Integer.parseInt(periodKey.substring(0, 4));
        int q = periodKey.charAt(6) - '0';
        return LocalDate.of(year, (q - 1) * 3 + 1, 1);
    }

    private static List<Integer> singleton(Integer v) {
        if (v == null) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        out.add(v);
        return out;
    }

    private String uniqueReportNo(String type, String periodKey) {
        String prefix = "AR" + typePrefix(type);
        String base = prefix + "-" + periodKey;
        if (reportDao.findByReportNo(base) == null) {
            return base;
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (reportDao.findByReportNo(candidate) == null) {
                return candidate;
            }
        }
        throw BizException.of(ErrorCode.UNIQUE_CONFLICT, "报表编号生成失败：" + base);
    }

    private static String typePrefix(String type) {
        if (TYPE_QUARTERLY.equals(type)) {
            return "Q";
        }
        if (TYPE_ANNUAL.equals(type)) {
            return "Y";
        }
        return "M";
    }

    private List<Integer> visible(long operatorId) {
        List<Integer> v = authService == null ? null : authService.visibleSubAccountIds(operatorId);
        return v == null ? new ArrayList<>() : new ArrayList<>(v);
    }

    // ------------------------------------------------------------ 接缝与载体

    /** 报表聚合数据源接缝（生产装配真实 DAO 实现）。 */
    public interface ReportDataProvider {
        /**
         * 取期间的聚合明细行（按 子账户 × 品种）。
         *
         * @param from              期间起
         * @param to                期间止
         * @param visibleSubAccounts 可见子账户（含哨兵 0 表示跨账户）
         * @param crossSubAccount  是否跨账户
         * @return 明细行
         */
        List<ReportRow> rows(LocalDate from, LocalDate to, List<Integer> visibleSubAccounts,
                             boolean crossSubAccount);
    }

    /** 报表明细行（子账户 × 品种）。 */
    public static final class ReportRow {
        /** 子账户。 */
        public Integer subAccountId;
        /** 品种。 */
        public Long productId;
        /** 品种名称。 */
        public String productName;
        /** 保值规模。 */
        public BigDecimal hedgeScale;
        /** 实货盈亏（{@code null} = 数据缺口）。 */
        public BigDecimal physicalPnl;
        /** 衍生品盈亏。 */
        public BigDecimal derivativePnl;
        /** 持仓量。 */
        public BigDecimal positionQty;
    }

    /** 重大事项专项报告请求（{@code FR-AUDIT-7} 输入列）。 */
    public static final class MajorEventRequest {
        /** 事项类型（必填）。 */
        public String eventType;
        /** 事项编号（可空，空则按类型 + 时间生成）。 */
        public String eventNo;
        /** 涉及子账户。 */
        public Integer subAccountId;
        /** 触发来源。 */
        public String triggerSource;
        /** 事项描述。 */
        public String eventDesc;
        /** 原因分析。 */
        public String causeAnalysis;
        /** 影响范围。 */
        public String impactScope;
        /** 处置措施。 */
        public String handlingMeasures;
        /** 整改计划。 */
        public String rectificationPlan;
    }

    /** 报表导出结果。 */
    public static final class ReportExport {
        /** 报表。 */
        public AuditReport report;
        /** 导出文件标识。 */
        public String fileId;
        /** 导出文件内容。 */
        public String content;
    }
}
