package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmExposureDetailDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmInventoryLedgerDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmProductSpotCategoryDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmProductionPlanDao;
import com.hedgeguard.dao.impl.DmProductionPlanLineDao;
import com.hedgeguard.dao.impl.DmPurchaseContractDao;
import com.hedgeguard.dao.impl.DmSalesContractDao;
import com.hedgeguard.dao.impl.DmSpotChangeLogDao;
import com.hedgeguard.dao.impl.DmSpotPriceDao;
import com.hedgeguard.dao.impl.DmSubAccountDao;
import com.hedgeguard.dao.impl.DmWipRecordDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.Bug006SpotPriceUnitSeed;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.T133SpotGuardSeed;
import com.hedgeguard.dao.testsupport.TransactionAwareInMemory;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.dao.entity.SpotPrice;
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
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BUG-006} 承重验收：现货价「批量粘贴 / CSV 导入」<b>新增行</b>的 {@code price_unit} 必填。
 *
 * <p><b>缺陷面</b>：{@code FR-SPOT-11}/{@code FR-SPOT-12} 的输入契约只含 品种编码 / 日期 / 现货价
 * （{@link SpotService#CSV_HEADER} 三列），而 {@code t_spot_price.price_unit} 为
 * {@code VARCHAR(16) NOT NULL} 且无 DEFAULT；修复前 {@code importSpotPrices} 的新增行支路从不赋
 * {@code priceUnit} ⇒ 落库即被约束拒绝（HTTP 500），导入功能整体不可用（仅同日覆盖支路可用）。
 *
 * <p><b>修复口径</b>：新增行由系统补默认单位
 * {@code price_unit = product.currency + "/" + t_product_unit.spot_unit}（{@code R-PRODUCT-6}）；
 * 按<b>价格日期</b>取当时生效的换算关系，取不到回退按当日取；仍无、或组合超列宽 ⇒ {@code 10001}
 * 定位到行号整批拒绝；<b>覆盖行</b>保留既有 {@code price_unit}（换算关系变更不追溯重算历史）。
 *
 * <p><b>形态（与缺陷单同口径，避免假绿）</b>：真实 {@code Dm*Dao} + {@link InMemoryDmEngine}
 * （{@code m1_*} 真实 DDL，语义等价 DM8 的 NOT NULL 判定）+ {@link TransactionAwareInMemory}。
 * 既有导入用例（{@code SpotT109FixTest} 等）驱动内存假 DAO、不执行任何列约束 ⇒ <b>不能</b>作为本单依据。
 */
class SpotBug006PriceUnitTest {

    private static final List<String> SCRIPTS = List.of(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql");

    /** 北京 2026-09-20 10:00（{@code Clocks.today()} = 2026-09-20）。 */
    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 0);

    private static final int SUB_A = 8;
    private static final long PRODUCT = 5L;
    private static final long PRODUCT_B = 6L;
    private static final long OPERATOR = 999L;

    private InMemoryDmEngine engine;
    private TransactionAwareInMemory aware;
    private DataSourcePool pool;
    private SpotService service;
    private DmSpotPriceDao priceDao;

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String file : SCRIPTS) {
            engine.executeScript(Files.readString(repoRoot().resolve(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB_A, "SUBA", "子账户A");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.product(engine, PRODUCT_B, "AL", "铝");
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware, new PoolConfig(0, 4, 500, 2));
        TxTemplate tx = new TxTemplate(pool);
        priceDao = new DmSpotPriceDao(tx);
        service = new SpotService(tx,
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx),
                new DmProductSpotCategoryDao(tx), new DmWipRecordDao(tx),
                new DmInventoryLedgerDao(tx), priceDao,
                new DmExposureDao(tx), new DmExposureDetailDao(tx), new DmSpotChangeLogDao(tx),
                new DmProductionPlanDao(tx), new DmProductionPlanLineDao(tx),
                new DmSubAccountDao(tx), new DmProductDao(tx),
                uid -> List.of(SUB_A), new DmHedgePlanExposureDao(tx), new DmProductUnitDao(tx));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
        assertEquals(0, aware.pendingCount(), "事务未残留");
        if (pool != null) {
            pool.close();
        }
    }

    // ============================================================ 一、新增行补默认单位

    @Test
    @DisplayName("BUG-006 用例 1：新增行导入成功 ⇒ price_unit = 品种币种/现货计量单位（CNY/TON）")
    void insertedRowGetsDefaultPriceUnit() throws Exception {
        Bug006SpotPriceUnitSeed.productUnit(engine, 1L, PRODUCT, "TON", "LOT", "5",
                "ENABLED", LocalDate.of(2020, 1, 1));

        Map<String, Object> summary = service.importSpotPrices(
                List.of("CU,2026-09-18,4500"), SUB_A, "BATCH_PASTE", null, false, OPERATOR);

        assertEquals(1, summary.get("inserted"), "新增行须真正落库（修复前此处被 NOT NULL 拒绝）");
        SpotPrice stored = priceDao.findGlobalPrice(PRODUCT, LocalDate.of(2026, 9, 18));
        assertNotNull(stored, "落库成功");
        assertEquals("CNY/TON", stored.priceUnit,
                "默认单位 = product.currency(CNY) + \"/\" + t_product_unit.spot_unit(TON)");
        assertEquals(0, new BigDecimal("4500").compareTo(stored.spotPrice), "价格值不受影响");
    }

    @Test
    @DisplayName("BUG-006 用例 2：品种无生效换算关系 ⇒ 10001「品种缺少生效计量单位」，整批 0 行")
    void rejectsWhenProductHasNoEffectiveUnit() throws Exception {
        Bug006SpotPriceUnitSeed.productUnit(engine, 1L, PRODUCT, "TON", "LOT", "5",
                "ENABLED", LocalDate.of(2020, 1, 1));

        BizException e = assertThrows(BizException.class, () -> service.importSpotPrices(
                List.of("AL,2026-09-18,9000"), SUB_A, "BATCH_PASTE", null, false, OPERATOR));

        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(), "须为可判定业务码 10001，不得 500");
        assertEquals(400, e.errorCode().httpStatus());
        assertTrue(e.getMessage().contains("品种缺少生效计量单位"),
                "拒因须点名缺生效计量单位：实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("R-PRODUCT-6"), "须点名 R-PRODUCT-6：实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("第 1 行"), "须定位到行号：实际=" + e.getMessage());
        assertTrue(engine.table("t_spot_price").rows.isEmpty(), "整批不导入 ⇒ zero-write");
    }

    @Test
    @DisplayName("BUG-006 用例 2b：无生效换算关系且价格日期早于首个生效日（回退当日亦无）⇒ 10001")
    void rejectsWhenNeitherPriceDateNorTodayHasEffectiveUnit() throws Exception {
        // 生效日 2026-09-15：价格日期 2026-09-10 取不到 → 回退当日（2026-09-20）取到 ⇒ 不是本用例；
        // 这里品种 B 完全没有换算关系，验「两次都取不到」的拒因。
        BizException e = assertThrows(BizException.class, () -> service.importSpotPrices(
                List.of("AL,2026-09-10,9000"), SUB_A, "BATCH_PASTE", null, false, OPERATOR));

        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("品种缺少生效计量单位"), "实际=" + e.getMessage());
        assertTrue(engine.table("t_spot_price").rows.isEmpty(), "zero-write");
    }

    @Test
    @DisplayName("BUG-006 用例 2c：价格日期早于首个生效日 ⇒ 回退按当日取当前生效换算关系")
    void fallsBackToCurrentEffectiveUnitWhenPriceDatePredatesFirstEffective() throws Exception {
        Bug006SpotPriceUnitSeed.productUnit(engine, 1L, PRODUCT, "TON", "LOT", "5",
                "ENABLED", LocalDate.of(2026, 9, 15));

        Map<String, Object> summary = service.importSpotPrices(
                List.of("CU,2026-09-10,4000"), SUB_A, "BATCH_PASTE", null, false, OPERATOR);

        assertEquals(1, summary.get("inserted"), "历史日期行须可导入（回退当日生效换算关系）");
        assertEquals("CNY/TON",
                priceDao.findGlobalPrice(PRODUCT, LocalDate.of(2026, 9, 10)).priceUnit,
                "回退取值仍须组合为 币种/单位");
    }

    // ============================================================ 二、覆盖行不得被默认单位改写

    @Test
    @DisplayName("BUG-006 用例 3：混合批次（1 新增 + 1 同日覆盖）⇒ 新增行补单位、覆盖行保留原单位")
    void mixedBatchKeepsExistingUnitOnOverwriteRow() throws Exception {
        Bug006SpotPriceUnitSeed.productUnit(engine, 1L, PRODUCT, "TON", "LOT", "5",
                "ENABLED", LocalDate.of(2020, 1, 1));
        // 既有行单位取「非默认值」，便于证明覆盖支路未被默认填充污染（R-PRODUCT-6：不追溯重算历史）
        T133SpotGuardSeed.spotPrice(engine, 1L, SUB_A, PRODUCT, LocalDate.of(2026, 9, 18), "4100",
                "MANUAL", "手工录入", "TON", NOW);

        Map<String, Object> summary = service.importSpotPrices(
                List.of("CU,2026-09-18,4500", "CU,2026-09-19,4600"), SUB_A, "BATCH_PASTE", null,
                true, OPERATOR);

        assertEquals(1, summary.get("inserted"), "1 行新日期 ⇒ 新增");
        assertEquals(1, summary.get("updated"), "1 行同日 ⇒ 覆盖");
        assertEquals("CNY/TON",
                priceDao.findGlobalPrice(PRODUCT, LocalDate.of(2026, 9, 19)).priceUnit,
                "新增行按品种配置补默认单位");
        assertEquals("TON",
                priceDao.findGlobalPrice(PRODUCT, LocalDate.of(2026, 9, 18)).priceUnit,
                "覆盖行保留既有 price_unit，不得被默认单位改写（R-PRODUCT-6）");
    }

    // ============================================================ 三、组合超列宽一律拒绝（不截断）

    @Test
    @DisplayName("BUG-006 用例 4：currency + \"/\" + spot_unit 超 16 字符 ⇒ 10001 明确拒绝（不截断）")
    void rejectsUnitLongerThanColumnWidth() throws Exception {
        // spot_unit = SHEET_PER_ORDER（15 字符，列宽内）；CNY/SHEET_PER_ORDER = 19 > VARCHAR(16)
        Bug006SpotPriceUnitSeed.productUnit(engine, 1L, PRODUCT, "SHEET_PER_ORDER", "LOT", "1",
                "ENABLED", LocalDate.of(2020, 1, 1));

        BizException e = assertThrows(BizException.class, () -> service.importSpotPrices(
                List.of("CU,2026-09-18,4500"), SUB_A, "BATCH_PASTE", null, false, OPERATOR));

        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(), "超列宽须 10001 明确拒绝，不得截断落库");
        assertTrue(e.getMessage().contains("价格单位超长"), "实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("CNY/SHEET_PER_ORDER"),
                "拒因须给出完整组合值（证明未截断）：实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("第 1 行"), "须定位到行号：实际=" + e.getMessage());
        assertTrue(engine.table("t_spot_price").rows.isEmpty(), "zero-write");
    }

    // ============================================================ 四、装配缺位不得静默

    @Test
    @DisplayName("BUG-006 用例 4b：未装配 ProductUnitDao ⇒ 10001 明确拒绝（不静默、不 NPE、不 500）")
    void rejectsWhenUnitDaoNotWired() throws Exception {
        Bug006SpotPriceUnitSeed.productUnit(engine, 1L, PRODUCT, "TON", "LOT", "5",
                "ENABLED", LocalDate.of(2020, 1, 1));
        SpotService notWired = serviceWithoutUnitDao();

        BizException e = assertThrows(BizException.class, () -> notWired.importSpotPrices(
                List.of("CU,2026-09-18,4500"), SUB_A, "BATCH_PASTE", null, false, OPERATOR));

        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(), "装配缺位须可判定业务码，不得 500/NPE");
        assertTrue(e.getMessage().contains("未装配"), "实际=" + e.getMessage());
        assertTrue(engine.table("t_spot_price").rows.isEmpty(), "zero-write");
    }

    /** 旧构造（无 {@code ProductUnitDao}）装配的实例——用于验证「未装配即明确拒绝」。 */
    private SpotService serviceWithoutUnitDao() {
        TxTemplate tx = new TxTemplate(pool);
        return new SpotService(tx,
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx),
                new DmProductSpotCategoryDao(tx), new DmWipRecordDao(tx),
                new DmInventoryLedgerDao(tx), new DmSpotPriceDao(tx),
                new DmExposureDao(tx), new DmExposureDetailDao(tx), new DmSpotChangeLogDao(tx),
                new DmProductionPlanDao(tx), new DmProductionPlanLineDao(tx),
                new DmSubAccountDao(tx), new DmProductDao(tx),
                uid -> List.of(SUB_A), new DmHedgePlanExposureDao(tx));
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("无法定位仓库根（docs/DATA_MODEL.md 未找到）");
    }
}
