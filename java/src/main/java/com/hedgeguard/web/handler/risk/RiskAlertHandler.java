package com.hedgeguard.web.handler.risk;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.service.RiskAlertService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDateTime;

/**
 * 风险告警查询与处置 REST 面（{@code T-065}；{@code FR-RISK-19} / {@code FR-RISK-15} / {@code FR-RISK-16}）。
 *
 * <pre>
 *   GET  /api/v1/risk/alert                  告警历史查询（筛选/分页/默认倒序/可见性隔离；since 补拉）
 *   POST /api/v1/risk/alert/{id}/dispose     告警处置（限制新开仓/解除/强制平仓提示/仅告警）
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("RISK")}（未新增路径）。
 * 历史记录只读：<b>不提供</b>删除/编辑入口（{@code FR-RISK-19} 验收 3、{@code R-RISK-11}）。
 */
public final class RiskAlertHandler implements RouteHandler {

    /** 查询模式。 */
    public static final String MODE_QUERY = "QUERY";
    /** 处置模式。 */
    public static final String MODE_DISPOSE = "DISPOSE";

    /** 告警查询路径（逐字取自 ApiRoutes）。 */
    public static final String PATH = ApiRoutes.full("/risk/alert");
    /** 告警处置路径（逐字取自 ApiRoutes）。 */
    public static final String PATH_DISPOSE = ApiRoutes.full("/risk/alert/{id}/dispose");

    private final String mode;
    private final RiskAlertService service;

    /**
     * 构造。
     *
     * @param mode    模式（QUERY/DISPOSE）
     * @param service 告警服务
     */
    public RiskAlertHandler(String mode, RiskAlertService service) {
        this.mode = mode;
        this.service = service;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if (MODE_DISPOSE.equals(mode)) {
            return dispose(ctx);
        }
        return query(ctx);
    }

    private HttpResult query(RequestContext ctx) {
        RiskAlertService.AlertQuery q = new RiskAlertService.AlertQuery();
        q.subAccountId = intOrNull(ctx.queryParam("subAccountId"));
        q.alertType = text(ctx.queryParam("alertType"));
        q.alertLevel = text(ctx.queryParam("alertLevel"));
        q.metricName = text(ctx.queryParam("metricName"));
        q.disposed = text(ctx.queryParam("disposed"));
        q.fromTime = localDateTime(ctx.queryParam("fromTime"), "fromTime");
        q.toTime = localDateTime(ctx.queryParam("toTime"), "toTime");
        q.sinceMs = longOrNull(ctx.queryParam("since"));

        PageQuery page = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "alert_time", "desc");
        RiskAlertService.AlertPage result = service.queryAlerts(q, page, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(result.items, result.total, result.page, result.size), ctx.traceId()));
    }

    private HttpResult dispose(RequestContext ctx) {
        String rawId = ctx.pathParam("id");
        Long id = longOrNull(rawId);
        if (id == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "告警 id 必填且须为整数：" + rawId);
        }
        String b = ctx.body();
        RiskAlertService.DisposeInput in = (b == null || b.trim().isEmpty())
                ? null : JsonCodec.fromJson(b, RiskAlertService.DisposeInput.class);
        RiskAlertService.DisposalView view = service.dispose(id, in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view, ctx.traceId()));
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

    private static LocalDateTime localDateTime(String s, String name) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(t);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    name + " 须为 ISO-8601 日期时间（yyyy-MM-ddTHH:mm:ss）：" + s);
        }
    }
}
