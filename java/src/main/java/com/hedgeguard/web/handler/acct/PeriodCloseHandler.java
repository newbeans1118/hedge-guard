package com.hedgeguard.web.handler.acct;

import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 期间关账/撤销关账 {@code POST /api/v1/acct/period/{id}/close}（{@code FR-ACCT-12} +
 * {@code R-ACCT-8} 双人复核；{@code T-079}）。
 *
 * <p>POST 请求体：{@code action}(CLOSE|REOPEN)/{@code reason}(REOPEN 必填)/
 * {@code reviewerId}(必填复核人，≠ 当前操作人)。关账三前置任一不满足 ⇒ {@code 10012}
 * 阻断并列出未审核凭证（验收 2）；撤销关账留痕（验收 4）。
 */
public final class PeriodCloseHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/period/{id}/close";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public PeriodCloseHandler(AcctService acctService) {
        this.acctService = acctService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "id 须为整数：" + ctx.pathParam("id"), ctx.traceId()));
        }
        CloseRequest in = JsonCodec.fromJson(AcctParams.body(ctx), CloseRequest.class);
        if (in == null || in.action == null || in.reviewerId == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "action（CLOSE/REOPEN）与 reviewerId（复核人，≠ 操作人）必填；复现=补齐后重试",
                    ctx.traceId()));
        }
        AcctService.PeriodCloseRequest req = new AcctService.PeriodCloseRequest();
        req.reason = in.reason;
        return HttpResult.json(200, ApiResponse.ok(VoucherPeriodHandler.logView(
                        acctService.closePeriod(id, in.action, req, ctx.currentUserId(),
                                in.reviewerId)),
                ctx.traceId()));
    }

    /** POST 请求体。 */
    public static final class CloseRequest {
        /** 动作（CLOSE/REOPEN）。 */
        public String action;
        /** 撤销关账原因（REOPEN 必填）。 */
        public String reason;
        /** 复核人（≠ 当前操作人）。 */
        public Long reviewerId;
    }
}
