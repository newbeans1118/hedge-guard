package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 出入金复核 {@code POST /api/v1/fund/{id}/review}（{@code FR-FUND-7}）。
 */
public final class FundReviewHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/{id}/review";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundReviewHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long id = FundParams.parseId(ctx.pathParam("id"));
        FundService.ReviewRequest in = JsonCodec.fromJson(
                FundParams.body(ctx), FundService.ReviewRequest.class);
        DepositWithdrawApply a = fundService.reviewDepositWithdraw(id, in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(FundApplyHandler.view(a), ctx.traceId()));
    }
}
