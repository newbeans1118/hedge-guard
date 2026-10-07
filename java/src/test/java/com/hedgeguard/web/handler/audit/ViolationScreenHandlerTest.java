package com.hedgeguard.web.handler.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.entity.TradePreTradeReject;
import com.hedgeguard.dao.impl.DmTradeOrderDao;
import com.hedgeguard.dao.impl.DmTradePreTradeRejectDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.impl.DmViolationScreenItemDao;
import com.hedgeguard.dao.impl.DmViolationScreenRunDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.ViolationScreenService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-068} 违规事项筛查 REST 端点层测试（{@code FR-AUDIT-4}）。
 *
 * <p>走<b>真实</b> {@link Router} + {@link RequestDispatcher} + {@link ViolationScreenHandler}
 * + 真实 DAO（内存假 JDBC 引擎）；验证路径注册、缺参 400、POST 执行筛查、GET 筛选查询。
 */
class ViolationScreenHandlerTest {

    private static final int SUB = 11;
    private static final long PRODUCT = 7001L;
    private static final long EXP = 2001L;
    private static final long PLAN = 1001L;
    private static final long TRADE_PLAN = 3001L;
    private static final long OPERATOR = 42L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private InMemoryDmEngine engine;
    private DmTradeOrderDao orderDao;
    private DmTradePreTradeRejectDao rejectDao;
    private DmTradeReasonDao reasonDao;
    private DmViolationScreenRunDao runDao;
    private DmViolationScreenItemDao itemDao;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            Path file = repoRoot().resolve(rel);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.exposure(engine, EXP, SUB, PRODUCT, "100", DAY);
        M2E2eSeed.hedgePlan(engine, PLAN, SUB, PRODUCT, "HP202610060001", "100", DAY, DAY.plusMonths(3));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN, SUB, PLAN, EXP, PRODUCT, "TP-1", "cu2612", "10",
                DAY.plusDays(30));
        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        orderDao = new DmTradeOrderDao(tx);
        rejectDao = new DmTradePreTradeRejectDao(tx);
        reasonDao = new DmTradeReasonDao(tx);
        runDao = new DmViolationScreenRunDao(tx);
        itemDao = new DmViolationScreenItemDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("端点层：/audit/violation-screen 的 GET/POST 均已注册并由 ViolationScreenHandler 处理")
    void routesRegistered() {
        Router router = new Router();
        ViolationScreenRoutes.register(router, service());
        for (String method : new String[]{"GET", "POST"}) {
            Router.RouteMatch m = router.match(method, ViolationScreenHandler.PATH);
            assertNotNull(m, "端点未注册：" + method);
            assertTrue(m.handler() instanceof ViolationScreenHandler,
                    "应由 ViolationScreenHandler 处理：" + method);
        }
    }

    @Test
    @DisplayName("端点层：POST 缺 dateFrom/dateTo ⇒ 400/10001")
    void postMissingRange(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "POST", "{}", null);
        assertEquals(400, r.status);
        assertEquals(10001, code(r));
    }

    @Test
    @DisplayName("端点层：POST 执行筛查 ⇒ 200/code=0，命中超规模")
    void postRunsScreen(@TempDir Path webRoot) {
        seedOverScale();
        String payload = "{\"dateFrom\":\"2026-10-06\",\"dateTo\":\"2026-10-06\","
                + "\"dimensions\":[\"OVER_SCALE\"],\"screenNo\":\"VS-REST-1\"}";
        HttpResult r = dispatch(webRoot, "POST", payload, null);
        assertEquals(200, r.status);
        JsonObject o = JsonParser.parseString(body(r)).getAsJsonObject();
        assertEquals(0, o.get("code").getAsInt());
        JsonObject data = o.getAsJsonObject("data");
        assertEquals(1, data.get("hitCount").getAsInt());
        assertEquals("OVER_SCALE",
                data.getAsJsonArray("items").get(0).getAsJsonObject().get("violationType").getAsString());
        assertEquals("发现 1 条违规",
                data.getAsJsonObject("run").get("conclusion").getAsString());
    }

    @Test
    @DisplayName("端点层：GET 按违规类型筛选 ⇒ 分页命中清单")
    void getQueryItems(@TempDir Path webRoot) {
        seedOverScale();
        dispatch(webRoot, "POST",
                "{\"dateFrom\":\"2026-10-06\",\"dateTo\":\"2026-10-06\","
                        + "\"dimensions\":[\"OVER_SCALE\"]}", null);
        HttpResult r = dispatch(webRoot, "GET", null, "violationType=OVER_SCALE");
        assertEquals(200, r.status);
        JsonObject data = JsonParser.parseString(body(r)).getAsJsonObject().getAsJsonObject("data");
        assertEquals(1, data.get("total").getAsInt());
        assertEquals("OVER_SCALE",
                data.getAsJsonArray("items").get(0).getAsJsonObject().get("violationType").getAsString());
    }

    // ------------------------------------------------------------ 夹具

    private void seedOverScale() {
        TradeOrder o = new TradeOrder();
        o.subAccountId = SUB;
        o.clientOrderRef = "HP-REST-1";
        o.tradePlanId = TRADE_PLAN;
        o.hedgePlanId = PLAN;
        o.exposureId = EXP;
        o.productId = PRODUCT;
        o.instrumentId = "cu2612";
        o.instrumentType = "FUTURES";
        o.direction = "BUY";
        o.offsetFlag = "OPEN";
        o.priceType = "LIMIT";
        o.limitPrice = new BigDecimal("70000");
        o.volume = 1;
        o.operatorId = OPERATOR;
        o.status = "SUBMITTED";
        o.submitTime = DAY.atTime(10, 0);
        o.clientTsMs = 0L;
        assertEquals(1, orderDao.insert(o, OPERATOR));

        TradePreTradeReject rej = new TradePreTradeReject();
        rej.subAccountId = SUB;
        rej.clientOrderRef = "HP-REST-1";
        rej.orderDigest = "digest";
        rej.productId = PRODUCT;
        rej.instrumentId = "cu2612";
        rej.direction = "BUY";
        rej.volume = 1;
        rej.ruleId = "R-RISK-2";
        rej.rejectCode = "0x1002";
        rej.limitLayer = "SUB_PRODUCT";
        rej.currentValue = "110";
        rej.thresholdValue = "100";
        rej.basePriceSource = "NOT_APPLICABLE";
        rej.rejectReason = "pre-trade reject";
        rej.operatorId = OPERATOR;
        rej.rejectTsMs = 0L;
        rej.rejectTime = DAY.atStartOfDay();
        rej.tradeDate = DAY;
        rej.eventSeq = 1L;
        rej.reportedRetryCount = 0;
        assertEquals(1, rejectDao.insert(rej, OPERATOR));
    }

    private ViolationScreenService service() {
        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        return new ViolationScreenService(rejectDao, orderDao, reasonDao, runDao, itemDao,
                uid -> Collections.singletonList(0), tx);
    }

    private HttpResult dispatch(Path webRoot, String method, String payload, String query) {
        Router router = new Router();
        ViolationScreenRoutes.register(router, service());
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-T068", OPERATOR, "auditor", 1L, Clocks.now().plusMinutes(30)));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T068");
        if (payload != null) {
            headers.put("Content-Type", "application/json");
        }
        return dispatcher.dispatch(new RequestContext(method, ApiRoutes.full("/audit/violation-screen"),
                query, headers, payload, "HGT068", "127.0.0.1"));
    }

    private static String body(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private static int code(HttpResult result) {
        return JsonParser.parseString(body(result)).getAsJsonObject().get("code").getAsInt();
    }

    private static List<String> schemaScripts() {
        return Arrays.asList(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m1_product.sql",
                "docs/DEPLOY/schema/m1_spot.sql",
                "docs/DEPLOY/schema/m1_plan.sql",
                "docs/DEPLOY/schema/m2_trade.sql",
                "docs/DEPLOY/schema/m3_audit.sql");
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/DEPLOY/schema"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("无法定位仓库根（docs/DEPLOY/schema）");
        }
        return p;
    }
}
