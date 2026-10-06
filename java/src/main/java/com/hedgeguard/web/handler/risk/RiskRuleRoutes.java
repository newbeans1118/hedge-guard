package com.hedgeguard.web.handler.risk;

import com.hedgeguard.service.RiskService;
import com.hedgeguard.web.Router;

/**
 * {@code RISK} 模块阈值配置面路由注册（{@code T-063}）。
 *
 * <p>注册 {@code GET|POST /api/v1/risk/rule}（方法与路径逐字取自 {@code ApiRoutes.byModule()} 的
 * {@code RISK} 清单 + {@code §4.1.1}），并由 {@code ApiRoutes.BY_MODULE_ROUTES} 的等值判据
 * （{@code ModuleRoutesConsistencyTest}）跟随核对。
 *
 * <p>与 {@code risk_intraday.RiskRoutes}（事中面 {@code /risk/mirror-status}）分文件，避免
 * 改动 {@code T-050} 交付件。
 */
public final class RiskRuleRoutes {

    private RiskRuleRoutes() {
    }

    /**
     * 注册风控阈值配置面路由。
     *
     * @param router      路由
     * @param riskService 风控阈值服务
     */
    public static void register(Router router, RiskService riskService) {
        router.register("GET", RiskRuleHandler.PATH, new RiskRuleHandler(riskService));
        router.register("POST", RiskRuleHandler.PATH, new RiskRuleHandler(riskService));
    }
}
