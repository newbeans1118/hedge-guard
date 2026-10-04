package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.entity.FundQuota;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金额度 {@code GET /api/v1/fund/quota}（查询，{@code FR-FUND-13} 验收4）与
 * {@code POST /api/v1/fund/quota}（设定，{@code FR-FUND-13}）。
 */
public final class FundQuotaHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/quota";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundQuotaHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (FundQuota q : fundService.listQuota(
                    FundParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                    FundParams.text(ctx.queryParam("quotaScope")), ctx.currentUserId())) {
                items.add(view(q));
            }
            return HttpResult.json(200, ApiResponse.ok(items, ctx.traceId()));
        }
        FundService.QuotaRequest in = JsonCodec.fromJson(
                FundParams.body(ctx), FundService.QuotaRequest.class);
        if (in == null) {
            in = new FundService.QuotaRequest();
        }
        FundQuota q = fundService.setQuota(in,
                FundParams.decimalOrNull(in.realAccountAvailable, "realAccountAvailable"),
                ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view(q), ctx.traceId()));
    }

    static Map<String, Object> view(FundQuota q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.id);
        m.put("subAccountId", q.subAccountId);
        m.put("quotaScope", q.quotaScope);
        m.put("quotaValue", q.quotaValue == null ? null : q.quotaValue.toPlainString());
        m.put("effectiveFrom", q.effectiveFrom == null ? null : q.effectiveFrom.toString());
        m.put("effectiveTo", q.effectiveTo == null ? null : q.effectiveTo.toString());
        m.put("operatorId", q.operatorId);
        m.put("changeReason", q.changeReason);
        return m;
    }
}
