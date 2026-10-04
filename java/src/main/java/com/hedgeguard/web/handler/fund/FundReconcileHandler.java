package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.entity.FundReconcile;
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
 * 每日资金对账 {@code POST /api/v1/fund/reconcile}（{@code FR-FUND-17}）。
 */
public final class FundReconcileHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/reconcile";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundReconcileHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        FundService.ReconcileRequest in = JsonCodec.fromJson(
                FundParams.body(ctx), FundService.ReconcileRequest.class);
        if (in == null) {
            in = new FundService.ReconcileRequest();
        }
        FundService.ReconcileOutcome out = fundService.dailyReconcile(
                FundParams.dateOrDefault(in.tradeDate, "tradeDate"),
                FundParams.decimalOrNull(in.realAccountAvailable, "realAccountAvailable"),
                ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tradeDate", out.tradeDate == null ? null : out.tradeDate.toString());
        data.put("result", out.result);
        List<Map<String, Object>> subs = new ArrayList<>();
        for (FundReconcile r : out.subRows) {
            subs.add(view(r));
        }
        data.put("subAccounts", subs);
        data.put("account", out.accountRow == null ? null : view(out.accountRow));
        data.put("differences", out.differences);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    static Map<String, Object> view(FundReconcile r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("subAccountId", r.subAccountId);
        m.put("reconcileLevel", r.reconcileLevel);
        m.put("beginBalance", r.beginBalance == null ? null : r.beginBalance.toPlainString());
        m.put("occurrenceTotal", r.occurrenceTotal == null ? null : r.occurrenceTotal.toPlainString());
        m.put("endBalance", r.endBalance == null ? null : r.endBalance.toPlainString());
        m.put("balanceEquationOk", r.balanceEquationOk);
        m.put("aggCompareValue", r.aggCompareValue == null ? null : r.aggCompareValue.toPlainString());
        m.put("realAccountValue", r.realAccountValue == null ? null : r.realAccountValue.toPlainString());
        m.put("aggConsistent", r.aggConsistent);
        m.put("result", r.result);
        return m;
    }
}
