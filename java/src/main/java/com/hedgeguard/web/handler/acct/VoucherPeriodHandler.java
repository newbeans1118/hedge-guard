package com.hedgeguard.web.handler.acct;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.AccountingPeriod;
import com.hedgeguard.dao.entity.PeriodCloseLog;
import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ErrorCode;
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
 * 会计期间 {@code GET /api/v1/acct/period}（分页列表 / 关账历史，{@code FR-ACCT-12}）与
 * {@code POST /api/v1/acct/period}（期间维护 = 开放期间创建，最小承载；{@code T-079}）。
 *
 * <p><b>GET</b>：无 {@code periodKey} = 分页列表；带 {@code periodKey} = 该期间关账历史
 * （{@code t_period_close_log}，审计留痕）。
 *
 * <p><b>POST 请求体</b>：{@code periodKey}(yyyy-MM)/{@code periodType}(MONTH|QUARTER|YEAR)/
 * {@code periodStart}(yyyy-MM-dd)/{@code periodEnd}(yyyy-MM-dd) —— 创建开放期间（幂等：
 * 期间键已存在 ⇒ 10010）。
 */
public final class VoucherPeriodHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/period";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public VoucherPeriodHandler(AcctService acctService) {
        this.acctService = acctService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return get(ctx);
        }
        CreateRequest in = JsonCodec.fromJson(AcctParams.body(ctx), CreateRequest.class);
        if (in == null || in.periodKey == null || in.periodType == null
                || in.periodStart == null || in.periodEnd == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "periodKey/periodType/periodStart/periodEnd 必填；复现=补齐后重试",
                    ctx.traceId()));
        }
        AccountingPeriod p = acctService.createPeriod(in.periodKey, in.periodType,
                in.periodStart, in.periodEnd, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view(p), ctx.traceId()));
    }

    private HttpResult get(RequestContext ctx) {
        String periodKey = AcctParams.text(ctx.queryParam("periodKey"));
        if (periodKey != null) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (PeriodCloseLog log : acctService.listCloseLogs(periodKey, ctx.currentUserId())) {
                items.add(logView(log));
            }
            return HttpResult.json(200, ApiResponse.ok(items, ctx.traceId()));
        }
        PageQuery q = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "period_key", "desc");
        PageResult<AccountingPeriod> page = acctService.listPeriods(q);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AccountingPeriod p : page.items()) {
            items.add(view(p));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    /** 期间视图。 */
    static Map<String, Object> view(AccountingPeriod p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("periodKey", p.periodKey);
        m.put("periodType", p.periodType);
        m.put("periodStart", p.periodStart == null ? null : p.periodStart.toString());
        m.put("periodEnd", p.periodEnd == null ? null : p.periodEnd.toString());
        m.put("status", p.status);
        m.put("closedBy", p.closedBy);
        m.put("closedTime", p.closedTime == null ? null : p.closedTime.toString());
        m.put("closeReviewerId", p.closeReviewerId);
        m.put("reopenedBy", p.reopenedBy);
        m.put("reopenedTime", p.reopenedTime == null ? null : p.reopenedTime.toString());
        m.put("reopenReason", p.reopenReason);
        return m;
    }

    /** 关账留痕视图。 */
    static Map<String, Object> logView(PeriodCloseLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.id);
        m.put("periodKey", log.periodKey);
        m.put("action", log.action);
        m.put("precheckResult", log.precheckResult);
        m.put("operatorId", log.operatorId);
        m.put("reviewerId", log.reviewerId);
        m.put("reason", log.reason);
        m.put("actionTime", log.actionTime == null ? null : log.actionTime.toString());
        return m;
    }

    /** POST 请求体（创建开放期间）。 */
    public static final class CreateRequest {
        /** 期间键（yyyy-MM）。 */
        public String periodKey;
        /** 期间类型（MONTH/QUARTER/YEAR）。 */
        public String periodType;
        /** 期间起。 */
        public java.time.LocalDate periodStart;
        /** 期间止。 */
        public java.time.LocalDate periodEnd;
    }
}
