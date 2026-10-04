package com.hedgeguard.web.handler.trade;

import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.service.TradeService;
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
 * 提交交易指令 {@code POST /api/v1/trade/order}（{@code T-046}；{@code FR-TRADE-1}）。
 *
 * <p>操作人取自会话（{@code ctx.currentUserId()}），<b>不接受</b>请求体传入身份——否则可绕过
 * {@code assertCanTrade}（{@code FR-AUTH-9} / {@code R-AUTH-13}）。
 *
 * <p><b>本端点不落库</b>：仅经 {@link TradeService#submitOrder} 校验后下发 C++（{@code PROJECT.md} §10-23）。
 */
public final class TradeOrderHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("TRADE")}）。 */
    public static final String PATH = "/api/v1/trade/order";

    private final TradeService tradeService;

    /**
     * 构造。
     *
     * @param tradeService 交易服务
     */
    public TradeOrderHandler(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long operatorId = ctx.currentUserId();
        TradeService.SubmitRequest request = JsonCodec.fromJson(safeBody(ctx),
                TradeService.SubmitRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "请求体不得为空：复现=提供 subAccountId/tradePlanId/reason 等（FR-TRADE-1）");
        }
        PlaceOrderResult result = tradeService.submitOrder(request, operatorId);
        return HttpResult.json(200, ApiResponse.ok(acceptedData(result), ctx.traceId()));
    }

    /**
     * 受理成功响应 {@code data}。
     *
     * <p>形状：{@code {"accepted":true,"orderId":<C++指令号>,"clientOrderRef":"HG…","limitLayer":…}}。
     * 阻断类失败不经本方法（服务层抛 {@code 10012}，由 {@code ErrorMapper} 统一成信封）。
     *
     * @param r 受理结果
     * @return 响应数据
     */
    private static Map<String, Object> acceptedData(PlaceOrderResult r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", r.accepted);
        data.put("orderId", r.orderId);
        data.put("clientOrderRef", r.clientOrderRef);
        data.put("limitLayer", r.limitLayer);
        data.put("status", TradeService.STATUS_PENDING_CONFIRM);
        return data;
    }

    private static String safeBody(RequestContext ctx) {
        String body = ctx.body();
        return body == null || body.trim().isEmpty() ? "{}" : body;
    }
}
