package com.hedgeguard.web.handler.settle;

import com.hedgeguard.dao.entity.SettleStressTest;
import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 场景化压力测试 {@code POST /api/v1/settle/stress-test}（{@code T-047}；{@code FR-SETTLE-11}）。
 */
public final class SettleStressTestHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
    public static final String PATH = "/api/v1/settle/stress-test";

    private final SettleService settleService;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     */
    public SettleStressTestHandler(SettleService settleService) {
        this.settleService = settleService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        SettleService.StressInput in = JsonCodec.fromJson(
                ctx.body() == null || ctx.body().trim().isEmpty() ? "{}" : ctx.body(),
                SettleService.StressInput.class);
        SettleStressTest r = settleService.runStressTest(in, ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", r.id);
        data.put("testDate", r.testDate == null ? null : r.testDate.toString());
        data.put("marginGap", r.marginGap == null ? "0" : r.marginGap.toPlainString());
        data.put("additionalRequirement",
                r.additionalRequirement == null ? "0" : r.additionalRequirement.toPlainString());
        data.put("equityAfter", r.equityAfter == null ? "0" : r.equityAfter.toPlainString());
        data.put("riskRatioAfter", r.riskRatioAfter == null ? null : r.riskRatioAfter.toPlainString());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }
}
