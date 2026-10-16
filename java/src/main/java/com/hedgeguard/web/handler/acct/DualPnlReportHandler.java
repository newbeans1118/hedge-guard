package com.hedgeguard.web.handler.acct;

import com.hedgeguard.dao.entity.DualPnlReport;
import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 双侧盈亏上报 {@code /api/v1/acct/report/dual-pnl}（{@code FR-ACCT-10}；{@code T-080}）。
 *
 * <p><b>GET</b>（查询上报记录）：{@code reportPeriod} 必填；{@code reportType} 可空
 * （空 = 三类型聚合）；{@code subAccountId} 可空（与 reportPeriod 成对出现）——
 * 留痕三件（上报人/时间/期间）与两栏（实货/衍生品）随行视图透出（R-ACCT-4）。
 *
 * <p><b>POST</b>（提交上报）：请求体 {@code {subAccountId?, reportPeriod, reportType}}——
 * 两栏恒在（{@code spot_pnl_column_present} 恒 'Y'，DB CHECK 结构保证）；实货缺失时
 * {@code spotPnlValue=null} + {@code spotPnlMissingReason} 必填（FR-ACCT-10 验收 2
 * 「不得省略栏位」）；上报留痕落 {@code t_dual_pnl_report}（验收 4）。上报动作
 * （推送监管）不在系统边界（工单边界④）。
 */
public final class DualPnlReportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/report/dual-pnl";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public DualPnlReportHandler(AcctService acctService) {
        this.acctService = acctService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return list(ctx);
        }
        AcctService.DualPnlReportRequest in = JsonCodec.fromJson(
                AcctParams.body(ctx), AcctService.DualPnlReportRequest.class);
        if (in == null || AcctParams.text(in.reportPeriod) == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "reportPeriod 必填（报告期间，如 2026-09）；复现=补齐请求体后重试",
                    ctx.traceId()));
        }
        if (AcctParams.text(in.reportType) == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "reportType 必填（MONTHLY/QUARTERLY/ANNUAL）；复现=补齐请求体后重试",
                    ctx.traceId()));
        }
        DualPnlReport r = acctService.submitDualPnlReport(in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(AcctService.dualPnlView(r), ctx.traceId()));
    }

    private HttpResult list(RequestContext ctx) {
        String reportPeriod = AcctParams.text(ctx.queryParam("reportPeriod"));
        String reportType = AcctParams.text(ctx.queryParam("reportType"));
        Integer subAccountId = AcctParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId");
        List<DualPnlReport> rows = acctService.listDualPnlReports(reportPeriod, reportType,
                subAccountId, ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (DualPnlReport r : rows) {
            items.add(AcctService.dualPnlView(r));
        }
        return HttpResult.json(200, ApiResponse.ok(items, ctx.traceId()));
    }
}
