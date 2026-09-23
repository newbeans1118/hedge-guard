package com.hedgeguard.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code T-151} / {@code BUG-004}：五模块**完整装配**后，5 条被「占位符路由」遮蔽的静态 GET
 * 必须**命中自身**（防路由遮蔽复发）。
 *
 * <p><b>缺陷（{@code BUG-004}）</b>：{@code Router.match} 按**注册序首次命中**，且占位符匹配任意非空单段
 * ⇒ 若占位符路由注册在**同段数**静态 GET 之前，后者被**静默遮蔽**（在生产装配下不可达）：
 * <ul>
 *   <li>{@code ProductRoutes}：{@code GET /product/{id}} 遮蔽 {@code /product/quota}、{@code /product/mapping}、
 *       {@code /product/change-log}、{@code /product/export}（4 条）；</li>
 *   <li>{@code SpotRoutes}：{@code GET /spot/exposure/{id}} 遮蔽 {@code /spot/exposure/export}（1 条）。</li>
 * </ul>
 * 合计 **5 条**。修复 = 各模块把占位符路由**最后**注册（{@code T-151}）；框架级裁定归 {@code T-153}，
 * 五模块穷举判据归 {@code T-154}。
 *
 * <p><b>判据</b>：完整装配下对这 5 条逐一断言 {@code match("GET", p).pattern()} **等于自身**，
 * 且命中**该端点自己的 Handler**（而非品种/敞口详情 Handler）。
 */
class RouteReachabilityBug004Test {

    @Test
    @DisplayName("BUG-004：5 条被遮蔽的静态 GET 在完整装配下全部命中自身（改前 5/5 遮蔽 → 改后 5/5 命中）")
    void fiveShadowedStaticGetsAreReachable() {
        Router router = fullAssembly();

        // ① PRODUCT 4 条（原被 GET /product/{id} 遮蔽）
        assertReaches(router, "/product/quota", "QuotaHandler");
        assertReaches(router, "/product/mapping", "ProductMappingHandler");
        assertReaches(router, "/product/change-log", "ProductChangeLogHandler");
        assertReaches(router, "/product/export", "ProductExportHandler");
        // ② SPOT 1 条（原被 GET /spot/exposure/{id} 遮蔽）
        assertReaches(router, "/spot/exposure/export", "SpotExposureExportHandler");
    }

    @Test
    @DisplayName("BUG-004 对照：占位符路由仍正确匹配其数字 id（修复未影响详情面）")
    void placeholdersStillMatchNumericIds() {
        Router router = fullAssembly();

        assertMatchesTemplate(router, "/product/123", "/product/{id}", "ProductHandler");
        assertMatchesTemplate(router, "/spot/exposure/123", "/spot/exposure/{id}",
                "SpotExposureHandler");
    }

    /** 五模块完整装配（服务入参 {@code null}：注册期不驱动业务逻辑）。 */
    private static Router fullAssembly() {
        return FullWebAssembly.assemble();
    }

    private static void assertReaches(Router router, String path, String expectedHandler) {
        Router.RouteMatch match = router.match("GET", ApiRoutes.full(path));
        assertNotNull(match, "GET " + path + " 未命中任何路由");
        assertEquals(ApiRoutes.full(path), match.pattern(),
                "GET " + path + " 被同段数占位符遮蔽：实际命中 " + match.pattern());
        assertEquals(expectedHandler, match.handler().getClass().getSimpleName(),
                "GET " + path + " 须命中 " + expectedHandler);
    }

    /** 占位符路由：命中**模板**（非字面路径）与预期 Handler。 */
    private static void assertMatchesTemplate(Router router, String path, String expectedTemplate,
                                              String expectedHandler) {
        Router.RouteMatch match = router.match("GET", ApiRoutes.full(path));
        assertNotNull(match, "GET " + path + " 未命中任何路由");
        assertEquals(ApiRoutes.full(expectedTemplate), match.pattern(),
                "GET " + path + " 须命中模板 " + expectedTemplate + "，实际 " + match.pattern());
        assertEquals(expectedHandler, match.handler().getClass().getSimpleName(),
                "GET " + path + " 须命中 " + expectedHandler);
    }
}
