package com.hedgeguard.web.handler.plan;

import com.hedgeguard.service.PlanService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 套期关系书面文件 {@code POST /api/v1/plan/{id}/doc}（{@code T-016}；CAS 24 三要素门禁）。
 *
 * <p><b>三要素任一为空即拒绝</b>（{@code 10012} 阻断四项齐备，{@code ruleId=R-PLAN-8}）：
 * 正式指定 / 有效性评估方法 / 再平衡机制。生成后 {@code pending_external_review} 恒为 {@code 'Y'}
 * （{@code R-ACCT-10}），且旧版本置非当前（多版本保留）。
 *
 * <p>要素正文以 {@code CLOB} 存储（库侧走 {@code setCharacterStream}）。
 */
public final class PlanDocHandler implements RouteHandler {

    /** 注册路径模板（{@code {id}} 独占整段）。 */
    public static final String PATH = ApiRoutes.full("/plan/{id}/doc");

    private final PlanService planService;

    /**
     * 构造。
     *
     * @param planService 计划服务
     */
    public PlanDocHandler(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long planId = PlanRoutes.parseId(ctx.pathParam("id"), "id");
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {elemFormalDesignation, elemEffectivenessMethod, elemRebalanceMechanism,"
                            + " hedgingInstrument}：复现=按 CAS 24 三要素提交");
        }
        int version = planService.generateHedgeDoc(planId, request.elemFormalDesignation,
                request.elemEffectivenessMethod, request.elemRebalanceMechanism,
                request.hedgingInstrument, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(version, ctx.traceId()));
    }

    /** 书面文件请求体。 */
    public static final class Request {

        /** 要素①正式指定（被套期项目 = 实货敞口标识；套期工具 = 合约）。 */
        public String elemFormalDesignation;

        /** 要素②有效性评估方法。 */
        public String elemEffectivenessMethod;

        /** 要素③再平衡机制。 */
        public String elemRebalanceMechanism;

        /** 套期工具（合约 / 持仓标识）。 */
        public String hedgingInstrument;
    }
}
