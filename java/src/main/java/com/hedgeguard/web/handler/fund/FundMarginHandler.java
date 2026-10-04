package com.hedgeguard.web.handler.fund;

import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保证金占用视图 {@code GET /api/v1/fund/margin}（{@code FR-FUND-15}）。
 */
public final class FundMarginHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/margin";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundMarginHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        FundService.MarginSummary s = fundService.marginView(
                FundParams.dateOrDefault(ctx.queryParam("tradeDate"), "tradeDate"),
                FundParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tradeDate", s.tradeDate == null ? null : s.tradeDate.toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FundService.MarginRow r : s.rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subAccountId", r.subAccountId);
            m.put("marginOccupied", r.marginOccupied);
            m.put("availableFund", r.availableFund);
            m.put("equity", r.equity);
            m.put("riskRatio", r.riskRatio);
            rows.add(m);
        }
        data.put("rows", rows);
        data.put("marginOccupiedTotal", s.marginOccupiedTotal.toPlainString());
        data.put("availableFundTotal", s.availableFundTotal.toPlainString());
        data.put("equityTotal", s.equityTotal.toPlainString());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }
}
