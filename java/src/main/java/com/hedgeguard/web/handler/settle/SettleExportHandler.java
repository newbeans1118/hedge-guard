package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 导出结算报表 {@code GET /api/v1/settle/export}（{@code T-047}；{@code FR-SETTLE-16}）。
 *
 * <p>仅导出有权限的子账户范围；含加总对账结论列；无数据返回空行（不生成空文件）。
 */
public final class SettleExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
    public static final String PATH = "/api/v1/settle/export";

    private final SettleService settleService;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     */
    public SettleExportHandler(SettleService settleService) {
        this.settleService = settleService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        SettleService.ExportResult r = settleService.exportReport(
                SettleCycleHandler.dateOrNull(ctx.queryParam("from"), "from"),
                SettleCycleHandler.dateOrNull(ctx.queryParam("to"), "to"),
                SettleCycleHandler.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportTime", r.exportTime == null ? null : r.exportTime.toString());
        data.put("exportedBy", r.exportedBy);
        data.put("columns", r.columns);
        data.put("rows", r.rows);
        data.put("rowCount", r.rows.size());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }
}
