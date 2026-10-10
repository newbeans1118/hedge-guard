package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.impl.DmAnnualScaleDao;
import com.hedgeguard.dao.impl.DmLimitUsageStatDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmProductContractDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmPurchaseContractDao;
import com.hedgeguard.dao.impl.DmRiskThresholdDao;
import com.hedgeguard.dao.impl.DmSalesContractDao;
import com.hedgeguard.dao.impl.DmTradePositionDao;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.T066RiskRecomputeSeed;
import com.hedgeguard.service.impl.RiskRecomputeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-066}：规模红线 / 时点净持仓 / 期限红线**复算与统计**——真实 {@code Dm*Dao} +
 * 内存假 JDBC 引擎（{@code NFR-TEST-2}；**禁假 DAO**）。
 *
 * <p>复算口径与 C++ {@code risk_engine}（{@code evalScale}/{@code evalNetPosition}/{@code evalTerm}）
 * 逐条对齐；本类以「唯一预期值」锚定判定与业务码，覆盖 {@code T-066} 验收 1~4。
 */
class T066RiskRecomputeTest {

    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql",
            "docs/DEPLOY/schema/m2_trade.sql",
            "docs/DEPLOY/schema/m2_settle.sql",
            "docs/DEPLOY/schema/m2_fund.sql",
            "docs/DEPLOY/schema/m3_risk.sql");

    private static final int SUB = 1;
    private static final int SUB2 = 2;
    private static final long PRODUCT = 5001L;
    private static final long OPERATOR = 9L;

    private InMemoryDmEngine engine;
    private RiskRecomputeServiceImpl service;
    private DmLimitUsageStatDao limitUsageDao;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        for (String file : SCRIPTS) {
            engine.executeScript(new String(Files.readAllBytes(resolve(file)), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SUB1", "子账户1");
        M2E2eSeed.subAccount(engine, SUB2, "SUB2", "子账户2");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");

        com.hedgeguard.dao.pool.PoolConfig pool =
                new com.hedgeguard.dao.pool.PoolConfig(0, 4, 500, 2);
        com.hedgeguard.dao.tx.TxTemplate tx = new com.hedgeguard.dao.tx.TxTemplate(
                new com.hedgeguard.dao.pool.DataSourcePool(engine, pool));
        limitUsageDao = new DmLimitUsageStatDao(tx);
        service = new RiskRecomputeServiceImpl(tx,
                new DmAnnualScaleDao(tx), new DmExposureDao(tx), new DmTradePositionDao(tx),
                new DmProductContractDao(tx), new DmProductUnitDao(tx), new DmHedgePlanDao(tx),
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx), limitUsageDao,
                new DmRiskThresholdDao(tx),
                userId -> userId == 100L ? Collections.singletonList(SUB) : Collections.emptyList(),
                LimitUsageThresholds.defaults());
    }

    // ================================================================ 规模红线

    @Test
    @DisplayName("规模红线·商品类 90% 触及 ⇒ REJECT（0x1007；R-RISK-4）")
    void scaleCommodityReaches90Rejected() throws Exception {
        T066RiskRecomputeSeed.annualScale(engine, 1, SUB, "SUB_ACCOUNT", PRODUCT, 2026,
                "1000", "900", "COMMODITY", "0.900000");
        RiskRecomputeService.ScaleResult r = service.recomputeScale(SUB, PRODUCT, 2026);
        assertEquals(RiskRecomputeService.REJECT, r.verdict);
        assertEquals("R-RISK-4", r.ruleId);
        assertEquals("0x1007", r.code);
        assertNum("0.9", r.current);
        assertNum("0.9", r.threshold);
    }

    @Test
    @DisplayName("规模红线·贸易类 80% 触及 ⇒ REJECT（0x1008；比例按分类取值，不得混用）")
    void scaleTradeReaches80Rejected() throws Exception {
        T066RiskRecomputeSeed.annualScale(engine, 1, SUB, "SUB_ACCOUNT", PRODUCT, 2026,
                "1000", "800", "TRADE", "0.800000");
        RiskRecomputeService.ScaleResult r = service.recomputeScale(SUB, PRODUCT, 2026);
        assertEquals(RiskRecomputeService.REJECT, r.verdict);
        assertEquals("0x1008", r.code);
        assertNum("0.8", r.current);
        assertNum("0.8", r.threshold);
    }

    @Test
    @DisplayName("规模红线·双算取严：子账户 85% 未越、全账户 90% 越 ⇒ REJECT（Q-1）")
    void scaleDualScopeTakeStricter() throws Exception {
        T066RiskRecomputeSeed.annualScale(engine, 1, SUB, "SUB_ACCOUNT", PRODUCT, 2026,
                "1000", "850", "COMMODITY", "0.900000");
        T066RiskRecomputeSeed.annualScale(engine, 2, 0, "TOTAL_ACCOUNT", PRODUCT, 2026,
                "1000", "900", "COMMODITY", "0.900000");
        RiskRecomputeService.ScaleResult r = service.recomputeScale(SUB, PRODUCT, 2026);
        assertEquals(RiskRecomputeService.REJECT, r.verdict);
        assertNum("0.85", r.subAccountUsageRatio);
        assertNum("0.9", r.totalAccountUsageRatio);
        assertTrue(r.detail.contains("全账户"), r.detail);
    }

    @Test
    @DisplayName("规模红线·分母缺失 ⇒ UNKNOWN（不得静默按 0 通过；DATA_MODEL §2.8 TB-RISK-11）")
    void scaleDenominatorMissingUnjudged() throws Exception {
        T066RiskRecomputeSeed.annualScale(engine, 1, SUB, "SUB_ACCOUNT", PRODUCT, 2026,
                "0", "0", "COMMODITY", "0.900000");
        RiskRecomputeService.ScaleResult r = service.recomputeScale(SUB, PRODUCT, 2026);
        assertEquals(RiskRecomputeService.UNKNOWN, r.verdict);
        assertTrue(r.denominatorMissing);
        assertNull(r.code);
    }

    // ================================================================ 时点净持仓

    @Test
    @DisplayName("时点净持仓 > 实货敞口 ⇒ REJECT（0x1009）；并标注敞口快照时点（Q-3）")
    void netPositionOverExposureRejected() throws Exception {
        seedConversion("10", "1");
        T066RiskRecomputeSeed.position(engine, 1, SUB, "CU2606", PRODUCT, "LONG", "100");
        LocalDateTime snapshot = LocalDateTime.of(2026, 10, 7, 9, 30);
        T066RiskRecomputeSeed.exposure(engine, 1, SUB, PRODUCT, "BUY", "500", snapshot);
        RiskRecomputeService.NetPositionResult r = service.recomputeNetPosition(SUB, PRODUCT);
        assertEquals(RiskRecomputeService.REJECT, r.verdict);
        assertEquals("0x1009", r.code);
        assertNum("1000", r.current);
        assertNum("500", r.threshold);
        assertEquals(snapshot, r.exposureSnapshotTime);
    }

    @Test
    @DisplayName("时点净持仓·等值不越限（严格大于才阻断，对齐 C++ ratioExceeds 的 >）")
    void netPositionEqualToExposurePasses() throws Exception {
        seedConversion("10", "1");
        T066RiskRecomputeSeed.position(engine, 1, SUB, "CU2606", PRODUCT, "LONG", "50");
        T066RiskRecomputeSeed.exposure(engine, 1, SUB, PRODUCT, "BUY", "500",
                LocalDateTime.of(2026, 10, 7, 9, 30));
        RiskRecomputeService.NetPositionResult r = service.recomputeNetPosition(SUB, PRODUCT);
        assertEquals(RiskRecomputeService.PASS, r.verdict);
        assertNum("500", r.netPosition);
    }

    @Test
    @DisplayName("时点净持仓·减仓方向不受限：净多 1000 vs 卖出敞口 2000 ⇒ PASS（R-RISK-5）")
    void netPositionCloseDirectionExempt() throws Exception {
        seedConversion("10", "1");
        T066RiskRecomputeSeed.position(engine, 1, SUB, "CU2606", PRODUCT, "LONG", "100");
        T066RiskRecomputeSeed.exposure(engine, 1, SUB, PRODUCT, "SELL", "2000",
                LocalDateTime.of(2026, 10, 7, 9, 30));
        RiskRecomputeService.NetPositionResult r = service.recomputeNetPosition(SUB, PRODUCT);
        assertEquals(RiskRecomputeService.PASS, r.verdict);
        assertNum("1000", r.netPosition);
    }

    @Test
    @DisplayName("时点净持仓·换算缺失 ⇒ UNKNOWN（不得按 1:1 降级；R-PRODUCT-6）")
    void netPositionConversionMissingUnjudged() throws Exception {
        T066RiskRecomputeSeed.position(engine, 1, SUB, "CU2606", PRODUCT, "LONG", "100");
        RiskRecomputeService.NetPositionResult r = service.recomputeNetPosition(SUB, PRODUCT);
        assertEquals(RiskRecomputeService.UNKNOWN, r.verdict);
        assertTrue(r.conversionMissing);
    }

    // ================================================================ 期限红线

    @Test
    @DisplayName("期限红线·超「建仓日+12 月」⇒ REJECT（0x100A；R-RISK-6）")
    void termExceeds12MonthsRejected() throws Exception {
        T066RiskRecomputeSeed.hedgePlan(engine, 1, SUB, PRODUCT, "P1", "SELL_HEDGE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 1), "APPROVED", null);
        RiskRecomputeService.TermResult r =
                service.recomputeTerm(1, LocalDate.of(2026, 2, 1));
        assertEquals(RiskRecomputeService.REJECT, r.verdict);
        assertEquals("0x100A", r.code);
        assertEquals("OPEN_PLUS_MONTHS", r.basis);
        assertEquals(LocalDate.of(2027, 1, 1), r.allowedEnd);
    }

    @Test
    @DisplayName("期限红线·合同到期+30 天为较早者 ⇒ 上限取合同侧（Q-4 取较早者）")
    void termContractGraceEarlier() throws Exception {
        T066RiskRecomputeSeed.salesContract(engine, 1, SUB, PRODUCT, "S1",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 1));
        T066RiskRecomputeSeed.hedgePlan(engine, 1, SUB, PRODUCT, "P1", "BUY_HEDGE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 15), "APPROVED", null);
        RiskRecomputeService.TermResult r =
                service.recomputeTerm(1, LocalDate.of(2026, 2, 1));
        assertEquals(RiskRecomputeService.REJECT, r.verdict);
        assertEquals("CONTRACT_PLUS_GRACE", r.basis);
        assertEquals(LocalDate.of(2026, 12, 1), r.allowedEnd);
    }

    @Test
    @DisplayName("期限红线·剩余 ≤ 30 天 ⇒ WARN（告警不阻断；三级处置第二级）")
    void termWarnWithinGrace() throws Exception {
        T066RiskRecomputeSeed.hedgePlan(engine, 1, SUB, PRODUCT, "P1", "SELL_HEDGE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 20), "APPROVED", null);
        RiskRecomputeService.TermResult r =
                service.recomputeTerm(1, LocalDate.of(2026, 12, 15));
        assertEquals(RiskRecomputeService.WARN, r.verdict);
        assertEquals(17L, r.remainingDays);
        assertNull(r.code);
    }

    @Test
    @DisplayName("期限红线·展期已审批 ⇒ 不阻断（不阻断平仓与展期置换；FR-RISK-6 验收 3）")
    void termExtensionApprovedNotBlocked() throws Exception {
        T066RiskRecomputeSeed.hedgePlan(engine, 1, SUB, PRODUCT, "P1", "SELL_HEDGE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 1), "APPROVED", "实货合同展期依据");
        RiskRecomputeService.TermResult r =
                service.recomputeTerm(1, LocalDate.of(2026, 2, 1));
        assertEquals(RiskRecomputeService.PASS, r.verdict);
        assertTrue(r.extensionApproved);
        assertNull(r.code);
    }

    // ================================================================ 统计落库 + 查询

    @Test
    @DisplayName("复算并落库：SCALE_REDLINE（含全账户哨兵行）+ POSITION_LIMIT，幂等重跑不翻倍")
    void recomputePersistsStatisticsIdempotently() throws Exception {
        T066RiskRecomputeSeed.annualScale(engine, 1, SUB, "SUB_ACCOUNT", PRODUCT, 2026,
                "1000", "900", "COMMODITY", "0.900000");
        T066RiskRecomputeSeed.annualScale(engine, 2, 0, "TOTAL_ACCOUNT", PRODUCT, 2026,
                "1000", "500", "COMMODITY", "0.900000");
        seedConversion("10", "1");
        T066RiskRecomputeSeed.position(engine, 1, SUB, "CU2606", PRODUCT, "LONG", "100");
        T066RiskRecomputeSeed.exposure(engine, 1, SUB, PRODUCT, "BUY", "500",
                LocalDateTime.of(2026, 10, 7, 9, 30));

        LocalDate date = LocalDate.of(2026, 10, 7);
        RiskRecomputeService.RecomputeSummary s = service.recomputeAndPersist(date, OPERATOR);
        assertEquals(2, s.scaleRows, "子账户 + 全账户两行");
        assertEquals(1, s.scaleBreachCount);
        assertEquals(1, s.positionRows);
        assertEquals(1, s.positionBreachCount);

        assertEquals(3, engine.table("t_limit_usage_stat").rows.size());
        Map<String, Object> subRow = statRow(SUB, "SCALE_REDLINE");
        assertEquals("900.0000", String.valueOf(subRow.get("limit_value")));
        assertEquals("1.000000", String.valueOf(subRow.get("usage_ratio")));
        assertEquals("EXCEEDED", subRow.get("hint_level"));
        Map<String, Object> totalRow = statRow(0, "SCALE_REDLINE");
        assertEquals("900.0000", String.valueOf(totalRow.get("limit_value")));
        Map<String, Object> posRow = statRow(SUB, "POSITION_LIMIT");
        assertEquals("500.0000", String.valueOf(posRow.get("limit_value")));
        assertEquals("1000.0000", String.valueOf(posRow.get("used_value")));

        // 幂等：重跑不再插入（同 stat_date + 子账户 + 品种 + 类型）
        RiskRecomputeService.RecomputeSummary s2 = service.recomputeAndPersist(date, OPERATOR);
        assertEquals(0, s2.scaleRows);
        assertEquals(0, s2.positionRows);
        assertEquals(3, engine.table("t_limit_usage_stat").rows.size());
    }

    @Test
    @DisplayName("统计查询·读取面按可见子账户隔离（空/受限 scope 不越权）")
    void queryUsageIsolation() throws Exception {
        T066RiskRecomputeSeed.annualScale(engine, 1, SUB, "SUB_ACCOUNT", PRODUCT, 2026,
                "1000", "900", "COMMODITY", "0.900000");
        T066RiskRecomputeSeed.annualScale(engine, 2, SUB2, "SUB_ACCOUNT", PRODUCT, 2026,
                "1000", "900", "COMMODITY", "0.900000");
        LocalDate date = LocalDate.of(2026, 10, 7);
        service.recomputeAndPersist(date, OPERATOR);

        RiskRecomputeService.UsageQuery q = new RiskRecomputeService.UsageQuery();
        q.statDate = date;
        PageQuery page = PageQuery.of("1", "100", "id,asc", "id");
        // 可见集 = {SUB} ⇒ 只返回 SUB 行
        RiskRecomputeService.UsagePage scoped = service.queryUsage(q, page, 100L);
        assertEquals(1, scoped.total);
        assertEquals(SUB, scoped.items.get(0).subAccountId);
        // 可见集 = ∅（其它用户）⇒ 恒假，返回空
        RiskRecomputeService.UsagePage none = service.queryUsage(q, page, 999L);
        assertEquals(0, none.total);
    }

    // ================================================================ 工具

    private void seedConversion(String multiplier, String factor) throws Exception {
        T066RiskRecomputeSeed.productContract(engine, 1, PRODUCT, "CU2606", multiplier,
                LocalDate.of(2027, 6, 30));
        T066RiskRecomputeSeed.productUnit(engine, 1, PRODUCT, factor, LocalDate.of(2026, 1, 1));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statRow(int subAccountId, String limitType) {
        for (Map<String, Object> row : engine.table("t_limit_usage_stat").rows) {
            if (Integer.valueOf(subAccountId).equals(row.get("sub_account_id"))
                    && limitType.equals(row.get("limit_type"))) {
                return row;
            }
        }
        throw new AssertionError("未找到统计行 sub=" + subAccountId + " type=" + limitType);
    }

    private static void assertNum(String expected, Object actual) {
        assertTrue(actual != null, "期望 " + expected + " 但实际为 null");
        assertEquals(0, new java.math.BigDecimal(expected)
                        .compareTo(new java.math.BigDecimal(String.valueOf(actual))),
                "数值不符：期望=" + expected + " 实际=" + actual);
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
