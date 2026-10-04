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
 * CTP 日终数据接收 {@code POST /api/v1/settle/ctp-data}（{@code T-047}；{@code FR-SETTLE-1}）。
 *
 * <p>数据未到达时返回 {@code pendingRetry=true}（任务置待重试），不产生结算结果。
 */
public final class SettleCtpDataHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
    public static final String PATH = "/api/v1/settle/ctp-data";

    private final SettleService settleService;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     */
    public SettleCtpDataHandler(SettleService settleService) {
        this.settleService = settleService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        SettleService.CtpDataRequest req = JsonCodec.fromJson(
                ctx.body() == null || ctx.body().trim().isEmpty() ? "{}" : ctx.body(),
                SettleService.CtpDataRequest.class);
        SettleService.CtpIngestResult r = settleService.ingestCtpData(req, ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", r.batchId);
        data.put("pendingRetry", r.pendingRetry);
        data.put("positionCount", r.positionCount);
        data.put("fundCount", r.fundCount);
        data.put("tradeCount", r.tradeCount);
        data.put("message", r.message);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    static BizException invalid(String message) {
        return BizException.of(ErrorCode.PARAM_INVALID, message);
    }
}
