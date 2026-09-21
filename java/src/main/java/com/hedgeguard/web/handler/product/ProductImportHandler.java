package com.hedgeguard.web.handler.product;

import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.List;

/**
 * 品种 CSV 导入 {@code POST /api/v1/product/import}（{@code T-012} 验收 3）。
 *
 * <p>请求体：{@code {"csvLines":["product_code,product_name,category,currency,remark","CU,铜,…"]}}。
 *
 * <p><b>全量预校验 → 一次性插入</b>：任一行不合格即返回 {@code 10019} 并给出**逐行问题清单**，
 * 且**不产生任何部分写入**（校验在服务层完成，落库在同一事务内）。
 */
public final class ProductImportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/product/import");

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public ProductImportHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.csvLines == null || request.csvLines.isEmpty()) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "导入须提交 {csvLines:[…]}（首行表头须为 " + VarietyService.CSV_HEADER + "）："
                            + "复现=按 T-012 验收 3 提交");
        }
        int inserted = varietyService.importProducts(request.csvLines, ctx.currentUserId());
        ImportResult result = new ImportResult();
        result.inserted = inserted;
        return HttpResult.json(200, ApiResponse.ok(result, ctx.traceId()));
    }

    /** 导入请求体。 */
    public static final class Request {

        /** CSV 行（首行表头）。 */
        public List<String> csvLines;
    }

    /** 导入结果。 */
    public static final class ImportResult {

        /** 成功导入行数（全量校验通过才会 > 0）。 */
        public int inserted;
    }
}
