package com.hedgeguard.web.handler.acct;

import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.Map;

/**
 * 凭证审核 {@code POST /api/v1/acct/voucher/{id}/review}（{@code FR-ACCT-7} +
 * {@code R-ACCT-6} 岗位分离；{@code T-079}）。
 *
 * <p>POST 请求体（{@code AcctService.VoucherReviewRequest} 同形）：
 * {@code action}(SUBMIT|APPROVE|REJECT|WITHDRAW)/{@code reviewComment}(可空)/
 * {@code rejectReason}(REJECT 必填)。生成人尝试审核自己生成的凭证被拒绝（FR-ACCT-7 验收 2）；
 * 驳回未填原因被拒绝（验收 3）；审核留痕含审核人、时间与意见（验收 4）。
 */
public final class VoucherReviewActionHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/voucher/{id}/review";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public VoucherReviewActionHandler(AcctService acctService) {
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
        AcctService.VoucherReviewRequest in = JsonCodec.fromJson(
                AcctParams.body(ctx), AcctService.VoucherReviewRequest.class);
        if (in == null || in.action == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "action 必填（SUBMIT/APPROVE/REJECT/WITHDRAW）；复现=补齐后重试",
                    ctx.traceId()));
        }
        return HttpResult.json(200, ApiResponse.ok(
                VoucherHandler.reviewView(acctService.reviewVoucher(id, in, ctx.currentUserId())),
                ctx.traceId()));
    }
}
