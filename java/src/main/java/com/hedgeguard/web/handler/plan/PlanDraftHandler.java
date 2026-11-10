package com.hedgeguard.web.handler.plan;

import com.hedgeguard.dao.entity.HedgePlan;
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
 * 修改草稿计划 {@code PUT /api/v1/plan/{id}}（{@code FR-PLAN-2}）。
 *
 * <p>路径逐字取自 {@code ARCHITECTURE.md §4.1.1} PLAN 行（已声明 {@code PUT(草稿)}）；
 * 与 {@code GET /api/v1/plan/{id}}（{@link PlanHandler}）同路径不同方法。
 *
 * <p>请求体为<strong>计划主表可修改要素</strong>（camelCase wire 字段）：类型 / 品种 / 方向 /
 * 数量 / 单位 / 期限起止 / 比率 / 比率方法 / 基准日。<strong>部分更新</strong>：省略或
 * {@code null} 的字段保持原值，可只提交 {@code planQty}（{@code FR-PLAN-2} 验收 1）。
 * 金额与数量一律字符串化十进制，日期为 {@code yyyy-MM-dd}；{@code plan_no}、状态、子账户、
 * 额度等系统字段不接受覆盖。绑定敞口增删继续走 {@code POST /api/v1/plan/{id}/bind-exposure}。
 *
 * <p>状态门禁、重新校验与变更留痕均由 {@link PlanService#updateDraft} 承载（服务端强制，
 * 客户端传入的任何结论不参与判定）。
 */
public final class PlanDraftHandler implements RouteHandler {

    /** 注册路径模板（{@code {id}} 独占整段）。 */
    public static final String PATH = ApiRoutes.full("/plan/{id}");

    private final PlanService planService;

    /**
     * 构造。
     *
     * @param planService 计划服务
     */
    public PlanDraftHandler(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long planId = PlanRoutes.parseId(ctx.pathParam("id"), "id");
        UpdateRequest request = JsonCodec.fromJson(ctx.body(), UpdateRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {planType, productId, hedgeDirection, planQty, unit, termStartDate,"
                            + " termEndDate, hedgeRatio, ratioMethod, calcBaseDate}：复现=按 FR-PLAN-2 补齐");
        }
        HedgePlan patch = new HedgePlan();
        patch.planType = request.planType;
        // 部分更新：仅当字段显式给出时才解析；null/省略 ⇒ 服务层保持原值。
        patch.productId = request.productId == null ? null
                : PlanRoutes.parseId(request.productId, "productId");
        patch.hedgeDirection = request.hedgeDirection;
        patch.planQty = request.planQty == null ? null
                : PlanRoutes.decimal(request.planQty, "planQty");
        patch.unit = request.unit;
        patch.termStartDate = request.termStartDate == null ? null
                : PlanRoutes.date(request.termStartDate, "termStartDate");
        patch.termEndDate = request.termEndDate == null ? null
                : PlanRoutes.date(request.termEndDate, "termEndDate");
        patch.hedgeRatio = request.hedgeRatio == null ? null
                : PlanRoutes.decimal(request.hedgeRatio, "hedgeRatio");
        patch.ratioMethod = request.ratioMethod;
        patch.calcBaseDate = request.calcBaseDate == null ? null
                : PlanRoutes.date(request.calcBaseDate, "calcBaseDate");
        long id = planService.updateDraft(planId, patch, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 修改草稿请求体（金额/数量字符串化十进制；ID 字符串化整数）。 */
    public static final class UpdateRequest {

        /** 计划类型（{@code COMMODITY} / {@code TRADE}）。 */
        public String planType;

        /** 品种 ID（字符串化整数）。 */
        public String productId;

        /** 套保方向（{@code BUY_HEDGE} / {@code SELL_HEDGE}）。 */
        public String hedgeDirection;

        /** 计划数量（字符串化十进制，&gt; 0）。 */
        public String planQty;

        /** 单位。 */
        public String unit;

        /** 期限起（{@code yyyy-MM-dd}）。 */
        public String termStartDate;

        /** 期限止（{@code yyyy-MM-dd}）。 */
        public String termEndDate;

        /** 套保比率（字符串化小数，∈ (0,1]）。 */
        public String hedgeRatio;

        /** 比率方法（{@code SIMPLE} / {@code MIN_VARIANCE} / {@code CUSTOM_RULE}）。 */
        public String ratioMethod;

        /** 比率计算基准日（{@code yyyy-MM-dd}）。 */
        public String calcBaseDate;
    }
}
