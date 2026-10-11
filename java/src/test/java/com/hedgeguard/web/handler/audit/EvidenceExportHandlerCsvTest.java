package com.hedgeguard.web.handler.audit;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.EvidenceExportDao;
import com.hedgeguard.dao.impl.DmAuditLogChecksumDao;
import com.hedgeguard.dao.impl.DmAuditLogDao;
import com.hedgeguard.dao.impl.DmEvidenceExportDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmOperationLogDao;
import com.hedgeguard.dao.impl.DmPlanApprovalDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmTradeOrderDao;
import com.hedgeguard.dao.impl.DmTradeOrderReportDao;
import com.hedgeguard.dao.impl.DmTradePlanDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.M3AuditTraceSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuditService;
import com.hedgeguard.service.AuditLogService;
import com.hedgeguard.service.EvidenceExportService;
import com.hedgeguard.service.SubAccountVisibility;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import com.hedgeguard.web.handler.audit.EvidenceExportHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-193}：证据链导出 POST <b>CSV 直返</b>端点用例（修复 {@code TE-T069-003}）。
 *
 * <p>与 {@code /spot/price/export}、{@code /product/export} 先例端点用例同构——真实 DAO +
 * InMemory 引擎 + 生产装配 {@code RequestDispatcher} 进程内分发，断言：
 * <ol>
 *   <li>{@code Content-Type} = {@code text/csv; charset=UTF-8}（先例同值）；</li>
 *   <li>{@code Content-Disposition} = {@code attachment; filename="evidence-<exportNo>.csv"}；</li>
 *   <li>首行为 CSV 表头、数据行与表头逐列等数；</li>
 *   <li>exportNo 仍落库（GET 记录面可见）；</li>
 *   <li>GET 面不受影响（仍 JSON）。</li>
 * </ol>
 */
class EvidenceExportHandlerCsvTest {

    private static final int SUB = 11;
    private static final long PRODUCT = 7001L;
    private static final long EXP = 2001L;
    private static final long PLAN = 1001L;
    private static final long TRADE_PLAN = 3001L;
    private static final long ORDER = 4001L;
    private static final long SETTLE = 5001L;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime TS = DAY.atTime(10, 0);

    private static final String PATH = "/api/v1/audit/evidence/export";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private EvidenceExportDao exportDao;
    private RequestDispatcher dispatcher;
    private SessionStore sessionStore;
    private Router router;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            engine.executeScript(new String(
                    Files.readAllBytes(repoRoot().resolve(rel)), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.exposure(engine, EXP, SUB, PRODUCT, "100", DAY);
        M2E2eSeed.hedgePlan(engine, PLAN, SUB, PRODUCT, "HP202610060001", "100", DAY, DAY.plusMonths(3));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN, SUB, PLAN, EXP, PRODUCT, "TP-1", "cu2612", "10",
                DAY.plusDays(30));
        M3AuditTraceSeed.hedgePlanExposure(engine, 6001L, SUB, PLAN, EXP, "100", TS);
        M3AuditTraceSeed.planApproval(engine, 6101L, SUB, PLAN, 1, TS);
        M3AuditTraceSeed.tradeOrder(engine, ORDER, SUB, "HP202610060001-C1", 9001L, TRADE_PLAN, PLAN,
                EXP, PRODUCT, "cu2612", "ALL_FILLED", TS);
        M3AuditTraceSeed.tradeOrderReport(engine, 6201L, SUB, "SYS-1", ORDER, "cu2612",
                "ALL_TRADED", 10, 10, TS);
        M3AuditTraceSeed.tradeFill(engine, 6301L, SUB, "TF-1", ORDER, TRADE_PLAN, PLAN, EXP,
                PRODUCT, "cu2612", "70000", 10, DAY, TS);
        M3AuditTraceSeed.settleCycleConfirmed(engine, SETTLE, SUB, "ST-1", DAY, TS);

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        exportDao = new DmEvidenceExportDao(tx);
        TradeReasonSeed.seed(tx);

        EvidenceExportService service = new EvidenceExportService(
                new AuditService(new DmExposureDao(tx), new DmHedgePlanExposureDao(tx),
                        new DmHedgePlanDao(tx), new DmPlanApprovalDao(tx), new DmTradePlanDao(tx),
                        new DmTradeOrderDao(tx), new DmTradeOrderReportDao(tx),
                        new com.hedgeguard.dao.impl.DmTradeFillDao(tx), new DmTradeReasonDao(tx),
                        new DmSettleCycleDao(tx), new DmOperationLogDao(tx),
                        crossReadonly(), voucherStub(), () -> 1, AuditService.endOfTradeDay()),
                new AuditLogService(new DmAuditLogDao(tx), new DmAuditLogChecksumDao(tx),
                        crossReadonly(), AuditLogService.TamperAlarm.NOOP, tx),
                exportDao, new DmTradeReasonDao(tx), dualPnlStub(), crossReadonly(), tx);

        router = new Router();
        EvidenceExportRoutes.register(router, service);
        sessionStore = new SessionStore.InMemory();
        sessionStore.save(new Session("S-T193", OPERATOR, "op1", 1L,
                Clocks.now().plusMinutes(30)));
        dispatcher = new RequestDispatcher(router, new StaticHandler(repoRoot()),
                new SessionAuthFilter(sessionStore, "HGSESSION"));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("POST 导出 ⇒ CSV 直返：Content-Type / Disposition / 表头 / 数据行（TE-T069-003）")
    void postReturnsCsvDirectly() {
        HttpResult r = call("POST", PATH, "{\"startPointType\":\"EXPOSURE\","
                + "\"startPointIds\":[\"" + EXP + "\"],"
                + "\"dateFrom\":\"2026-10-06\",\"dateTo\":\"2026-10-06\"}");

        assertEquals(200, r.status);
        assertEquals("text/csv; charset=UTF-8", r.contentType, "Content-Type 须为 CSV（先例同值）");
        assertEquals("attachment; filename=\"evidence-EV20261006120000000.csv\"",
                r.headers().get("Content-Disposition"),
                "Disposition 须含 exportNo 命名（Clocks 固定时钟 ⇒ exportNo 确定性）");
        String body = new String(r.body, StandardCharsets.UTF_8);
        String[] lines = body.split("\r\n");
        assertTrue(lines.length > 1, "CSV 须含表头行 + 数据行");
        assertTrue(lines[0].startsWith("section,start_type,start_id,"),
                "首行须为 CSV 表头：" + lines[0]);
        assertEquals("meta", lines[1].split(",", -1)[0], "首个数据行须为 meta 行");
        assertTrue(body.contains("node,"), "须含链节点行");
        assertTrue(body.contains("dual_pnl,"), "须含双侧盈亏行");
        assertTrue(body.contains(EvidenceExportService.MARK_COMPLETE), "须含完整性标注");

        // exportNo 仍落库（GET 记录面可见）
        assertNotNull(exportDao.findByExportNo("EV20261006120000000"), "导出记录须落库");
    }

    @Test
    @DisplayName("GET 记录面不受影响：仍 JSON 信封且新导出记录可查")
    void getRecordUnchanged() {
        call("POST", PATH, "{\"startPointType\":\"EXPOSURE\","
                + "\"startPointIds\":[\"" + EXP + "\"]}");
        HttpResult r = call("GET", PATH, "exportNo=EV20261006120000000");
        assertEquals(200, r.status);
        assertEquals("application/json; charset=UTF-8", r.contentType, "GET 面 JSON 形态不变");
        String body = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"exportNo\":\"EV20261006120000000\""), "记录须可查：" + body);
    }

    // ------------------------------------------------------------ 夹具

    private HttpResult call(String method, String path, String body) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T193");
        return dispatcher.dispatch(new RequestContext(method, path, null, headers,
                body == null ? "" : body, "HG-T193", "127.0.0.1"));
    }

    private static SubAccountVisibility crossReadonly() {
        return uid -> Collections.singletonList(
                com.hedgeguard.dao.SubAccountScope.ALL_ACCOUNT_SENTINEL);
    }

    private static EvidenceExportService.DualPnlProvider dualPnlStub() {
        return (subAccountId, from, to) -> {
            EvidenceExportService.DualPnl dp = new EvidenceExportService.DualPnl();
            dp.physicalPnl = new BigDecimal("800");
            dp.derivativePnl = new BigDecimal("1200");
            return dp;
        };
    }

    private static AuditService.VoucherLookup voucherStub() {
        return new AuditService.VoucherLookup() {
            private AuditService.VoucherRef ref() {
                AuditService.VoucherRef v = new AuditService.VoucherRef();
                v.id = 90001L;
                v.voucherNo = "V-1";
                v.voucherType = "CLOSE_PNL";
                v.subAccountId = SUB;
                v.hedgePlanId = PLAN;
                v.exposureId = EXP;
                v.settleCycleId = SETTLE;
                v.status = "POSTED";
                return v;
            }

            @Override
            public java.util.List<AuditService.VoucherRef> findByExposure(long exposureId) {
                return exposureId == EXP ? Collections.singletonList(ref()) : Collections.emptyList();
            }

            @Override
            public java.util.List<AuditService.VoucherRef> findByHedgePlan(long hedgePlanId) {
                return hedgePlanId == PLAN ? Collections.singletonList(ref()) : Collections.emptyList();
            }

            @Override
            public java.util.List<AuditService.VoucherRef> findBySettleCycle(long settleCycleId) {
                return settleCycleId == SETTLE ? Collections.singletonList(ref())
                        : Collections.emptyList();
            }

            @Override
            public AuditService.VoucherRef findByVoucher(String voucherIdOrNo) {
                return ("V-1".equals(voucherIdOrNo) || "90001".equals(voucherIdOrNo)) ? ref() : null;
            }
        };
    }

    /** 交易理由种子（沿用 {@code EvidenceExportServiceTest#seedReason} 同构，独立成静态夹具）。 */
    private static final class TradeReasonSeed {
        static void seed(TxTemplate tx) {
            com.hedgeguard.dao.entity.TradeReason reason = new com.hedgeguard.dao.entity.TradeReason();
            reason.subAccountId = SUB;
            reason.tradeOrderId = ORDER;
            reason.relatedType = "PLAN_EXECUTION";
            reason.relatedPlanId = PLAN;
            reason.relatedExposureId = EXP;
            reason.timeDesc = "PRICE_TRIGGERED";
            reason.reasonText = "套保执行";
            reason.forcedByFreqAlert = "N";
            reason.relatedObjectMissingFlag = "N";
            reason.reasonMissingFlag = "N";
            reason.isModified = "N";
            reason.operatorId = OPERATOR;
            reason.fillTime = TS;
            tx.inTransaction(connection -> new DmTradeReasonDao(tx).insert(reason, OPERATOR));
        }
    }

    private static java.util.List<String> schemaScripts() {
        return java.util.Arrays.asList(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m1_product.sql",
                "docs/DEPLOY/schema/m1_spot.sql",
                "docs/DEPLOY/schema/m1_plan.sql",
                "docs/DEPLOY/schema/m2_trade.sql",
                "docs/DEPLOY/schema/m2_settle.sql",
                "docs/DEPLOY/schema/m2_fund.sql",
                "docs/DEPLOY/schema/m3_risk.sql",
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
