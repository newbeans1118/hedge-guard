package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保证金与风险度视图 {@code GET /api/v1/settle/margin}（{@code T-047}；{@code FR-SETTLE-11}）。
 */
public final class SettleMarginHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
    public static final String PATH = "/api/v1/settle/margin";

    private final SettleService settleService;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     */
    public SettleMarginHandler(SettleService settleService) {
        this.settleService = settleService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        String raw = ctx.queryParam("tradeDate");
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "tradeDate 必填（yyyy-MM-dd）");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "tradeDate 格式非法：" + raw);
        }
        Integer sub = SettleCycleHandler.intOrNull(ctx.queryParam("subAccountId"), "subAccountId");
        SettleService.MarginView view = settleService.marginView(date, sub, ctx.queryParam("threshold"),
                ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tradeDate", date.toString());
        data.put("sumEquity", view.sumEquity == null ? "0" : view.sumEquity.toPlainString());
        data.put("sumMarginOccupied", view.sumMarginOccupied == null ? "0" : view.sumMarginOccupied.toPlainString());
        data.put("aggregateRiskRatio", view.aggregateRiskRatio == null ? null : view.aggregateRiskRatio.toPlainString());
        data.put("aggregateAlert", view.aggregateAlert);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SettleService.MarginRow row : view.rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subAccountId", row.subAccountId);
            m.put("equity", row.equity.toPlainString());
            m.put("marginOccupied", row.marginOccupied.toPlainString());
            m.put("riskRatio", row.riskRatio == null ? null : row.riskRatio.toPlainString());
            m.put("alert", row.alert);
            rows.add(m);
        }
        data.put("items", rows);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }
}
