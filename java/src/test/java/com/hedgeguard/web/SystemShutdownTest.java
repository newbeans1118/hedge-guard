package com.hedgeguard.web;

import com.hedgeguard.web.handler.system.SystemRoutes;
import com.hedgeguard.web.handler.system.SystemShutdownHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端「关闭系统」单测（{@code ARCHITECTURE.md §4.1.1 SYSTEM}）。
 *
 * <p>覆盖：状态机单向 CAS、二次确认与原因校验、账户级鉴权（fail-closed）、C++ STOPPING 下发、
 * 排空调度（动作可注入、响应先于停机）、重复关闭拒止、停机态拒新请求、路由声明与注册。
 *
 * <p><b>全部依赖为可注入端口</b>：无数据库、无 CTP、无 HTTP socket，<b>绝不触发 System.exit</b>
 * （{@code NFR-TEST-2} / {@code NFR-TEST-4}）。
 */
class SystemShutdownTest {

    private static final String PATH = ApiRoutes.full(ApiRoutes.SYSTEM_SHUTDOWN);
    private static final String VALID_BODY = "{\"confirm\":true,\"reason\":\"EOD close\"}";

    /** 记录型调度器：只保存 (delay, action)，不真起线程、不真停机。 */
    private static final class RecordingScheduler implements ShutdownCoordinator.ShutdownScheduler {

        long delayMillis = -1L;
        Runnable action;

        @Override
        public void schedule(long delayMillis, Runnable action) {
            this.delayMillis = delayMillis;
            this.action = action;
        }
    }

    private static RequestContext ctx(String method, String path, String body, long userId) {
        RequestContext c = new RequestContext(method, path, null, Collections.emptyMap(),
                body, "trace-shutdown", "10.0.0.1");
        c.bindSession(new Session("sess", userId, "admin", 1L, LocalDateTime.now().plusHours(1)));
        return c;
    }

    private static RequestContext post(String body, long userId) {
        return ctx("POST", PATH, body, userId);
    }

    private static String json(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("状态机：RUNNING → STOPPING 仅受理一次 → STOPPED，停机动作可注入且只执行一次")
    void stateMachineIsOneWayAndActionIsInjected() {
        ShutdownCoordinator c = new ShutdownCoordinator(0L, (delay, action) -> { });
        AtomicInteger actions = new AtomicInteger();
        c.onShutdown(actions::incrementAndGet);

        assertEquals(ShutdownCoordinator.State.RUNNING, c.state());
        assertFalse(c.isStopping());
        assertTrue(c.beginStopping(42L, "admin", "EOD"));
        assertEquals(ShutdownCoordinator.State.STOPPING, c.state());
        assertTrue(c.isStopping());
        assertFalse(c.beginStopping(43L, "intruder", "again"), "并发/重复关闭不得二次受理");

        assertEquals(42L, c.operatorId());
        assertEquals("admin", c.operator());
        assertEquals("EOD", c.reason());

        c.runShutdown();
        assertEquals(ShutdownCoordinator.State.STOPPED, c.state());
        assertEquals(1, actions.get(), "停机动作须且仅须执行一次");
    }

    @Test
    @DisplayName("参数门禁：缺 confirm / confirm=false / 原因空白 / 原因超长 ⇒ 10001，且不触发任何副作用")
    void requestValidationIsServerSide() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ShutdownCoordinator c = new ShutdownCoordinator(1500L, scheduler);
        AtomicInteger authCalls = new AtomicInteger();
        AtomicInteger notifyCalls = new AtomicInteger();
        SystemShutdownHandler h = new SystemShutdownHandler(c,
                userId -> authCalls.incrementAndGet(),
                (operatorId, reason) -> notifyCalls.incrementAndGet());

        String tooLong = "{\"confirm\":true,\"reason\":\""
                + String.join("", Collections.nCopies(SystemShutdownHandler.MAX_REASON_LENGTH + 1, "x"))
                + "\"}";
        for (String body : Arrays.asList("{}", "{\"confirm\":false,\"reason\":\"x\"}",
                "{\"confirm\":true}", "{\"confirm\":true,\"reason\":\"   \"}", tooLong)) {
            BizException ex = assertThrows(BizException.class, () -> h.handle(post(body, 7L)),
                    "非法请求体须被拒：" + body);
            assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());
        }
        assertEquals(ShutdownCoordinator.State.RUNNING, c.state(), "校验失败不得改变状态机");
        assertEquals(0, authCalls.get(), "校验失败不得触发鉴权");
        assertEquals(0, notifyCalls.get(), "校验失败不得下发 C++ STOPPING");
        assertNull(scheduler.action, "校验失败不得安排停机");
    }

    @Test
    @DisplayName("鉴权 fail-closed：无账户级配置权 ⇒ 10003，状态机不变、不下发")
    void unauthorizedIsRejectedBeforeStateChange() {
        ShutdownCoordinator c = new ShutdownCoordinator(0L, (delay, action) -> { });
        SystemShutdownHandler h = new SystemShutdownHandler(c,
                userId -> {
                    throw BizException.of(ErrorCode.FUNCTION_PERMISSION_DENIED, "无权关闭系统");
                },
                (operatorId, reason) -> {
                    throw new AssertionError("鉴权未通过时不得下发");
                });
        BizException ex = assertThrows(BizException.class, () -> h.handle(post(VALID_BODY, 7L)));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, ex.errorCode());
        assertEquals(ShutdownCoordinator.State.RUNNING, c.state());
    }

    @Test
    @DisplayName("正常受理：鉴权 + C++ STOPPING 下发 + 排空调度；响应先于停机动作")
    void acceptedShutdownReturnsBeforeActionRuns() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ShutdownCoordinator c = new ShutdownCoordinator(2500L, scheduler);
        AtomicLong authorizedUserId = new AtomicLong();
        AtomicReference<String> notified = new AtomicReference<>();
        AtomicInteger actions = new AtomicInteger();
        c.onShutdown(actions::incrementAndGet);
        SystemShutdownHandler h = new SystemShutdownHandler(c,
                authorizedUserId::set,
                (operatorId, reason) -> notified.set(operatorId + ":" + reason));

        HttpResult result = h.handle(post(VALID_BODY, 88L));
        assertEquals(200, result.status);
        String body = json(result);
        assertTrue(body.contains("\"code\":0"), "统一信封 code=0；实际=" + body);
        assertTrue(body.contains("\"state\":\"STOPPING\""), "回执须回显 STOPPING；实际=" + body);
        assertTrue(body.contains("\"reason\":\"EOD close\""), "回执须回显原因；实际=" + body);
        assertTrue(body.contains("\"drainMillis\":2500"), "回执须回显排空窗口；实际=" + body);

        assertEquals(88L, authorizedUserId.get());
        assertEquals("88:EOD close", notified.get());
        assertEquals(2500L, scheduler.delayMillis);
        assertNotNull(scheduler.action, "须安排排空后停机");
        assertEquals(0, actions.get(), "响应阶段不得执行停机动作（否则响应写不回）");

        scheduler.action.run();
        assertEquals(1, actions.get());
        assertEquals(ShutdownCoordinator.State.STOPPED, c.state());
    }

    @Test
    @DisplayName("重复关闭：第二次请求 ⇒ 10009，状态机不变、不下发第二次")
    void duplicateShutdownIsRejected() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ShutdownCoordinator c = new ShutdownCoordinator(0L, scheduler);
        AtomicInteger notifyCalls = new AtomicInteger();
        SystemShutdownHandler h = new SystemShutdownHandler(c,
                userId -> { },
                (operatorId, reason) -> notifyCalls.incrementAndGet());

        assertEquals(200, h.handle(post(VALID_BODY, 1L)).status);
        BizException ex = assertThrows(BizException.class, () -> h.handle(post(VALID_BODY, 2L)));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.errorCode());
        assertEquals(ShutdownCoordinator.State.STOPPING, c.state());
        assertEquals(1, notifyCalls.get(), "C++ STOPPING 只下发一次");
    }

    @Test
    @DisplayName("C++ 下发失败不阻断本地停机（显式记日志，不静默假装生效）")
    void notifierFailureDoesNotBlockShutdown() {
        RecordingScheduler scheduler = new RecordingScheduler();
        ShutdownCoordinator c = new ShutdownCoordinator(0L, scheduler);
        SystemShutdownHandler h = new SystemShutdownHandler(c,
                userId -> { },
                (operatorId, reason) -> {
                    throw new IllegalStateException("bridge down");
                });
        assertEquals(200, h.handle(post(VALID_BODY, 1L)).status);
        assertEquals(ShutdownCoordinator.State.STOPPING, c.state());
        assertNotNull(scheduler.action, "下发失败仍须安排本地停机");
    }

    @Test
    @DisplayName("停机态拒新请求：业务路径 10009，关闭端点 / ping / logout 放行，且先委托认证")
    void stoppingGuardRejectsBusinessRequests() {
        ShutdownCoordinator c = new ShutdownCoordinator(0L, (delay, action) -> { });
        AtomicInteger authCalls = new AtomicInteger();
        AuthFilter delegate = new AuthFilter() {
            @Override
            public boolean isPublic(RequestContext context) {
                return false;
            }

            @Override
            public void authenticate(RequestContext context) {
                authCalls.incrementAndGet();
            }
        };
        StoppingGuardFilter guard = new StoppingGuardFilter(delegate, c);

        assertDoesNotThrow(() -> guard.authenticate(ctx("GET", ApiRoutes.full("/product"), "", 1L)),
                "RUNNING 期间不得影响正常请求");
        assertTrue(c.beginStopping(1L, "admin", "EOD"));

        BizException ex = assertThrows(BizException.class,
                () -> guard.authenticate(ctx("GET", ApiRoutes.full("/product"), "", 1L)));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.errorCode());
        assertDoesNotThrow(() -> guard.authenticate(ctx("POST", PATH, VALID_BODY, 1L)),
                "关闭端点自身须放行，让 handler 给出 10009 与当前状态");
        assertDoesNotThrow(() -> guard.authenticate(ctx("GET", ApiRoutes.full(ApiRoutes.PING), "", 1L)),
                "存活探针须放行");
        assertDoesNotThrow(() -> guard.authenticate(ctx("POST", ApiRoutes.full("/auth/logout"), "", 1L)),
                "主动登出须放行");
        assertEquals(5, authCalls.get(), "每次请求都须先委托既有认证（不泄露停机态给未认证方）");
    }

    @Test
    @DisplayName("路由面：SYSTEM 端点已声明 + 已注册（装配专用实例不触发真实停机）")
    void routeIsDeclaredAndRegistered() {
        Router router = new Router();
        SystemRoutes.register(router, null);
        assertEquals(1, router.routes().size());
        assertEquals("POST " + PATH, router.routes().get(0));
        assertNotNull(router.match("POST", PATH));
        assertTrue(ApiRoutes.allRoutes().contains("POST " + PATH),
                "冻结对级声明须包含 SYSTEM 端点");
        assertTrue(ApiRoutes.byModule().get("SYSTEM").contains(ApiRoutes.SYSTEM_SHUTDOWN));

        // inert() 实例：仅构造路由面，零排空窗口 + 空调度，绝不触发 System.exit。
        assertEquals(200, SystemShutdownHandler.inert().handle(post(VALID_BODY, 1L)).status);
    }
}
