package com.hedgeguard.web.handler.risk;

import com.hedgeguard.service.RiskAlertService;
import com.hedgeguard.web.Router;

/**
 * {@code RISK} 模块告警查询与处置路由注册（{@code T-065}；{@code FR-RISK-19}/{@code FR-RISK-15}/{@code FR-RISK-16}）。
 *
 * <pre>
 *   GET  /api/v1/risk/alert                  告警历史查询
 *   POST /api/v1/risk/alert/{id}/dispose     告警处置
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("RISK")}（未新增路径）；
 * 与 {@code RiskRuleRoutes}（阈值面）、{@code risk_intraday.RiskRoutes}（镜像面）分文件。
 * 占位符路由 {@code /risk/alert/{id}/dispose} 与静态路径段数不同，不存在 {@code BUG-004} 型遮蔽。
 */
public final class RiskAlertRoutes {

    private RiskAlertRoutes() {
    }

    /**
     * 注册告警查询与处置路由（2 个 {@code (方法,路径)} 对）。
     *
     * @param router  路由
     * @param service 告警服务
     */
    public static void register(Router router, RiskAlertService service) {
        router.register("GET", RiskAlertHandler.PATH,
                new RiskAlertHandler(RiskAlertHandler.MODE_QUERY, service));
        router.register("POST", RiskAlertHandler.PATH_DISPOSE,
                new RiskAlertHandler(RiskAlertHandler.MODE_DISPOSE, service));
    }
}
