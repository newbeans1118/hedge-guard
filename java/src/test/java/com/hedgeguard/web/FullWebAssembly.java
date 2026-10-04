package com.hedgeguard.web;

import com.hedgeguard.web.handler.auth.AuthRoutes;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.product.ProductRoutes;
import com.hedgeguard.risk_intraday.RiskRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;

/**
 * 六模块（{@code AUTH}/{@code CONFIG}/{@code PRODUCT}/{@code SPOT}/{@code PLAN}/{@code RISK}）的**生产装配**，
 * 供路由级判据共用。
 *
 * <p>为什么必须共用：{@code Router.match} 是「**注册序首次命中**」，因此**注册序本身就是被判对象**
 * （{@code BUG-004} 的成因）。若各测试各写一份装配，两份注册序会各自漂移，判据便失去一致性。
 *
 * <p>服务入参传 {@code null}：装配期只调用 {@code router.register(...)} 构造 Handler，
 * 不驱动任何业务逻辑（与 {@code ModuleRoutesConsistencyTest} 同法）。
 *
 * <h2>「单一来源」的适用范围（{@code T-156} 据 {@code T-154} 复核观察 {@code O-4} 收口）</h2>
 * <p>本类**仅对「可达性系」测试**构成单一来源。当前实际共用它的测试**仅**以下三者：
 * <ul>
 *   <li>{@link RouteReachabilityExhaustiveTest}（全量穷举实例回查）；</li>
 *   <li>{@link RouteReachabilityBug004Test}（{@code BUG-004} 的 5 条回归留痕）；</li>
 *   <li>{@link RouteReachabilityProductionParityTest}（{@code T-157} 装配面集合一致性）。</li>
 * </ul>
 * <p><b>「全仓装配已统一」不成立</b>：仍有 <b>8</b> 个测试类**各自调用模块注册器**（自测口径：在
 * {@code java/src/test} 下检索形如 {@code AuthRoutes.register} / {@code ConfigRoutes.register} … 的调用，
 * 按文件去重后去掉本类）：{@code AUTHEndpointRegistrationTest}、{@code BindExposureHandlerTest}、
 * {@code PlanListRouteTest}、{@code ModuleRoutesConsistencyTest}、{@code ProductRouteDispatchTest}、
 * {@code ProductReadScopeResidualTest}、{@code T109EndpointRegistrationTest}、
 * {@code M1EndToEndAcceptanceTest}。它们各自的服务入参 / 装配范围不同（故未强求统一），
 * 但其**注册序不受本类约束**。
 *
 * <h2>不覆盖面</h2>
 * <ol>
 *   <li><b>(a) {@code Bootstrap} 直接注册的路由不在本装配面内</b>：{@code Bootstrap.assembleWithResources}
 *       在六模块注册器**之前**先注册平台级 {@code GET|HEAD /api/v1/ping}（3 段、最先、全字面），
 *       本类**不含**这 2 条 ⇒ **生产装配面 = 本装配面 + 2**。该差 2 由
 *       {@link RouteReachabilityProductionParityTest}（{@code T-157}）以集合断言显式对齐；
 *       在它落地前，此类漂移只被 {@code BootstrapAssemblyTest} 的 {@code routeCount()==84} 间接兜底。</li>
 *   <li><b>(b) 「镜像方向」断言非穷举</b>：{@link RouteReachabilityExhaustiveTest#unregisteredPathsAreNotMatched()}
 *       只**手选 4 条**未注册路径做反向验证（段数同而末段不匹配 / 未注册模块 / 未注册方法 / 不存在前缀），
 *       并**非**对「全部未注册路径」的穷举。</li>
 * </ol>
 *
 * <p>归属：测试装配面（{@code T-154}）；不改变任何 {@code main} 侧注册序，仅**按原序重放**。
 */
final class FullWebAssembly {

    /**
     * 生产装配下已注册路由条数（六模块完整装配）。
     *
     * <p>{@code T-050} 据「事中风控 REST 面」新增 {@code GET /api/v1/risk/mirror-status} 一条
     * ⇒ {@code 81 → 82}（非为过测试而放宽：{@code ApiRoutes} 已声明该路径，本任务只做<b>最小注册</b>）。
     */
    static final int ROUTE_COUNT = 82;

    private FullWebAssembly() {
    }

    /** 构造六模块生产装配的路由器（注册序与生产一致）。 */
    static Router assemble() {
        Router router = new Router();
        AuthRoutes.register(router, null, null);
        ConfigRoutes.register(router, null);
        ProductRoutes.register(router, null);
        SpotRoutes.register(router, null);
        PlanRoutes.register(router, null);
        // T-050：事中风控 REST 面（GET /api/v1/risk/mirror-status）。服务入参 null：
        // 装配期只调用 router.register(...) 构造 Handler，不驱动任何业务逻辑。
        RiskRoutes.register(router, null, null);
        return router;
    }
}
