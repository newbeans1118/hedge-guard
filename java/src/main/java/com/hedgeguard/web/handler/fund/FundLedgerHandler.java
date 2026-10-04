package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.FundLedger;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金台账 {@code GET /api/v1/fund/ledger}（列表，{@code FR-FUND-3}）与
 * {@code POST /api/v1/fund/ledger}（建立台账，{@code FR-FUND-1}）。
 */
public final class FundLedgerHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/ledger";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundLedgerHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return list(ctx);
        }
        FundService.LedgerInitRequest in = JsonCodec.fromJson(
                FundParams.body(ctx), FundService.LedgerInitRequest.class);
        FundLedger l = fundService.initLedger(in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view(l), ctx.traceId()));
    }

    private HttpResult list(RequestContext ctx) {
        PageQuery q = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "ledger_date", "desc");
        PageResult<FundLedger> page = fundService.listLedger(q,
                FundParams.dateOrNull(ctx.queryParam("from"), "from"),
                FundParams.dateOrNull(ctx.queryParam("to"), "to"),
                FundParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (FundLedger l : page.items()) {
            items.add(view(l));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    static Map<String, Object> view(FundLedger l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id);
        m.put("subAccountId", l.subAccountId);
        m.put("ledgerDate", l.ledgerDate == null ? null : l.ledgerDate.toString());
        m.put("beginBalance", FundParams.dec(l.beginBalance));
        m.put("amountIn", FundParams.dec(nz(l.amountIn)));
        m.put("amountOut", FundParams.dec(nz(l.amountOut)));
        m.put("transferIn", FundParams.dec(nz(l.transferIn)));
        m.put("transferOut", FundParams.dec(nz(l.transferOut)));
        m.put("pnlCarryover", FundParams.dec(nz(l.pnlCarryover)));
        m.put("commission", FundParams.dec(nz(l.commission)));
        m.put("marginChange", FundParams.dec(nz(l.marginChange)));
        m.put("differenceAdjust", FundParams.dec(nz(l.differenceAdjust)));
        m.put("endBalance", FundParams.dec(l.endBalance));
        m.put("availableFund", FundParams.dec(l.availableFund));
        m.put("marginOccupied", FundParams.dec(l.marginOccupied));
        m.put("equity", FundParams.dec(l.equity));
        m.put("riskRatio", FundParams.dec(l.riskRatio));
        m.put("isClosed", l.isClosed);
        return m;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
