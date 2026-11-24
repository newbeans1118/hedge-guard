package com.hedgeguard.web.handler.system;

import com.hedgeguard.web.Router;

/**
 * {@code SYSTEM} 模块路由注册（管理端「关闭系统」）。
 *
 * <p>路径与方法逐字取自 {@code ApiRoutes} 的 {@code SYSTEM} 清单，由
 * {@code ModuleRoutesConsistencyTest} 的对级等值判据跟随核对。
 * 服务依赖传 {@code null} 时注册 {@link SystemShutdownHandler#inert()} 装配专用实例
 * （只构造 handler、不驱动业务，与其余 {@code *Routes} 同姿态）。
 */
public final class SystemRoutes {

    private SystemRoutes() {
    }

    /**
     * 注册关闭系统端点。
     *
     * @param router  路由
     * @param handler 处理函数（装配面可传 {@code null}）
     */
    public static void register(Router router, SystemShutdownHandler handler) {
        router.register("POST", SystemShutdownHandler.PATH,
                handler == null ? SystemShutdownHandler.inert() : handler);
    }
}
