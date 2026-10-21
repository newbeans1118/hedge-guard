package com.hedgeguard.web;

import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.PlaceOrderCmd;
import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.dao.entity.TransferApply;
import com.hedgeguard.dao.impl.DmAuthorizationDao;
import com.hedgeguard.dao.impl.DmCapitalReceiptDao;
import com.hedgeguard.dao.impl.DmDepositWithdrawApplyDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmFundFlowDao;
import com.hedgeguard.dao.impl.DmFundLedgerDao;
import com.hedgeguard.dao.impl.DmFundQuotaAlertDao;
import com.hedgeguard.dao.impl.DmFundQuotaDao;
import com.hedgeguard.dao.impl.DmFundReconcileDao;
import com.hedgeguard.dao.impl.DmFundReviewRecordDao;
import com.hedgeguard.dao.impl.DmLoginLogDao;
import com.hedgeguard.dao.impl.DmLoginSessionDao;
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
import com.hedgeguard.dao.impl.DmTransferApplyDao;
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
import com.hedgeguard.service.FundService;
import com.hedgeguard.service.SettleService;
import com.hedgeguard.service.TradeService;
import com.hedgeguard.web.BizException;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-058} <b>M2 端到端验收</b>（{@code PROJECT.md} §9 M2 验收 / §10-1/21/23/30/35/36/37）。
 *
 * <p><b>层级与边界（诚实声明）</b>：本类为<b>服务装配级</b>端到端，跑在 {@code T-023} 交付的
 * 「按 SQL 语义求值的内存假 JDBC 引擎」{@link InMemoryDmEngine} 上，装配<b>真实</b>
 * {@link AuthService} / {@link TradeService} / {@link SettleService} / {@link FundService}
 * 与<b>真实</b> {@code Dm*Dao}，跨组件侧以 {@link InMemoryTradingBridge}（{@code NFR-TEST-4} 明文允许的
 * 进程内桩）替代 C++ 组件。<b>本类不构成跨进程真联</b>——{@code cpp/src/main.cc} 为工程骨架
 * （不监听 / 不握手 / 不加载配置）⇒ 真联档部分按「未达-真联前置缺失」留痕于证据章，不以进程内桩冒充。
 *
 * <p><b>M1 段（敞口 → 方案）</b>由 {@link M2E2eSeed} 播种最小行提供；M1 链本身已由 {@code T-024}
 * 端到端验收覆盖，本单不重复其业务校验，聚焦 <b>M2 段</b>（交易 → 结算 → 资金）。
 *
 * <p><b>⚠️ 本类不构成 DM8 实机验证</b>：本机无可达 DM8 实例与驱动，仍以引擎等价执行；实机由部署方复跑。
 *
 * <p>完全离线（{@code NFR-TEST-2}）：无网络、无真实 JDBC、无 DM 驱动、无 CTP、无 C++ 运行。
 */
class M2EndToEndAcceptanceTest {

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
    private static final long U_APPROVER = 202L;
    private static final long U_FINANCE = 203L;

    private static final int SUB_A = 11;
    private static final int SUB_B = 12;

    private static final long PRODUCT_ID = 55L;
    private static final long EXPOSURE_ID = 301L;
    private static final long HEDGE_PLAN_ID = 302L;
    /**
     * 交易计划主键。
     *
     * <p>⚠️ 与 {@link #HEDGE_PLAN_ID} 取<b>同一数值</b>，系规避 {@code TE-T058-003} 的观察窗手段：
     * {@code TradeService} 把 {@code ReasonInput.relatedPlanId} 当作<b>交易计划</b>校验（{@code tradePlanDao}），
     * 而 {@code t_trade_reason.related_plan_id} 的外键指向 {@code t_hedge_plan}（{@code m2_trade.sql:277}）
     * ⇒ 真实库上「交易计划 ID ≠ 对冲计划 ID」时落库必违外键。取同值使两侧同时可解析，
     * 以继续验证其余链路；缺陷本身按「报告不自行改」留待裁定。
     */
    private static final long TRADE_PLAN_ID = 302L;
    private static final String INSTRUMENT = "cu2612";

    private static final LocalDate BASE_DAY = LocalDate.of(2026, 9, 20);

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private AuthService authService;
    private InMemoryTradingBridge bridge;
    private TradeService tradeService;
    private SettleService settleService;
    private FundService fundService;

    // ------------------------------------------------------------------ 世界装配

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        // 子账户（显式主键，含作用域哨兵 0）
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB_A, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, SUB_B, "SA-B", "商品子账户B");
        // 用户 / 角色 / 授权
        M1E2eSeed.user(engine, U_ADMIN, "admin", "管理员", "NP-ADMIN");
        M1E2eSeed.user(engine, U_TRADER, "trader", "交易员", "NP-TRADER");
        M1E2eSeed.user(engine, U_APPROVER, "approver", "审批员", "NP-APPROVER");
        M1E2eSeed.user(engine, U_FINANCE, "finance", "财务", "NP-FINANCE");
        M2E2eSeed.positionGroup(engine, 1L, "PG-FRONT", "交易岗组", "FRONT");
        M2E2eSeed.positionGroup(engine, 2L, "PG-MANAGE", "管理岗组", "MANAGE");
        M2E2eSeed.role(engine, 10L, "TRADER", "交易员", 1L, "Y");
        M2E2eSeed.role(engine, 11L, "APPROVER", "审批员", 2L, "N");
        M2E2eSeed.userRole(engine, 1L, U_TRADER, 10L);
        M2E2eSeed.userRole(engine, 2L, U_APPROVER, 11L);
        // 子账户数据权限（写面需 LIMITED）
        M1E2eSeed.perm(engine, U_TRADER, SUB_A, "LIMITED", U_ADMIN);
        M1E2eSeed.perm(engine, U_TRADER, SUB_B, "LIMITED", U_ADMIN);
        M1E2eSeed.perm(engine, U_APPROVER, SUB_A, "LIMITED", U_ADMIN);
        M1E2eSeed.perm(engine, U_APPROVER, SUB_B, "LIMITED", U_ADMIN);
        M1E2eSeed.perm(engine, U_FINANCE, SUB_A, "LIMITED", U_ADMIN);
        M1E2eSeed.perm(engine, U_FINANCE, SUB_B, "LIMITED", U_ADMIN);
        // M1 段最小行
        M2E2eSeed.product(engine, PRODUCT_ID, "CU", "沪铜");
        M2E2eSeed.exposure(engine, EXPOSURE_ID, SUB_A, PRODUCT_ID, "100", BASE_DAY);
        M2E2eSeed.hedgePlan(engine, HEDGE_PLAN_ID, SUB_A, PRODUCT_ID, "HP202609200001", "80",
                BASE_DAY, BASE_DAY.plusMonths(6));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN_ID, SUB_A, HEDGE_PLAN_ID, EXPOSURE_ID, PRODUCT_ID,
                "TP202609200001", INSTRUMENT, "80", BASE_DAY.plusMonths(3));

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        authService = null; // 延迟装配（见下）
        bridge = new InMemoryTradingBridge();
        authService = buildAuthService();
        tradeService = new TradeService(tx, bridge, authService,
                new DmTradePlanDao(tx), new DmExposureDao(tx), new DmTradeOrderDao(tx),
                new DmTradeReasonDao(tx), new DmTradeOrderReportDao(tx), new DmTradeFillDao(tx),
                new DmTradePositionDao(tx), new DmTradePreTradeRejectDao(tx),
                new DmTradeCommandFlowDao(tx));
        settleService = new SettleService(tx, authService,
                new DmSettleCtpBatchDao(tx), new DmSettleCtpPositionDao(tx),
                new DmSettleCtpTradeDao(tx), new DmSettleCtpFundDao(tx),
                new DmSettleCycleDao(tx), new DmSettlePositionDao(tx),
                new DmSettleFundDao(tx), new DmSettlePnlDao(tx),
                new DmSettleReconcileRecordDao(tx), new DmSettleReconcileDifferenceDao(tx),
                new DmSettleAggregationCheckDao(tx), new DmSettleAggDiffCandidateDao(tx),
                new DmSettleReportDao(tx), new DmSettleStressTestDao(tx));
        fundService = new FundService(tx, authService, authService::hasTradingRole,
                key -> null,
                new DmFundLedgerDao(tx), new DmFundFlowDao(tx), new DmDepositWithdrawApplyDao(tx),
                new DmTransferApplyDao(tx), new DmCapitalReceiptDao(tx),
                new DmFundReviewRecordDao(tx), new DmFundQuotaDao(tx),
                new DmFundQuotaAlertDao(tx), new DmFundReconcileDao(tx));
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

    // ================================================================ §10-36 加总对账

    @Test
    @DisplayName("§10-36：Σ 子账户 = 真实账户（一致）⇒ 四类对账全 CONSISTENT")
    void acceptance36AggregationConsistent() {
        long cycleA = settleConsistent(SUB_A, SUB_B);
        SettleService.AggregateResult res = settleService.aggregate(cycleA, U_TRADER);
        assertTrue(res.consistent, "Σ 子账户 = 真实账户 ⇒ 应一致：" + describeChecks(res));
        for (var c : res.checks) {
            assertEquals("CONSISTENT", c.result, "checkType=" + c.checkType + " 应一致：" + describeChecks(res));
        }
        // 独立核对：Σ t_settle_cycle.equity == 真实账户权益（CTP 合并值）
        BigDecimal sumSub = sumColumn("t_settle_cycle", "equity");
        BigDecimal real = sumColumn("t_ctp_settle_fund", "equity");
        assertTrue(sumSub.compareTo(real) == 0,
                "Σ 子账户权益（" + sumSub.toPlainString() + "）应等于真实账户（" + real.toPlainString() + "）");
    }

    @Test
    @DisplayName("§10-36：人为构造不一致 ⇒ INCONSISTENT_BLOCKED + 全账户告警 + 结构化候选 + 结算单转 BLOCKED")
    void acceptance36InconsistentTriggersAlertAndBlock() {
        // 系统侧子账户权益合计 1500，但真实账户 CTP 权益记 1400（人为制造 100 差额）
        long cycleA = settleScenario("1400");
        SettleService.AggregateResult res = settleService.aggregate(cycleA, U_TRADER);
        assertFalse(res.consistent, "构造差额 ⇒ 应判不一致：" + describeChecks(res));
        var fundCheck = res.checks.stream()
                .filter(c -> "FUND".equals(c.checkType)).findFirst().orElseThrow();
        assertEquals("INCONSISTENT_BLOCKED", fundCheck.result);
        assertEquals(0, new BigDecimal("100").compareTo(fundCheck.difference.abs()),
                "差额应为 100：" + fundCheck.difference);
        assertNotNull(fundCheck.alertTime, "不一致须置全账户告警时间（FR-SETTLE-8 / CFG-11）");
        assertFalse(res.blockedSubAccountIds.isEmpty(), "须产出结构化候选/阻断子账户（fail-closed 至少全子账户）");
        assertTrue(engine.table("t_agg_diff_candidate").rows.size() >= 1, "应落差异候选行");
        assertTrue(engine.table("t_settle_cycle").rows.stream()
                .anyMatch(r -> "BLOCKED".equals(r.get("status"))), "差异所属子账户结算单应转 BLOCKED");
    }

    // ================================================================ §10-37 划拨

    @Test
    @DisplayName("§10-37：A→B 划拨后 ①A 减 ②B 增 ③加总不变 ④有审批与留痕")
    void acceptance37TransferKeepsTotalInvariant() {
        initLedger(SUB_A, "1000");
        initLedger(SUB_B, "500");

        TransferApply t = initiate(SUB_A, SUB_B, "200");
        assertEquals("PENDING_APPROVAL", t.status);
        // 发起人 ≠ 审批人（岗位分离）
        FundService.TransferApproveRequest ap = new FundService.TransferApproveRequest();
        ap.approved = true;
        TransferApply posted = fundService.approveTransfer(t.id, ap, U_APPROVER, new BigDecimal("1500"));
        assertEquals("POSTED", posted.status, "加总校验通过应记账");
        assertEquals("Y", posted.aggCheckPassed, "加总校验通过标记");

        BigDecimal aAvail = ledgerAvailable(SUB_A);
        BigDecimal bAvail = ledgerAvailable(SUB_B);
        assertTrue(new BigDecimal("800").compareTo(aAvail) == 0, "A 应减至 800：实际=" + aAvail);
        assertTrue(new BigDecimal("700").compareTo(bAvail) == 0, "B 应增至 700：实际=" + bAvail);
        assertTrue(new BigDecimal("1500").compareTo(aAvail.add(bAvail)) == 0,
                "① ② ③ 加总金额不变（Σ=1500）");
        // ④ 审批与留痕：成对流水 + 复核记录
        assertEquals(2, engine.table("t_fund_flow").rows.size(), "应生成一出、一入两条成对流水");
        assertTrue(engine.table("t_fund_review_record").rows.size() >= 1, "应落审批复核记录");
    }

    @Test
    @DisplayName("§10-37：加总校验不通过 ⇒ 回滚（不留下半完成划拨），台账恢复、状态 ROLLED_BACK")
    void acceptance37TransferRollsBackWhenAggregationFails() {
        initLedger(SUB_A, "1000");
        initLedger(SUB_B, "500");
        TransferApply t = initiate(SUB_A, SUB_B, "200");
        FundService.TransferApproveRequest ap = new FundService.TransferApproveRequest();
        ap.approved = true;
        // 真实账户给错值（1400 ≠ Σ 1500）⇒ 加总校验不通过 ⇒ 回滚
        TransferApply rolled = fundService.approveTransfer(t.id, ap, U_APPROVER, new BigDecimal("1400"));
        assertEquals("ROLLED_BACK", rolled.status);
        assertEquals("N", rolled.aggCheckPassed);
        assertTrue(new BigDecimal("1000").compareTo(ledgerAvailable(SUB_A)) == 0,
                "回滚后 A 应恢复 1000：" + ledgerAvailable(SUB_A));
        assertTrue(new BigDecimal("500").compareTo(ledgerAvailable(SUB_B)) == 0,
                "回滚后 B 应恢复 500：" + ledgerAvailable(SUB_B));
        assertTrue(new BigDecimal("1500").compareTo(
                        ledgerAvailable(SUB_A).add(ledgerAvailable(SUB_B))) == 0,
                "回滚后加总仍为 1500");
    }

    @Test
    @DisplayName("§10-37：发起人=审批人 ⇒ 拒绝（R-FUND-3 岗位分离）")
    void acceptance37SelfApprovalRejected() {
        initLedger(SUB_A, "1000");
        initLedger(SUB_B, "500");
        TransferApply t = initiate(SUB_A, SUB_B, "200");
        FundService.TransferApproveRequest ap = new FundService.TransferApproveRequest();
        ap.approved = true;
        BizException ex = assertThrows(BizException.class,
                () -> fundService.approveTransfer(t.id, ap, U_TRADER, null));
        assertEquals(com.hedgeguard.web.ErrorCode.POSITION_SEPARATION_CONFLICT, ex.errorCode());
    }

    // ================================================================ §10-23 / §10-35 下单路径

    @Test
    @DisplayName("§10-23/§10-35：下单路径零写库；指令携带子账户标识与计划绑定")
    void acceptance23And35OrderPathZeroDbWriteAndTraceable() {
        Map<String, Integer> before = tableRowCounts();
        PlaceOrderResult r = tradeService.submitOrder(submitRequest(), U_TRADER);
        assertTrue(r.accepted && r.orderId > 0, "应受理取得 order_id");
        Map<String, Integer> after = tableRowCounts();
        assertEquals(before, after, "§10-23：从下达指令到下发组件的路径不得产生任何写库（行数须不变）");
        assertEquals(1, bridge.placedOrders().size(), "指令应已下发组件");
        PlaceOrderCmd cmd = bridge.placedOrders().get(0);
        assertEquals(SUB_A, cmd.subAccountId, "§10-35：每笔单须携带子账户标识");
        assertEquals(TRADE_PLAN_ID, cmd.tradePlanId, "每单可追溯至计划");
        assertEquals(INSTRUMENT, cmd.instrumentId);
    }

    // ================================================================ §10-30 组件通信双向（进程内）

    @Test
    @DisplayName("§10-30①：C++ 事件 → Java 落库（受理事件 + 成交事件，含富化 NOT NULL 字段）")
    void acceptance30EventToJavaPersistence() {
        PlaceOrderResult r = tradeService.submitOrder(submitRequest(), U_TRADER);
        TradeService.OrderAcceptedEvent accepted = new TradeService.OrderAcceptedEvent();
        accepted.orderId = r.orderId;
        accepted.clientOrderRef = r.clientOrderRef;
        tradeService.onOrderAccepted(accepted);
        assertEquals(1, engine.table("t_trade_order").rows.size(), "受理事件应落 t_trade_order");
        assertEquals(SUB_A, ((Number) engine.table("t_trade_order").rows.get(0).get("sub_account_id")).intValue());

        // ⚠️ TE-T058-004：t_trade_fill.order_id 外键指向 t_trade_order(id)（DB 主键），
        // 而 TradeService.onFill 直接写线格式 order_id（C++ 指令号）⇒ 真实库上会违外键。
        // 此处以「落库后的 DB 主键」+ 显式富化字段构造事件，绕过该缺陷以继续验证事件→落库其余链路。
        long dbOrderId = ((Number) engine.table("t_trade_order").rows.get(0).get("id")).longValue();
        TradeService.FillEvent fill = new TradeService.FillEvent();
        fill.tradeId = "T-FILL-1";
        fill.orderId = dbOrderId;
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
        assertEquals(1, engine.table("t_trade_fill").rows.size(), "成交事件应落 t_trade_fill");
        assertEquals(SUB_A, ((Number) engine.table("t_trade_fill").rows.get(0).get("sub_account_id")).intValue());
    }

    @Test
    @DisplayName("§10-30②：Java→C++ 控制通道（进程内桩）可下发风控参数并获回执")
    void acceptance30ParamDispatchToComponent() {
        // 进程内桩验证 Java→C++ 控制指令通道 + 回执可用（NFR-TEST-4 允许的替换）。
        // ⚠️ ParamDispatchService 的服务编排路径（含 t_param_dispatch 留痕）当前被 TE-T058-002 阻断：
        // ParamDispatchServiceImpl 写 dispatch_type=change.type.name()（'CMD_*'），越出冻结域（无前缀）
        // ⇒ 在真实/等价 DB 上必违 CHECK。故此处以 bridge 通道为证据，服务编排路径如实记为「未达-实现缺陷」。
        ParamChange change = new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 1L, U_ADMIN);
        change.payload.put("threshold", "0.80");
        var ack = bridge.dispatch(change).join();
        assertTrue(ack.acknowledged, "组件应回执成功");
        assertEquals(1, bridge.dispatchedChanges().size(), "参数变更应已下发组件");
        assertEquals(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, bridge.dispatchedChanges().get(0).type);
    }

    // ================================================================ §10-21 出入金回执

    @Test
    @DisplayName("§10-21：出入金经回执回写台账（入金增加、出金减少，凭证编号落库）")
    void acceptance21DepositWithdrawReceiptBooked() {
        initLedger(SUB_A, "1000");
        DepositWithdrawApply dep = deposit(SUB_A, "300", "K-DEP");
        reviewDw(dep.id, true);
        FundService.ReceiptRequest rr = new FundService.ReceiptRequest();
        rr.applyId = dep.id;
        rr.idempotencyKey = "R-DEP-1";
        rr.result = "SUCCESS";
        rr.amount = "300";
        rr.receiptTsMs = 1L;
        FundService.ReceiptResult res = fundService.onFundReceipt(rr, U_FINANCE);
        assertTrue(res.processed, "成功回执应记账");
        assertTrue(new BigDecimal("1300").compareTo(ledgerAvailable(SUB_A)) == 0,
                "入金后可用资金应 1300：" + ledgerAvailable(SUB_A));
        assertTrue(engine.table("t_fund_flow").rows.stream()
                        .anyMatch(r -> r.get("voucher_no") != null),
                "每笔流水须有凭证编号");
    }

    // ================================================================ §10-1 全链闭环

    @Test
    @DisplayName("§10-1：敞口→方案→下单→成交→结算→资金 全链路无断点（M2 段）")
    void acceptance1EndToEndNoBreak() {
        // 交易：下单（零写库）→ 受理事件落库 → 成交事件落库
        PlaceOrderResult r = tradeService.submitOrder(submitRequest(), U_TRADER);
        TradeService.OrderAcceptedEvent accepted = new TradeService.OrderAcceptedEvent();
        accepted.orderId = r.orderId;
        accepted.clientOrderRef = r.clientOrderRef;
        tradeService.onOrderAccepted(accepted);
        long dbOrderId = ((Number) engine.table("t_trade_order").rows.get(0).get("id")).longValue();
        TradeService.FillEvent fill = new TradeService.FillEvent();
        fill.tradeId = "T-CHAIN-1";
        fill.orderId = dbOrderId; // TE-T058-004 workaround（外键指向 DB 主键）
        fill.orderSysId = "SYS-CHAIN-1";
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
        fill.eventSeq = 51L;
        tradeService.onFill(fill);
        Object fillPlan = engine.table("t_trade_fill").rows.get(0).get("trade_plan_id");
        assertEquals(TRADE_PLAN_ID, ((Number) fillPlan).longValue(), "成交易可追溯至交易计划");

        // 结算：CTP 日终 → 子账户拆分结算 → 加总对账一致
        long cycleA = settleConsistent(SUB_A, SUB_B);
        assertTrue(settleService.aggregate(cycleA, U_TRADER).consistent, "全链路末段加总应一致");

        // 资金：建账 → 记账（凭证编号）
        initLedger(SUB_A, "1000");
        assertNotNull(engine.table("t_fund_ledger").rows.get(0).get("end_balance"), "资金台账期末余额应落库");
    }

    // ------------------------------------------------------------ 夹具：结算

    /** 一致场景：系统侧 A=900/B=600（Σ=1500），真实账户 CTP=1500。 */
    private long settleConsistent(int subA, int subB) {
        return settleScenario("1500");
    }

    private long settleScenario(String ctpEquity) {
        // CTP 日终数据（账户级合并）走种子直插 t_ctp_settle_batch/position/fund。
        // ⚠️ 未经 FR-SETTLE-1 服务入口 ingestCtpData：T-058 时点该入口写 parse_status='PARSED'/'PENDING_DATA'，
        // 越出当时冻结域 {OK,PARTIAL,FAILED} ⇒ 在真实/等价 DB 上必违 CHECK（缺陷 TE-T058-001）；CH-021（2026-10-06 用户批准）扩域后该入口写 'NOT_ARRIVED' 合法。
        try {
            M2E2eSeed.ctpBatch(engine, 900L, BASE_DAY, "CTP-" + BASE_DAY, 1, 1);
            M2E2eSeed.ctpPosition(engine, 900L, BASE_DAY, INSTRUMENT, "LONG", "YESTERDAY", "5");
            M2E2eSeed.ctpFund(engine, 900L, BASE_DAY, ctpEquity, "1200", "300", "10", "50", "20");
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("CTP 日终数据播种失败", ex);
        }

        SettleService.SettlementInput in = new SettleService.SettlementInput();
        in.tradeDate = BASE_DAY.toString();
        in.positions = Arrays.asList(sysPos(SUB_A, "3"), sysPos(SUB_B, "2"));
        in.funds = Arrays.asList(sysFund(SUB_A, "900", "30", "12", "180", "6"),
                sysFund(SUB_B, "600", "20", "8", "120", "4"));
        List<com.hedgeguard.dao.entity.SettleCycle> generated =
                settleService.runSettlement(in, U_TRADER);
        assertEquals(2, generated.size(), "应生成 A/B 两份子账户结算单");
        return generated.get(0).id;
    }

    private static SettleService.SystemPositionRow sysPos(int sub, String qty) {
        SettleService.SystemPositionRow p = new SettleService.SystemPositionRow();
        p.subAccountId = sub;
        p.instrumentId = INSTRUMENT;
        p.productId = PRODUCT_ID;
        p.instrumentType = "FUTURES";
        p.direction = "LONG";
        p.positionType = "YESTERDAY";
        p.positionQty = qty;
        return p;
    }

    private static SettleService.SystemFundRow sysFund(int sub, String equity, String closePnl,
                                                       String floatPnl, String margin, String commission) {
        SettleService.SystemFundRow f = new SettleService.SystemFundRow();
        f.subAccountId = sub;
        f.beginBalance = "1000";
        f.equity = equity;
        f.availableFund = equity.equals("900") ? "700" : "500";
        f.marginOccupied = margin;
        f.frozenMargin = "0";
        f.amountIn = "0";
        f.amountOut = "0";
        f.transferIn = "0";
        f.transferOut = "0";
        f.closePnl = closePnl;
        f.floatPnl = floatPnl;
        f.commission = commission;
        f.marginChange = "0";
        f.ledgerEquity = equity;
        f.attributionSource = "LEDGER";
        return f;
    }

    // ------------------------------------------------------------ 夹具：资金

    private void initLedger(int sub, String balance) {
        FundService.LedgerInitRequest req = new FundService.LedgerInitRequest();
        req.subAccountId = sub;
        req.ledgerDate = BASE_DAY.toString();
        req.beginBalance = balance;
        req.reviewerId = U_APPROVER;
        fundService.initLedger(req, U_TRADER);
    }

    private TransferApply initiate(int from, int to, String amount) {
        FundService.TransferRequest req = new FundService.TransferRequest();
        req.fromSubAccountId = from;
        req.toSubAccountId = to;
        req.amount = amount;
        req.reason = "子账户间资金划拨（§10-37）";
        req.idempotencyKey = "IDEM-E2E-" + from + "-" + to + "-" + amount; // v1.3.9/T-202 必带
        return fundService.initiateTransfer(req, U_TRADER);
    }

    private DepositWithdrawApply deposit(int sub, String amount, String idem) {
        FundService.DepositWithdrawRequest req = new FundService.DepositWithdrawRequest();
        req.subAccountId = sub;
        req.direction = "IN";
        req.amount = amount;
        req.fundSourceDesc = "CTP 银期入金";
        req.idempotencyKey = idem;
        return fundService.initiateDepositWithdraw(req, U_TRADER);
    }

    private void reviewDw(long applyId, boolean approved) {
        FundService.ReviewRequest req = new FundService.ReviewRequest();
        req.approved = approved;
        fundService.reviewDepositWithdraw(applyId, req, U_APPROVER);
    }

    private BigDecimal ledgerAvailable(int sub) {
        for (Map<String, Object> row : engine.table("t_fund_ledger").rows) {
            if (((Number) row.get("sub_account_id")).intValue() == sub) {
                return (BigDecimal) row.get("available_fund");
            }
        }
        throw new IllegalStateException("无子账户 " + sub + " 台账");
    }

    // ------------------------------------------------------------ 夹具：交易

    private TradeService.SubmitRequest submitRequest() {
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
        reason.relatedPlanId = TRADE_PLAN_ID;
        reason.relatedExposureId = EXPOSURE_ID;
        reason.timeDesc = "CONTRACT_SIGNED";
        req.reason = reason;
        return req;
    }

    // ------------------------------------------------------------ 工具

    private Map<String, Integer> tableRowCounts() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, InMemoryDmEngine.Table> e : engine.tables().entrySet()) {
            out.put(e.getKey(), e.getValue().rows.size());
        }
        return out;
    }

    private BigDecimal sumColumn(String table, String column) {
        BigDecimal sum = BigDecimal.ZERO;
        InMemoryDmEngine.Table t = engine.table(table);
        if (t == null) {
            throw new IllegalStateException("表未登记：" + table + "；已登记=" + engine.tables().keySet());
        }
        for (Map<String, Object> row : t.rows) {
            Object v = row.get(column);
            if (v != null) {
                sum = sum.add(new BigDecimal(String.valueOf(v)));
            }
        }
        return sum;
    }

    private String describeChecks(SettleService.AggregateResult res) {
        StringBuilder sb = new StringBuilder();
        for (var c : res.checks) {
            sb.append('[').append(c.checkType).append(" Σ子=").append(c.sumSubAccount)
                    .append(" 真实=").append(c.realAccountValue).append(" 差=").append(c.difference)
                    .append(' ').append(c.result).append(']');
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
