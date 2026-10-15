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
 * 凭证冲销 {@code POST /api/v1/acct/voucher/{id}/reverse}（{@code FR-ACCT-13} +
 * {@code R-ACCT-5}；生成反向凭证并关联原凭证；双人复核 DC-032；{@code T-079}）。
 *
 * <p>POST 请求体（{@code AcctService.VoucherReverseRequest} 同形）：
 * {@code reason}(必填)/{@code coReviewerId}(必填，≠ 发起人且 ≠ 生成人)。
 * 复核人与发起人同人被拒（验收 3）；已关账期间冲销落当期调整（验收 4）。
 */
public final class VoucherReverseHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/voucher/{id}/reverse";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public VoucherReverseHandler(AcctService acctService) {
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
        AcctService.VoucherReverseRequest in = JsonCodec.fromJson(
                AcctParams.body(ctx), AcctService.VoucherReverseRequest.class);
        if (in == null || in.reason == null || in.reason.trim().isEmpty()) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "reason 必填（冲销原因，FR-ACCT-13）；复现=补齐后重试", ctx.traceId()));
        }
        Map<String, Object> out = VoucherHandler.view(
                acctService.reverseVoucher(id, in, ctx.currentUserId()));
        out.put("draftPendingEntryNote", AcctService.DRAFT_PENDING_ENTRY_NOTE);
        return HttpResult.json(200, ApiResponse.ok(out, ctx.traceId()));
    }
}
