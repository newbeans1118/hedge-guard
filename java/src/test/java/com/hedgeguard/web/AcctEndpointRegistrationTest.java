package com.hedgeguard.web;

import com.hedgeguard.web.handler.acct.AcctRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-077} ACCT 首批注册面（4 对）的注册与可达性（{@code BootstrapAssemblyTest} 抽查同法，
 * 服务入参 {@code null}——装配期只注册，不驱动业务；T-109 端点注册测试同形态）。
 */
class AcctEndpointRegistrationTest {

    private Router router;

    @BeforeEach
    void setUp() {
        router = new Router();
        AcctRoutes.register(router, null);
    }

    @Test
    @DisplayName("注册面恰 4 对：GET|POST /acct/hedge-relation、GET|POST /acct/effectiveness")
    void registersExactlyFourPairs() {
        List<String> routes = new ArrayList<>(router.routes());
        Collections.sort(routes);
        assertEquals(List.of(
                "GET /api/v1/acct/effectiveness",
                "GET /api/v1/acct/hedge-relation",
                "POST /api/v1/acct/effectiveness",
                "POST /api/v1/acct/hedge-relation"), routes,
                "ACCT 首批注册面 = 4 对（方法注册表键新增；路径逐字取自 ApiRoutes ACCT 清单）");
    }

    @Test
    @DisplayName("路径逐字一致：handler PATH 常量 == ApiRoutes ACCT 路径级声明成员")
    void handlerPathsMatchDeclaration() {
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/hedge-relation"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/effectiveness"));
        assertEquals("/api/v1/acct/hedge-relation",
                com.hedgeguard.web.handler.acct.HedgeRelationHandler.PATH);
        assertEquals("/api/v1/acct/effectiveness",
                com.hedgeguard.web.handler.acct.EffectivenessHandler.PATH);
        // 方法注册表键存在且恰含 4 对（ACCT 键从零建）
        assertEquals(4, ApiRoutes.byModuleRoutes().get("ACCT").size(),
                "BY_MODULE_ROUTES ACCT 键 = 4 对");
    }

    @Test
    @DisplayName("可达性：4 对均命中（非 404）；未注册方法 DELETE 不命中")
    void routesAreReachable() {
        for (String[] pair : new String[][]{
                {"GET", "/api/v1/acct/hedge-relation"},
                {"POST", "/api/v1/acct/hedge-relation"},
                {"GET", "/api/v1/acct/effectiveness"},
                {"POST", "/api/v1/acct/effectiveness"}}) {
            assertNotNull(router.match(pair[0], pair[1]),
                    pair[0] + " " + pair[1] + " 应已注册");
        }
        assertNull(router.match("DELETE", "/api/v1/acct/hedge-relation"),
                "未注册方法不得命中");
        assertNull(router.match("GET", "/api/v1/acct/rebalance"),
                "未交付路径（T-078 承接）不得命中");
    }

    private static RequestContext req(String method, String path) {
        return new RequestContext(method, path, null,
                java.util.Collections.singletonMap("X-HG-Session", "S-1"), "",
                "HGTEST", "127.0.0.1");
    }

}
