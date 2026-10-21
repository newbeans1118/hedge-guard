package com.hedgeguard.web.handler.fund;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.FundService;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-202}：划拨端点 {@code POST /api/v1/fund/transfer} 幂等承载用例
 * （{@code FR-FUND-10}；{@code R1-003} 收口·方案 A；{@code DATA_MODEL} v1.3.9）。
 *
 * <p>真实 DAO + InMemory 引擎 + 生产装配 {@code RequestDispatcher} 进程内分发，断言：
 * <ol>
 *   <li><b>Idempotency-Key 头兜底</b>（{@code FundApplyHandler} 先例形态）：请求体不带
 *       {@code idempotencyKey}、经 {@code Idempotency-Key} 头携带时成功落库
 *       （缺头兜底 ⇒ 422/PARAM_INVALID，变异 M2 击杀面）；</li>
 *   <li><b>同键重放返回首单</b>：同一 {@code Idempotency-Key} 两次 POST，
 *       第二次返回首单单号且 {@code t_transfer_apply} 仍 1 行（不产生第二笔划拨）。</li>
 * </ol>
 */
class FundTransferHandlerIdempotencyTest {

    private static final int SUB_A = 11;
    private static final int SUB_B = 12;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private static final String PATH = "/api/v1/fund/transfer";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private RequestDispatcher dispatcher;
    private SessionStore sessionStore;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : List.of(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m2_fund.sql")) {
            engine.executeScript(new String(
                    Files.readAllBytes(repoRoot().resolve(rel)), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB_A, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, SUB_B, "SA-B", "商品子账户B");
        M1E2eSeed.user(engine, OPERATOR, "op1", "操作人1", "NP-OP1");
        M1E2eSeed.perm(engine, OPERATOR, SUB_A, "LIMITED", OPERATOR);
        M1E2eSeed.perm(engine, OPERATOR, SUB_B, "LIMITED", OPERATOR);

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        FundService fundService = new FundService(tx, uid -> List.of(SUB_A, SUB_B),
                uid -> false, key -> null,
                new com.hedgeguard.dao.impl.DmFundLedgerDao(tx),
                new com.hedgeguard.dao.impl.DmFundFlowDao(tx),
                new com.hedgeguard.dao.impl.DmDepositWithdrawApplyDao(tx),
                new com.hedgeguard.dao.impl.DmTransferApplyDao(tx),
                new com.hedgeguard.dao.impl.DmCapitalReceiptDao(tx),
                new com.hedgeguard.dao.impl.DmFundReviewRecordDao(tx),
                new com.hedgeguard.dao.impl.DmFundQuotaDao(tx),
                new com.hedgeguard.dao.impl.DmFundQuotaAlertDao(tx),
                new com.hedgeguard.dao.impl.DmFundReconcileDao(tx));

        // 划出方台账（加总对账口径：无未闭环差异）
        FundService.LedgerInitRequest ledger = new FundService.LedgerInitRequest();
        ledger.subAccountId = SUB_A;
        ledger.ledgerDate = DAY.toString();
        ledger.beginBalance = "1000";
        ledger.reviewerId = OPERATOR; // 双人复核哨兵：操作人与复核人须不同
        M1E2eSeed.user(engine, 101L, "op2", "复核人1", "NP-OP2");
        ledger.reviewerId = 101L;
        fundService.initLedger(ledger, OPERATOR);

        Router router = new Router();
        FundRoutes.register(router, fundService);
        sessionStore = new SessionStore.InMemory();
        sessionStore.save(new Session("S-OP", OPERATOR, "op1", 1L, Clocks.now().plusMinutes(30)));
        dispatcher = new RequestDispatcher(router, new StaticHandler(repoRoot()),
                new SessionAuthFilter(sessionStore, "HGSESSION"));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("T-202：请求体缺键时取 Idempotency-Key 头兜底；同键重放返回首单且不产生第二笔")
    void headerFallbackAndReplayReturnsFirstApply() {
        String body = "{\"fromSubAccountId\":" + SUB_A + ",\"toSubAccountId\":" + SUB_B
                + ",\"amount\":\"200\",\"reason\":\"幂等承载\"}";

        HttpResult first = post(PATH, body, "IDEM-H1", "S-OP");
        assertEquals(200, first.status, "首笔须成功（头兜底承载）：" + new String(first.body, StandardCharsets.UTF_8));
        String firstBody = new String(first.body, StandardCharsets.UTF_8);
        assertTrue(firstBody.contains("\"applyNo\":\"TF20261006"),
                "首笔须落库并返回单号：" + firstBody);
        assertEquals(1, engine.table("t_transfer_apply").rows.size(), "首笔后恰 1 行");

        HttpResult replay = post(PATH, body, "IDEM-H1", "S-OP");
        assertEquals(200, replay.status, "同键重放须 200（返回首单）：" + new String(replay.body, StandardCharsets.UTF_8));
        assertEquals(firstBody, new String(replay.body, StandardCharsets.UTF_8),
                "重放须返回首单（同 id/单号/状态）");
        assertEquals(1, engine.table("t_transfer_apply").rows.size(),
                "同键重放不得产生第二笔划拨（t_transfer_apply 仍 1 行）");
    }

    @Test
    @DisplayName("T-202：请求体与头均缺幂等标识 ⇒ 400/PARAM_INVALID（FR-FUND-10）")
    void missingKeyEverywhereRejected() {
        String body = "{\"fromSubAccountId\":" + SUB_A + ",\"toSubAccountId\":" + SUB_B
                + ",\"amount\":\"200\",\"reason\":\"缺键\"}";
        HttpResult r = post(PATH, body, null, "S-OP");
        assertEquals(400, r.status, "缺键须 4xx：实际=" + r.status);
        String resp = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(resp.contains("10001"), "业务码须 PARAM_INVALID(10001)：" + resp);
        assertEquals(0, engine.table("t_transfer_apply").rows.size(), "缺键不得落库");
    }

    private HttpResult post(String path, String jsonBody, String idemHeader, String session) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + session);
        if (idemHeader != null) {
            headers.put("Idempotency-Key", idemHeader);
        }
        return dispatcher.dispatch(new RequestContext("POST", path, null, headers,
                jsonBody, "HG-T202", "127.0.0.1"));
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
