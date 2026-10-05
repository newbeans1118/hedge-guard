package com.hedgeguard.web;

import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.impl.DmAuthorizationDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmLoginLogDao;
import com.hedgeguard.dao.impl.DmLoginSessionDao;
import com.hedgeguard.dao.impl.DmParamDispatchDao;
import com.hedgeguard.dao.impl.DmPasswordHistoryDao;
import com.hedgeguard.dao.impl.DmPermissionChangeLogDao;
import com.hedgeguard.dao.impl.DmPositionConflictDao;
import com.hedgeguard.dao.impl.DmPositionGroupDao;
import com.hedgeguard.dao.impl.DmRoleDao;
import com.hedgeguard.dao.impl.DmSettleAggDiffCandidateDao;
import com.hedgeguard.dao.impl.DmSettleAggregationCheckDao;
import com.hedgeguard.dao.impl.DmSettleCtpBatchDao;
import com.hedgeguard.dao.impl.DmSettleCtpFundDao;
import com.hedgeguard.dao.impl.DmSettleCtpPositionDao;
import com.hedgeguard.dao.impl.DmSettleCtpTradeDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmSettleFundDao;
import com.hedgeguard.dao.impl.DmSettlePnlDao;
import com.hedgeguard.dao.impl.DmSettlePositionDao;
import com.hedgeguard.dao.impl.DmSettleReconcileDifferenceDao;
import com.hedgeguard.dao.impl.DmSettleReconcileRecordDao;
import com.hedgeguard.dao.impl.DmSettleReportDao;
import com.hedgeguard.dao.impl.DmSettleStressTestDao;
import com.hedgeguard.dao.impl.DmTradeCommandFlowDao;
import com.hedgeguard.dao.impl.DmTradeFillDao;
import com.hedgeguard.dao.impl.DmTradeOrderDao;
import com.hedgeguard.dao.impl.DmTradeOrderReportDao;
import com.hedgeguard.dao.impl.DmTradePlanDao;
import com.hedgeguard.dao.impl.DmTradePositionDao;
import com.hedgeguard.dao.impl.DmTradePreTradeRejectDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.impl.DmUnauthorizedAttemptDao;
import com.hedgeguard.dao.impl.DmUserAccountDao;
import com.hedgeguard.dao.impl.DmUserRoleDao;
import com.hedgeguard.dao.impl.DmUserSubAccountPermDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.service.SettleService;
import com.hedgeguard.service.TradeService;
import com.hedgeguard.service.impl.ParamDispatchServiceImpl;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-178} <b>M2 装配级 e2e 五缺陷（001a/002/003/004/005）服务侧修复回归</b>。
 *
 * <p><b>层级</b>：与 {@code T-058} 同法——真实 {@code Dm*Dao} + {@link InMemoryDmEngine}
 * （按 SQL 语义求值：强制 NOT NULL / 主键唯一 / 外键 / 可求值 CHECK）装配真实服务；
 * 跨组件侧以 {@link InMemoryTradingBridge} 替代 C++。<b>不构成跨进程真联 / DM8 实机验证</b>。
 *
 * <p><b>为何新增而非改既有件</b>：{@code M2EndToEndAcceptanceTest} 系 {@code T-058} 冻结验收件
 * （本单不得改动），其用例以「同值计划 ID / 预解析 DB 主键」观察窗手段绕过 003/004 以继续验证其余链路；
 * 本类以<b>判别性用例</b>（交易计划 ID ≠ 套保计划 ID；事件携带 C++ 指令号）驱动真实服务与真实 DDL，
 * 使五处违规在<b>真实约束下</b>可判红/判绿。
 *
 * <p><b>诚实声明</b>：001b（冻结域 {@code {OK,PARTIAL,FAILED}} 无「未到达」承载）<b>不在本单范围</b>
 * （已转技术席，扩域属 L3 须 {@code CH}）⇒ 未到达分支在本类中如实体现在真实 DDL 下受阻，见
 * {@link #notArrivedBranchBlockedByFrozenDomainGap()}。
 */
class T178ViolationRegressionTest {

    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql",
            "docs/DEPLOY/schema/m2_trade.sql",
            "docs/DEPLOY/schema/m2_settle.sql",
            "docs/DEPLOY/schema/m2_fund.sql",
            "docs/DEPLOY/schema/m3_risk.sql");

    private static final long U_ADMIN = 100L;
    private static final long U_TRADER = 201L;
    private static final int SUB_A = 11;
    private static final long PRODUCT_ID = 55L;
    private static final long EXPOSURE_ID = 301L;
    private static final long HEDGE_PLAN_ID = 302L;
    /** 交易计划主键：<b>故意 ≠</b> {@link #HEDGE_PLAN_ID}（003 判别的关键）。 */
    private static final long TRADE_PLAN_ID = 303L;
    private static final String INSTRUMENT = "cu2612";
    private static final LocalDate BASE_DAY = LocalDate.of(2026, 9, 20);

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private InMemoryTradingBridge bridge;
    private TradeService tradeService;
    private SettleService settleService;
    private ParamDispatchServiceImpl paramDispatchService;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB_A, "SA-A", "商品子账户A");
        M1E2eSeed.user(engine, U_ADMIN, "admin", "管理员", "NP-ADMIN");
        M1E2eSeed.user(engine, U_TRADER, "trader", "交易员", "NP-TRADER");
        M2E2eSeed.positionGroup(engine, 1L, "PG-FRONT", "交易岗组", "FRONT");
        M2E2eSeed.role(engine, 10L, "TRADER", "交易员", 1L, "Y");
        M2E2eSeed.userRole(engine, 1L, U_TRADER, 10L);
        M1E2eSeed.perm(engine, U_TRADER, SUB_A, "LIMITED", U_ADMIN);
        M2E2eSeed.product(engine, PRODUCT_ID, "CU", "沪铜");
        M2E2eSeed.exposure(engine, EXPOSURE_ID, SUB_A, PRODUCT_ID, "100", BASE_DAY);
        M2E2eSeed.hedgePlan(engine, HEDGE_PLAN_ID, SUB_A, PRODUCT_ID, "HP202609200001", "80",
                BASE_DAY, BASE_DAY.plusMonths(6));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN_ID, SUB_A, HEDGE_PLAN_ID, EXPOSURE_ID, PRODUCT_ID,
                "TP202609200001", INSTRUMENT, "80", BASE_DAY.plusMonths(3));

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        AuthService authService = buildAuthService();
        bridge = new InMemoryTradingBridge();
        tradeService = new TradeService(tx, bridge, authService,
                new DmTradePlanDao(tx), new DmHedgePlanDao(tx), new com.hedgeguard.dao.impl.DmExposureDao(tx),
                new DmTradeOrderDao(tx), new DmTradeReasonDao(tx), new DmTradeOrderReportDao(tx),
                new DmTradeFillDao(tx), new DmTradePositionDao(tx), new DmTradePreTradeRejectDao(tx),
                new DmTradeCommandFlowDao(tx));
        settleService = new SettleService(tx, authService,
                new DmSettleCtpBatchDao(tx), new DmSettleCtpPositionDao(tx),
                new DmSettleCtpTradeDao(tx), new DmSettleCtpFundDao(tx),
                new DmSettleCycleDao(tx), new DmSettlePositionDao(tx),
                new DmSettleFundDao(tx), new DmSettlePnlDao(tx),
                new DmSettleReconcileRecordDao(tx), new DmSettleReconcileDifferenceDao(tx),
                new DmSettleAggregationCheckDao(tx), new DmSettleAggDiffCandidateDao(tx),
                new DmSettleReportDao(tx), new DmSettleStressTestDao(tx));
        paramDispatchService = new ParamDispatchServiceImpl(tx, new DmParamDispatchDao(tx), bridge);
    }

    @AfterEach
    void restoreClock() {
        Clocks.reset();
    }

    private AuthService buildAuthService() {
        return new AuthService(tx,
                new DmUserAccountDao(tx), new DmRoleDao(tx), new DmUserRoleDao(tx),
                new DmPositionConflictDao(tx), new DmLoginSessionDao(tx), new DmLoginLogDao(tx),
                new DmPasswordHistoryDao(tx), new DmUserSubAccountPermDao(tx),
                new DmUnauthorizedAttemptDao(tx), new SessionStore.InMemory(),
                new DmAuthorizationDao(tx), new DmPermissionChangeLogDao(tx),
                new DmPositionGroupDao(tx), Config.empty());
    }

    // ============================================================ 001a / 005：CTP 摄入到达分支

    @Test
    @DisplayName("001a/005：到达分支落 parse_status='OK'（冻结域）且 retry_count=0（真实 DmSettleCtpBatchDao+引擎）")
    void arrivedBranchWritesFrozenDomainAndResetsRetry() {
        SettleService.CtpDataRequest req = arrivedRequest();
        SettleService.CtpIngestResult res = settleService.ingestCtpData(req, U_TRADER);
        assertTrue(!res.pendingRetry, "到达分支不应为待重试");

        Map<String, Object> batch = engine.table("t_ctp_settle_batch").rows.get(0);
        // 001a：'PARSED' 越冻结域 {OK,PARTIAL,FAILED}（m2_settle.sql:42）；改回 'PARSED' ⇒ 引擎 CHECK 拒绝 ⇒ 本用例必红
        assertEquals("OK", batch.get("parse_status"), "parse_status 须落冻结域（TE-T058-001a）");
        // 005：DDL DEFAULT 0 对显式 NULL 不生效 ⇒ 到达分支须显式置 0（否则 NOT NULL 拒绝）
        assertEquals(0, ((Number) batch.get("retry_count")).intValue(), "到达分支 retry_count 须为 0（TE-T058-005）");
        assertEquals("Y", batch.get("is_confirmed"));
        assertEquals(1, engine.table("t_ctp_settle_position").rows.size(), "持仓明细应落库");
        assertEquals(1, engine.table("t_ctp_settle_fund").rows.size(), "资金明细应落库");
    }

    @Test
    @DisplayName("001a：同一批次重复接收幂等（更新分支仍落冻结域 'OK'）")
    void arrivedBranchIdempotentReIngest() {
        long first = settleService.ingestCtpData(arrivedRequest(), U_TRADER).batchId;
        long second = settleService.ingestCtpData(arrivedRequest(), U_TRADER).batchId;
        assertEquals(first, second, "同 (trade_date, source) 应幂等");
        assertEquals(1, engine.table("t_ctp_settle_batch").rows.size(), "不得重复建批次");
        assertEquals("OK", engine.table("t_ctp_settle_batch").rows.get(0).get("parse_status"));
    }

    /**
     * <b>001b（不在本单范围，已转技术席）</b>：未到达分支写 {@code PENDING_DATA}，而冻结域
     * {@code {OK,PARTIAL,FAILED}} <b>无「未到达」承载</b>（{@code FR-SETTLE-1} 三态）⇒ 在真实 DDL 下
     * 被 CHECK 拒绝。本用例<b>如实记录该缺口</b>（判据：一旦 001b 经裁定落地，本用例应改为断言成功）。
     */
    @Test
    @DisplayName("001b（出范围）：未到达分支在冻结域下被拒（无「未到达」承载），如实记录")
    void notArrivedBranchBlockedByFrozenDomainGap() {
        SettleService.CtpDataRequest req = new SettleService.CtpDataRequest();
        req.tradeDate = BASE_DAY.toString();
        req.sourceIdentifier = "CTP-NOT-ARRIVED";
        req.positions = Collections.emptyList();
        req.funds = Collections.emptyList();
        req.trades = Collections.emptyList();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> settleService.ingestCtpData(req, U_TRADER),
                "001b：PENDING_DATA 越冻结域，真实 DDL 下必被 CHECK 拒绝（扩域/承载须 CH，不在本单）");
        assertTrue(causeMessagesContain(ex, "ck_t_ctp_settle_batch_parse_status")
                        || causeMessagesContain(ex, "parse_status"),
                "应命中冻结域 CHECK（ck_t_ctp_settle_batch_parse_status）：" + causeMessages(ex));
        // 不产生任何基于推算的结算批次
        assertEquals(0, engine.table("t_ctp_settle_batch").rows.size(), "被拒批次不得留痕");
    }

    // ============================================================ 002：参数下发留痕

    @Test
    @DisplayName("002：参数下发留痕 dispatch_type 落冻结域（去 CMD_ 前缀）（真实 DmParamDispatchDao+引擎）")
    void paramDispatchTrailWithinFrozenDomain() {
        ParamChange change = new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 1L, U_ADMIN);
        change.payload.put("threshold", "0.80");
        paramDispatchService.dispatch(change).join();

        Map<String, Object> row = engine.table("t_param_dispatch").rows.get(0);
        // 002：'CMD_UPDATE_RISK_THRESHOLD' 越冻结域（m3_risk.sql:53-55）⇒ 改回带前缀必被 CHECK 拒绝
        assertEquals("UPDATE_RISK_THRESHOLD", row.get("dispatch_type"),
                "dispatch_type 须为冻结域取值（无 CMD_ 前缀）（TE-T058-002）");
    }

    // ============================================================ 003：交易理由关联计划

    @Test
    @DisplayName("003：交易理由 related_plan_id 按套保计划落库（FK→t_hedge_plan；交易计划 ID ≠ 套保计划 ID）")
    void tradeReasonRelatedPlanResolvesHedgePlan() {
        PlaceOrderResult r = tradeService.submitOrder(submitRequest(HEDGE_PLAN_ID), U_TRADER);
        TradeService.OrderAcceptedEvent accepted = new TradeService.OrderAcceptedEvent();
        accepted.orderId = r.orderId;
        accepted.clientOrderRef = r.clientOrderRef;
        tradeService.onOrderAccepted(accepted);

        assertEquals(1, engine.table("t_trade_reason").rows.size(), "受理事件应落交易理由");
        Map<String, Object> reason = engine.table("t_trade_reason").rows.get(0);
        assertEquals(HEDGE_PLAN_ID, ((Number) reason.get("related_plan_id")).longValue(),
                "related_plan_id 须为套保计划 ID（TE-T058-003；改回按交易计划校验 ⇒ relatedPlanId=" + HEDGE_PLAN_ID + " 报「计划不存在」");
    }

    @Test
    @DisplayName("003：关联计划传交易计划 ID（非套保计划）⇒ 提交拒绝（对齐冻结 FK）")
    void tradeReasonRejectsTradePlanIdAsRelatedPlan() {
        // TRADE_PLAN_ID(303) 存在为交易计划但非套保计划 ⇒ 按套保计划校验应拒
        BizException ex = assertThrows(BizException.class,
                () -> tradeService.submitOrder(submitRequest(TRADE_PLAN_ID), U_TRADER));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());
    }

    // ============================================================ 004：成交 / 回报外键解析

    @Test
    @DisplayName("004：成交事件落 t_trade_fill.order_id = 父指令 DB 主键（非 C++ 指令号）")
    void fillOrderIdResolvedToDbPrimaryKey() {
        PlaceOrderResult r = tradeService.submitOrder(submitRequest(HEDGE_PLAN_ID), U_TRADER);
        TradeService.OrderAcceptedEvent accepted = new TradeService.OrderAcceptedEvent();
        accepted.orderId = r.orderId;
        accepted.clientOrderRef = r.clientOrderRef;
        tradeService.onOrderAccepted(accepted);
        long dbOrderId = ((Number) engine.table("t_trade_order").rows.get(0).get("id")).longValue();
        assertNotEquals(dbOrderId, r.orderId, "前置：C++ 指令号 ≠ DB 主键（判别性）");

        TradeService.FillEvent fill = new TradeService.FillEvent();
        fill.tradeId = "T-FILL-1";
        fill.orderId = r.orderId; // 线格式：C++ 指令号
        fill.orderSysId = "SYS-1";
        fill.subAccountId = SUB_A;
        fill.tradePlanId = TRADE_PLAN_ID;
        fill.exposureId = EXPOSURE_ID;
        fill.productId = PRODUCT_ID;
        fill.instrumentId = INSTRUMENT;
        fill.instrumentType = "FUTURES";
        fill.direction = "BUY";
        fill.offsetFlag = "OPEN";
        fill.fillPrice = "70500";
        fill.fillVolume = 4;
        fill.tradeTsMs = BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant().toEpochMilli();
        fill.eventSeq = 41L;
        tradeService.onFill(fill);

        assertEquals(1, engine.table("t_trade_fill").rows.size(), "成交应落库");
        assertEquals(dbOrderId, ((Number) engine.table("t_trade_fill").rows.get(0).get("order_id")).longValue(),
                "t_trade_fill.order_id 须为 t_trade_order(id)（TE-T058-004；写 C++ 指令号 ⇒ FK 拒绝）");
    }

    @Test
    @DisplayName("004：委托回报落 t_trade_order_report.order_id = 父指令 DB 主键")
    void orderReportOrderIdResolvedToDbPrimaryKey() {
        PlaceOrderResult r = tradeService.submitOrder(submitRequest(HEDGE_PLAN_ID), U_TRADER);
        TradeService.OrderAcceptedEvent accepted = new TradeService.OrderAcceptedEvent();
        accepted.orderId = r.orderId;
        accepted.clientOrderRef = r.clientOrderRef;
        tradeService.onOrderAccepted(accepted);
        long dbOrderId = ((Number) engine.table("t_trade_order").rows.get(0).get("id")).longValue();

        TradeService.OrderReportEvent e = new TradeService.OrderReportEvent();
        e.orderId = r.orderId;
        e.orderSysId = "SYS-R1";
        e.orderStatus = "ORDER_STATUS_ALL_TRADED";
        e.volumeTotal = 4;
        e.volumeTraded = 4;
        e.volumeLeft = 0;
        e.reportTsMs = BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant().toEpochMilli();
        e.eventSeq = 71L;
        e.mappedOrderStatus = "FILLED";
        tradeService.onOrderReport(e);

        assertEquals(1, engine.table("t_trade_order_report").rows.size(), "回报应落库");
        assertEquals(dbOrderId,
                ((Number) engine.table("t_trade_order_report").rows.get(0).get("order_id")).longValue(),
                "t_trade_order_report.order_id 须为 t_trade_order(id)（TE-T058-004 同构第二表）");
    }

    // ============================================================ 夹具

    private SettleService.CtpDataRequest arrivedRequest() {
        SettleService.CtpDataRequest req = new SettleService.CtpDataRequest();
        req.tradeDate = BASE_DAY.toString();
        req.sourceIdentifier = "CTP-" + BASE_DAY;
        SettleService.CtpPositionRow p = new SettleService.CtpPositionRow();
        p.instrumentId = INSTRUMENT;
        p.direction = "LONG";
        p.positionType = "YESTERDAY";
        p.positionQty = "5";
        p.avgPrice = "70000";
        p.settlementPrice = "70500";
        p.margin = "12000";
        SettleService.CtpFundRow f = new SettleService.CtpFundRow();
        f.equity = "1500";
        f.availableFund = "1200";
        f.marginOccupied = "300";
        f.frozenMargin = "0";
        f.commissionToday = "10";
        f.depositToday = "0";
        f.withdrawToday = "0";
        f.closePnl = "50";
        f.floatPnl = "20";
        SettleService.CtpTradeRow t = new SettleService.CtpTradeRow();
        t.tradeId = "CTP-TRADE-1";
        t.instrumentId = INSTRUMENT;
        t.direction = "BUY";
        t.offsetFlag = "OPEN";
        t.fillPrice = "70500";
        t.fillVolume = 1;
        t.tradeTsMs = BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant().toEpochMilli();
        t.commission = "5";
        req.positions = Collections.singletonList(p);
        req.funds = Collections.singletonList(f);
        req.trades = Collections.singletonList(t);
        return req;
    }

    private TradeService.SubmitRequest submitRequest(long relatedPlanId) {
        TradeService.SubmitRequest req = new TradeService.SubmitRequest();
        req.subAccountId = SUB_A;
        req.tradePlanId = TRADE_PLAN_ID;
        req.exposureId = EXPOSURE_ID;
        req.productId = PRODUCT_ID;
        req.instrumentId = INSTRUMENT;
        req.direction = "BUY";
        req.offsetFlag = "OPEN";
        req.priceType = "LIMIT";
        req.price = "70000";
        req.volume = 4;
        TradeService.ReasonInput reason = new TradeService.ReasonInput();
        reason.relatedType = "PLAN_EXECUTION";
        reason.relatedPlanId = relatedPlanId;
        reason.relatedExposureId = EXPOSURE_ID;
        reason.timeDesc = "CONTRACT_SIGNED";
        req.reason = reason;
        return req;
    }

    private static boolean causeMessagesContain(Throwable t, String needle) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String causeMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append('|').append(c.getClass().getSimpleName()).append(':').append(c.getMessage());
        }
        return sb.toString();
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("docs/DEPLOY/schema"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（含 docs/DEPLOY/schema）");
    }
}
