package com.hedgeguard.web.handler.risk;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.service.RiskRecomputeService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;

/**
 * 红线复算与限额使用统计 REST 面（{@code T-066}；{@code FR-RISK-4/5/6} + {@code FR-RISK-18}）。
 *
 * <pre>
 *   GET  /api/v1/risk/limit-usage   限额使用统计查询（{@code SCALE_REDLINE} / {@code POSITION_LIMIT}）
 *   POST /api/v1/risk/limit-usage   触发红线复算并落库统计（返回规模/净持仓/期限复算汇总）
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("RISK")}（未新增路径）；方法与 {@code §4.1.1} 的
 * {@code RISK} 行 GET/POST 一致。读取面按调用者可见子账户隔离（{@code NFR-SEC-7}）。
 */
public final class RiskLimitUsageHandler implements RouteHandler {

    /** 查询模式。 */
    public static final String MODE_QUERY = "QUERY";
    /** 复算模式。 */
    public static final String MODE_RECOMPUTE = "RECOMPUTE";

    /** 注册路径（逐字取自 {@code ApiRoutes}）。 */
    public static final String PATH = ApiRoutes.full("/risk/limit-usage");

    private final String mode;
    private final RiskRecomputeService service;

    /**
     * 构造。
     *
     * @param mode    模式（QUERY/RECOMPUTE）
     * @param service 复算服务
     */
    public RiskLimitUsageHandler(String mode, RiskRecomputeService service) {
        this.mode = mode;
        this.service = service;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if (MODE_RECOMPUTE.equals(mode)) {
            return recompute(ctx);
        }
        return query(ctx);
    }

    private HttpResult query(RequestContext ctx) {
        RiskRecomputeService.UsageQuery q = new RiskRecomputeService.UsageQuery();
        q.statDate = date(ctx.queryParam("statDate"), "statDate");
        if (q.statDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "statDate 必填（yyyy-MM-dd）：复现=按统计日期查询（FR-RISK-18）");
        }
        q.subAccountId = intOrNull(ctx.queryParam("subAccountId"));
        q.limitType = text(ctx.queryParam("limitType"));
        q.hintLevel = text(ctx.queryParam("hintLevel"));
        PageQuery page = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "id", "asc");
        RiskRecomputeService.UsagePage result = service.queryUsage(q, page, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(result.items, result.total, result.page, result.size), ctx.traceId()));
    }

    private HttpResult recompute(RequestContext ctx) {
        RecomputeRequest request = null;
        String body = ctx.body();
        if (body != null && !body.trim().isEmpty()) {
            request = JsonCodec.fromJson(body, RecomputeRequest.class);
        }
        LocalDate statDate = request == null ? null : date(request.statDate, "statDate");
        RiskRecomputeService.RecomputeSummary summary =
                service.recomputeAndPersist(statDate, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(summary, ctx.traceId()));
    }

    private static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Integer intOrNull(String s) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "整数参数非法：" + s);
        }
    }

    private static LocalDate date(String s, String name) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        try {
            return LocalDate.parse(t);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    name + " 须为 yyyy-MM-dd：" + s);
        }
    }

    /** 复算请求体（{@code statDate} 可空，缺省当日）。 */
    public static final class RecomputeRequest {

        /** 统计日期（yyyy-MM-dd；可空）。 */
        public String statDate;
    }
}
