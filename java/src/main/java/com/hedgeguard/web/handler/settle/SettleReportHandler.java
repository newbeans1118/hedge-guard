package com.hedgeguard.web.handler.settle;

import com.hedgeguard.dao.entity.SettleReport;
import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成/查询每日结算报告 {@code GET /api/v1/settle/report}（{@code T-047}；{@code FR-SETTLE-10/12}）。
 */
public final class SettleReportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
    public static final String PATH = "/api/v1/settle/report";

    private final SettleService settleService;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     */
    public SettleReportHandler(SettleService settleService) {
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
            throw BizException.of(ErrorCode.PARAM_INVALID, "tradeDate 格式非法（yyyy-MM-dd）：" + raw);
        }
        String type = ctx.queryParam("reportType");
        SettleReport report = settleService.generateReport(date, type, ctx.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", report.id);
        data.put("tradeDate", report.tradeDate.toString());
        data.put("reportType", report.reportType);
        data.put("aggResult", report.aggResult);
        data.put("contentJson", report.contentJson);
        data.put("generatedBy", report.generatedBy);
        data.put("generatedTime", report.generatedTime == null ? null : report.generatedTime.toString());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }
}
