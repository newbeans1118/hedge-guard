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
 * 实货敞口导出 {@code GET /api/v1/spot/exposure/export}（{@code T-109}；{@code FR-SPOT-18}）。
 *
 * <p>导出内容含子账户 / 品种 / 方向 / 数量 / 状态列，并携带导出时间、导出人与计算基准日期
 * （供审计复现口径）；<b>仅导出指定子账户范围</b>；<b>无数据时不生成空文件</b>（返回明确错误）。
 */
public final class SpotExposureExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/spot/exposure/export");

    private final SpotService spotService;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     */
    public SpotExposureExportHandler(SpotService spotService) {
        this.spotService = spotService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Integer subAccountId = intOrNull(ctx.queryParam("subAccountId"), "subAccountId");
        LocalDate asOfDate = dateOrNull(ctx.queryParam("asOfDate"), "asOfDate");
        List<String> lines = spotService.exportExposuresCsv(subAccountId, asOfDate, ctx.currentUserId());
        String csv = String.join("\r\n", lines) + "\r\n";
        return HttpResult.bytes(200, "text/csv; charset=UTF-8", csv.getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition", "attachment; filename=\"exposures.csv\"");
    }

    private static Integer intOrNull(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数须为整数：" + field + "=" + raw + "；复现=修正查询参数", e);
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
