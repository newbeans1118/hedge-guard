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
 *   GET  /api/v1/product            品种列表（分页 + 排序 + 筛选 + 子账户隔离）
 *   POST /api/v1/product            新增品种
 *   GET  /api/v1/product/{id}       品种详情
 *   PUT  /api/v1/product/{id}       品种修改（FR-PRODUCT-2；编码不可改，分类变更 10012）
 *   POST /api/v1/product/{id}/disable  停用（动作式；原因必填；级联移出白名单；FR-PRODUCT-3）
 *   POST /api/v1/product/{id}/enable   启用（动作式；前置＝存在生效换算关系；FR-PRODUCT-4）
 *   POST /api/v1/product/{id}/unit     新增计量单位换算关系（动作式；FR-PRODUCT-13）
 *   GET  /api/v1/product/whitelist  当前完整白名单（productId / subAccountId / status）
 *   POST /api/v1/product/whitelist  白名单加入/移出（action=ENABLE|DISABLE）
 *   GET  /api/v1/product/quota      三层限额全景（展示）
 *   POST /api/v1/product/quota      维护层①/②/③额度
 * </pre>
 * 另含（{@code T-012} 剩余面）：{@code /product/{id}/contracts}、{@code /product/mapping}
 * （{@code GET｜POST}）、{@code /product/change-log}、{@code /product/import}、{@code /product/export}。
 * ⇒ {@code PRODUCT} 模块的 {@code ApiRoutes} 清单**已全量注册**。
 *
 * <p><b>注册序约束（{@code T-151}）</b>：{@code Router.match} 按**注册序首次命中**，故占位符路由
 * {@code GET /product/{id}} 必须**最后**注册；否则会抢占**同段数**的静态 GET
 * （{@code /product/quota}、{@code /product/mapping}、{@code /product/change-log}、{@code /product/export}），
 * 使其在生产装配下不可达（被解析为品种详情 {@code id=quota|mapping|…}）。
 * 新增本模块路由时须**先静态、后占位符**。
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
        // ★T-131：白名单读面（GET）与写面（POST）同路径；静态 2 段路由须先于占位符 /product/{id} 注册
        router.register("GET", WhitelistHandler.PATH, new WhitelistHandler(varietyService));
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
        // T-131：品种写动作（停用 / 启用 / 计量单位）——动作式 POST；三条子路径已在
        //   §4.1.1 v1.5.27 的 PRODUCT 行声明（先声明后改 ApiRoutes，NFR-MAINT-5）
        for (ProductActionHandler.Action action : ProductActionHandler.Action.values()) {
            router.register("POST", action.path(), new ProductActionHandler(varietyService, action));
        }
        // ★T-151：占位符路由 `/product/{id}` 必须**最后**注册。Router.match 为「注册序首次命中」，
        //   若先注册会抢占同段数的静态 GET（/product/quota、/product/mapping、/product/change-log、
        //   /product/export）⇒ 这 4 条端点在生产装配下不可达（被解析为品种详情 id=quota/mapping/…）。
        router.register("GET", ProductHandler.PATH_BY_ID, new ProductHandler(varietyService));
        // T-131：修改面 PUT /product/{id}（同占位符模板，语义 = §4.1.1「PUT 修改」）——随占位符最后注册
        router.register("PUT", ProductHandler.PATH_BY_ID, new ProductHandler(varietyService));
    }
}
