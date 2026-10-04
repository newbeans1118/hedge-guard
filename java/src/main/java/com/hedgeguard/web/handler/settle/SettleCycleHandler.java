package com.hedgeguard.web.handler.settle;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算单集合 {@code GET /api/v1/settle/cycle}（列表，{@code FR-SETTLE-15}）与
 * {@code POST /api/v1/settle/cycle}（执行子账户拆分与独立结算，{@code FR-SETTLE-2/3/4}）。
 */
public final class SettleCycleHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
    public static final String PATH = "/api/v1/settle/cycle";

    private final SettleService settleService;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     */
    public SettleCycleHandler(SettleService settleService) {
        this.settleService = settleService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return list(ctx);
        }
        SettleService.SettlementInput in = JsonCodec.fromJson(
                ctx.body() == null || ctx.body().trim().isEmpty() ? "{}" : ctx.body(),
                SettleService.SettlementInput.class);
        List<SettleCycle> cycles = settleService.runSettlement(in, ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (SettleCycle c : cycles) {
            items.add(view(c));
        }
        return HttpResult.json(200, ApiResponse.ok(items, ctx.traceId()));
    }

    private HttpResult list(RequestContext ctx) {
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "trade_date", "desc");
        PageResult<SettleCycle> page = settleService.listCycles(query,
                dateOrNull(ctx.queryParam("from"), "from"), dateOrNull(ctx.queryParam("to"), "to"),
                text(ctx.queryParam("status")), intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (SettleCycle c : page.items()) {
            items.add(view(c));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    static Map<String, Object> view(SettleCycle c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("settleNo", c.settleNo);
        m.put("tradeDate", c.tradeDate == null ? null : c.tradeDate.toString());
        m.put("subAccountId", c.subAccountId);
        m.put("status", c.status);
        m.put("closePnl", c.closePnl == null ? "0" : c.closePnl.toPlainString());
        m.put("floatPnl", c.floatPnl == null ? "0" : c.floatPnl.toPlainString());
        m.put("commission", c.commission == null ? "0" : c.commission.toPlainString());
        m.put("marginOccupied", c.marginOccupied == null ? "0" : c.marginOccupied.toPlainString());
        m.put("availableFund", c.availableFund == null ? "0" : c.availableFund.toPlainString());
        m.put("equity", c.equity == null ? "0" : c.equity.toPlainString());
        m.put("riskRatio", c.riskRatio == null ? null : c.riskRatio.toPlainString());
        m.put("negativeFundFlag", c.negativeFundFlag);
        return m;
    }

    static LocalDate dateOrNull(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 格式非法（yyyy-MM-dd）：" + raw);
        }
    }

    static Integer intOrNull(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 须为整数：" + raw);
        }
    }

    static String text(String raw) {
        return raw == null || raw.trim().isEmpty() ? null : raw.trim();
    }
}
