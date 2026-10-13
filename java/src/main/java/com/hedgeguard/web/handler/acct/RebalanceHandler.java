package com.hedgeguard.web.handler.acct;

import com.hedgeguard.dao.entity.RebalanceRecord;
import com.hedgeguard.service.AcctService;
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

/**
 * 再平衡 {@code GET /api/v1/acct/rebalance}（按套期关系取再平衡史，{@code FR-ACCT-3}）
 * 与 {@code POST /api/v1/acct/rebalance}（执行再平衡 / 重新指定，{@code FR-ACCT-3} +
 * {@code R-ACCT-3}；四类触发场景，Q-8；{@code T-078}）。
 *
 * <p><b>GET</b>：{@code hedgeRelationId} 必填（按关系取再平衡史；
 * {@code idx_rebalance_relation_time}）。
 *
 * <p><b>POST 请求体</b>（{@code AcctService.RebalanceRequest} 同形）：
 * {@code hedgeRelationId}/{@code triggerScenario}(EXPOSURE_DELTA|RATIO_DEVIATION|PLAN_REVISED|
 * EXPOSURE_GONE)/{@code triggerReason}(必填)/{@code ratioAfter}(∈(0,1])/{@code qtyAfter}(>0,
 * 敞口消失=0)/{@code planFeedbackRequired}(Y|N)。偏离值由服务端按数据面复核（不采信客户端
 * 声明）；触发条件不满足 ⇒ {@code 10012} 阻断并给出当前偏离值（FR-ACCT-3 验收 4）。
 */
public final class RebalanceHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/rebalance";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public RebalanceHandler(AcctService acctService) {
        this.acctService = acctService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return history(ctx);
        }
        AcctService.RebalanceRequest in = JsonCodec.fromJson(
                AcctParams.body(ctx), AcctService.RebalanceRequest.class);
        if (in == null || in.hedgeRelationId == null) {
            return HttpResult.json(400, ApiResponse.fail(
                    ErrorCode.PARAM_INVALID,
                    "hedgeRelationId 必填；复现=补齐请求体后重试", ctx.traceId()));
        }
        RebalanceRecord r = acctService.rebalance(in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(AcctService.rebalanceView(r), ctx.traceId()));
    }

    private HttpResult history(RequestContext ctx) {
        Long relationId = AcctParams.longOrNull(ctx.queryParam("hedgeRelationId"),
                "hedgeRelationId");
        if (relationId == null) {
            return HttpResult.json(400, ApiResponse.fail(
                    ErrorCode.PARAM_INVALID,
                    "hedgeRelationId 必填（按套期关系取再平衡史）；复现=追加查询参数后重试",
                    ctx.traceId()));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (RebalanceRecord r : acctService.listRebalances(relationId, ctx.currentUserId())) {
            items.add(AcctService.rebalanceView(r));
        }
        return HttpResult.json(200, ApiResponse.ok(items, ctx.traceId()));
    }
}
