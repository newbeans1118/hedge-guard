package com.hedgeguard.service;

import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.entity.TradePosition;
import com.hedgeguard.dao.impl.DmAnnualScaleDao;
import com.hedgeguard.dao.impl.DmAuthorizationDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmLimitUsageStatDao;
import com.hedgeguard.dao.impl.DmLoginLogDao;
import com.hedgeguard.dao.impl.DmLoginSessionDao;
import com.hedgeguard.dao.impl.DmPasswordHistoryDao;
import com.hedgeguard.dao.impl.DmPermissionChangeLogDao;
import com.hedgeguard.dao.impl.DmPositionConflictDao;
import com.hedgeguard.dao.impl.DmPositionGroupDao;
import com.hedgeguard.dao.impl.DmProductContractDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmPurchaseContractDao;
import com.hedgeguard.dao.impl.DmRiskThresholdDao;
import com.hedgeguard.dao.impl.DmRoleDao;
import com.hedgeguard.dao.impl.DmSalesContractDao;
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
import com.hedgeguard.dao.testsupport.T066RiskRecomputeSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.impl.RiskRecomputeServiceImpl;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.SessionStore;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-187}（跨栈方向词汇统一·方案 A，用户 2026-10-10 裁定）：
 * <b>摄取侧 BUY/SELL→LONG/SHORT 映射 + 复算侧按 SHORT 取负</b>的承重测试。
 *
 * <p><b>缺陷背景</b>：线上 0x0205（{@code EVT_POSITION_CHANGE}）词汇为 BUY/SELL，
 * {@code t_position.direction} 的 DDL 冻结域为 LONG/SHORT（{@code ck_position_direction}，
 * {@code m2_trade.sql:320}）。修复前 {@code TradeService.onPositionChange} 把剥离前缀后的
 * BUY/SELL 直写落库（真实库必违反 CHECK），且 {@code RiskRecomputeServiceImpl} 以 "SELL"
 * 比对持仓方向取负 ⇒ LONG/SHORT 域下净持仓恒 ≥0，卖向越限（0x1009）结构性检不出。
 *
 * <p><b>层级与边界</b>：装配级全链路（真实 {@link TradeService} / {@link AuthService} /
 * 真实 {@code Dm*Dao} / {@link InMemoryDmEngine} 按 SQL 语义求值含 CHECK 约束），
 * 禁假 DAO；跨组件侧以 {@link InMemoryTradingBridge} 进程内桩承载（{@code NFR-TEST-4}）。
 */
class T187DirectionVocabularyTest {

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
    private static final int SUB = 11;
    private static final long PRODUCT_ID = 55L;
    private static final long EXPOSURE_ID = 301L;
    private static final long HEDGE_PLAN_ID = 302L;
    private static final long TRADE_PLAN_ID = 303L;
    private static final String INSTRUMENT = "cu2612";
    /** 卖向敞口（实物量，合约乘数 = 换算系数 = 1 ⇒ 1 手 = 1.0）。 */
    private static final String SELL_EXPOSURE_QTY = "35";
    private static final LocalDate BASE_DAY = LocalDate.of(2026, 10, 4);

    private InMemoryDmEngine engine;
    private TradeService tradeService;
    private RiskRecomputeServiceImpl riskRecomputeService;
    private DmTradePositionDao positionDao;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            engine.executeScript(new String(Files.readAllBytes(resolve(rel)), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M1E2eSeed.user(engine, U_ADMIN, "admin", "管理员", "NP-ADMIN");
        M1E2eSeed.user(engine, U_TRADER, "trader", "交易员", "NP-TRADER");
        M2E2eSeed.positionGroup(engine, 1L, "PG-FRONT", "交易岗组", "FRONT");
        M2E2eSeed.positionGroup(engine, 2L, "PG-MANAGE", "管理岗组", "MANAGE");
        M2E2eSeed.role(engine, 10L, "TRADER", "交易员", 1L, "Y");
        M2E2eSeed.userRole(engine, 1L, U_TRADER, 10L);
        M1E2eSeed.perm(engine, U_TRADER, SUB, "LIMITED", U_ADMIN);
        M2E2eSeed.product(engine, PRODUCT_ID, "CU", "沪铜");
        M2E2eSeed.exposure(engine, EXPOSURE_ID, SUB, PRODUCT_ID, SELL_EXPOSURE_QTY, BASE_DAY);
        M2E2eSeed.hedgePlan(engine, HEDGE_PLAN_ID, SUB, PRODUCT_ID, "HP202610040001",
                "80", BASE_DAY, BASE_DAY.plusMonths(6));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN_ID, SUB, HEDGE_PLAN_ID, EXPOSURE_ID, PRODUCT_ID,
                "TP202610040001", INSTRUMENT, "80", BASE_DAY.plusMonths(3));
        // 复算侧换算关系：合约乘数 = 换算系数 = 1（与 T-186 golden 对拍口径同设）
        T066RiskRecomputeSeed.productContract(engine, 1001L, PRODUCT_ID, INSTRUMENT, "1",
                LocalDate.of(2027, 6, 30));
        T066RiskRecomputeSeed.productUnit(engine, 1002L, PRODUCT_ID, "1",
                LocalDate.of(2026, 1, 1));

        TxTemplate tx = new TxTemplate(
                new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        AuthService authService = new AuthService(tx,
                new DmUserAccountDao(tx), new DmRoleDao(tx), new DmUserRoleDao(tx),
                new DmPositionConflictDao(tx), new DmLoginSessionDao(tx), new DmLoginLogDao(tx),
                new DmPasswordHistoryDao(tx), new DmUserSubAccountPermDao(tx),
                new DmUnauthorizedAttemptDao(tx), new SessionStore.InMemory(),
                new DmAuthorizationDao(tx), new DmPermissionChangeLogDao(tx),
                new DmPositionGroupDao(tx), Config.empty());
        tradeService = new TradeService(tx, new InMemoryTradingBridge(), authService,
                new DmTradePlanDao(tx), new DmHedgePlanDao(tx), new DmExposureDao(tx),
                new DmTradeOrderDao(tx), new DmTradeReasonDao(tx),
                new DmTradeOrderReportDao(tx), new DmTradeFillDao(tx),
                new DmTradePositionDao(tx), new DmTradePreTradeRejectDao(tx),
                new DmTradeCommandFlowDao(tx));
        positionDao = new DmTradePositionDao(tx);
        riskRecomputeService = new RiskRecomputeServiceImpl(tx,
                new DmAnnualScaleDao(tx), new DmExposureDao(tx), new DmTradePositionDao(tx),
                new DmProductContractDao(tx), new DmProductUnitDao(tx), new DmHedgePlanDao(tx),
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx),
                new DmLimitUsageStatDao(tx),
                new DmRiskThresholdDao(tx),
                userId -> Collections.singletonList(SUB),
                LimitUsageThresholds.defaults());
    }

    @AfterEach
    void restoreClock() {
        Clocks.reset();
    }

    // ---------------------------------------------------------------- 映射单测（④·一）

    @Test
    @DisplayName("T-187 映射单测：摄取 BUY→LONG / SELL→SHORT 落库（DDL 域）；同键二次事件按映射键 upsert")
    void positionDirectionMappedToDdlDomain() {
        submitToRegisterInstrumentIndex();

        ingestPosition("BUY", "5");
        TradePosition longRow = positionDao.findByKey(SUB, INSTRUMENT, "LONG", "YESTERDAY");
        assertNotNull(longRow, "BUY 事件应映射为 LONG 行落库");
        assertEquals("LONG", longRow.direction, "落库域须为 ck_position_direction 域（LONG/SHORT）");
        assertEquals(0, new BigDecimal("5").compareTo(longRow.positionQty));
        assertNull(positionDao.findByKey(SUB, INSTRUMENT, "BUY", "YESTERDAY"),
                "落库不得残留线上词汇 BUY");

        ingestPosition("SELL", "3");
        TradePosition shortRow = positionDao.findByKey(SUB, INSTRUMENT, "SHORT", "YESTERDAY");
        assertNotNull(shortRow, "SELL 事件应映射为 SHORT 行落库");
        assertEquals("SHORT", shortRow.direction, "落库域须为 ck_position_direction 域（LONG/SHORT）");
        assertEquals(0, new BigDecimal("3").compareTo(shortRow.positionQty));

        // 同一映射键二次事件 ⇒ upsert 更新（四元键含映射后的 direction），不另插行
        ingestPosition("SELL", "7");
        assertEquals(2, engine.table("t_position").rows.size(), "四元键 upsert 不应新增行");
        assertEquals(0, new BigDecimal("7").compareTo(
                positionDao.findByKey(SUB, INSTRUMENT, "SHORT", "YESTERDAY").positionQty),
                "同键事件应更新既有 SHORT 行");
    }

    @Test
    @DisplayName("T-187 映射单测：非法方向（LONG 透传 / 未知词）显式 BizException 拒收，不落库不静默")
    void illegalPositionDirectionRejectedExplicitly() {
        submitToRegisterInstrumentIndex();

        BizException ex1 = assertThrows(BizException.class, () -> ingestPosition("LONG", "5"),
                "落库域词汇 LONG 不是线上词汇，摄取侧必须拒收（防上游语义漂移静默穿透）");
        assertTrue(ex1.getMessage().contains("BUY/SELL"),
                "错误信息须说明合法线上词汇：" + ex1.getMessage());
        BizException ex2 = assertThrows(BizException.class, () -> ingestPosition("HOLD", "5"),
                "未知方向词必须显式拒收");
        assertTrue(ex2.getMessage().contains("LONG/SHORT"),
                "错误信息须说明落库域：" + ex2.getMessage());
        assertEquals(0, engine.table("t_position").rows.size(), "拒收事件不得落库");
    }

    // ---------------------------------------------------------------- 全链路场景（④·二）

    @Test
    @DisplayName("T-187 全链路：摄取 SELL→SHORT 40 手落库 → 复算卖向投影 40 > 卖敞口 35 ⇒ REJECT 0x1009（修复前结构性检不出）")
    void fullChainShortSideSellBreachDetected() {
        submitToRegisterInstrumentIndex();
        ingestPosition("SELL", "40");

        TradePosition row = positionDao.findByKey(SUB, INSTRUMENT, "SHORT", "YESTERDAY");
        assertNotNull(row, "SHORT 行应落库（40 手空头）");

        RiskRecomputeService.NetPositionResult r = riskRecomputeService
                .recomputeNetPosition(SUB, PRODUCT_ID);
        assertEquals(RiskRecomputeService.REJECT, r.verdict, "净空头 40 越卖向敞口 35 须阻断");
        assertEquals("0x1009", r.code, "理由码");
        assertDecimalEquals("阈值（卖向敞口）", new BigDecimal(SELL_EXPOSURE_QTY), r.threshold);
        assertDecimalEquals("当前值（卖向投影 = -净持仓）", new BigDecimal("40"), r.current);
        assertEquals("SELL", r.breachDirection, "越限方向为卖向");    }

    @Test
    @DisplayName("T-187 全链路：摄取 BUY→LONG 40 手落库 → 净多不误触卖向红线 ⇒ PASS（修复不引入误拒）")
    void fullChainLongSideNotFalseRejected() {
        submitToRegisterInstrumentIndex();
        ingestPosition("BUY", "40");

        TradePosition row = positionDao.findByKey(SUB, INSTRUMENT, "LONG", "YESTERDAY");
        assertNotNull(row, "LONG 行应落库（40 手多头）");

        RiskRecomputeService.NetPositionResult r = riskRecomputeService
                .recomputeNetPosition(SUB, PRODUCT_ID);
        assertEquals(RiskRecomputeService.PASS, r.verdict,
                "净多 40：卖向投影 -40 < 卖敞口 35、无买敞口行 ⇒ 不得误拒");
        assertNull(r.code, "PASS 不应有理由码");
        assertEquals(0, new BigDecimal("40").compareTo(new BigDecimal(String.valueOf(r.netPosition))),
                "净持仓 = LONG 40（折算系数 1）");
        assertNull(r.breachDirection, "多头侧不得出现越限标记");
    }

    // ---------------------------------------------------------------- 夹具

    /** 真实提交一笔指令：登记 instrument→product 索引（持仓事件富化的既有硬边界②路径）。 */
    private void submitToRegisterInstrumentIndex() {
        TradeService.SubmitRequest req = new TradeService.SubmitRequest();
        req.subAccountId = SUB;
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
        reason.relatedPlanId = HEDGE_PLAN_ID; // related_plan_id 冻结外键指向 t_hedge_plan（TE-T058-003）
        reason.relatedExposureId = EXPOSURE_ID;
        reason.timeDesc = "CONTRACT_SIGNED";
        req.reason = reason;
        tradeService.submitOrder(req, U_TRADER);
    }

    /** 事件序号计数器（幂等/追溯用，测试内单调唯一）。 */
    private long eventSeq;

    /** 模拟桥接层剥离 {@code DIRECTION_} 前缀后的 0x0205 持仓事件摄取。 */
    private void ingestPosition(String wireDirection, String qty) {
        TradeService.PositionChangeEvent e = new TradeService.PositionChangeEvent();
        e.subAccountId = SUB;
        e.instrumentId = INSTRUMENT;
        e.direction = wireDirection;
        e.positionType = "YESTERDAY";
        e.positionQty = new BigDecimal(qty);
        e.availableQty = new BigDecimal(qty);
        e.frozenQty = BigDecimal.ZERO;
        e.asOfTime = LocalDateTime.of(2026, 10, 4, 10, 0);
        e.eventSeq = ++eventSeq;
        tradeService.onPositionChange(e);
    }

    private static void assertDecimalEquals(String what, BigDecimal expected, Object actual) {
        assertTrue(actual != null, what + "：期望 " + expected + " 但实际为 null");
        assertEquals(0, expected.compareTo(new BigDecimal(String.valueOf(actual))),
                what + "：期望=" + expected + " 实际=" + actual);
    }

    private static Path resolve(String relative) {
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path.resolve(relative);
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test");
    }
}
