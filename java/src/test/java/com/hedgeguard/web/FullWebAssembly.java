package com.hedgeguard.web;

import com.hedgeguard.web.handler.auth.AuthRoutes;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.product.ProductRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;

/**
 * 五模块（{@code AUTH}/{@code CONFIG}/{@code PRODUCT}/{@code SPOT}/{@code PLAN}）的**生产装配**，
 * 供路由级判据共用。
 *
 * <p>为什么必须共用：{@code Router.match} 是「**注册序首次命中**」，因此**注册序本身就是被判对象**
 * （{@code BUG-004} 的成因）。若各测试各写一份装配，两份注册序会各自漂移，判据便失去一致性。
 *
 * <p>服务入参传 {@code null}：装配期只调用 {@code router.register(...)} 构造 Handler，
 * 不驱动任何业务逻辑（与 {@code ModuleRoutesConsistencyTest} 同法）。
 *
 * <p>归属：测试装配面（{@code T-154}）；不改变任何 {@code main} 侧注册序，仅**按原序重放**。
 */
final class FullWebAssembly {

    /** 生产装配下已注册路由条数（五模块完整装配）。 */
    static final int ROUTE_COUNT = 81;

    private FullWebAssembly() {
    }

    /** 构造五模块生产装配的路由器（注册序与生产一致）。 */
    static Router assemble() {
        Router router = new Router();
        AuthRoutes.register(router, null, null);
        ConfigRoutes.register(router, null);
        ProductRoutes.register(router, null);
        SpotRoutes.register(router, null);
        PlanRoutes.register(router, null);
        return router;
    }
}
