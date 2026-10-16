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
 * {@code T-077} ACCT 首批注册面（4 对）+ {@code T-078} 再平衡面（2 对）+ {@code T-079}
 * 凭证/期间面（7 对）+ {@code T-080} 效果评估与双侧盈亏上报面（4 对）的注册与可达性
 * （{@code BootstrapAssemblyTest} 抽查同法，服务入参 {@code null}——装配期只注册，
 * 不驱动业务；T-109 端点注册测试同形态）。
 */
class AcctEndpointRegistrationTest {

    private Router router;

    @BeforeEach
    void setUp() {
        router = new Router();
        AcctRoutes.register(router, null);
    }

    @Test
    @DisplayName("注册面恰 17 对：hedge-relation/effectiveness/rebalance/voucher/period/effect/dual-pnl 面")
    void registersExactlyFourPairs() {
        List<String> routes = new ArrayList<>(router.routes());
        Collections.sort(routes);
        assertEquals(List.of(
                "GET /api/v1/acct/effect",
                "GET /api/v1/acct/effectiveness",
                "GET /api/v1/acct/hedge-relation",
                "GET /api/v1/acct/period",
                "GET /api/v1/acct/rebalance",
                "GET /api/v1/acct/report/dual-pnl",
                "GET /api/v1/acct/voucher",
                "POST /api/v1/acct/effect",
                "POST /api/v1/acct/effectiveness",
                "POST /api/v1/acct/hedge-relation",
                "POST /api/v1/acct/period",
                "POST /api/v1/acct/period/{id}/close",
                "POST /api/v1/acct/rebalance",
                "POST /api/v1/acct/report/dual-pnl",
                "POST /api/v1/acct/voucher",
                "POST /api/v1/acct/voucher/{id}/reverse",
                "POST /api/v1/acct/voucher/{id}/review"), routes,
                "ACCT 注册面 = 17 对（T-077 的 4 + T-078 的 2 + T-079 的 7 + T-080 的 4；"
                        + "方法注册表键 13→17）");
    }

    @Test
    @DisplayName("路径逐字一致：handler PATH 常量 == ApiRoutes ACCT 路径级声明成员")
    void handlerPathsMatchDeclaration() {
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/hedge-relation"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/effectiveness"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/rebalance"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/voucher"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/voucher/{id}/review"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/voucher/{id}/reverse"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/period"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/period/{id}/close"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/effect"));
        assertTrue(ApiRoutes.byModule().get("ACCT").contains("/acct/report/dual-pnl"));
        assertEquals("/api/v1/acct/hedge-relation",
                com.hedgeguard.web.handler.acct.HedgeRelationHandler.PATH);
        assertEquals("/api/v1/acct/effectiveness",
                com.hedgeguard.web.handler.acct.EffectivenessHandler.PATH);
        assertEquals("/api/v1/acct/rebalance",
                com.hedgeguard.web.handler.acct.RebalanceHandler.PATH);
        assertEquals("/api/v1/acct/voucher",
                com.hedgeguard.web.handler.acct.VoucherHandler.PATH);
        assertEquals("/api/v1/acct/voucher/{id}/review",
                com.hedgeguard.web.handler.acct.VoucherReviewActionHandler.PATH);
        assertEquals("/api/v1/acct/voucher/{id}/reverse",
                com.hedgeguard.web.handler.acct.VoucherReverseHandler.PATH);
        assertEquals("/api/v1/acct/period",
                com.hedgeguard.web.handler.acct.VoucherPeriodHandler.PATH);
        assertEquals("/api/v1/acct/period/{id}/close",
                com.hedgeguard.web.handler.acct.PeriodCloseHandler.PATH);
        assertEquals("/api/v1/acct/effect",
                com.hedgeguard.web.handler.acct.EffectHandler.PATH);
        assertEquals("/api/v1/acct/report/dual-pnl",
                com.hedgeguard.web.handler.acct.DualPnlReportHandler.PATH);
        // 方法注册表键存在且恰含 17 对（T-077 建 4 + T-078 增 2 + T-079 增 7 + T-080 增 4）
        assertEquals(17, ApiRoutes.byModuleRoutes().get("ACCT").size(),
                "BY_MODULE_ROUTES ACCT 键 = 17 对");
    }

    @Test
    @DisplayName("可达性：17 对均命中（非 404）；未注册方法 DELETE 不命中")
    void routesAreReachable() {
        for (String[] pair : new String[][]{
                {"GET", "/api/v1/acct/hedge-relation"},
                {"POST", "/api/v1/acct/hedge-relation"},
                {"GET", "/api/v1/acct/effectiveness"},
                {"POST", "/api/v1/acct/effectiveness"},
                {"GET", "/api/v1/acct/rebalance"},
                {"POST", "/api/v1/acct/rebalance"},
                {"GET", "/api/v1/acct/voucher"},
                {"POST", "/api/v1/acct/voucher"},
                {"POST", "/api/v1/acct/voucher/1/review"},
                {"POST", "/api/v1/acct/voucher/1/reverse"},
                {"GET", "/api/v1/acct/period"},
                {"POST", "/api/v1/acct/period"},
                {"POST", "/api/v1/acct/period/1/close"},
                {"GET", "/api/v1/acct/effect"},
                {"POST", "/api/v1/acct/effect"},
                {"GET", "/api/v1/acct/report/dual-pnl"},
                {"POST", "/api/v1/acct/report/dual-pnl"}}) {
            assertNotNull(router.match(pair[0], pair[1]),
                    pair[0] + " " + pair[1] + " 应已注册");
        }
        assertNull(router.match("DELETE", "/api/v1/acct/rebalance"),
                "未注册方法不得命中");
        assertNull(router.match("GET", "/api/v1/acct/selfcheck"),
                "未交付路径（FR-ACCT-14 后续单）不得命中");
        assertNull(router.match("GET", "/api/v1/acct/export"),
                "未交付路径（FR-ACCT-9 后续单）不得命中");
    }

    private static RequestContext req(String method, String path) {
        return new RequestContext(method, path, null,
                java.util.Collections.singletonMap("X-HG-Session", "S-1"), "",
                "HGTEST", "127.0.0.1");
    }

}
