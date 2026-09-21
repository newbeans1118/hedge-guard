package com.hedgeguard.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Router} 单元测试（{@code ARCHITECTURE.md} §4.1.1 路径约定）。 */
class RouterTest {

    private static Router routerWithPing() {
        Router router = new Router();
        router.register("GET", ApiRoutes.full(ApiRoutes.PING), ctx -> HttpResult.text(200, "pong"));
        router.register("GET", "/api/v1/product/{id}", ctx -> HttpResult.text(200, "product"));
        router.register("POST", "/api/v1/product", ctx -> HttpResult.text(200, "created"));
        router.register("GET", "/api/v1/plan/{id}/trade-plans", ctx -> HttpResult.text(200, "plans"));
        return router;
    }

    @Test
    @DisplayName("静态路由与占位符路由均命中，占位符取值正确")
    void matchesStaticAndPlaceholderRoutes() {
        Router router = routerWithPing();

        assertNotNull(router.match("GET", "/api/v1/ping"));
        assertEquals("/api/v1/ping", router.match("GET", "/api/v1/ping").pattern());

        Router.RouteMatch m = router.match("GET", "/api/v1/product/123");
        assertNotNull(m);
        assertEquals("123", m.pathParams().get("id"));

        Router.RouteMatch multi = router.match("GET", "/api/v1/plan/77/trade-plans");
        assertNotNull(multi);
        assertEquals("77", multi.pathParams().get("id"));
    }

    @Test
    @DisplayName("段数不同或字面段不符则不命中；占位符不跨段")
    void rejectsNonMatchingPaths() {
        Router router = routerWithPing();
        assertNull(router.match("GET", "/api/v1/product/123/contracts"));
        assertNull(router.match("GET", "/api/v1/unknown"));
        assertNull(router.match("GET", "/api/v1/product"));
        assertNull(router.match("GET", "/api/v1/product/"));
    }

    @Test
    @DisplayName("方法不匹配时 match 为 null，但 pathExists 为 true（用于 405 判定）")
    void distinguishesMethodMismatchFromMissingPath() {
        Router router = routerWithPing();
        assertNull(router.match("PUT", "/api/v1/product"));
        assertTrue(router.pathExists("/api/v1/product"));
        assertFalse(router.pathExists("/api/v1/not-registered"));
    }

    @Test
    @DisplayName("路径尾斜杠与查询串被归一化")
    void normalizesTrailingSlashAndQuery() {
        Router router = routerWithPing();
        assertNotNull(router.match("GET", "/api/v1/ping/"));
        assertNotNull(router.match("GET", "/api/v1/ping?x=1"));
    }

    @Test
    @DisplayName("重复注册同一 (方法, 路径) 即启动期失败（禁止静默覆盖）")
    void rejectsDuplicateRegistration() {
        Router router = routerWithPing();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> router.register("GET", "/api/v1/ping", ctx -> HttpResult.text(200, "x")));
        assertTrue(e.getMessage().contains("重复注册"));
    }

    @Test
    @DisplayName("非法路由模式被拒绝（不以 / 开头、含空段、占位符未独占整段）")
    void rejectsInvalidPatterns() {
        Router router = new Router();
        assertThrows(IllegalArgumentException.class,
                () -> router.register("GET", "api/v1/ping", ctx -> HttpResult.text(200, "x")));
        assertThrows(IllegalArgumentException.class,
                () -> router.register("GET", "/api/v1//ping", ctx -> HttpResult.text(200, "x")));
        assertThrows(IllegalArgumentException.class,
                () -> router.register("GET", "/api/v1/x{id}", ctx -> HttpResult.text(200, "x")));
    }

    @Test
    @DisplayName("已注册路由清单可用于自检与文档生成")
    void exposesRouteList() {
        Router router = routerWithPing();
        assertTrue(router.routes().contains("GET /api/v1/ping"));
        assertTrue(router.routes().contains("POST /api/v1/product"));
        assertEquals(4, router.routes().size());
    }

    @Test
    @DisplayName("查询串解析：重复参数取最后一个，URL 编码被解码")
    void parsesQuery() {
        Map<String, String> q = QueryStrings.parse("page=1&size=20&sort=create_time%2Cdesc&size=50");
        assertEquals("1", q.get("page"));
        assertEquals("50", q.get("size"));
        assertEquals("create_time,desc", q.get("sort"));
        assertEquals(Collections.emptyMap(), QueryStrings.parse(null));
        assertEquals(Collections.emptyMap(), QueryStrings.parse(""));
    }

    @Test
    @DisplayName("请求上下文：头名大小写不敏感、幂等键与当前用户可读取")
    void requestContextAccessors() {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("idempotency-key", "IDEM-1");
        RequestContext ctx = new RequestContext("post", "/api/v1/fund/transfer", "a=1", headers, "{}",
                "HGT", "127.0.0.1");
        assertEquals("application/json", ctx.header("Content-Type"));
        assertEquals("IDEM-1", ctx.idempotencyKey());
        assertEquals(0L, ctx.currentUserId());
        assertNull(ctx.session());
        ctx.bindSession(new com.hedgeguard.web.Session("S1", 42L, "zhangsan", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        assertEquals(42L, ctx.currentUserId());
    }
}
