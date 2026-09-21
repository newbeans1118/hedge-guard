package com.hedgeguard.web.handler.spot;

import com.hedgeguard.service.SpotService;
import com.hedgeguard.web.Router;

/**
 * {@code SPOT} 模块路由注册（{@code #java2} 产出；路径逐字取自 {@code ApiRoutes.byModule().get("SPOT")}）。
 *
 * <p>归属声明：本类与 {@code web/handler/spot/**} 由 {@code backend-engineer#java2} 新增；
 * {@code web/} 框架类保持 {@code #java} 写入域不变（本类只调用 {@code router.register}）。
 *
 * <pre>
 *   POST /api/v1/spot/purchase         采购合同（CREATE / ACTIVATE / VOID / DELETE_DRAFT）
 *   POST /api/v1/spot/sales            销售合同（CREATE）
 *   POST /api/v1/spot/inventory        库存台账录入（负值须二次确认）
 *   POST /api/v1/spot/wip              在制品录入（完工比例 0–1）
 *   POST /api/v1/spot/price            现货价录入/覆盖（覆盖须确认）
 *   GET  /api/v1/spot/price            取价（purpose=RATIO 缺价拒绝 / VALUATION 可沿用并标注）
 *   POST /api/v1/spot/exposure         计算并落库敞口快照（Σ分量≠合计拒写）
 *   GET  /api/v1/spot/exposure         待套保敞口列表（计划编制入口）
 *   GET  /api/v1/spot/exposure/{id}    敞口详情
 *   POST /api/v1/spot/exposure/recalc  展示侧重算（只告警+留痕，不回写）
 *   POST /api/v1/spot/production       生产计划录入（FR-SPOT-8；重叠提示不阻断）
 *   POST /api/v1/spot/price/import     现货价批量导入（FR-SPOT-11/12；全量预校验）
 *   GET  /api/v1/spot/price/export     现货价序列导出（FR-SPOT-13 派生口径）
 *   GET  /api/v1/spot/exposure/export  实货敞口导出（FR-SPOT-18）
 *   POST /api/v1/spot/doc/sub-account  单据子账户归属变更（FR-SPOT-16）
 * </pre>
 * ⇒ {@code SPOT} 模块的 {@code ApiRoutes} 清单**已全量注册**（{@code T-109} 补齐声明面）。
 */
public final class SpotRoutes {

    private SpotRoutes() {
    }

    /**
     * 注册 {@code SPOT} 模块路由。
     *
     * @param router      路由
     * @param spotService 现货服务
     */
    public static void register(Router router, SpotService spotService) {
        router.register("POST", SpotContractHandler.PATH_PURCHASE,
                new SpotContractHandler(spotService, true));
        router.register("POST", SpotContractHandler.PATH_SALES,
                new SpotContractHandler(spotService, false));
        router.register("POST", SpotStockHandler.PATH_INVENTORY,
                new SpotStockHandler(spotService, true));
        router.register("POST", SpotStockHandler.PATH_WIP, new SpotStockHandler(spotService, false));
        router.register("POST", SpotPriceHandler.PATH, new SpotPriceHandler(spotService));
        router.register("GET", SpotPriceHandler.PATH, new SpotPriceHandler(spotService));
        router.register("POST", SpotExposureHandler.PATH, new SpotExposureHandler(spotService));
        router.register("GET", SpotExposureHandler.PATH, new SpotExposureHandler(spotService));
        router.register("GET", SpotExposureHandler.PATH_BY_ID, new SpotExposureHandler(spotService));
        router.register("POST", SpotExposureHandler.PATH_RECALC, new SpotExposureHandler(spotService));
        // T-109：补齐声明面
        router.register("POST", ProductionPlanHandler.PATH, new ProductionPlanHandler(spotService));
        router.register("POST", SpotPriceImportHandler.PATH, new SpotPriceImportHandler(spotService));
        router.register("GET", SpotPriceExportHandler.PATH, new SpotPriceExportHandler(spotService));
        router.register("GET", SpotExposureExportHandler.PATH,
                new SpotExposureExportHandler(spotService));
        router.register("POST", SpotDocSubAccountHandler.PATH,
                new SpotDocSubAccountHandler(spotService));
    }
}
