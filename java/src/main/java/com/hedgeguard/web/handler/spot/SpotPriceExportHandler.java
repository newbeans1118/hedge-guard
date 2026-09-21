package com.hedgeguard.web.handler.spot;

import com.hedgeguard.service.SpotService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 现货价序列导出 {@code GET /api/v1/spot/price/export}（{@code T-109}）。
 *
 * <p>导出形态对应 {@code FR-SPOT-13} 的价格序列查询（筛选：{@code productId}、{@code from}~{@code to}）；
 * 返回 {@code text/csv; charset=UTF-8}。⚠️ <b>需求侧无独立"现货价导出" FR</b>——本端点的口径由
 * {@code FR-SPOT-13}（查询现货价格序列）派生，已在交付回执中显式登记为待裁定项。
 */
public final class SpotPriceExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/spot/price/export");

    private final SpotService spotService;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     */
    public SpotPriceExportHandler(SpotService spotService) {
        this.spotService = spotService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Long productId = longOrNull(ctx.queryParam("productId"));
        LocalDate from = dateOrNull(ctx.queryParam("from"), "from");
        LocalDate to = dateOrNull(ctx.queryParam("to"), "to");
        List<String> lines = spotService.exportSpotPricesCsv(productId, from, to);
        String csv = String.join("\r\n", lines) + (lines.isEmpty() ? "" : "\r\n");
        return HttpResult.bytes(200, "text/csv; charset=UTF-8", csv.getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition", "attachment; filename=\"spot-prices.csv\"");
    }

    private static Long longOrNull(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数须为整数：productId=" + raw + "；复现=修正查询参数", e);
        }
    }

    private static LocalDate dateOrNull(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数须为 yyyy-MM-dd：" + field + "=" + raw + "；复现=修正日期格式", e);
        }
    }
}
