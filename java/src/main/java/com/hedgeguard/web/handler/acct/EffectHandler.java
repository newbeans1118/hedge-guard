package com.hedgeguard.web.handler.acct;

import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.Map;

/**
 * 套保效果综合评估 {@code /api/v1/acct/effect}（{@code FR-ACCT-5}；{@code T-080}）。
 *
 * <p><b>GET</b>（查询）：{@code subAccountId} + {@code periodKey} 必填——返回
 * {@code summary}（子账户级汇总行）+ {@code details}（逐敞口明细行），双侧盈亏字段
 * 成对恒在（R-ACCT-4 同屏双栏；实货缺失时值 null + {@code spotPriceMissing}/
 * {@code missingDesc}/{@code priceAsOf}/{@code nonCurrentPriceFlag} 三态标注）。
 *
 * <p><b>POST</b>（计算）：请求体 {@code {subAccountId, periodKey}}——计算并落库该子账户
 * 该期间的效果行（每 BOUND 敞口一行 + 子账户汇总一行），幂等键
 * {@code uk_hedge_effect_key} 命中即覆盖更新（R-ACCT-12 口径单一）。
 * 上报口径与效果评估一致（FR-ACCT-10 验收 3 / R-ACCT-12）。
 */
public final class EffectHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/effect";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public EffectHandler(AcctService acctService) {
        this.acctService = acctService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return get(ctx);
        }
        EffectRequest in = JsonCodec.fromJson(AcctParams.body(ctx), EffectRequest.class);
        if (in == null || in.subAccountId == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "subAccountId 必填；复现=补齐请求体后重试", ctx.traceId()));
        }
        if (AcctParams.text(in.periodKey) == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "periodKey 必填（会计期间，如 2026-09）；复现=补齐请求体后重试",
                    ctx.traceId()));
        }
        AcctService.effectView(acctService.evaluateEffect(in.subAccountId,
                AcctParams.text(in.periodKey), ctx.currentUserId()));
        // 计算完成后返回同屏视图（summary + details，供前端一次拉齐）
        Map<String, Object> view = acctService.getEffect(in.subAccountId,
                AcctParams.text(in.periodKey), ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view, ctx.traceId()));
    }

    private HttpResult get(RequestContext ctx) {
        Integer subAccountId = AcctParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId");
        String periodKey = AcctParams.text(ctx.queryParam("periodKey"));
        if (subAccountId == null || periodKey == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "subAccountId 与 periodKey 均必填（idx_hedge_effect_sub_period 联合索引语义）；"
                            + "复现=补齐查询参数后重试", ctx.traceId()));
        }
        return HttpResult.json(200, ApiResponse.ok(
                acctService.getEffect(subAccountId, periodKey, ctx.currentUserId()),
                ctx.traceId()));
    }

    /** 效果计算请求体（POST）。 */
    public static final class EffectRequest {
        /** 子账户（必填）。 */
        public Integer subAccountId;
        /** 会计期间（yyyy-MM；必填）。 */
        public String periodKey;
    }
}
