package com.hedgeguard.web.handler.product;

import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 子账户品种白名单 {@code POST /api/v1/product/whitelist}（{@code T-012}；{@code FR-PRODUCT-12}）。
 *
 * <p>单端点双动作，用 {@code action} 区分（{@code §4.1.1}「新增或动作」语义）：
 * <pre>
 *   {"action":"ENABLE",  "subAccountId":3, "productId":7, "categoryOverride":"TRADE"}
 *   {"action":"DISABLE", "subAccountId":3, "productId":7, "reason":"指标调整"}
 * </pre>
 * 移出（{@code DISABLE}）是**置 {@code DISABLED} 并保留历史**，不是物理删除
 * （{@code R-PRODUCT-5}）；变更置 {@code dispatch_status='PENDING'}`待下发。
 */
public final class WhitelistHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/product/whitelist");

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public WhitelistHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.action == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {action, subAccountId, productId, categoryOverride?/reason?}："
                            + "复现=action ∈ {ENABLE, DISABLE}");
        }
        if ("DISABLE".equalsIgnoreCase(request.action)) {
            varietyService.removeWhitelist(request.subAccountId, request.productId, request.reason,
                    ctx.currentUserId());
        } else if ("ENABLE".equalsIgnoreCase(request.action)) {
            varietyService.putWhitelist(request.subAccountId, request.productId,
                    request.categoryOverride, ctx.currentUserId());
        } else {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "未知 action：" + request.action + "；复现=取 ENABLE 或 DISABLE");
        }
        return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
    }

    /** 请求体。 */
    public static final class Request {

        /** {@code ENABLE}（加入/重新加入）或 {@code DISABLE}（移出并保留历史）。 */
        public String action;

        /** 子账户（真实子账户，&gt; 0）。 */
        public int subAccountId;

        /** 品种。 */
        public long productId;

        /** 品种级分类覆盖（可空 = 继承子账户分类，{@code Q-2}）。 */
        public String categoryOverride;

        /** 移出原因（{@code DISABLE} 时必填）。 */
        public String reason;
    }
}
