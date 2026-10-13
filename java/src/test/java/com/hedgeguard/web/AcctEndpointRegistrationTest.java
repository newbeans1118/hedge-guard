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
 * {@code T-077} ACCT 首批注册面（4 对）+ {@code T-078} 再平衡面（2 对）的注册与可达性
 * （{@code BootstrapAssemblyTest} 抽查同法，服务入参 {@code null}——装配期只注册，不驱动业务；
 * T-109 端点注册测试同形态）。
 */
class AcctEndpointRegistrationTest {

    private Router router;

    @BeforeEach
    void setUp() {
        router = new Router();
        AcctRoutes.register(router, null);
    }

    @Test
    @DisplayName("注册面恰 6 对：hedge-relation/effectiveness/rebalance 各 GET|POST")
    void registersExactlyFourPairs() {
        List<String> routes = new ArrayList<>(router.routes());
        Collections.sort(routes);
        assertEquals(List.of(
                "GET /api/v1/acct/effectiveness",
                "GET /api/v1/acct/hedge-relation",
                "GET /api/v1/acct/rebalance",
                "POST /api/v1/acct/effectiveness",
                "POST /api/v1/acct/hedge-relation",
                "POST /api/v1/acct/rebalance"), routes,
                "ACCT 注册面 = 6 对（T-077 的 4 对 + T-078 再平衡 2 对；方法注册表键 4→6）");
    }

    @Test
    @DisplayName("路径逐字一致：handler PATH 常量 == ApiRoutes ACCT 路径级声明成员")
    void handlerPathsMatchDeclaration() {
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/hedge-relation"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/effectiveness"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/rebalance"));
        assertEquals("/api/v1/acct/hedge-relation",
                com.hedgeguard.web.handler.acct.HedgeRelationHandler.PATH);
        assertEquals("/api/v1/acct/effectiveness",
                com.hedgeguard.web.handler.acct.EffectivenessHandler.PATH);
        assertEquals("/api/v1/acct/rebalance",
                com.hedgeguard.web.handler.acct.RebalanceHandler.PATH);
        // 方法注册表键存在且恰含 6 对（T-077 建 4 + T-078 增 2）
        assertEquals(6, ApiRoutes.byModuleRoutes().get("ACCT").size(),
                "BY_MODULE_ROUTES ACCT 键 = 6 对");
    }

    @Test
    @DisplayName("可达性：6 对均命中（非 404）；未注册方法 DELETE 不命中")
    void routesAreReachable() {
        for (String[] pair : new String[][]{
                {"GET", "/api/v1/acct/hedge-relation"},
                {"POST", "/api/v1/acct/hedge-relation"},
                {"GET", "/api/v1/acct/effectiveness"},
                {"POST", "/api/v1/acct/effectiveness"},
                {"GET", "/api/v1/acct/rebalance"},
                {"POST", "/api/v1/acct/rebalance"}}) {
            assertNotNull(router.match(pair[0], pair[1]),
                    pair[0] + " " + pair[1] + " 应已注册");
        }
        assertNull(router.match("DELETE", "/api/v1/acct/rebalance"),
                "未注册方法不得命中");
        assertNull(router.match("GET", "/api/v1/acct/voucher"),
                "未交付路径（T-079 承接）不得命中");
    }

    private static RequestContext req(String method, String path) {
        return new RequestContext(method, path, null,
                java.util.Collections.singletonMap("X-HG-Session", "S-1"), "",
                "HGTEST", "127.0.0.1");
    }

}
