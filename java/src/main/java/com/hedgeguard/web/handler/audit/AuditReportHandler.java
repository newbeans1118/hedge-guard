package com.hedgeguard.web.handler.audit;

import com.hedgeguard.dao.entity.AuditReport;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.service.AuditReportService;
import com.hedgeguard.service.AuditReportService.MajorEventRequest;
import com.hedgeguard.service.AuditReportService.ReportExport;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计报表 REST 面（{@code T-069}；{@code FR-AUDIT-5/6/7/12}）。
 *
 * <p>三条预声明路径（逐字取自 {@code ApiRoutes} {@code §4.1.1}），每条 GET/POST：
 * <ul>
 *   <li>{@code /audit/report/quarterly}（{@code FR-AUDIT-5}/{@code 12}）；</li>
 *   <li>{@code /audit/report/annual}（{@code FR-AUDIT-6}）；</li>
 *   <li>{@code /audit/report/major-event}（{@code FR-AUDIT-7}）。</li>
 * </ul>
 * POST = 生成 / 提交（{@code action=GENERATE|SUBMIT}）；GET = 导出（须 {@code reportNo}；否则重大事项返回待生成提醒）。
 */
public final class AuditReportHandler implements RouteHandler {

    /** 季度报表路径。 */
    public static final String PATH_QUARTERLY = "/api/v1/audit/report/quarterly";
    /** 年度专项报告路径。 */
    public static final String PATH_ANNUAL = "/api/v1/audit/report/annual";
    /** 重大事项专项报告路径。 */
    public static final String PATH_MAJOR_EVENT = "/api/v1/audit/report/major-event";

    /** 模式：季度。 */
    public static final String MODE_QUARTERLY = "QUARTERLY";
    /** 模式：年度。 */
    public static final String MODE_ANNUAL = "ANNUAL";
    /** 模式：重大事项。 */
    public static final String MODE_MAJOR_EVENT = "MAJOR_EVENT";

    private final AuditReportService service;
    private final String mode;

    /**
     * 构造。
     *
     * @param mode    报表模式（{@link #MODE_QUARTERLY}/{@link #MODE_ANNUAL}/{@link #MODE_MAJOR_EVENT}）
     * @param service 报表服务
     */
    public AuditReportHandler(String mode, AuditReportService service) {
        this.mode = mode;
        this.service = service;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        return "GET".equalsIgnoreCase(ctx.method()) ? get(ctx) : post(ctx);
    }

    private HttpResult post(RequestContext ctx) {
        Input in = body(ctx, Input.class);
        if (in == null) {
            in = new Input();
        }
        String action = in.action == null ? "GENERATE" : in.action.trim().toUpperCase(java.util.Locale.ROOT);
        if ("SUBMIT".equals(action)) {
            if (in.reportNo == null || in.reportNo.trim().isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "提交须提供 reportNo（FR-AUDIT-7 验收 4）");
            }
            AuditReport r = service.submit(in.reportNo.trim(), in.submittedTo, ctx.currentUserId());
            return HttpResult.json(200, ApiResponse.ok(reportView(r), ctx.traceId()));
        }
        long operator = ctx.currentUserId();
        AuditReport r;
        if (MODE_QUARTERLY.equals(mode)) {
            if (in.periodKey == null || in.periodKey.trim().isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "季度必填（periodKey，如 2026-Q3）");
            }
            r = service.generateQuarterly(in.periodKey.trim(), in.subAccountIds, operator);
        } else if (MODE_ANNUAL.equals(mode)) {
            if (in.year == null || in.year.trim().isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "年度必填（year，如 2026）");
            }
            r = service.generateAnnual(in.year.trim(), in.subAccountIds, operator);
        } else {
            MajorEventRequest req = new MajorEventRequest();
            req.eventType = in.eventType;
            req.eventNo = in.eventNo;
            req.subAccountId = in.subAccountId;
            req.triggerSource = in.triggerSource;
            req.eventDesc = in.eventDesc;
            req.causeAnalysis = in.causeAnalysis;
            req.impactScope = in.impactScope;
            req.handlingMeasures = in.handlingMeasures;
            req.rectificationPlan = in.rectificationPlan;
            r = service.generateMajorEvent(req, operator);
        }
        return HttpResult.json(200, ApiResponse.ok(reportView(r), ctx.traceId()));
    }

    private HttpResult get(RequestContext ctx) {
        long operator = ctx.currentUserId();
        String reportNo = text(ctx.queryParam("reportNo"));
        if (reportNo == null && MODE_MAJOR_EVENT.equals(mode)) {
            List<Map<String, Object>> pending = new ArrayList<>();
            for (MajorEvent e : service.pendingMajorEvents(operator)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("eventNo", e.eventNo);
                m.put("eventType", e.eventType);
                m.put("status", e.status);
                m.put("triggerSource", e.triggerSource);
                m.put("triggeredTime", e.triggeredTime == null ? null : e.triggeredTime.toString());
                pending.add(m);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pendingMajorEvents", pending);
            data.put("note", pending.isEmpty() ? "无待生成的重大事项专项报告" : "命中触发条件，须生成专项报告");
            return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
        }
        if (reportNo == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "导出须提供 reportNo（FR-AUDIT-12）");
        }
        ReportExport ex = service.export(reportNo, operator);
        return HttpResult.bytes(200, "text/plain; charset=UTF-8",
                        ex.content.getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition", "attachment; filename=\"" + ex.fileId + ".txt\"");
    }

    private static Map<String, Object> reportView(AuditReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) {
            return m;
        }
        m.put("id", r.id);
        m.put("reportNo", r.reportNo);
        m.put("reportType", r.reportType);
        m.put("periodKey", r.periodKey);
        m.put("subAccountId", r.subAccountId);
        m.put("dualPnlIncluded", r.dualPnlIncluded);
        m.put("chainIntegrity", r.chainIntegrity);
        m.put("breakList", r.breakList);
        m.put("dataGapDesc", r.dataGapDesc);
        m.put("generatedBy", r.generatedBy);
        m.put("generatedTime", r.generatedTime == null ? null : r.generatedTime.toString());
        m.put("submitted", r.submitted);
        m.put("submittedTo", r.submittedTo);
        m.put("submittedTime", r.submittedTime == null ? null : r.submittedTime.toString());
        m.put("contentJson", r.contentJson);
        return m;
    }

    private static <T> T body(RequestContext ctx, Class<T> type) {
        String b = ctx.body();
        if (b == null || b.trim().isEmpty()) {
            return null;
        }
        return JsonCodec.fromJson(b, type);
    }

    private static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** POST 请求体。 */
    public static final class Input {
        /** 动作：{@code GENERATE}（缺省）/ {@code SUBMIT}。 */
        public String action;
        /** 季度键（季度模式必填）。 */
        public String periodKey;
        /** 年度（年度模式必填）。 */
        public String year;
        /** 子账户范围。 */
        public List<Integer> subAccountIds;
        /** 报表编号（提交/导出）。 */
        public String reportNo;
        /** 提交对象。 */
        public String submittedTo;
        /** 事项类型（重大事项必填）。 */
        public String eventType;
        /** 事项编号。 */
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
}
