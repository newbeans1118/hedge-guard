package com.hedgeguard.web.handler.monitor;

import com.hedgeguard.monitor.ComponentMonitor;
import com.hedgeguard.web.Router;

/**
 * 阶段 1 监控面路由注册（{@code GET /api/v1/monitor/component}）。
 *
 * <p>路径与方法逐字取自 {@code ApiRoutes} 的 {@code MONITOR} 清单，
 * 由 {@code ModuleRoutesConsistencyTest} 的对级等值判据跟随核对。
 */
public final class MonitorRoutes {

    private MonitorRoutes() {
    }

    /**
     * 注册监控端点。
     *
     * @param router  路由
     * @param monitor 监控服务（装配期可传 {@code null}，处理函数会如实返回未装配快照）
     */
    public static void register(Router router, ComponentMonitor monitor) {
        router.register("GET", MonitorHandler.PATH, new MonitorHandler(monitor));
    }
}
