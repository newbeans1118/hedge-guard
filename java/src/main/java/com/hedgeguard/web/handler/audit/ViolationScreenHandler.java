package com.hedgeguard.web.handler.audit;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.ViolationScreenItem;
import com.hedgeguard.dao.entity.ViolationScreenRun;
import com.hedgeguard.service.ViolationScreenService;
import com.hedgeguard.service.ViolationScreenService.ViolationScreenQuery;
import com.hedgeguard.service.ViolationScreenService.ViolationScreenRequest;
import com.hedgeguard.service.ViolationScreenService.ViolationScreenResult;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 违规事项筛查 REST 面（{@code T-068}；{@code FR-AUDIT-4}、{@code R-AUDIT-8/9}）。
 *
 * <p>单一路径 {@code /api/v1/audit/violation-screen}（方法分派；路径逐字取自 {@code ApiRoutes}，
 * {@code ARCHITECTURE.md} §4.1.1 记 GET/POST）：
 * <ul>
 *   <li>{@code POST} —— 执行一次筛查（落 {@code t_violation_screen_run} + 命中项），返回运行 + 命中清单；</li>
 *   <li>{@code GET}  —— 查询命中清单（支持按时间 / 子账户 / 违规类型 / 批次筛选，分页）。</li>
 * </ul>
 */
public final class ViolationScreenHandler implements RouteHandler {

    /** 路径（逐字取自 {@code ApiRoutes}）。 */
    public static final String PATH = "/api/v1/audit/violation-screen";

    private final ViolationScreenService service;

    /**
     * 构造。
     *
     * @param service 筛查服务
     */
    public ViolationScreenHandler(ViolationScreenService service) {
        this.service = service;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        return "GET".equalsIgnoreCase(ctx.method()) ? get(ctx) : post(ctx);
    }

    private HttpResult post(RequestContext ctx) {
        Input in = body(ctx, Input.class);
        if (in == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体必填（FR-AUDIT-4）");
        }
        ViolationScreenRequest req = new ViolationScreenRequest();
        req.dateFrom = date(in.dateFrom, "dateFrom");
        req.dateTo = date(in.dateTo, "dateTo");
        req.subAccountIds = in.subAccountIds;
        if (in.dimensions != null) {
            req.dimensions = new LinkedHashSet<>(in.dimensions);
        }
        req.ruleVersion = text(in.ruleVersion);
        req.screenNo = text(in.screenNo);
        ViolationScreenResult result = service.screen(req, ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run", runView(result.run));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ViolationScreenItem it : result.items) {
            items.add(itemView(it));
        }
        data.put("items", items);
        data.put("hitCount", result.items.size());
        data.put("permissionLimited", result.permissionLimited);
        data.put("note", result.note);
        data.put("visibleSubAccounts", result.visibleSubAccounts);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    private HttpResult get(RequestContext ctx) {
        ViolationScreenQuery q = new ViolationScreenQuery();
        q.dateFrom = date(text(ctx.queryParam("dateFrom")), "dateFrom");
        q.dateTo = date(text(ctx.queryParam("dateTo")), "dateTo");
        q.subAccountId = intOrNull(ctx.queryParam("subAccountId"));
        q.violationType = text(ctx.queryParam("violationType"));
        String screenNo = text(ctx.queryParam("screenNo"));
        if (screenNo != null) {
            ViolationScreenRun run = service.findRun(screenNo, ctx.currentUserId());
            if (run == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "筛查批次不存在：screenNo=" + screenNo);
            }
            q.screenRunId = run.id;
        }
        q.screenRunId = q.screenRunId != null ? q.screenRunId
                : longOrNull(ctx.queryParam("screenRunId"));
        PageQuery query = PageQuery.of(text(ctx.queryParam("page")), text(ctx.queryParam("size")),
                text(ctx.queryParam("sort")), "id", "asc");
        PageResult<ViolationScreenItem> page = service.queryItems(q, query, ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ViolationScreenItem it : page.items()) {
            items.add(itemView(it));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    private static Map<String, Object> runView(ViolationScreenRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (run == null) {
            return m;
        }
        m.put("id", run.id);
        m.put("screenNo", run.screenNo);
        m.put("dateFrom", run.dateFrom == null ? null : run.dateFrom.toString());
        m.put("dateTo", run.dateTo == null ? null : run.dateTo.toString());
        m.put("subAccountScope", run.subAccountScope);
        m.put("screenDimensions", run.screenDimensions);
        m.put("ruleVersion", run.ruleVersion);
        m.put("hitCount", run.hitCount);
        m.put("operatorId", run.operatorId);
        m.put("screenTime", run.screenTime == null ? null : run.screenTime.toString());
        m.put("conclusion", run.conclusion);
        return m;
    }

    private static Map<String, Object> itemView(ViolationScreenItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.id);
        m.put("subAccountId", it.subAccountId);
        m.put("screenRunId", it.screenRunId);
        m.put("violationType", it.violationType);
        m.put("severity", it.severity);
        m.put("ruleId", it.ruleId);
        m.put("objectType", it.objectType);
        m.put("objectId", it.objectId);
        m.put("occurTime", it.occurTime == null ? null : it.occurTime.toString());
        m.put("violationDesc", it.violationDesc);
        m.put("tradeReasonId", it.tradeReasonId);
        m.put("relatedObjectInfo", it.relatedObjectInfo);
        return m;
    }

    private static <T> T body(RequestContext ctx, Class<T> type) {
        String b = ctx.body();
        if (b == null || b.trim().isEmpty()) {
            return null;
        }
        return JsonCodec.fromJson(b, type);
    }

    private static LocalDate date(String raw, String name) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        try {
            return LocalDate.parse(t);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, name + " 须为 yyyy-MM-dd：" + raw);
        }
    }

    private static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Long longOrNull(String s) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "整数参数非法：" + s);
        }
    }

    private static Integer intOrNull(String s) {
        Long v = longOrNull(s);
        return v == null ? null : v.intValue();
    }

    /** POST 请求体（FR-AUDIT-4 输入列）。 */
    public static final class Input {
        /** 筛查时间范围（起，yyyy-MM-dd）。 */
        public String dateFrom;
        /** 筛查时间范围（止，yyyy-MM-dd）。 */
        public String dateTo;
        /** 子账户范围（可空 = 全部可见）。 */
        public List<Integer> subAccountIds;
        /** 筛查维度（可空 = 全部）。 */
        public List<String> dimensions;
        /** 规则口径版本（可空）。 */
        public String ruleVersion;
        /** 指定批次号（可空）。 */
        public String screenNo;
    }
}
