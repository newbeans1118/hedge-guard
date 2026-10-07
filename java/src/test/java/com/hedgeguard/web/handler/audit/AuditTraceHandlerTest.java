package com.hedgeguard.web.handler.audit;

import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.service.AuditService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-067} 审计追溯 REST 端点层对接测试（{@code FR-AUDIT-1/2/3}）。
 *
 * <p>与 {@code com.hedgeguard.service.AuditTraceServiceTest}（服务层真实 DAO）互补：本类走
 * <b>真实 {@link RequestDispatcher} + {@link Router} + {@link AuditTraceHandler}</b>，验证
 * <ul>
 *   <li>4 条已声明 AUDIT 路径的 GET/POST 共 8 对均已注册且由 {@link AuditTraceHandler} 处理；</li>
 *   <li>参数缺失在端点层表现为 {@code 400/10001}；</li>
 *   <li>反序列化/序列化链路通（POST 信封 {@code code=0} + {@code data.chainIntegrity}）。</li>
 * </ul>
 * 完全离线（服务以 {@code null} DAO 装配，仅走「校验/分发」路径；{@code NFR-TEST-2}）。
 */
class AuditTraceHandlerTest {

    private static final long OPERATOR = 42L;

    @Test
    @DisplayName("端点层：AUDIT 4 路径 × GET/POST 共 8 对均已注册并由 AuditTraceHandler 处理")
    void allAuditTraceRoutesRegistered() {
        Router router = new Router();
        AuditTraceRoutes.register(router, service());
        String[][] expected = {
                {"GET", AuditTraceHandler.PATH_FORWARD},
                {"POST", AuditTraceHandler.PATH_FORWARD},
                {"GET", AuditTraceHandler.PATH_BACKWARD},
                {"POST", AuditTraceHandler.PATH_BACKWARD},
                {"GET", AuditTraceHandler.PATH_INTEGRITY},
                {"POST", AuditTraceHandler.PATH_INTEGRITY},
                {"GET", AuditTraceHandler.PATH_OPLOG},
                {"POST", AuditTraceHandler.PATH_OPLOG},
        };
        for (String[] pair : expected) {
            Router.RouteMatch m = router.match(pair[0], pair[1]);
            assertNotNull(m, "端点未注册：" + pair[0] + " " + pair[1]);
            assertTrue(m.handler() instanceof AuditTraceHandler,
                    "应由 AuditTraceHandler 处理：" + pair[0] + " " + pair[1]
                            + " 实际=" + m.handler().getClass().getName());
        }
    }

    @Test
    @DisplayName("端点层：GET /audit/trace/forward 缺 exposureId ⇒ 400/10001")
    void forwardMissingExposureId(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "GET", "/audit/trace/forward", null, null);
        assertEquals(400, r.status);
        assertEquals(10001, code(r));
    }

    @Test
    @DisplayName("端点层：GET /audit/trace/integrity 缺 exposureId ⇒ 400/10001")
    void integrityMissingExposureId(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "GET", "/audit/trace/integrity", null, null);
        assertEquals(400, r.status);
        assertEquals(10001, code(r));
    }

    @Test
    @DisplayName("端点层：POST /audit/trace/backward 起点类型非法 ⇒ 200 且 chainIntegrity=HAS_BREAK（信封锁通）")
    void backwardInvalidStartType(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "POST", "/audit/trace/backward", "{}", null);
        assertEquals(200, r.status);
        assertEquals(0, code(r));
        assertTrue(body(r).contains("HAS_BREAK"), "应显式标注断点，不得输出完整：" + body(r));
    }

    // ------------------------------------------------------------ 夹具

    private static AuditService service() {
        // 本类只走校验/分发路径，不触发 DAO；可见范围以 lambda 注入。
        return new AuditService(null, null, null, null, null, null, null, null, null, null, null,
                uid -> Collections.singletonList(1), null, () -> 1, AuditService.endOfTradeDay());
    }

    private static HttpResult dispatch(Path webRoot, String method, String modulePath, String payload,
                                       String query) {
        Router router = new Router();
        AuditTraceRoutes.register(router, service());
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-T067", OPERATOR, "auditor", 1L, Clocks.now().plusMinutes(30)));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T067");
        if (payload != null) {
            headers.put("Content-Type", "application/json");
        }
        return dispatcher.dispatch(new RequestContext(method, ApiRoutes.full(modulePath), query, headers,
                payload, "HGT067", "127.0.0.1"));
    }

    private static String body(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private static int code(HttpResult result) {
        return JsonParser.parseString(body(result)).getAsJsonObject().get("code").getAsInt();
    }
}
