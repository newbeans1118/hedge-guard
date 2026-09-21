package com.hedgeguard.web.handler.product;

import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 品种导出 {@code GET /api/v1/product/export}（{@code FR-PRODUCT-8}；{@code T-012} 验收 4 / {@code T-105}）。
 *
 * <p>返回 {@code text/csv; charset=UTF-8}（导出不受 {@code §4.1.1} 的 JSON 请求体约束）；
 * 首行为表头，字段含逗号时按 CSV 规则加引号（服务层负责转义）。
 *
 * <p><b>范围与排序</b>（{@code T-105} 验收 4）：仅导出当前用户有权限的子账户范围，且排序与
 * {@code GET /product} 的缺省排序（{@code created_time} 倒序）对齐；调用者身份取自会话
 * （{@code ctx.currentUserId()}），不可由查询参数伪造。
 */
public final class ProductExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/product/export");

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public ProductExportHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        List<String> lines = varietyService.exportProducts(ctx.currentUserId());
        String csv = String.join("\r\n", lines) + (lines.isEmpty() ? "" : "\r\n");
        return HttpResult.bytes(200, "text/csv; charset=UTF-8", csv.getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition", "attachment; filename=\"products.csv\"");
    }
}
