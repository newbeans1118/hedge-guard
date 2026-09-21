package com.hedgeguard.web.handler.config;

import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 系统参数导出 {@code GET /api/v1/config/export}（{@code T-109}；{@code FR-CONFIG-13} 验收 1）。
 *
 * <p>返回 {@code text/csv; charset=UTF-8}（导出不受 {@code §4.1.1} 的 JSON 请求体约束）；
 * <b>系统级强制排除敏感字段取值</b>（敏感项取值列恒为空，无选项可包含）。
 */
public final class ConfigExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/export");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public ConfigExportHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        List<String> lines = configService.exportConfigItemsCsv(ctx.queryParam("category"));
        String csv = String.join("\r\n", lines) + (lines.isEmpty() ? "" : "\r\n");
        return HttpResult.bytes(200, "text/csv; charset=UTF-8", csv.getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition", "attachment; filename=\"config.csv\"");
    }
}
