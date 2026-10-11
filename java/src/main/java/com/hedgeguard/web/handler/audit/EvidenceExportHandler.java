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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据链导出 REST 面（{@code T-069}；{@code FR-AUDIT-8}）。
 *
 * <p>路径 {@code /api/v1/audit/evidence/export}（GET/POST）：
 * POST = 执行导出，<b>CSV 直返</b>（{@code T-193} 修复 {@code TE-T069-003}：
 * {@code text/csv; charset=UTF-8} + {@code Content-Disposition: attachment; filename="evidence-<exportNo>.csv"}，
 * 形态对齐 {@code /spot/price/export}、{@code /product/export} 先例）；GET = 查导出记录（{@code exportNo} 或分页）。
 *
 * <p><b>「流式」实现裁量（据实披露）</b>：验收原文「直导出 CSV（流式）」——本项目 HTTP 载体
 * {@link HttpResult} 为<b>同步字节体</b>（无 chunked / 流式接缝，既有全部 CSV 导出先例同构），
 * 故「流式」按先例口径实现为<b>服务端同步拼装 + CSV 直返</b>（不落盘、不经 JSON 内嵌）。
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
        // CSV 直返（T-193）：body 与落库内容同源（render 输出），表头/数据行由服务层拼装。
        return HttpResult.bytes(200, "text/csv; charset=UTF-8",
                        r.content.getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition",
                        "attachment; filename=\"evidence-" + r.exportNo + ".csv\"");
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
        // 子账户可见性隔离（O-1 修复）：查询人 + 可选 subAccountId 筛选一并交服务端 fail-closed 判定
        // （R-AUDIT-9 软隔离 / NFR-SEC-7；null/空可见集 ⇒ 空集，越权筛选 ⇒ 空集）。
        Integer subAccountIdFilter = intOrNull(ctx.queryParam("subAccountId"));
        PageQuery query = PageQuery.of(text(ctx.queryParam("page")), text(ctx.queryParam("size")),
                text(ctx.queryParam("sort")), "exported_time", "desc");
        PageResult<EvidenceExport> page =
                service.queryExports(query, ctx.currentUserId(), subAccountIdFilter);
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

    /** 解析整数查询参数（空/缺省返回 {@code null}；非整数明确报参数错误，不静默忽略）。 */
    private static Integer intOrNull(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "subAccountId 须为整数：" + raw);
        }
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
