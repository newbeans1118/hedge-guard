package com.hedgeguard.web.handler.spot;

import com.hedgeguard.service.SpotService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.Map;

/**
 * 单据子账户归属变更 {@code POST /api/v1/spot/doc/sub-account}（{@code T-109}；{@code FR-SPOT-16}）。
 *
 * <p>请求体：{@code {"docType":"PURCHASE_CONTRACT|SALES_CONTRACT|PRODUCTION_PLAN","docId":…,
 * "targetSubAccountId":…}}。
 *
 * <p>规则：变更<b>仅允许在敞口未被任何生效计划引用时</b>执行；目标子账户须存在且启用；
 * 变更留痕。{@code T-111} 起归属变更与敞口重算<b>同一事务同步完成</b>——响应 {@code recomputeRequired=true}
 * 语义为「服务端已重算」（旧「调用方负责重算」语义废止）；旧快照身份留痕，新世代快照标识可审计复现。
 */
public final class SpotDocSubAccountHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/spot/doc/sub-account");

    private final SpotService spotService;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     */
    public SpotDocSubAccountHandler(SpotService spotService) {
        this.spotService = spotService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.docType == null || request.targetSubAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {docType, docId, targetSubAccountId}：复现=按 FR-SPOT-16 提交");
        }
        Map<String, Object> result = spotService.changeDocSubAccount(request.docType, request.docId,
                request.targetSubAccountId, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(result, ctx.traceId()));
    }

    /** 变更请求体。 */
    public static final class Request {

        /** 单据类型（{@code PURCHASE_CONTRACT} / {@code SALES_CONTRACT} / {@code PRODUCTION_PLAN}）。 */
        public String docType;

        /** 单据 ID。 */
        public long docId;

        /** 目标子账户。 */
        public Integer targetSubAccountId;
    }
}
