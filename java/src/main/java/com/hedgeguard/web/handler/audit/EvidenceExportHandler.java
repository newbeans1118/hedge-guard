package com.hedgeguard.web.handler.audit;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.EvidenceExport;
import com.hedgeguard.service.EvidenceExportService;
import com.hedgeguard.service.EvidenceExportService.EvidenceExportRequest;
import com.hedgeguard.service.EvidenceExportService.EvidenceExportResult;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据链导出 REST 面（{@code T-069}；{@code FR-AUDIT-8}）。
 *
 * <p>路径 {@code /api/v1/audit/evidence/export}（GET/POST）：
 * POST = 执行导出（返回导出文件内容与元数据）；GET = 查导出记录（{@code exportNo} 或分页）。
 */
public final class EvidenceExportHandler implements RouteHandler {

    /** 路径（逐字取自 {@code ApiRoutes}）。 */
    public static final String PATH = "/api/v1/audit/evidence/export";

    private final EvidenceExportService service;

    /** 构造。 */
    public EvidenceExportHandler(EvidenceExportService service) {
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
        EvidenceExportRequest req = new EvidenceExportRequest();
        req.startPointType = in.startPointType;
        req.startPointIds = in.startPointIds == null ? new ArrayList<>() : in.startPointIds;
        req.dateFrom = date(in.dateFrom);
        req.dateTo = date(in.dateTo);
        req.subAccountIds = in.subAccountIds;
        req.includeSourceDetail = Boolean.TRUE.equals(in.includeSourceDetail);
        EvidenceExportResult r = service.export(req, ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportNo", r.exportNo);
        data.put("fileId", r.fileId);
        data.put("chainIntegrity", r.chainIntegrity);
        data.put("breakList", r.breakList);
        data.put("dualPnlIncluded", r.dualPnlIncluded);
        data.put("reasonIncluded", r.reasonIncluded);
        data.put("auditLogId", r.auditLogId);
        data.put("nodeCount", r.nodeCount);
        data.put("segments", r.segments);
        data.put("content", r.content);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    private HttpResult get(RequestContext ctx) {
        String exportNo = text(ctx.queryParam("exportNo"));
        if (exportNo != null) {
            EvidenceExport ex = service.findByExportNo(exportNo);
            if (ex == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "导出记录不存在：exportNo=" + exportNo);
            }
            return HttpResult.json(200, ApiResponse.ok(view(ex), ctx.traceId()));
        }
        PageQuery query = PageQuery.of(text(ctx.queryParam("page")), text(ctx.queryParam("size")),
                text(ctx.queryParam("sort")), "exported_time", "desc");
        PageResult<EvidenceExport> page = service.queryExports(query);
        List<Map<String, Object>> items = new ArrayList<>();
        for (EvidenceExport ex : page.items()) {
            items.add(view(ex));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    private static Map<String, Object> view(EvidenceExport ex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ex.id);
        m.put("exportNo", ex.exportNo);
        m.put("startPointType", ex.startPointType);
        m.put("startPointIds", ex.startPointIds);
        m.put("dateFrom", ex.dateFrom == null ? null : ex.dateFrom.toString());
        m.put("dateTo", ex.dateTo == null ? null : ex.dateTo.toString());
        m.put("subAccountScope", ex.subAccountScope);
        m.put("includeSourceDetail", ex.includeSourceDetail);
        m.put("chainIntegrity", ex.chainIntegrity);
        m.put("breakList", ex.breakList);
        m.put("dualPnlIncluded", ex.dualPnlIncluded);
        m.put("reasonIncluded", ex.reasonIncluded);
        m.put("fileId", ex.fileId);
        m.put("exportedBy", ex.exportedBy);
        m.put("exportedTime", ex.exportedTime == null ? null : ex.exportedTime.toString());
        m.put("auditLogId", ex.auditLogId);
        return m;
    }

    private static LocalDate date(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        try {
            return LocalDate.parse(t);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "日期须为 yyyy-MM-dd：" + raw);
        }
    }

    private static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static <T> T body(RequestContext ctx, Class<T> type) {
        String b = ctx.body();
        if (b == null || b.trim().isEmpty()) {
            return null;
        }
        return JsonCodec.fromJson(b, type);
    }

    /** POST 请求体（{@code FR-AUDIT-8} 输入列）。 */
    public static final class Input {
        /** 追溯起点类型：{@code EXPOSURE}/{@code TRADE}/{@code VOUCHER}。 */
        public String startPointType;
        /** 追溯起点标识集合。 */
        public List<String> startPointIds;
        /** 时间范围（起，yyyy-MM-dd）。 */
        public String dateFrom;
        /** 时间范围（止，yyyy-MM-dd）。 */
        public String dateTo;
        /** 子账户范围。 */
        public List<Integer> subAccountIds;
        /** 是否含原始单据明细。 */
        public Boolean includeSourceDetail;
    }
}
