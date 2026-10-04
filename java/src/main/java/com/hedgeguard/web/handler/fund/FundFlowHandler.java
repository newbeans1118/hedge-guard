package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.FundFlow;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金流水 {@code GET /api/v1/fund/flow}（{@code FR-FUND-3} / {@code FR-FUND-16}）。
 */
public final class FundFlowHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/flow";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundFlowHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        PageQuery q = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "ledger_date", "asc");
        String transferApplyId = ctx.queryParam("transferApplyId");
        List<Map<String, Object>> items = new ArrayList<>();
        if (transferApplyId != null && !transferApplyId.trim().isEmpty()) {
            for (FundFlow f : fundService.listTransferFlows(
                    FundParams.parseId(transferApplyId), ctx.currentUserId())) {
                items.add(view(f));
            }
            return HttpResult.json(200, ApiResponse.ok(items, ctx.traceId()));
        }
        PageResult<FundFlow> page = fundService.listFlow(q,
                FundParams.dateOrNull(ctx.queryParam("from"), "from"),
                FundParams.dateOrNull(ctx.queryParam("to"), "to"),
                FundParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                FundParams.text(ctx.queryParam("flowType")),
                ctx.currentUserId());
        for (FundFlow f : page.items()) {
            items.add(view(f));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    static Map<String, Object> view(FundFlow f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id);
        m.put("subAccountId", f.subAccountId);
        m.put("voucherNo", f.voucherNo);
        m.put("ledgerDate", f.ledgerDate == null ? null : f.ledgerDate.toString());
        m.put("flowType", f.flowType);
        m.put("amount", f.amount == null ? null : f.amount.toPlainString());
        m.put("direction", f.direction);
        m.put("balanceAfter", f.balanceAfter == null ? null : f.balanceAfter.toPlainString());
        m.put("transferApplyId", f.transferApplyId);
        m.put("depositWithdrawId", f.depositWithdrawId);
        m.put("counterpartSubAccountId", f.counterpartSubAccountId);
        m.put("operatorId", f.operatorId);
        m.put("flowTime", f.flowTime == null ? null : f.flowTime.toString());
        m.put("remark", f.remark);
        m.put("doubleReviewRequired", f.doubleReviewRequired);
        return m;
    }
}
