package com.hedgeguard.web.handler.trade;

import com.hedgeguard.bridge.AckResult;
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
 * 交易指令动作 {@code POST /api/v1/trade/order/{id}/confirm} 与
 * {@code POST /api/v1/trade/order/{id}/cancel}（{@code T-046}；{@code FR-TRADE-2} / {@code FR-TRADE-6}）。
 *
 * <p>操作人取自会话（{@code ctx.currentUserId()}），不接受请求体传入身份。
 */
public final class TradeOrderActionHandler implements RouteHandler {

    /** 可执行动作。 */
    public enum Action {
        /** 人工确认（强制环节，{@code FR-TRADE-2}）。 */
        CONFIRM("confirm"),
        /** 撤单（{@code FR-TRADE-6}）。 */
        CANCEL("cancel");

        private final String segment;

        Action(String segment) {
            this.segment = segment;
        }

        /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("TRADE")}）。 */
        public String path() {
            return "/api/v1/trade/order/{id}/" + segment;
        }
    }

    private final TradeService tradeService;
    private final Action action;

    /**
     * 构造。
     *
     * @param tradeService 交易服务
     * @param action       动作
     */
    public TradeOrderActionHandler(TradeService tradeService, Action action) {
        this.tradeService = tradeService;
        this.action = action;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long orderId = parseId(ctx.pathParam("id"), "id");
        long operatorId = ctx.currentUserId();
        Request body = JsonCodec.fromJson(safeBody(ctx), Request.class);
        AckResult ack;
        if (action == Action.CONFIRM) {
            String token = body == null ? null : body.confirmToken;
            ack = tradeService.confirmOrder(orderId, operatorId, token);
        } else {
            String reason = body == null ? null : body.reason;
            ack = tradeService.cancelOrder(orderId, reason, operatorId);
        }
        return HttpResult.json(200, ApiResponse.ok(data(ack), ctx.traceId()));
    }

    private static Map<String, Object> data(AckResult ack) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("acknowledged", ack.acknowledged);
        data.put("componentCode", ack.componentCode);
        data.put("message", ack.message);
        data.put("ackTime", ack.ackTime);
        return data;
    }

    private static long parseId(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 不得为空：复现=在路径给出指令号");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    field + " 须为整数：值=" + raw + "；复现=改为合法指令号");
        }
    }

    private static String safeBody(RequestContext ctx) {
        String body = ctx.body();
        return body == null || body.trim().isEmpty() ? "{}" : body;
    }

    /** 动作请求体（按动作取用）。 */
    public static final class Request {
        /** 确认令牌（CONFIRM 必填，{@code 0x1018}）。 */
        public String confirmToken;
        /** 撤单原因（CANCEL 可选）。 */
        public String reason;
    }
}
