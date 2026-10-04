package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.entity.TransferApply;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 划拨审批（含记账与加总校验）{@code POST /api/v1/fund/transfer/{id}/approve}
 * （{@code FR-FUND-11} / {@code FR-FUND-12}）。
 */
public final class FundTransferApproveHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/transfer/{id}/approve";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundTransferApproveHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long id = FundParams.parseId(ctx.pathParam("id"));
        FundService.TransferApproveRequest in = JsonCodec.fromJson(
                FundParams.body(ctx), FundService.TransferApproveRequest.class);
        if (in == null) {
            in = new FundService.TransferApproveRequest();
        }
        TransferApply t = fundService.approveTransfer(id, in, ctx.currentUserId(),
                FundParams.decimalOrNull(in.realAccountAvailable, "realAccountAvailable"));
        return HttpResult.json(200, ApiResponse.ok(FundTransferHandler.view(t), ctx.traceId()));
    }
}
