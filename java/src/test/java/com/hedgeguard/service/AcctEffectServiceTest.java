package com.hedgeguard.service;

import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.DualPnlReport;
import com.hedgeguard.dao.entity.HedgeEffect;
import com.hedgeguard.dao.impl.DmAccountSubjectDao;
import com.hedgeguard.dao.impl.DmAccountingPeriodDao;
import com.hedgeguard.dao.impl.DmDualPnlReportDao;
import com.hedgeguard.dao.impl.DmEffectivenessAssessDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgeDocDao;
import com.hedgeguard.dao.impl.DmHedgeEffectDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmHedgeRelationDao;
import com.hedgeguard.dao.impl.DmPeriodCloseLogDao;
import com.hedgeguard.dao.impl.DmRebalanceRecordDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmSettlePnlDao;
import com.hedgeguard.dao.impl.DmSpotPriceDao;
import com.hedgeguard.dao.impl.DmVoucherDao;
import com.hedgeguard.dao.impl.DmVoucherLineDao;
import com.hedgeguard.dao.impl.DmVoucherReviewDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.T077AcctSeed;
import com.hedgeguard.dao.testsupport.T080AcctEffectSeed;
import com.hedgeguard.dao.testsupport.TransactionAwareInMemory;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-080} 套保效果综合评估与双侧盈亏上报单测（真实 DAO + {@code InMemoryDmEngine}
 * 等价执行；事务感知包装 {@code TransactionAwareInMemory} + {@code pendingCount}
 * {@code @AfterEach} + {@code Clocks.set} 固定时钟——全纪律三件套）。
 *
 * <p><b>覆盖</b>：FR-ACCT-5 验收 1（同屏双栏）/2（衍生品已实现-未实现区分）/3（缺价三态
 * price_as_of 标注）/4（子账户切换 + 汇总=明细之和）；FR-ACCT-10 验收 1（两栏同上报）/
 * 2（缺价保留栏位+标注）/3（口径与 FR-ACCT-5 一致）/4（上报留痕）；
 * R-ACCT-4 17 号文红线；R-ACCT-9 Q-12；R-ACCT-12 口径单一（幂等）；取号形态；
 * 写面隔离（T-115）；汇总上报写面门禁 + 归集。
 *
 * <p>播种件：{@link T080AcctEffectSeed}（SQL 集中于 {@code dao} 命名空间，E5 纪律）。
 */
class AcctEffectServiceTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m4_acct.sql";

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TransactionAwareInMemory aware;
    private TxTemplate tx;
    private AcctService svc;
    private DmHedgeEffectDao effectDao;
    private DmDualPnlReportDao reportDao;

    private List<Integer> visible = List.of(1);

    @BeforeEach
    void setUp() throws Exception {
        com.hedgeguard.common.Clocks.set(
                Clock.fixed(java.time.LocalDate.of(2026, 10, 13)
                                .atStartOfDay(com.hedgeguard.common.Clocks.ZONE).toInstant(),
                        com.hedgeguard.common.Clocks.ZONE));
        engine = new InMemoryDmEngine();
        engine.executeScript(Files.readString(repoRoot().resolve(SCRIPT), StandardCharsets.UTF_8));
        T077AcctSeed.createParentTables(engine);
        T077AcctSeed.seedChains(engine);
        T080AcctEffectSeed.createEffectTables(engine);
        T080AcctEffectSeed.seedEffectChains(engine);
        // TE-T077-001：事务感知包装（引擎裸连接 rollback=no-op ⇒ 拒绝路径残留半笔写假绿）
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware, new PoolConfig(0, 4, 500, 2));
        tx = new TxTemplate(pool);
        effectDao = new DmHedgeEffectDao(tx);
        reportDao = new DmDualPnlReportDao(tx);
        svc = new AcctService(tx,
                (uid) -> visible,
                (key) -> null,
                new DmHedgePlanDao(tx), new DmHedgeDocDao(tx),
                new DmHedgeRelationDao(tx), new DmEffectivenessAssessDao(tx),
                new DmRebalanceRecordDao(tx), new DmExposureDao(tx),
                new DmVoucherDao(tx), new DmVoucherLineDao(tx), new DmVoucherReviewDao(tx),
                new DmAccountingPeriodDao(tx), new DmPeriodCloseLogDao(tx),
                new DmSettleCycleDao(tx), new DmAccountSubjectDao(tx),
                effectDao, reportDao, new DmHedgePlanExposureDao(tx),
                new DmSettlePnlDao(tx), new DmSpotPriceDao(tx));
    }

    @AfterEach
    void tearDown() {
        com.hedgeguard.common.Clocks.reset();
        assertEquals(0, aware.pendingCount(),
                "事务未残留（te-verify-t077d O-1：pendingCount 归零自证）");
        if (pool != null) {
            pool.close();
        }
    }

    // ================================================================== FR-ACCT-5

    @Test
    @DisplayName("FR-ACCT-5①同屏双栏：视图恒含实货 spotPnl* 与衍生品 derivativePnl* 两组成对字段")
    void sameScreenBothPanels() {
        svc.evaluateEffect(1, "2026-10", 7L);

        Map<String, Object> view = svc.getEffect(1, "2026-10", 7L);
        assertNotNull(view.get("summary"), "子账户级汇总行");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) view.get("details");
        assertEquals(2, details.size(), "子账户 1 两条 BOUND 明细");

        for (Map<String, Object> row : details) {
            assertTrue(row.containsKey("spotPnlRealized") && row.containsKey("spotPnlUnrealized")
                    && row.containsKey("spotPnlTotal"), "实货栏三件恒在（R-ACCT-4 不省略）");
            assertTrue(row.containsKey("derivativePnlRealized")
                    && row.containsKey("derivativePnlUnrealized")
                    && row.containsKey("derivativePnlTotal"), "衍生品栏三件恒在");
            assertTrue(row.containsKey("netEffect") && row.containsKey("unhedgedQty")
                    && row.containsKey("unhedgedAmount"), "净效果与未对冲部分随行");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) view.get("summary");
        assertEquals("N", summary.get("nonCurrentPriceFlag"), "汇总行当日价标注 N");
        assertNotNull(summary.get("spotPnlTotal"), "汇总行实货合计随行");
        assertNotNull(summary.get("derivativePnlTotal"), "汇总行衍生品合计随行");
    }

    @Test
    @DisplayName("FR-ACCT-5②：衍生品盈亏区分已实现（CLOSE_REALIZED）与未实现（FLOAT_UNREALIZED）")
    void derivativeRealizedVsUnrealized() {
        HedgeEffect summary = svc.evaluateEffect(1, "2026-10", 7L);

        // 计划 11 = -8000/-250；计划 13 = -2000/-100；子账户汇总 = -10000/-350
        assertEquals(0, new BigDecimal("-10000.0000").compareTo(summary.derivativePnlRealized),
                "已实现 = CLOSE_REALIZED 求和");
        assertEquals(0, new BigDecimal("-350.0000").compareTo(summary.derivativePnlUnrealized),
                "未实现 = FLOAT_UNREALIZED 求和（含期权，R-ACCT-7）");
        assertEquals(0, new BigDecimal("-10350.0000").compareTo(summary.derivativePnlTotal));
    }

    @Test
    @DisplayName("FR-ACCT-5③三态①：当日价 ⇒ nonCurrentPriceFlag='N'、priceAsOf=null")
    void priceStateCurrent() {
        HedgeEffect summary = svc.evaluateEffect(1, "2026-10", 7L);

        assertEquals("N", summary.spotPriceMissing, "有价：不缺失");
        assertEquals("N", summary.nonCurrentPriceFlag, "当日价");
        assertNull(summary.priceAsOf, "当日价不标注价格时点");
        assertNull(summary.missingDesc);
        // 实货盈亏 = 敞口量 × 当日价（子账户 1：100×110 + 50×110 = 16500）
        assertEquals(0, new BigDecimal("16500.0000").compareTo(summary.spotPnlTotal));
    }

    @Test
    @DisplayName("FR-ACCT-5③三态②：仅历史价 ⇒ 按最近一期计算 + price_as_of 标注 + 非当日价")
    void priceStateCarryForwardLabeled() {
        T080AcctEffectSeed.onlyHistoricalPrice(engine);

        HedgeEffect summary = svc.evaluateEffect(1, "2026-10", 7L);

        assertEquals("N", summary.spotPriceMissing, "有历史价：非缺失");
        assertEquals("Y", summary.nonCurrentPriceFlag, "非当日价标注");
        assertEquals(java.time.LocalDate.of(2026, 10, 20), summary.priceAsOf, "price_as_of 必带");
        assertNotNull(summary.missingDesc, "须显式标注沿用到期时点");
        // 100×105 + 50×105 = 15750
        assertEquals(0, new BigDecimal("15750.0000").compareTo(summary.spotPnlTotal));
    }

    @Test
    @DisplayName("FR-ACCT-5③三态③：无任何历史价 ⇒ 实货列 NULL + 缺失原因，栏位不省略；衍生品照常")
    void priceStateMissingKeepsPanels() {
        T080AcctEffectSeed.noPriceAtAll(engine);

        HedgeEffect summary = svc.evaluateEffect(1, "2026-10", 7L);

        assertEquals("Y", summary.spotPriceMissing, "无任何历史价 ⇒ 缺失标记");
        assertNull(summary.spotPnlTotal, "实货列 NULL（不得以 0 冒充）");
        assertNotNull(summary.missingDesc, "缺失原因必填（R-ACCT-4 保留栏位并标注）");
        // 关键：衍生品一侧照常计算（R-ACCT-4「禁止只报一侧」）
        assertNotNull(summary.derivativePnlTotal, "另一侧不得省略");
        assertEquals(0, new BigDecimal("-10350.0000").compareTo(summary.derivativePnlTotal));
    }

    @Test
    @DisplayName("FR-ACCT-5④：汇总 = 明细之和（两条明细行的实货/衍生品/净效果分别求和）")
    void summaryEqualsSumOfDetails() {
        svc.evaluateEffect(1, "2026-10", 7L);

        List<HedgeEffect> rows = effectDao.findBySubAccountAndPeriod(1, "2026-10");
        HedgeEffect summary = null;
        BigDecimal spot = BigDecimal.ZERO;
        BigDecimal deriv = BigDecimal.ZERO;
        int detailCount = 0;
        for (HedgeEffect r : rows) {
            if (r.hedgePlanId == null) {
                summary = r;
            } else {
                detailCount++;
                spot = spot.add(r.spotPnlTotal);
                deriv = deriv.add(r.derivativePnlTotal);
            }
        }
        assertEquals(2, detailCount, "两条明细");
        assertNotNull(summary, "汇总行 hedge_plan_id=NULL");
        assertEquals(0, summary.spotPnlTotal.compareTo(spot), "汇总实货 = Σ明细实货");
        assertEquals(0, summary.derivativePnlTotal.compareTo(deriv), "汇总衍生品 = Σ明细衍生品");
        assertEquals(0, summary.netEffect.compareTo(summary.spotPnlTotal.add(summary.derivativePnlTotal)),
                "净效果 = 两侧合计");
    }

    @Test
    @DisplayName("R-ACCT-12 口径单一：重复计算幂等（同键覆盖更新，不产生重复行）")
    void evaluateIsIdempotent() {
        svc.evaluateEffect(1, "2026-10", 7L);
        svc.evaluateEffect(1, "2026-10", 7L);

        List<HedgeEffect> rows = effectDao.findBySubAccountAndPeriod(1, "2026-10");
        assertEquals(3, rows.size(), "2 明细 + 1 汇总（重复计算不增行；汇总行查找须含 IS NULL 语义）");
    }

    // ================================================================== FR-ACCT-10

    @Test
    @DisplayName("FR-ACCT-10①②④：上报两栏恒在 + 留痕三件 + 口径与 FR-ACCT-5 一致")
    void reportBothColumnsAndTrace() {
        HedgeEffect summary = svc.evaluateEffect(1, "2026-10", 7L);

        AcctService.DualPnlReportRequest in = new AcctService.DualPnlReportRequest();
        in.subAccountId = 1;
        in.reportPeriod = "2026-10";
        in.reportType = AcctService.REPORT_TYPE_MONTHLY;
        DualPnlReport r = svc.submitDualPnlReport(in, 7L);

        assertEquals("Y", r.spotPnlColumnPresent, "实货栏恒在（验收 1/2；DB CHECK 结构保证）");
        assertEquals(0, summary.spotPnlTotal.compareTo(r.spotPnlValue),
                "实货口径与效果评估一致（验收 3 / R-ACCT-12）");
        assertEquals(0, summary.derivativePnlTotal.compareTo(r.derivativePnlValue),
                "衍生品口径与效果评估一致");
        assertEquals(0, new BigDecimal("-10000.0000").compareTo(r.derivativePnlRealized),
                "上报衍生品已实现 = 子账户聚合 CLOSE_REALIZED（验收 3 / R-ACCT-12）");
        assertEquals(0, new BigDecimal("-350.0000").compareTo(r.derivativePnlUnrealized));
        // 留痕三件（验收 4）
        assertEquals(7L, r.submittedBy, "上报人");
        assertNotNull(r.submittedTime, "上报时间");
        assertEquals("2026-10", r.reportPeriod, "报告期间");
        assertEquals("Y", r.pendingExternalReview, "R-ACCT-10 恒标注");
        // 取号形态 DR<yyyyMMdd><seq4>（Clocks 固定 2026-10-13）
        assertEquals("DR202610130001", r.reportNo);
    }

    @Test
    @DisplayName("FR-ACCT-10②：仅历史价 ⇒ 保留实货栏 + 非当日价说明（不省略该栏）")
    void reportKeepsSpotColumnWhenCarryForward() {
        T080AcctEffectSeed.onlyHistoricalPrice(engine);
        svc.evaluateEffect(1, "2026-10", 7L);

        DualPnlReport r = svc.submitDualPnlReport(reportReq(1, "2026-10"), 7L);

        assertEquals("Y", r.spotPnlColumnPresent, "栏位保留");
        assertNotNull(r.spotPnlValue, "沿用最近一期价填值");
        assertEquals(0, new BigDecimal("15750.0000").compareTo(r.spotPnlValue));
        assertTrue(r.dataCompletenessNote.contains("非当日价"),
                "须标注非当日价：" + r.dataCompletenessNote);
    }

    @Test
    @DisplayName("FR-ACCT-10②：无任何历史价 ⇒ 保留实货栏 + 值 NULL + 缺失原因（不得只报一侧）")
    void reportKeepsSpotColumnWhenMissing() {
        T080AcctEffectSeed.noPriceAtAll(engine);
        svc.evaluateEffect(1, "2026-10", 7L);

        DualPnlReport r = svc.submitDualPnlReport(reportReq(1, "2026-10"), 7L);

        assertEquals("Y", r.spotPnlColumnPresent, "栏位保留（不得省略）");
        assertNull(r.spotPnlValue, "实货无法计算 ⇒ 值 NULL");
        assertNotNull(r.spotPnlMissingReason, "缺失原因必填");
        assertNotNull(r.derivativePnlValue, "衍生品一侧照常上报（禁止只报一侧）");
    }

    @Test
    @DisplayName("FR-ACCT-10③：未先评估 ⇒ 10015 拒绝（口径须与 FR-ACCT-5 一致）")
    void reportBeforeEvaluateRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> svc.submitDualPnlReport(reportReq(1, "2026-10"), 7L));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, ex.errorCode());
        assertEquals(0, reportDao.findBySubAccountAndPeriod(1, "2026-10").size(),
                "拒绝不留半笔写");
    }

    @Test
    @DisplayName("FR-ACCT-10：reportType 非法取值 ⇒ 10006 参数错误")
    void reportTypeValidated() {
        svc.evaluateEffect(1, "2026-10", 7L);
        AcctService.DualPnlReportRequest in = reportReq(1, "2026-10");
        in.reportType = "WEEKLY";
        BizException ex = assertThrows(BizException.class,
                () -> svc.submitDualPnlReport(in, 7L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());
    }

    @Test
    @DisplayName("FR-ACCT-10 取号：DR 前缀当日前缀 max+1（两次上报 ⇒ …0001 / …0002）")
    void reportNoIncrements() {
        svc.evaluateEffect(1, "2026-10", 7L);
        DualPnlReport r1 = svc.submitDualPnlReport(reportReq(1, "2026-10"), 7L);
        DualPnlReport r2 = svc.submitDualPnlReport(reportReq(1, "2026-10"), 7L);
        assertEquals("DR202610130001", r1.reportNo);
        assertEquals("DR202610130002", r2.reportNo);
    }

    @Test
    @DisplayName("FR-ACCT-10 汇总上报：按限写子账户集聚合（两栏 + 勾稽）")
    void summaryReportAggregates() {
        visible = List.of(1, 2);
        svc.evaluateEffect(1, "2026-10", 7L);
        svc.evaluateEffect(2, "2026-10", 7L);

        AcctService.DualPnlReportRequest in = new AcctService.DualPnlReportRequest();
        in.reportPeriod = "2026-10";
        in.reportType = AcctService.REPORT_TYPE_QUARTERLY;
        DualPnlReport r = svc.submitDualPnlReport(in, 7L);

        assertNull(r.subAccountId, "汇总上报 sub_account_id=NULL");
        assertEquals("Y", r.spotPnlColumnPresent);
        // 子账户 1：实货 16500 / 衍生品 -10350；子账户 2：实货 11000 / 衍生品 -4100
        assertEquals(0, new BigDecimal("27500.0000").compareTo(r.spotPnlValue));
        assertEquals(0, new BigDecimal("-14450.0000").compareTo(r.derivativePnlValue));
        assertEquals(0, new BigDecimal("13050.0000").compareTo(r.netEffect));
    }

    // ================================================================== 隔离与门禁

    @Test
    @DisplayName("写面隔离：跨子账户只读（哨兵 0 唯一授权）不得计算效果（T-115）")
    void writePathRejectsCrossReadonly() {
        visible = List.of(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        BizException ex = assertThrows(BizException.class,
                () -> svc.evaluateEffect(1, "2026-10", 7L));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, ex.errorCode());
        assertEquals(0, effectDao.findBySubAccountAndPeriod(1, "2026-10").size());
    }

    @Test
    @DisplayName("汇总上报写面门禁：跨子账户只读（哨兵 0）不得提交汇总上报（T-115）")
    void summaryReportRejectsCrossReadonly() {
        visible = List.of(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        AcctService.DualPnlReportRequest in = new AcctService.DualPnlReportRequest();
        in.reportPeriod = "2026-10";
        in.reportType = AcctService.REPORT_TYPE_MONTHLY;
        BizException ex = assertThrows(BizException.class,
                () -> svc.submitDualPnlReport(in, 7L));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, ex.errorCode());
    }

    @Test
    @DisplayName("读面隔离：不可见子账户的效果查询被拒（哨兵 0 跨账户只读放行）")
    void readPathScopeEnforced() {
        visible = List.of(2);
        BizException ex = assertThrows(BizException.class,
                () -> svc.getEffect(1, "2026-10", 7L));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, ex.errorCode());
    }

    @Test
    @DisplayName("上报记录查询：按子账户+期间过滤 + 期间缺失明确报参数错误")
    void listReportsValidation() {
        svc.evaluateEffect(1, "2026-10", 7L);
        svc.submitDualPnlReport(reportReq(1, "2026-10"), 7L);
        assertEquals(1, svc.listDualPnlReports("2026-10", null, 1, 7L).size(),
                "按子账户+期间可查（idx_dual_pnl_sub_period）");
        BizException ex = assertThrows(BizException.class,
                () -> svc.listDualPnlReports(null, null, 1, 7L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode(), "期间缺失须报参数错误");
    }

    // ================================================================== 夹具

    private static AcctService.DualPnlReportRequest reportReq(Integer subAccountId, String period) {
        AcctService.DualPnlReportRequest in = new AcctService.DualPnlReportRequest();
        in.subAccountId = subAccountId;
        in.reportPeriod = period;
        in.reportType = AcctService.REPORT_TYPE_MONTHLY;
        return in;
    }

    private static Path repoRoot() {
        Path path = java.nio.file.Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (java.nio.file.Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test");
    }
}
