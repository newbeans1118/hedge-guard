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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 绑定实货敞口 {@code POST /api/v1/plan/{id}/bind-exposure}（{@code T-109}；{@code FR-PLAN-19}）。
 *
 * <p>请求体：{@code {"binds":[{exposureId, coveredQty}]}}。绑定敞口须与计划<b>同子账户、同品种、
 * 方向相反</b>；覆盖量不得超敞口未覆盖量、绑定合计不得超计划数量；<b>仅草稿态可直接增删绑定</b>，
 * 已批准计划须走变更流程（{@code FR-PLAN-19} 验收 4 / {@code R-PLAN-6}）。
 *
 * <p>写路径经 {@code PlanService.requireWritablePlan} 强制子账户可见性（{@code NFR-SEC-7}）。
 */
public final class BindExposureHandler implements RouteHandler {

    /** 注册路径模板（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/plan/{id}/bind-exposure");

    private final PlanService planService;

    /**
     * 构造。
     *
     * @param planService 计划服务
     */
    public BindExposureHandler(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long planId = PlanRoutes.parseId(ctx.pathParam("id"), "id");
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.binds == null || request.binds.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {binds:[{exposureId, coveredQty}]}（至少一条）：复现=按 FR-PLAN-19 提交");
        }
        List<PlanService.BindSpec> binds = new ArrayList<>();
        for (Item item : request.binds) {
            BigDecimal covered = PlanRoutes.decimal(item.coveredQty, "coveredQty");
            binds.add(new PlanService.BindSpec(item.exposureId, covered));
        }
        int bound = planService.bindExposure(planId, binds, ctx.currentUserId());
        BindResult result = new BindResult();
        result.bound = bound;
        return HttpResult.json(200, ApiResponse.ok(result, ctx.traceId()));
    }

    /** 绑定请求体。 */
    public static final class Request {

        /** 待绑定条目。 */
        public List<Item> binds;
    }

    /** 单条绑定入参。 */
    public static final class Item {

        /** 敞口 ID。 */
        public long exposureId;

        /** 覆盖数量（字符串化十进制，&gt; 0）。 */
        public String coveredQty;
    }

    /** 绑定结果。 */
    public static final class BindResult {

        /** 新增绑定条数。 */
        public int bound;
    }
}
