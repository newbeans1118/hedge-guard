package com.hedgeguard.web.handler.fund;

import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 处理 CTP 资金划转回执 {@code POST /api/v1/fund/receipt}（{@code FR-FUND-8}）。
 */
public final class FundReceiptHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/receipt";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundReceiptHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        FundService.ReceiptRequest in = JsonCodec.fromJson(
                FundParams.body(ctx), FundService.ReceiptRequest.class);
        FundService.ReceiptResult r = fundService.onFundReceipt(in, ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("receiptId", r.receiptId);
        data.put("processed", r.processed);
        data.put("detail", r.detail);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }
}
