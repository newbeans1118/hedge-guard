package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FR-AUTH-16} 处理规则「首次登录与重置后强制修改（口令）」的<b>服务端强制点</b>判据。
 *
 * <p>强制点 = {@link SessionAuthFilter#authenticate}（请求分发前的统一鉴权层，覆盖 HTTP 与 WebSocket 握手）。
 * 会话 {@link Session#mustChangePassword} 为真时，仅放行「本人改口令」（{@code POST /api/v1/auth/password}）
 * 与「登出」（{@code POST /api/v1/auth/logout}），其余业务端点一律 {@code 10003}。
 *
 * <p><b>反退化</b>：删除 {@code SessionAuthFilter.authenticate} 内的门禁分支 ⇒
 * {@link #flaggedBusinessEndpointRejected()} 由 403/10003 变 200（红）。
 */
class MustChangePasswordGateTest {

    private static final String SESSION_ID = "S-CHG";
    private static final String BUSINESS = "/api/v1/auth/user";
    private static final String PASSWORD = "/api/v1/auth/password";
    private static final String LOGOUT = "/api/v1/auth/logout";

    private static RequestDispatcher dispatcher(SessionStore store, boolean flagged) {
        Router router = new Router();
        router.register("GET", BUSINESS,
                ctx -> HttpResult.json(200, ApiResponse.ok("business-ok", ctx.traceId())));
        router.register("POST", PASSWORD,
                ctx -> HttpResult.json(200, ApiResponse.ok("pwd-changed", ctx.traceId())));
        router.register("PUT", PASSWORD,
                ctx -> HttpResult.json(200, ApiResponse.ok("admin-reset", ctx.traceId())));
        router.register("POST", LOGOUT,
                ctx -> HttpResult.json(200, ApiResponse.ok("bye", ctx.traceId())));
        store.save(new Session(SESSION_ID, 7L, "lisi", 1L, flagged,
                Clocks.now().plusMinutes(30)));
        return new RequestDispatcher(router, new StaticHandler(Paths.get(".")),
                new SessionAuthFilter(store, "HGSESSION"));
    }

    private static RequestContext req(String method, String path) {
        return req(method, path, Collections.singletonMap("X-HG-Session", SESSION_ID));
    }

    private static RequestContext req(String method, String path, Map<String, String> headers) {
        return new RequestContext(method, path, null, headers, "{}", "HG-CHG", "127.0.0.1");
    }

    private static String body(HttpResult r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("①未改口令：访问业务端点被拒（403/10003，提示须先改口令）")
    void flaggedBusinessEndpointRejected() {
        RequestDispatcher d = dispatcher(new SessionStore.InMemory(), true);
        HttpResult r = d.dispatch(req("GET", BUSINESS));
        assertEquals(403, r.status, "强制改口令期间访问业务端点须被拒（服务端强制，非前端提示）");
        String b = body(r);
        assertTrue(b.contains("\"code\":10003"), "须复用既有错误码 10003（无新增错误码）body=" + b);
        assertTrue(b.contains("修改口令"), "提示须告知须先修改口令 body=" + b);
    }

    @Test
    @DisplayName("改口令端点（POST）在门禁期间放行")
    void passwordChangeAllowedWhileFlagged() {
        HttpResult r = dispatcher(new SessionStore.InMemory(), true).dispatch(req("POST", PASSWORD));
        assertEquals(200, r.status);
        assertTrue(body(r).contains("pwd-changed"));
    }

    @Test
    @DisplayName("登出端点（POST）在门禁期间放行")
    void logoutAllowedWhileFlagged() {
        HttpResult r = dispatcher(new SessionStore.InMemory(), true).dispatch(req("POST", LOGOUT));
        assertEquals(200, r.status);
    }

    @Test
    @DisplayName("管理员重置端点（PUT /auth/password）在门禁期间被拒（非「本人改口令」）")
    void adminResetRejectedWhileFlagged() {
        HttpResult r = dispatcher(new SessionStore.InMemory(), true).dispatch(req("PUT", PASSWORD));
        assertEquals(403, r.status, "PUT=管理员重置他人，不属强制改口令的放行面");
        assertTrue(body(r).contains("\"code\":10003"));
    }

    @Test
    @DisplayName("③未置位（普通登录）：业务端点不受影响（防误拒）")
    void unflaggedSessionUnaffected() {
        HttpResult r = dispatcher(new SessionStore.InMemory(), false).dispatch(req("GET", BUSINESS));
        assertEquals(200, r.status, "普通会话不得被门禁误拒");
        assertTrue(body(r).contains("business-ok"));
    }

    @Test
    @DisplayName("续期器路径：门禁同样生效（会话续期不绕过强制改口令）")
    void renewerPathStillGated() {
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session(SESSION_ID, 7L, "lisi", 1L, true, Clocks.now().plusMinutes(30)));
        // 带续期器的过滤器（模拟生产装配 authService::touchSession 的滑动超时路径）
        SessionAuthFilter renewingFilter = new SessionAuthFilter(store, "HGSESSION", store::find);
        Router router = new Router();
        router.register("GET", BUSINESS,
                ctx -> HttpResult.json(200, ApiResponse.ok("business-ok", ctx.traceId())));
        RequestDispatcher d2 = new RequestDispatcher(router, new StaticHandler(Paths.get(".")), renewingFilter);
        HttpResult r = d2.dispatch(req("GET", BUSINESS));
        assertEquals(403, r.status, "经续期器续期后的会话仍须受门禁约束");
        // 反向对照：未置位会话经同一续期器应放行
        store.save(new Session("S-OK", 8L, "wangwu", 1L, false, Clocks.now().plusMinutes(30)));
        RequestContext ok = new RequestContext("GET", BUSINESS, null,
                Collections.singletonMap("X-HG-Session", "S-OK"), "{}", "HG-CHG", "127.0.0.1");
        assertEquals(200, d2.dispatch(ok).status);
    }

    @Test
    @DisplayName("WebSocket 握手同受门禁（/ws/alert 为业务数据通道）")
    void wsHandshakeBlockedWhileFlagged() {
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session(SESSION_ID, 7L, "lisi", 1L, true, Clocks.now().plusMinutes(30)));
        AuthFilter filter = new SessionAuthFilter(store, "HGSESSION");

        BizException blocked = assertThrows(BizException.class, () -> filter.authenticate(
                req("GET", "/ws/alert")), "置位会话的 WS 握手须被拒");
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, blocked.errorCode());

        // 反向对照：未置位会话的 WS 握手不被门禁拦截（会话成功绑定）
        store.save(new Session("S-OK", 8L, "wangwu", 1L, false, Clocks.now().plusMinutes(30)));
        RequestContext ok = new RequestContext("GET", "/ws/alert", null,
                Collections.singletonMap("X-HG-Session", "S-OK"), "", "HG-CHG", "127.0.0.1");
        filter.authenticate(ok);
        assertNotNull(ok.session());
    }
}
