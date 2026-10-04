package com.hedgeguard.risk_intraday;

import com.hedgeguard.service.SubAccountVisibility;
import com.hedgeguard.web.Router;

/**
 * {@code RISK} 模块事中面路由注册（本里程碑仅 {@code /risk/mirror-status}）。
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("RISK")}，<b>不新增路径</b>；
 * 其余 RISK 路径（{@code /risk/dashboard}、{@code /risk/alert} 等）属 {@code T-065} 等后续任务。
 *
 * <p><b>可达性</b>：本类调用 {@link Router#register}；生产装配的点位在
 * {@code web/Bootstrap#registerBusinessRoutes}。注册序无占位段（单一路径、全字面），
 * 不存在 {@code BUG-004} 型遮蔽风险。
 */
public final class RiskRoutes {

    private RiskRoutes() {
    }

    /**
     * 注册 RISK 模块事中面路由。
     *
     * @param router     路由
     * @param monitor    事中风控引擎
     * @param visibility 子账户可见性（{@code null} 表示不启用隔离）
     */
    public static void register(Router router, IntradayRiskMonitor monitor,
                                SubAccountVisibility visibility) {
        router.register("GET", MirrorStatusHandler.PATH,
                new MirrorStatusHandler(new MirrorStatusService(monitor, visibility)));
    }
}
