package com.hedgeguard.web.handler.product;

import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.Router;

/**
 * {@code PRODUCT} 模块路由注册（{@code #java2} 产出；路径逐字取自 {@code ApiRoutes.byModule().get("PRODUCT")}）。
 *
 * <p>归属声明：本类与 {@code web/handler/product/**} 由 {@code backend-engineer#java2} 新增；
 * {@code web/} 框架类保持 {@code #java} 写入域不变（本类只调用 {@code router.register}）。
 *
 * <pre>
 *   GET  /api/v1/product            品种列表（分页 + 创建时间倒序 + 子账户隔离）
 *   POST /api/v1/product            新增品种
 *   GET  /api/v1/product/{id}       品种详情
 *   POST /api/v1/product/whitelist  白名单加入/移出（action=ENABLE|DISABLE）
 *   GET  /api/v1/product/quota      三层限额全景（展示）
 *   POST /api/v1/product/quota      维护层①/②/③额度
 * </pre>
 * 另含（{@code T-012} 剩余面）：{@code /product/{id}/contracts}、{@code /product/mapping}
 * （{@code GET｜POST}）、{@code /product/change-log}、{@code /product/import}、{@code /product/export}。
 * ⇒ {@code PRODUCT} 模块的 {@code ApiRoutes} 清单**已全量注册**。
 */
public final class ProductRoutes {

    private ProductRoutes() {
    }

    /**
     * 注册 {@code PRODUCT} 模块路由。
     *
     * @param router         路由
     * @param varietyService 品种服务
     */
    public static void register(Router router, VarietyService varietyService) {
        router.register("GET", ProductHandler.PATH, new ProductHandler(varietyService));
        router.register("POST", ProductHandler.PATH, new ProductHandler(varietyService));
        router.register("GET", ProductHandler.PATH_BY_ID, new ProductHandler(varietyService));
        router.register("POST", WhitelistHandler.PATH, new WhitelistHandler(varietyService));
        router.register("GET", QuotaHandler.PATH, new QuotaHandler(varietyService));
        router.register("POST", QuotaHandler.PATH, new QuotaHandler(varietyService));
        // T-012 剩余路径（合约关联 / 现货映射 / 变更历史 / 导入 / 导出）
        router.register("GET", ProductContractHandler.PATH_BY_PRODUCT,
                new ProductContractHandler(varietyService));
        router.register("POST", ProductContractHandler.PATH_BY_PRODUCT,
                new ProductContractHandler(varietyService));
        router.register("GET", ProductMappingHandler.PATH, new ProductMappingHandler(varietyService));
        router.register("POST", ProductMappingHandler.PATH, new ProductMappingHandler(varietyService));
        router.register("GET", ProductChangeLogHandler.PATH,
                new ProductChangeLogHandler(varietyService));
        router.register("POST", ProductImportHandler.PATH, new ProductImportHandler(varietyService));
        router.register("GET", ProductExportHandler.PATH, new ProductExportHandler(varietyService));
    }
}
