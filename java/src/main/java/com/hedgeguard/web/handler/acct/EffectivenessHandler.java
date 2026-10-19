package com.hedgeguard.web.handler.acct;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.EffectivenessAssess;
import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有效性评估 {@code GET /api/v1/acct/effectiveness}（查询 / 监测复核清单 / 未评估提醒，
 * {@code FR-ACCT-2} + {@code FR-ACCT-4}）与 {@code POST /api/v1/acct/effectiveness}
 * （提交评估，三项定性观察点；{@code T-077}）。
 *
 * <p><b>GET 三形态</b>（互斥，按查询参数分流）：
 * <ul>
 *   <li>缺省：评估记录分页（{@code subAccountId}/{@code periodKey}/{@code conclusion} 筛选，
 *       缺省 {@code assess_time,asc}）；</li>
 *   <li>{@code monitorOnly=true}：仅监测超阈值记录（CFG-07b 复核提示清单，
 *       Q-7：仅提示不判定）；</li>
 *   <li>{@code unassessedPeriod=2026-09}：该期间未评估的套期关系清单
 *       （FR-ACCT-2 验收 4「系统提醒」取数入口）。</li>
 * </ul>
 *
 * <p><b>POST 请求体</b>（{@code AcctService.AssessRequest} 同形）：
 * {@code hedgeRelationId}/{@code periodKey}/{@code observerLinkage}(Y|N)/
 * {@code linkageNote}(近相关必填)/{@code observerDirection}(Y|N)/{@code observerTerm}(Y|N)/
 * {@code exposureExpectedDate}(yyyy-MM-dd)/{@code monitorDeviationRatio}(可空，仅监测)。
 * 结论 = 三项观察点布尔合取；量化监测<b>不参与判定</b>（FR-ACCT-4 验收 2/5）。
 */
public final class EffectivenessHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/effectiveness";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public EffectivenessHandler(AcctService acctService) {
        this.acctService = acctService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return get(ctx);
        }
        AcctService.AssessRequest in = JsonCodec.fromJson(
                AcctParams.body(ctx), AcctService.AssessRequest.class);
        EffectivenessAssess ea = acctService.assessEffectiveness(in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view(ea), ctx.traceId()));
    }

    private HttpResult get(RequestContext ctx) {
        String unassessedPeriod = AcctParams.text(ctx.queryParam("unassessedPeriod"));
        if (unassessedPeriod != null) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (HedgeRelationLike r : HedgeRelationLike.of(
                    acctService.unassessedRelations(unassessedPeriod, ctx.currentUserId()))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("unassessedPeriod", unassessedPeriod);
                m.put("relation", r);
                items.add(m);
            }
            return HttpResult.json(200, ApiResponse.ok(items, ctx.traceId()));
        }
        PageQuery q = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "assess_time", "asc");
        boolean monitorOnly = "true".equalsIgnoreCase(AcctParams.text(ctx.queryParam("monitorOnly")));
        PageResult<EffectivenessAssess> page = acctService.listAssessments(q,
                AcctParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                AcctParams.text(ctx.queryParam("periodKey")),
                AcctParams.text(ctx.queryParam("conclusion")),
                monitorOnly, ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (EffectivenessAssess ea : page.items()) {
            items.add(view(ea));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    /** 评估记录行视图（观察点逐项可见，FR-ACCT-4 验收 1）。 */
    static Map<String, Object> view(EffectivenessAssess ea) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ea.id);
        m.put("subAccountId", ea.subAccountId);
        m.put("hedgeRelationId", ea.hedgeRelationId);
        m.put("periodKey", ea.periodKey);
        m.put("assessMethod", ea.assessMethod);
        m.put("observerLinkage", ea.observerLinkage);
        m.put("linkageNote", ea.linkageNote);
        m.put("observerDirection", ea.observerDirection);
        m.put("observerTerm", ea.observerTerm);
        m.put("exposureExpectedDate", ea.exposureExpectedDate == null ? null : ea.exposureExpectedDate.toString());
        m.put("termDeviationDays", ea.termDeviationDays);
        m.put("monitorDeviationRatio", ea.monitorDeviationRatio == null ? null : ea.monitorDeviationRatio.toPlainString());
        m.put("monitorReviewFlag", ea.monitorReviewFlag);
        m.put("monitorNote", "仅监测提示复核，不参与有效性判定（Q-7 / FR-ACCT-4 验收 2）");
        m.put("observerDetail", ea.observerDetail);
        m.put("conclusion", ea.conclusion);
        m.put("needActionFlag", ea.needActionFlag);
        m.put("actionNote", ea.actionNote);
        m.put("assessorId", ea.assessorId);
        m.put("assessTime", ea.assessTime == null ? null : ea.assessTime.toString());
        m.put("correctedFromId", ea.correctedFromId);
        m.put("isCorrected", ea.isCorrected);
        // v1.3.8/BUG-005/T-201：标注随持久化行承载（DB CHECK 恒 'Y'）——取行值不再硬编码
        m.put("pendingExternalReview", ea.pendingExternalReview);
        return m;
    }

    /** 未评估清单行（字段取套期关系行视图同源；Java 11 无 record ⇒ 静态嵌套类）。 */
    static final class HedgeRelationLike {
        /** 关联套保计划。 */
        final Long hedgePlanId;
        /** 套期关系编号。 */
        final String relationNo;
        /** 子账户。 */
        final Integer subAccountId;
        /** 当前状态。 */
        final String status;

        HedgeRelationLike(Long hedgePlanId, String relationNo, Integer subAccountId, String status) {
            this.hedgePlanId = hedgePlanId;
            this.relationNo = relationNo;
            this.subAccountId = subAccountId;
            this.status = status;
        }

        static List<HedgeRelationLike> of(List<com.hedgeguard.dao.entity.HedgeRelation> rows) {
            List<HedgeRelationLike> out = new ArrayList<>();
            for (com.hedgeguard.dao.entity.HedgeRelation r : rows) {
                out.add(new HedgeRelationLike(r.hedgePlanId, r.relationNo, r.subAccountId, r.status));
            }
            return out;
        }
    }
}
