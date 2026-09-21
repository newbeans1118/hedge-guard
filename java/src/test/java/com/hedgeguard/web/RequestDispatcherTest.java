package com.hedgeguard.web;

import com.hedgeguard.common.Config;
import com.hedgeguard.web.handler.PingHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequestDispatcher} 端到端（进程内）测试。
 *
 * <p>覆盖验收项："{@code GET /api/v1/ping} 返回 {@code {"code":0,...}} 信封"与
 * "静态托管生效"——不依赖 socket，直接走分发器（{@code NFR-TEST-2}）。
 */
class RequestDispatcherTest {

    private static RequestDispatcher dispatcher(Path webRoot) {
        SessionStore store = new SessionStore.InMemory();
        AuthFilter auth = new SessionAuthFilter(store, "HGSESSION");
        Router router = new Router();
        router.register("GET", ApiRoutes.full(ApiRoutes.PING), new PingHandler());
        router.register("GET", "/api/v1/auth/user", ctx -> HttpResult.json(200,
                ApiResponse.ok("users", ctx.traceId())));
        router.register("POST", "/api/v1/auth/logout", ctx -> HttpResult.text(200, "bye"));
        return new RequestDispatcher(router, new StaticHandler(webRoot), auth);
    }

    private static RequestContext req(String method, String path) {
        return req(method, path, Collections.emptyMap());
    }

    private static RequestContext req(String method, String path, Map<String, String> headers) {
        return new RequestContext(method, path, null, headers, "", "HGTEST", "127.0.0.1");
    }

    @Test
    @DisplayName("GET /api/v1/ping 为公开路径，返回 code=0 信封（含 traceId）")
    void pingIsPublicAndReturnsEnvelope(@TempDir Path root) {
        HttpResult r = dispatcher(root).dispatch(req("GET", "/api/v1/ping"));
        assertEquals(200, r.status);
        assertEquals("application/json; charset=UTF-8", r.contentType);
        String body = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(body.startsWith("{\"code\":0,"), "信封须以 code:0 开头，实际=" + body);
        assertTrue(body.contains("\"message\":\"OK\""));
        assertTrue(body.contains("\"data\":{"));
        assertTrue(body.contains("\"traceId\":\"HGTEST\""));
        assertTrue(body.contains("\"service\":\"hg-biz\""));
    }

    @Test
    @DisplayName("未登录访问业务接口 → 10006（安全默认：非白名单一律拒绝）")
    void protectedRouteRequiresSession(@TempDir Path root) {
        HttpResult r = dispatcher(root).dispatch(req("GET", "/api/v1/auth/user"));
        assertEquals(401, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":10006"), "body=" + body);
        assertTrue(body.contains("\"traceId\":\"HGTEST\""));
    }

    @Test
    @DisplayName("携带有效会话 Cookie 可访问业务接口")
    void validSessionAllowsAccess(@TempDir Path root) {
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-1", 7L, "lisi", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        Router router = new Router();
        router.register("GET", "/api/v1/auth/user", ctx -> HttpResult.json(200,
                ApiResponse.ok("uid=" + ctx.currentUserId(), ctx.traceId())));
        RequestDispatcher d = new RequestDispatcher(router, new StaticHandler(root),
                new SessionAuthFilter(store, "HGSESSION"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "other=x; HGSESSION=S-1; theme=dark");
        HttpResult r = d.dispatch(req("GET", "/api/v1/auth/user", headers));
        assertEquals(200, r.status);
        assertTrue(new String(r.body, StandardCharsets.UTF_8).contains("uid=7"));

        HttpResult viaHeader = d.dispatch(new RequestContext("GET", "/api/v1/auth/user", null,
                Collections.singletonMap("x-hg-session", "S-1"), "", "HGTEST", "127.0.0.1"));
        assertEquals(200, viaHeader.status);
    }

    @Test
    @DisplayName("过期会话 → 10006")
    void expiredSessionRejected(@TempDir Path root) {
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-OLD", 7L, "lisi", 1L,
                com.hedgeguard.common.Clocks.now().minusMinutes(1)));
        Router router = new Router();
        router.register("GET", "/api/v1/auth/user", ctx -> HttpResult.text(200, "ok"));
        RequestDispatcher d = new RequestDispatcher(router, new StaticHandler(root),
                new SessionAuthFilter(store, "HGSESSION"));
        assertEquals(401, d.dispatch(req("GET", "/api/v1/auth/user",
                Collections.singletonMap("x-hg-session", "S-OLD"))).status);
    }

    @Test
    @DisplayName("路由失败：已认证请求下未知接口 404、方法用错 405、非保留前缀 404")
    void routingFailures(@TempDir Path root) {
        RequestDispatcher d = authenticatedDispatcher(root);
        Map<String, String> auth = sessionHeader();
        assertEquals(404, d.dispatch(req("GET", "/api/v1/nope", auth)).status);
        assertEquals(405, d.dispatch(req("POST", "/api/v1/ping", auth)).status);
        assertEquals(404, d.dispatch(req("GET", "/favicon.ico", auth)).status);
        assertEquals(404, d.dispatch(req("GET", "/", auth)).status);
    }

    @Test
    @DisplayName("未认证请求不区分路径是否存在（均 10006，避免端点枚举）")
    void unauthenticatedRequestsDoNotEnumerateEndpoints(@TempDir Path root) {
        RequestDispatcher d = authenticatedDispatcher(root);
        assertEquals(401, d.dispatch(req("GET", "/api/v1/nope")).status);
        assertEquals(401, d.dispatch(req("GET", "/api/v1/auth/user")).status);
        assertEquals(401, d.dispatch(req("POST", "/api/v1/ping")).status);
    }

    @Test
    @DisplayName("静态资源经分发器可访问，白名单外扩展名 404")
    void staticThroughDispatcher(@TempDir Path root) throws IOException {
        Files.write(root.resolve("index.html"), "<html>ok</html>".getBytes(StandardCharsets.UTF_8));
        RequestDispatcher d = dispatcher(root);
        assertEquals(200, d.dispatch(req("GET", "/web/index.html")).status);
        assertEquals(404, d.dispatch(req("GET", "/web/index.htm")).status);
    }

    @Test
    @DisplayName("处理器抛业务异常时转为统一错误响应（不冒泡）")
    void handlerExceptionMappedToErrorEnvelope(@TempDir Path root) {
        Router router = new Router();
        router.register("GET", "/api/v1/risk/dashboard", ctx -> {
            throw BizException.blocking(ErrorCode.AGGREGATION_MISMATCH, "Σ 子账户 1000.00 ≠ 真实账户 999.00",
                    "R-SETTLE-2", "1000.00", "999.00", "请定位差异所属子账户后处理");
        });
        RequestDispatcher d = new RequestDispatcher(router, new StaticHandler(root),
                new SessionAuthFilter(loggedInStore(), "HGSESSION"));
        HttpResult r = d.dispatch(req("GET", "/api/v1/risk/dashboard", sessionHeader()));
        assertEquals(412, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":10013"));
        assertTrue(body.contains("\"ruleId\":\"R-SETTLE-2\""));
        assertTrue(body.contains("\"current\":\"1000.00\""));
        assertTrue(body.contains("\"threshold\":\"999.00\""));
        assertTrue(body.contains("\"nextStep\""));
    }

    /** 已含一个有效会话（7 号用户）的存储。 */
    private static SessionStore loggedInStore() {
        SessionStore.InMemory store = new SessionStore.InMemory();
        store.save(new Session("S-1", 7L, "lisi", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        return store;
    }

    private static Map<String, String> sessionHeader() {
        return Collections.singletonMap("X-HG-Session", "S-1");
    }

    private static RequestDispatcher authenticatedDispatcher(Path webRoot) {
        SessionStore store = loggedInStore();
        Router router = new Router();
        router.register("GET", ApiRoutes.full(ApiRoutes.PING), new PingHandler());
        router.register("GET", "/api/v1/auth/user", ctx -> HttpResult.json(200,
                ApiResponse.ok("users", ctx.traceId())));
        router.register("POST", "/api/v1/auth/logout", ctx -> HttpResult.text(200, "bye"));
        return new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
    }

    @Test
    @DisplayName("Bootstrap.assemble 装配出的分发器：ping 可用且 Web 根目录来自配置")
    void bootstrapAssemblyWorks(@TempDir Path root) throws IOException {
        Files.write(root.resolve("index.html"), "<html>b</html>".getBytes(StandardCharsets.UTF_8));
        Config config = Config.empty();
        System.setProperty(Config.KEY_WEB_ROOT, root.toString());
        try {
            RequestDispatcher d = Bootstrap.assemble(config);
            assertEquals(200, d.dispatch(req("GET", "/api/v1/ping")).status);
            assertEquals(200, d.dispatch(req("GET", "/web/index.html")).status);
            assertEquals(200, d.dispatch(req("HEAD", "/api/v1/ping")).status);
        } finally {
            System.clearProperty(Config.KEY_WEB_ROOT);
        }
    }

    @Test
    @DisplayName("会话存储：按用户批量失效与过期清理")
    void sessionStoreInvalidatesByUser(@TempDir Path root) {
        SessionStore.InMemory store = new SessionStore.InMemory();
        store.save(new Session("A", 1L, "u1", 1L, com.hedgeguard.common.Clocks.now().plusMinutes(10)));
        store.save(new Session("B", 1L, "u1", 1L, com.hedgeguard.common.Clocks.now().plusMinutes(10)));
        store.save(new Session("C", 2L, "u2", 1L, com.hedgeguard.common.Clocks.now().minusMinutes(1)));
        assertEquals(2, store.invalidateByUser(1L));
        assertNull(store.find("A"));
        assertNull(store.find("B"));
        assertEquals(1, store.purgeExpired());
        assertNull(store.find("C"));
        assertNull(store.find(null));
    }
}
