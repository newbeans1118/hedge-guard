package com.hedgeguard.web.handler.acct;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 套期关系 {@code GET /api/v1/acct/hedge-relation}（列表，{@code FR-ACCT-1}）与
 * {@code POST /api/v1/acct/hedge-relation}（生成 / 正式指定，{@code FR-ACCT-1} + {@code R-ACCT-1}
 * 三要素门禁；{@code T-077}）。
 *
 * <p>POST 请求体：{@code planId} / {@code exposureId} / {@code instrumentType}(FUTURES|OPTION) /
 * {@code instrumentDesc}。三要素承载于计划的当前版本书面文件（{@code t_hedge_doc}，PLAN 模块写入）；
 * 缺任一要素 ⇒ {@code 10012} 阻断并列缺失项。文档「待外部复核」标注经
 * {@code pendingExternalReview}（{@code t_hedge_doc} 行，恒 'Y'）随详情透出。
 */
public final class HedgeRelationHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/hedge-relation";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public HedgeRelationHandler(AcctService acctService) {
        this.acctService = acctService;
    }

    /** 生成请求体。 */
    public static final class CreateRequest {
        /** 套保计划。 */
        public Long planId;
        /** 被套期项目（实货敞口）。 */
        public Long exposureId;
        /** 套期工具类型（FUTURES / OPTION）。 */
        public String instrumentType;
        /** 套期工具描述（合约与持仓标识）。 */
        public String instrumentDesc;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return list(ctx);
        }
        CreateRequest in = JsonCodec.fromJson(
                AcctParams.body(ctx), CreateRequest.class);
        if (in == null || in.planId == null || in.exposureId == null) {
            return HttpResult.json(400, ApiResponse.fail(
                    com.hedgeguard.web.ErrorCode.PARAM_INVALID,
                    "planId / exposureId 必填；复现=补齐请求体后重试", ctx.traceId()));
        }
        HedgeRelation r = acctService.generateHedgeRelation(in.planId, in.exposureId,
                in.instrumentType, in.instrumentDesc, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view(r), ctx.traceId()));
    }

    private HttpResult list(RequestContext ctx) {
        PageQuery q = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "designated_time", "asc");
        PageResult<HedgeRelation> page = acctService.listHedgeRelations(q,
                AcctParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                AcctParams.text(ctx.queryParam("status")), ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (HedgeRelation r : page.items()) {
            items.add(view(r));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    /** 行视图（字段名 camelCase；「待外部复核」标注经 pendingExternalReview 透出）。 */
    static Map<String, Object> view(HedgeRelation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("subAccountId", r.subAccountId);
        m.put("relationNo", r.relationNo);
        m.put("hedgePlanId", r.hedgePlanId);
        m.put("hedgedItemExposureId", r.hedgedItemExposureId);
        m.put("hedgingInstrumentType", r.hedgingInstrumentType);
        m.put("hedgingInstrumentDesc", r.hedgingInstrumentDesc);
        m.put("hedgeDocId", r.hedgeDocId);
        m.put("pendingExternalReview", "Y"); // R-ACCT-10：本期的套期会计文档一律「待外部复核」
        m.put("status", r.status);
        m.put("designatedTime", r.designatedTime == null ? null : r.designatedTime.toString());
        m.put("terminatedTime", r.terminatedTime == null ? null : r.terminatedTime.toString());
        return m;
    }
}
