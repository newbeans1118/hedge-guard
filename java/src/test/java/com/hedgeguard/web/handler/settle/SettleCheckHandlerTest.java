package com.hedgeguard.web.handler.settle;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.service.FakeTx;
import com.hedgeguard.service.SettleCheckFakes;
import com.hedgeguard.service.SettleCheckService;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-048} 核对/签署 REST 面单测（handler 级；{@code FR-SETTLE-13/14/17}、{@code FR-AUDIT-13}）。
 *
 * <p>验证 POST 生成/签署、GET 只读查询/CSV 导出、代签拒绝经 REST 面呈现（错误码透传）。
 */
class SettleCheckHandlerTest {

    private SettleCheckFakes.CheckTasks tasks;
    private SettleCheckFakes.CheckSignatures signatures;
    private SettleCheckHandler handler;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(LocalDate.of(2026, 10, 5).atStartOfDay(Clocks.ZONE).toInstant(),
                Clocks.ZONE));
        tasks = new SettleCheckFakes.CheckTasks();
        signatures = new SettleCheckFakes.CheckSignatures();
        SettleCheckFakes.Attempts attempts = new SettleCheckFakes.Attempts();
        Map<Long, List<String>> roles = Map.of(
                11L, Arrays.asList("FIN_RISK"),
                12L, Arrays.asList("TRADER"),
                13L, Arrays.asList("SETTLER"),
                15L, Arrays.asList("AUDITOR"));
        SettleCheckService.RoleLookup rl = u -> roles.getOrDefault(u, Arrays.asList());
        SettleCheckService.ConfigReader cr = k -> "settle.sign.deadline_days".equals(k) ? "30"
                : ("settle.sign.roles".equals(k) ? "RISK,TRADE,FINANCE" : null);
        SettleCheckService.SnapshotProvider sp = (t, p, s) -> "H1";
        SettleCheckService service = new SettleCheckService(FakeTx.newTemplate(),
                u -> Arrays.asList(0, 101), rl, u -> false, cr, sp, tasks, signatures, attempts);
        handler = new SettleCheckHandler(SettleCheckService.CHECK_MONTHLY, service);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    private RequestContext ctx(String method, String path, String query, String body, long userId) {
        RequestContext c = new RequestContext(method, path, query, Collections.emptyMap(),
                body, "tr-1", "10.0.0.1");
        c.bindSession(new Session("S-1", userId, "u", 1L, Clocks.now().plusMinutes(30)));
        return c;
    }

    private Map<?, ?> data(HttpResult r) {
        Map<?, ?> env = JsonCodec.fromJson(new String(r.body, StandardCharsets.UTF_8), Map.class);
        return (Map<?, ?>) env.get("data");
    }

    @Test
    @DisplayName("POST 生成 → 三方各自签署 → 任务 COMPLETED（REST 面）")
    void postGenerateAndSign() {
        HttpResult gen = handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY, null,
                "{\"action\":\"GENERATE\",\"checkType\":\"MONTHLY\",\"periodKey\":\"2026-09\","
                        + "\"subAccountId\":101}", 11L));
        assertEquals(200, gen.status);
        assertEquals("PENDING_SIGN", data(gen).get("status"));

        String sign = "{\"action\":\"SIGN\",\"checkType\":\"MONTHLY\",\"periodKey\":\"2026-09\","
                + "\"subAccountId\":101,\"signRole\":\"%s\"}";
        assertEquals("PARTIAL_SIGNED", data(handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY,
                null, String.format(sign, "RISK"), 11L))).get("status"));
        assertEquals("PARTIAL_SIGNED", data(handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY,
                null, String.format(sign, "TRADE"), 12L))).get("status"));
        assertEquals("COMPLETED", data(handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY,
                null, String.format(sign, "FINANCE"), 13L))).get("status"));
    }

    @Test
    @DisplayName("GET 只读查询返回任务与签署；审计席（跨账户只读）可见")
    void getQueryAndAuditRead() {
        handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY, null,
                "{\"action\":\"GENERATE\",\"checkType\":\"MONTHLY\",\"periodKey\":\"2026-09\","
                        + "\"subAccountId\":101}", 11L));
        handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY, null,
                "{\"action\":\"SIGN\",\"checkType\":\"MONTHLY\",\"periodKey\":\"2026-09\","
                        + "\"subAccountId\":101,\"signRole\":\"RISK\"}", 11L));
        HttpResult got = handler.handle(ctx("GET", SettleCheckHandler.PATH_MONTHLY,
                "checkType=MONTHLY&periodKey=2026-09", null, 15L));
        assertEquals(200, got.status);
        Map<?, ?> d = data(got);
        assertEquals(1.0, d.get("total"));
        List<?> items = (List<?>) d.get("items");
        assertEquals(1, items.size());
        assertNotNull(((Map<?, ?>) items.get(0)).get("signatures"));
    }

    @Test
    @DisplayName("GET format=csv 导出核对记录与签署记录（含 IP）")
    void getExportCsv() {
        handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY, null,
                "{\"action\":\"GENERATE\",\"checkType\":\"MONTHLY\",\"periodKey\":\"2026-09\","
                        + "\"subAccountId\":101}", 11L));
        handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY, null,
                "{\"action\":\"SIGN\",\"checkType\":\"MONTHLY\",\"periodKey\":\"2026-09\","
                        + "\"subAccountId\":101,\"signRole\":\"RISK\"}", 11L));
        HttpResult csv = handler.handle(ctx("GET", SettleCheckHandler.PATH_MONTHLY,
                "checkType=MONTHLY&periodKey=2026-09&format=csv", null, 15L));
        assertEquals(200, csv.status);
        String text = new String(csv.body, StandardCharsets.UTF_8);
        assertTrue(text.contains("signerId"));
        assertTrue(text.contains("RISK"));
        assertTrue(text.contains("10.0.0.1"));
    }

    @Test
    @DisplayName("REST 面代签拒绝透传 10003（FR-SETTLE-17 验收 3）")
    void postProxySignRejected() {
        handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY, null,
                "{\"action\":\"GENERATE\",\"checkType\":\"MONTHLY\",\"periodKey\":\"2026-09\","
                        + "\"subAccountId\":101}", 11L));
        BizException e = assertThrows(BizException.class,
                () -> handler.handle(ctx("POST", SettleCheckHandler.PATH_MONTHLY, null,
                        "{\"action\":\"SIGN\",\"checkType\":\"MONTHLY\",\"periodKey\":\"2026-09\","
                                + "\"subAccountId\":101,\"signRole\":\"RISK\"}", 12L)));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode());
    }
}
