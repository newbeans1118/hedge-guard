package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.service.SubAccountVisibility;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-050} REST 面：{@code GET /api/v1/risk/mirror-status} 的注册、可达与子账户隔离。
 *
 * <p>覆盖 {@code FR-RISK-12}（子账户/汇总视图）、{@code FR-RISK-19}（只读、按可见范围）。
 */
class MirrorStatusEndpointTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 10, 0);

    private InMemoryTradingBridge bridge;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        bridge = new InMemoryTradingBridge();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    private void seedTwoSubAccounts() {
        bridge.setMirror(TestSnapshots.snapshot(NOW.minusSeconds(1).atZone(ZONE).toInstant().toString(), 9,
                TestSnapshots.list(
                        TestSnapshots.position(1, "rb2601", "10", "8", "100", "1000"),
                        TestSnapshots.position(2, "cu2601", "5", "5", "50", "500")),
                TestSnapshots.funds(
                        TestSnapshots.fund(1, "10000", "8000", "1000", "0.10"),
                        TestSnapshots.fund(2, "5000", "4000", "500", "0.10"))));
    }

    private static RequestContext request(long userId) {
        RequestContext ctx = new RequestContext("GET", MirrorStatusHandler.PATH, null,
                Collections.emptyMap(), "", "HGTEST", "127.0.0.1");
        ctx.bindSession(new Session("S-1", userId, "tester", 1L, Clocks.now().plusMinutes(30)));
        return ctx;
    }

    private MirrorStatusService service(SubAccountVisibility visibility) {
        IntradayRiskMonitor monitor = new IntradayRiskMonitor(bridge, RiskThresholds.defaults(),
                new AbnormalOrderTracker(Clocks.clock()), new NoopRiskMetricSink(), null, 0L);
        return new MirrorStatusService(monitor, visibility);
    }

    @Test
    @DisplayName("路径已在 ApiRoutes RISK 清单；RiskRoutes 注册后 GET 命中该 handler")
    void routeIsRegisteredAndReachable() {
        assertTrue(ApiRoutes.all().contains(MirrorStatusHandler.PATH),
                "路径须 ∈ ApiRoutes 冻结清单（不得臆造）：" + MirrorStatusHandler.PATH);
        Router router = new Router();
        RiskRoutes.register(router, new IntradayRiskMonitor(bridge, RiskThresholds.defaults(),
                new AbnormalOrderTracker(Clocks.clock()), new NoopRiskMetricSink(), null, 0L), null);
        Router.RouteMatch m = router.match("GET", MirrorStatusHandler.PATH);
        assertNotNull(m, "GET " + MirrorStatusHandler.PATH + " 应可匹配（可达）");
        assertEquals(MirrorStatusHandler.PATH, m.pattern());
        assertTrue(m.handler() instanceof MirrorStatusHandler);
    }

    @Test
    @DisplayName("无隔离（visibility=null）：返回全部子账户与汇总")
    void returnsAllWithoutVisibility() {
        seedTwoSubAccounts();
        HttpResult r = new MirrorStatusHandler(service(null)).handle(request(7L));
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"subAccounts\""), body);
        assertTrue(body.contains("\"aggregate\""), body);
    }

    @Test
    @DisplayName("子账户隔离：仅返回可见子账户（NFR-SEC-7 只读面）")
    void filtersByVisibility() {
        seedTwoSubAccounts();
        SubAccountVisibility onlyOne = userId -> Collections.singletonList(1);
        MirrorStatusView view = service(onlyOne).current(7L);
        assertEquals(1, view.subAccounts.size());
        assertEquals(1, view.subAccounts.get(0).subAccountId);
        // 汇总恒返回（FR-RISK-12 验收 2），但明细被隔离
        assertNotNull(view.aggregate);
    }

    @Test
    @DisplayName("跨账户只读（哨兵 0）：不过滤，返回全部")
    void crossReadonlySentinelReturnsAll() {
        seedTwoSubAccounts();
        SubAccountVisibility cross = userId -> Collections.singletonList(0);
        MirrorStatusView view = service(cross).current(7L);
        assertEquals(2, view.subAccounts.size());
    }

    @Test
    @DisplayName("空可见集：fail-closed，无任何可见子账户")
    void emptyVisibilityFailsClosed() {
        seedTwoSubAccounts();
        SubAccountVisibility none = userId -> Collections.emptyList();
        MirrorStatusView view = service(none).current(7L);
        assertTrue(view.subAccounts.isEmpty(), "空可见集应返回空明细（不退化为全放行）");
        assertTrue(view.alerts.isEmpty());
    }
}
