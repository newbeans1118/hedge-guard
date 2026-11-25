package com.hedgeguard.web.handler.trade;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.TradingBridge;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 动态行情订阅 {@code POST /api/v1/trade/quote-subscription}（{@code CH-027}）。
 *
 * <p>请求体 {@code {"instruments":["rb2701","IF2612"]}} —— 合约代码<b>保留交易所原生大小写</b>
 * （SHFE/DCE/INE 小写、CFFEX/CZCE 大写；§19 结论）。完整替换 C++ 侧订阅集；空集 = 退订全部。
 *
 * <p>经 {@link TradingBridge#setQuoteSubscription(List)} 下发 {@code CMD_SET_QUOTE_SUBSCRIPTION}
 * 至 C++；失败回执不视为已生效（{@code R-CONFIG-3} 同族口径）。本端点<b>不落库</b>。
 */
public final class QuoteSubscriptionHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModuleRoutes().get("TRADE")}）。 */
    public static final String PATH = "/api/v1/trade/quote-subscription";

    private final TradingBridge bridge;

    public QuoteSubscriptionHandler(TradingBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        String body = ctx.body();
        if (body == null || body.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "请求体不得为空：复现=提供 {\"instruments\":[...]}");
        }
        Request req = JsonCodec.fromJson(body, Request.class);
        if (req == null || req.instruments == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提供 instruments 数组（可为空数组 = 退订全部）");
        }
        List<String> instruments = new ArrayList<>();
        for (Object o : req.instruments) {
            if (o != null) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) {
                    instruments.add(s);  // 原生大小写，不做归一（§19）
                }
            }
        }
        CompletableFuture<AckResult> future = bridge.setQuoteSubscription(instruments);
        AckResult ack;
        try {
            ack = future.get();
        } catch (Exception e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "行情订阅下发失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("instruments", instruments);
        data.put("acknowledged", ack.acknowledged);
        data.put("componentCode", ack.componentCode);
        data.put("message", ack.message);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    /** 请求体。 */
    public static final class Request {
        public List<Object> instruments;
    }
}
