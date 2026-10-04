package com.hedgeguard.web.handler.fund;

import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 导出资金对账表 {@code GET /api/v1/fund/export}（{@code FR-FUND-4}）。
 *
 * <p>含期初 / 各类型发生额小计 / 期末 / 凭证编号；含子账户小计与全账户合计；
 * 无数据返回空行（不生成空文件）。
 */
public final class FundExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/export";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundExportHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        FundService.ExportResult r = fundService.exportReconcile(
                FundParams.dateOrNull(ctx.queryParam("from"), "from"),
                FundParams.dateOrNull(ctx.queryParam("to"), "to"),
                FundParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportTime", r.exportTime == null ? null : r.exportTime.toString());
        data.put("exportedBy", r.exportedBy);
        data.put("columns", r.columns);
        data.put("rows", r.rows);
        data.put("rowCount", r.rows.size());
        data.put("subTotals", r.subTotals);
        data.put("grandTotal", r.grandTotal == null ? null : r.grandTotal.toPlainString());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }
}
