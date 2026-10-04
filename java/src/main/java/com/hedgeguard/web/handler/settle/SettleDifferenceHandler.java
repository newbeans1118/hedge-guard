package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 差异处理 {@code PUT /api/v1/settle/difference}（{@code T-047}；{@code FR-SETTLE-9}）。
 *
 * <p>请求体 {@code {id, handleStatus, handleMethod, handleDesc, ownerId, reviewerId}}。
 * 调整/冲正类须双人复核（{@code reviewerId ≠ 处理人}）。
 */
public final class SettleDifferenceHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
    public static final String PATH = "/api/v1/settle/difference";

    private final SettleService settleService;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     */
    public SettleDifferenceHandler(SettleService settleService) {
        this.settleService = settleService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        HandleRequest req = JsonCodec.fromJson(
                ctx.body() == null || ctx.body().trim().isEmpty() ? "{}" : ctx.body(),
                HandleRequest.class);
        long id = req.id;
        SettleService.DifferenceInput in = new SettleService.DifferenceInput();
        in.handleStatus = req.handleStatus;
        in.handleMethod = req.handleMethod;
        in.handleDesc = req.handleDesc;
        in.ownerId = req.ownerId;
        in.reviewerId = req.reviewerId;
        SettleService.DifferenceResult r = settleService.handleDifference(id, in, ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("differenceId", r.differenceId);
        data.put("handleStatus", r.handleStatus);
        data.put("closed", r.closed);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    /** 差异处理请求体。 */
    public static final class HandleRequest {
        /** 差异号。 */
        public long id;
        /** 处理状态（PENDING/HANDLING/CLOSED）。 */
        public String handleStatus;
        /** 处理方式（SUPPLEMENT/REVERSE/ADJUST）。 */
        public String handleMethod;
        /** 处理说明。 */
        public String handleDesc;
        /** 责任人。 */
        public Long ownerId;
        /** 复核人。 */
        public Long reviewerId;
    }
}
