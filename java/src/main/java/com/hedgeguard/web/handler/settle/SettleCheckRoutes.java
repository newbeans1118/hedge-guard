package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleCheckService;
import com.hedgeguard.web.Router;

/**
 * {@code SETTLE} 模块核对任务与电子签署路由注册（{@code T-048}；{@code FR-SETTLE-13/14/17}）。
 *
 * <pre>
 *   GET  /api/v1/settle/monthly-check      只读查询月度核对任务与签署记录（FR-AUDIT-13）
 *   POST /api/v1/settle/monthly-check      生成/签署月度核对任务（FR-SETTLE-13/17）
 *   GET  /api/v1/settle/quarterly-report   只读查询季度报告任务与签署记录（FR-AUDIT-13）
 *   POST /api/v1/settle/quarterly-report   生成/会签/签收季度报告（FR-SETTLE-14）
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}（未新增路径）。
 */
public final class SettleCheckRoutes {

    private SettleCheckRoutes() {
    }

    /**
     * 注册核对与签署路由（4 个 (方法,路径) 对）。
     *
     * @param router  路由
     * @param service 核对与签署服务
     */
    public static void register(Router router, SettleCheckService service) {
        router.register("GET", SettleCheckHandler.PATH_MONTHLY,
                new SettleCheckHandler(SettleCheckService.CHECK_MONTHLY, service));
        router.register("POST", SettleCheckHandler.PATH_MONTHLY,
                new SettleCheckHandler(SettleCheckService.CHECK_MONTHLY, service));
        router.register("GET", SettleCheckHandler.PATH_QUARTERLY,
                new SettleCheckHandler(SettleCheckService.CHECK_QUARTERLY, service));
        router.register("POST", SettleCheckHandler.PATH_QUARTERLY,
                new SettleCheckHandler(SettleCheckService.CHECK_QUARTERLY, service));
    }
}
