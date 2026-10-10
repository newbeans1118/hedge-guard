package com.hedgeguard.web.handler.risk;

import com.hedgeguard.service.RiskRecomputeService;
import com.hedgeguard.web.Router;

/**
 * {@code RISK} 模块红线复算与限额使用统计路由注册（{@code T-066}）。
 *
 * <p>注册 {@code GET|POST /api/v1/risk/limit-usage}（方法与路径逐字取自
 * {@code ApiRoutes.byModule().get("RISK")} 的 {@code /risk/limit-usage} 路径级声明 +
 * {@code ARCHITECTURE.md} §4.1.1 的 {@code RISK} 行 GET/POST），并由
 * {@code ApiRoutes.BY_MODULE_ROUTES} 的等值判据（{@code ModuleRoutesConsistencyTest}）跟随核对。
 *
 * <p>与 {@code RiskRuleRoutes}（阈值面）、{@code RiskAlertRoutes}（告警面）、
 * {@code risk_intraday.RiskRoutes}（镜像面）分文件，避免改动既有交付件。
 */
public final class RiskLimitUsageRoutes {

    private RiskLimitUsageRoutes() {
    }

    /**
     * 注册红线复算与限额使用统计路由（2 个 {@code (方法,路径)} 对）。
     *
     * @param router  路由
     * @param service 复算服务
     */
    public static void register(Router router, RiskRecomputeService service) {
        router.register("GET", RiskLimitUsageHandler.PATH,
                new RiskLimitUsageHandler(RiskLimitUsageHandler.MODE_QUERY, service));
        router.register("POST", RiskLimitUsageHandler.PATH,
                new RiskLimitUsageHandler(RiskLimitUsageHandler.MODE_RECOMPUTE, service));
    }
}
