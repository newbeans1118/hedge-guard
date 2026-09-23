package com.hedgeguard.web.handler.product;

import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.Router;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code T-151}：{@code PRODUCT} 模块<b>路由可达性</b>守卫（防「占位符路由抢占静态路由」）。
 *
 * <p><b>为何要这组用例</b>：{@code Router.match} 按<b>注册序首次命中</b>。若占位符路由
 * {@code GET /product/{id}} 注册在**同段数**静态 GET 之前，会把
 * {@code /product/quota}、{@code /product/mapping}、{@code /product/change-log}、{@code /product/export}
 * 全部抢占（解析为品种详情 {@code id=quota|mapping|…}）——这 4 条端点在生产装配下**不可达**。
 * 本用例对 {@code ProductRoutes} 注册的**每一条**路由做「自路径命中自身」检查，任何排序回退即红。
 */
class ProductRouteDispatchTest {

    @Test
    @DisplayName("T-151 全部 PRODUCT 路由自命中：任一路由被占位符抢占即失败（无屏蔽）")
    void noProductRouteIsShadowed() {
        Router router = new Router();
        ProductRoutes.register(router, null);

        List<String> routes = router.routes();
        assertNotNull(routes);
        for (String route : routes) {
            int sp = route.indexOf(' ');
            String method = route.substring(0, sp);
            String path = route.substring(sp + 1);
            Router.RouteMatch match = router.match(method, path);
            assertNotNull(match, "路由未命中自身：" + route);
            assertEquals(path, match.pattern(),
                    "路由被其它路由抢占：" + route + " 实际命中 " + match.pattern()
                            + "（Router.match 为注册序首次命中，占位符须最后注册）");
        }
    }

    @Test
    @DisplayName("T-151 同段数静态 GET 不得被 /product/{id} 抢占（逐条点名）")
    void staticGetRoutesBeatPlaceholder() {
        Router router = new Router();
        ProductRoutes.register(router, null);

        assertHandler(router, "/product/quota", "QuotaHandler");
        assertHandler(router, "/product/mapping", "ProductMappingHandler");
        assertHandler(router, "/product/change-log", "ProductChangeLogHandler");
        assertHandler(router, "/product/export", "ProductExportHandler");
        // 对照：数字 id 仍走详情
        assertHandler(router, "/product/123", "ProductHandler");
    }

    private static void assertHandler(Router router, String path, String expectedSimpleName) {
        Router.RouteMatch match = router.match("GET", ApiRoutes.full(path));
        assertNotNull(match, "GET " + path + " 未命中");
        assertEquals(expectedSimpleName, match.handler().getClass().getSimpleName(),
                "GET " + path + " 须命中 " + expectedSimpleName + "，实际="
                        + match.handler().getClass().getSimpleName() + "（命中 " + match.pattern() + "）");
    }
}
