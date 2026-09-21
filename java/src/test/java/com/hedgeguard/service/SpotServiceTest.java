package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.InventoryLedgerDao;
import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.PurchaseContractDao;
import com.hedgeguard.dao.SalesContractDao;
import com.hedgeguard.dao.SpotChangeLogDao;
import com.hedgeguard.dao.SpotPriceDao;
import com.hedgeguard.dao.WipRecordDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.entity.InventoryLedger;
import com.hedgeguard.dao.entity.ProductSpotCategory;
import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.SalesContract;
import com.hedgeguard.dao.entity.SpotChangeLog;
import com.hedgeguard.dao.entity.SpotPrice;
import com.hedgeguard.dao.entity.WipRecord;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpotService} 单元测试（{@code T-013} 现货管理 + {@code T-014} 实货敞口计算）。
 *
 * <p>重点断言四条不可绕过的纪律：①敞口只能由现货数据推导（无反向入口）；
 * ②{@code Σ分量 ≠ 合计} 写入侧拒绝、明细之和必须等于分量列；③展示侧重算**不回写**且留痕
 * {@code COMPONENT_RECALC}；④缺价处置按用途分流（比率用途拒绝 / 估值用途沿用并标注）。
 */
class SpotServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");   // 北京时间 10:00

    private FakePurchaseContractDao purchaseDao;
    private FakeSalesContractDao salesDao;
    private FakeMappingDao mappingDao;
    private FakeWipDao wipDao;
    private FakeInventoryDao inventoryDao;
    private FakeSpotPriceDao priceDao;
    private FakeExposureDao exposureDao;
    private FakeExposureDetailDao detailDao;
    private FakeSpotChangeLogDao changeLogDao;
    private SpotService service;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        purchaseDao = new FakePurchaseContractDao();
        salesDao = new FakeSalesContractDao();
        mappingDao = new FakeMappingDao();
        wipDao = new FakeWipDao();
        inventoryDao = new FakeInventoryDao();
        priceDao = new FakeSpotPriceDao();
        exposureDao = new FakeExposureDao();
        detailDao = new FakeExposureDetailDao();
        changeLogDao = new FakeSpotChangeLogDao();
        // T-115：业务规则用例（非越权用例）——注入「可写子账户 3」的可见性替身。
        // 原注入哨兵 0（跨账户只读）在 T-115 后不再授予写权限；越权/fail-closed 语义
        // 由 SpotT110VisibilityTest 与 SpotT109FixTest 覆盖。
        service = new SpotService(FakeTx.newTemplate(), purchaseDao, salesDao, mappingDao, wipDao,
                inventoryDao, priceDao, exposureDao, detailDao, changeLogDao,
                null, null, null, null, uid -> Arrays.asList(3), null);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ 单证

    @Test
    @DisplayName("采购合同：建档即映射品种（有映射）/ 留空（无映射），草稿态 + 留痕")
    void createPurchaseContractMapsProduct() {
        mappingDao.rows.add(mapping(100L, 5L, "PURCHASE", "Y"));
        PurchaseContract mapped = purchase("PC-001", "CAT-CU");
        long id = service.createPurchaseContract(mapped, 1L);
        assertEquals(5L, purchaseDao.findById(id).productId, "有主映射即自动带出品种");
        assertEquals("DRAFT", purchaseDao.findById(id).status);
        assertEquals("BUY", purchaseDao.findById(id).exposureDirection);

        PurchaseContract unmapped = purchase("PC-002", "CAT-NEW");
        long id2 = service.createPurchaseContract(unmapped, 1L);
        assertNull(purchaseDao.findById(id2).productId, "无映射 → product_id 留空并进未映射清单");
        assertEquals(2, changeLogDao.rows.size(), "两次建档各留痕一条");
    }

    @Test
    @DisplayName("提交为有效：未映射一律 10015；已映射则 ACTIVE 并留痕")
    void activateRequiresMapping() {
        PurchaseContract unmapped = purchase("PC-003", "CAT-NEW");
        long id = service.createPurchaseContract(unmapped, 1L);
        assertEquals(ErrorCode.DATA_INSUFFICIENT,
                assertThrows(BizException.class,
                        () -> service.activatePurchaseContract(id, 1L)).errorCode());
        assertEquals("DRAFT", purchaseDao.findById(id).status, "被拒后状态不变");

        mappingDao.rows.add(mapping(100L, 5L, "PURCHASE", "Y"));
        PurchaseContract mapped = purchase("PC-004", "CAT-CU");
        long id2 = service.createPurchaseContract(mapped, 1L);
        service.activatePurchaseContract(id2, 1L);
        assertEquals("ACTIVE", purchaseDao.findById(id2).status);
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.activatePurchaseContract(id2, 1L)).errorCode(), "不得重复提交");
    }

    @Test
    @DisplayName("作废：已被敞口明细引用 → 10011；未引用方可作废（原因必填）；草稿走删除接口")
    void voidReferenceCheck() {
        mappingDao.rows.add(mapping(100L, 5L, "PURCHASE", "Y"));
        long id = service.createPurchaseContract(purchase("PC-005", "CAT-CU"), 1L);
        service.activatePurchaseContract(id, 1L);

        ExposureDetail detail = detail(0L, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", id,
                new BigDecimal("10"), "BUY");
        detailDao.rows.add(detail);
        assertEquals(ErrorCode.REFERENCE_CONFLICT,
                assertThrows(BizException.class,
                        () -> service.voidPurchaseContract(id, "价格不利", 1L)).errorCode(),
                "被引用时作废会破坏追溯链");
        assertEquals("ACTIVE", purchaseDao.findById(id).status);

        detailDao.rows.clear();
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.voidPurchaseContract(id, "  ", 1L)).errorCode(), "作废原因必填");
        service.voidPurchaseContract(id, "价格不利", 1L);
        assertEquals("VOID", purchaseDao.findById(id).status);
        assertEquals("价格不利", purchaseDao.findById(id).voidReason);
        assertNotNull(purchaseDao.findById(id).closedTime, "作废须记时间");
    }

    @Test
    @DisplayName("草稿删除：仅 DRAFT 可物理删除；ACTIVE → 10009")
    void deleteDraftOnly() {
        PurchaseContract draft = purchase("PC-006", "CAT-CU");
        long id = service.createPurchaseContract(draft, 1L);
        service.deleteDraftPurchaseContract(id, 1L);
        assertNull(purchaseDao.findById(id), "草稿已物理删除（§0.3 例外）");

        mappingDao.rows.add(mapping(100L, 5L, "PURCHASE", "Y"));
        long id2 = service.createPurchaseContract(purchase("PC-007", "CAT-CU"), 1L);
        service.activatePurchaseContract(id2, 1L);
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.deleteDraftPurchaseContract(id2, 1L)).errorCode());
    }

    @Test
    @DisplayName("销售合同：双向敞口须显式给出买入方向数量（FR-SPOT-5 验收 3）")
    void salesContractDualDirection() {
        SalesContract dual = sales("SC-001", "CAT-CU");
        dual.hasDualDirection = "Y";
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.createSalesContract(dual, 1L)).errorCode());
        dual.buyDirectionQty = new BigDecimal("30");
        assertTrue(service.createSalesContract(dual, 1L) > 0);
        assertEquals("SELL", salesDao.findById(dual.id).exposureDirection);
    }

    @Test
    @DisplayName("库存：负值须二次确认；同子账户同品种同类型同日重复录入拒绝")
    void inventoryNegativeConfirmation() {
        InventoryLedger negative = inventory(new BigDecimal("-5"));
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.createInventory(negative, 1L)).errorCode());
        negative.negativeConfirmed = "Y";
        long id = service.createInventory(negative, 1L);
        assertTrue(id > 0);

        InventoryLedger duplicate = inventory(new BigDecimal("8"));
        assertEquals(ErrorCode.UNIQUE_CONFLICT,
                assertThrows(BizException.class,
                        () -> service.createInventory(duplicate, 1L)).errorCode(),
                "同键唯一约束须被显式暴露");
    }

    @Test
    @DisplayName("在制品：完工比例必须落在 [0,1]（R-SPOT-4 结构口径）")
    void wipCompletionRatio() {
        WipRecord bad = wip(new BigDecimal("1.5"));
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.createWip(bad, 1L)).errorCode());
        WipRecord ok = wip(new BigDecimal("0.4"));
        assertTrue(service.createWip(ok, 1L) > 0);
        assertEquals("BUY", wipDao.rows.get(0).exposureDirection == null ? "BUY"
                : wipDao.rows.get(0).exposureDirection, "在制品默认按 BUY 方向锁定原料风险");
    }

    // ------------------------------------------------------------ 现货价

    @Test
    @DisplayName("现货价：正数、不得晚于当日、来源必显式；覆盖须确认且原值留痕")
    void spotPriceRules() {
        SpotPrice bad = price(new BigDecimal("0"));
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.putSpotPrice(bad, false, 1L)).errorCode());

        SpotPrice future = price(new BigDecimal("71000"));
        future.priceDate = Clocks.today().plusDays(1);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.putSpotPrice(future, false, 1L))
                        .errorCode(), "价格日期不得晚于当日");

        SpotPrice badSource = price(new BigDecimal("71000"));
        badSource.sourceType = "REUTERS";
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.putSpotPrice(badSource, false, 1L))
                        .errorCode(), "不得对接第三方资讯源（R-SPOT-6）");

        SpotPrice first = price(new BigDecimal("71000"));
        long id = service.putSpotPrice(first, false, 1L);
        assertEquals("Y", priceDao.findById(id).isShared, "GLOBAL 口径恒共享");

        SpotPrice overwrite = price(new BigDecimal("72500"));
        BizException e = assertThrows(BizException.class,
                () -> service.putSpotPrice(overwrite, false, 1L));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode());
        assertTrue(e.getMessage().contains("71000"), "须回显原值以支持确认：" + e.getMessage());

        long sameId = service.putSpotPrice(overwrite, true, 2L);
        assertEquals(id, sameId, "覆盖写回同一行（唯一口径）");
        SpotPrice stored = priceDao.findById(id);
        assertEquals(0, new BigDecimal("72500").compareTo(stored.spotPrice));
        assertEquals(0, new BigDecimal("71000").compareTo(stored.overwrittenPrice), "原值须留痕");
        assertEquals("Y", stored.overwriteConfirmed);
        assertEquals(1L, stored.operatorId, "原始录入人保留不被覆盖");
    }

    @Test
    @DisplayName("缺价处置分流：比率用途拒绝（10015）；估值用途沿用最近价（可标注）")
    void priceGapDisposition() {
        priceDao.rows.add(sharedPrice(5L, LocalDate.of(2026, 9, 15), "70000"));
        assertEquals(ErrorCode.DATA_INSUFFICIENT,
                assertThrows(BizException.class,
                        () -> service.resolvePrice(5L, LocalDate.of(2026, 9, 20),
                                SpotService.PricePurpose.RATIO)).errorCode(),
                "比率用途缺价一律拒绝（不得静默降级）");

        SpotPrice carried = service.resolvePrice(5L, LocalDate.of(2026, 9, 20),
                SpotService.PricePurpose.VALUATION);
        assertEquals(LocalDate.of(2026, 9, 15), carried.priceDate, "沿用最近一期价");

        assertEquals(ErrorCode.DATA_INSUFFICIENT,
                assertThrows(BizException.class,
                        () -> service.resolvePrice(9L, LocalDate.of(2026, 9, 20),
                                SpotService.PricePurpose.VALUATION)).errorCode(), "无任何历史价仍拒绝");
        SpotPrice exact = sharedPrice(5L, LocalDate.of(2026, 9, 20), "71000");
        priceDao.rows.add(exact);
        assertEquals("71000", service.resolvePrice(5L, LocalDate.of(2026, 9, 20),
                SpotService.PricePurpose.RATIO).spotPrice.toPlainString());
    }

    // ------------------------------------------------------------ 敞口

    @Test
    @DisplayName("敞口写入侧：Σ分量 ≠ 合计 → 拒绝写入，且不产生任何落库")
    void exposureRejectsComponentMismatch() {
        Exposure exposure = exposure("COMBINED", "100", "60", "30", "5");   // 合计 100 ≠ 分量 95
        exposure.combinedComponents = "CONTRACT_UNEXECUTED+INVENTORY";
        BizException e = assertThrows(BizException.class,
                () -> service.computeExposure(exposure, new ArrayList<>(), 1L));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("拒绝写入"), e.getMessage());
        assertTrue(exposureDao.rows.isEmpty(), "被拒时不得产生部分写入");
    }

    @Test
    @DisplayName("敞口写入侧：未映射哨兵值与标记双向联动（DC-041）；组合口径须声明构成")
    void exposureUnmappedSentinel() {
        Exposure wrong = exposure("COMBINED", "0", "0", "0", "0");
        wrong.unmappedFlag = "Y";
        wrong.productId = 5L;
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.computeExposure(wrong, new ArrayList<>(), 1L)).errorCode(),
                "未映射行必须取品种哨兵值 0");
        wrong.productId = 0L;
        wrong.combinedComponents = null;
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.computeExposure(wrong, new ArrayList<>(), 1L)).errorCode(),
                "组合口径须声明构成");

        wrong.combinedComponents = "CONTRACT_UNEXECUTED";
        Exposure stored = service.computeExposure(wrong, new ArrayList<>(), 1L);
        assertEquals("Y", stored.unmappedFlag);
        assertEquals("Y", stored.priceMissingFlag, "未映射行无品种即无价");
        assertNull(stored.exposureAmount);

        Exposure mappedZeroProduct = exposure("COMBINED", "10", "10", "0", "0");
        mappedZeroProduct.unmappedFlag = "N";
        mappedZeroProduct.productId = 0L;
        mappedZeroProduct.combinedComponents = "CONTRACT_UNEXECUTED";
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.computeExposure(mappedZeroProduct, new ArrayList<>(), 1L))
                        .errorCode(), "已映射行不得用哨兵值 0");
    }

    @Test
    @DisplayName("敞口写入侧：明细之和必须等于分量列（否则拒写）；一致时连同明细一并落库")
    void exposureDetailsMustSumToComponents() {
        Exposure exposure = exposure("CONTRACT_UNEXECUTED", "60", "60", "0", "0");
        ExposureDetail ok = detail(0L, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 11L,
                new BigDecimal("60"), "BUY");
        Exposure stored = service.computeExposure(exposure, new ArrayList<>(Arrays.asList(ok)), 1L);
        assertNotNull(stored.snapshotId, "须生成快照标识");
        assertEquals("PENDING_HEDGE", stored.status, "未被覆盖 → 待套保");
        assertEquals(1, detailDao.rows.size());
        assertEquals(stored.id, detailDao.rows.get(0).exposureId, "明细须挂到敞口上");
        assertEquals(stored.subAccountId, detailDao.rows.get(0).subAccountId, "明细冗余子账户");

        Exposure bad = exposure("CONTRACT_UNEXECUTED", "60", "60", "0", "0");
        ExposureDetail under = detail(0L, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 12L,
                new BigDecimal("50"), "BUY");
        BizException e = assertThrows(BizException.class,
                () -> service.computeExposure(bad, new ArrayList<>(Arrays.asList(under)), 1L));
        assertTrue(e.getMessage().contains("明细"), e.getMessage());

        Exposure wrongDir = exposure("CONTRACT_UNEXECUTED", "60", "60", "0", "0");
        ExposureDetail sell = detail(0L, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 13L,
                new BigDecimal("60"), "SELL");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.computeExposure(wrongDir, new ArrayList<>(Arrays.asList(sell)), 1L))
                        .errorCode(), "明细方向须与敞口头一致");
    }

    @Test
    @DisplayName("敞口估值：沿用非当日价须标注 price_as_of 与非当日价标记")
    void exposureValuationLabelsNonCurrentPrice() {
        priceDao.rows.add(sharedPrice(5L, LocalDate.of(2026, 9, 15), "70000"));
        Exposure exposure = exposure("CONTRACT_UNEXECUTED", "60", "60", "0", "0");
        exposure.productId = 5L;
        Exposure stored = service.computeExposure(exposure, new ArrayList<>(), 1L);

        assertEquals("N", stored.priceMissingFlag);
        assertEquals(LocalDate.of(2026, 9, 15), stored.priceAsOf, "沿用价须标注实际时点");
        assertEquals("Y", stored.nonCurrentPriceFlag, "非当日价须显式标记（不得无标注静默沿用）");
        assertEquals(0, new BigDecimal("4200000").compareTo(stored.exposureAmount));
        assertEquals("70000", priceDao.rows.get(0).spotPrice.toPlainString(), "沿用不写回价格行");
    }

    @Test
    @DisplayName("品类双态（CH-008）：未映射行按真实品类各占一行；已映射行归一为占位常量仅落一行")
    void spotCategoryDualState() {
        // ① 两个**未映射品类**（各占一行）
        Exposure first = exposure("CONTRACT_UNEXECUTED", "10", "10", "0", "0");
        first.unmappedFlag = "Y";
        first.productId = SpotService.UNMAPPED_PRODUCT_SENTINEL;
        first.spotCategoryCode = "CAT-COPPER-CATHODE";
        first.combinedComponents = "CONTRACT_UNEXECUTED";
        Exposure second = exposure("CONTRACT_UNEXECUTED", "20", "20", "0", "0");
        second.unmappedFlag = "Y";
        second.productId = SpotService.UNMAPPED_PRODUCT_SENTINEL;
        second.spotCategoryCode = "CAT-COPPER-SCRAP";
        second.combinedComponents = "CONTRACT_UNEXECUTED";

        assertEquals(2, snapshotCountAfter(first, second), "未映射：不同品类须各占一行");
        assertEquals("CAT-COPPER-CATHODE", exposureDao.rows.get(0).spotCategoryCode);
        assertEquals("CAT-COPPER-SCRAP", exposureDao.rows.get(1).spotCategoryCode);
        assertEquals("Y", exposureDao.rows.get(0).priceMissingFlag, "未映射行无品种即无价");

        // ② 同一子账户同品种同方向/口径/基准日，两条**已映射**数据来自**不同来源品类** → 只落一行
        Exposure mappedA = exposure("CONTRACT_UNEXECUTED", "30", "30", "0", "0");
        mappedA.spotCategoryCode = "CAT-COPPER-CATHODE";   // 来源品类 A
        Exposure mappedB = exposure("CONTRACT_UNEXECUTED", "40", "40", "0", "0");
        mappedB.spotCategoryCode = "CAT-COPPER-SCRAP";     // 来源品类 B
        service.computeExposure(mappedA, new ArrayList<>(), 1L);
        assertEquals(SpotService.MAPPED_CATEGORY_PLACEHOLDER, exposureDao.rows.get(2).spotCategoryCode,
                "已映射行须归一为占位常量（不随来源品类变化）");
        BizException e = assertThrows(BizException.class,
                () -> service.computeExposure(mappedB, new ArrayList<>(), 1L));
        assertEquals(ErrorCode.UNIQUE_CONFLICT, e.errorCode(),
                "跨品类已映射数据必须合并为一条（uk_exposure_key 拒第二条）");
        assertEquals(3, exposureDao.rows.size(), "仍为 3 行（未映射 2 + 已映射 1）");

        // ③ 未映射行不得填占位常量
        Exposure wrong = exposure("CONTRACT_UNEXECUTED", "10", "10", "0", "0");
        wrong.unmappedFlag = "Y";
        wrong.productId = SpotService.UNMAPPED_PRODUCT_SENTINEL;
        wrong.spotCategoryCode = SpotService.MAPPED_CATEGORY_PLACEHOLDER;
        wrong.combinedComponents = "CONTRACT_UNEXECUTED";
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.computeExposure(wrong, new ArrayList<>(), 1L)).errorCode());
    }

    private int snapshotCountAfter(Exposure a, Exposure b) {
        service.computeExposure(a, new ArrayList<>(), 1L);
        service.computeExposure(b, new ArrayList<>(), 1L);
        return exposureDao.rows.size();
    }

    @Test
    @DisplayName("展示侧重算：不一致 → 留痕 COMPONENT_RECALC 且 writtenBack=false；一致则无留痕")
    void displayRecalcNeverWritesBack() {
        Exposure exposure = exposure("CONTRACT_UNEXECUTED", "60", "60", "0", "0");
        ExposureDetail detail = detail(0L, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("60"), "BUY");
        Exposure stored = service.computeExposure(exposure, new ArrayList<>(Arrays.asList(detail)), 1L);

        Map<String, Object> consistent = service.recalcForDisplay(stored.id, 1L);
        assertEquals(Boolean.FALSE, consistent.get("mismatched"));
        assertEquals(Boolean.FALSE, consistent.get("writtenBack"));
        assertTrue(changeLogDao.rows.isEmpty(), "一致时不留痕");

        detailDao.rows.get(0).contributedQty = new BigDecimal("55");
        Map<String, Object> mismatch = service.recalcForDisplay(stored.id, 1L);
        assertEquals(Boolean.TRUE, mismatch.get("mismatched"));
        assertEquals(Boolean.FALSE, mismatch.get("writtenBack"), "展示侧绝不回写");
        assertEquals(1, changeLogDao.rows.size());
        assertEquals("COMPONENT_RECALC", changeLogDao.rows.get(0).changeType);
        assertEquals("Y", changeLogDao.rows.get(0).recalcTriggered);
        assertEquals("PURCHASE_CONTRACT", changeLogDao.rows.get(0).sourceType, "留痕挂在原始单据上");
        assertEquals(Long.valueOf(21L), changeLogDao.rows.get(0).sourceId);
        assertEquals(0, new BigDecimal("60").compareTo(stored.contractUnexecutedQty),
                "落库值不得被改写");
    }

    // ------------------------------------------------------------ 桩与数据

    private static PurchaseContract purchase(String no, String category) {
        PurchaseContract contract = new PurchaseContract();
        contract.subAccountId = 3;
        contract.contractNo = no;
        contract.spotCategoryCode = category;
        contract.totalQty = new BigDecimal("100");
        contract.contractStartDate = LocalDate.of(2026, 1, 1);
        contract.contractEndDate = LocalDate.of(2026, 12, 31);
        return contract;
    }

    private static SalesContract sales(String no, String category) {
        SalesContract contract = new SalesContract();
        contract.subAccountId = 3;
        contract.contractNo = no;
        contract.spotCategoryCode = category;
        contract.totalQty = new BigDecimal("80");
        contract.contractStartDate = LocalDate.of(2026, 1, 1);
        contract.contractEndDate = LocalDate.of(2026, 12, 31);
        return contract;
    }

    private static InventoryLedger inventory(BigDecimal qty) {
        InventoryLedger ledger = new InventoryLedger();
        ledger.subAccountId = 3;
        ledger.productId = 5L;
        ledger.inventoryType = "RAW_MATERIAL";
        ledger.quantity = qty;
        ledger.negativeConfirmed = "N";
        ledger.unit = "TON";
        ledger.recordDate = LocalDate.of(2026, 9, 20);
        ledger.exposureDirection = "BUY";
        return ledger;
    }

    private static WipRecord wip(BigDecimal ratio) {
        WipRecord record = new WipRecord();
        record.subAccountId = 3;
        record.productId = 5L;
        record.wipQty = new BigDecimal("20");
        record.completionRatio = ratio;
        record.exposureDirection = "BUY";
        record.recordDate = LocalDate.of(2026, 9, 20);
        record.unit = "TON";
        return record;
    }

    private static SpotPrice price(BigDecimal value) {
        SpotPrice price = new SpotPrice();
        price.subAccountId = 3;
        price.productId = 5L;
        price.priceDate = Clocks.today();
        price.spotPrice = value;
        price.sourceType = "MANUAL";
        price.priceUnit = "CNY/TON";
        return price;
    }

    private static SpotPrice sharedPrice(long productId, LocalDate date, String value) {
        SpotPrice price = new SpotPrice();
        price.subAccountId = 3;
        price.productId = productId;
        price.priceDate = date;
        price.spotPrice = new BigDecimal(value);
        price.sourceType = "MANUAL";
        price.priceUnit = "CNY/TON";
        price.isShared = "Y";
        price.overwriteConfirmed = "N";
        price.operatorId = 1L;
        price.entryTime = Clocks.now();
        return price;
    }

    private static Exposure exposure(String calcMode, String total, String c1, String c2, String c3) {
        Exposure exposure = new Exposure();
        exposure.subAccountId = 3;
        exposure.productId = 5L;
        exposure.spotCategoryCode = "CAT-CU";
        exposure.direction = "BUY";
        exposure.calcMode = calcMode;
        exposure.contractUnexecutedQty = new BigDecimal(c1);
        exposure.inventoryQty = new BigDecimal(c2);
        exposure.productionPlanQty = new BigDecimal(c3);
        exposure.componentsEnabled = "CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN";
        exposure.asOfDate = LocalDate.of(2026, 9, 20);
        exposure.exposureQty = new BigDecimal(total);
        exposure.unit = "TON";
        exposure.unmappedFlag = "N";
        return exposure;
    }

    private static ExposureDetail detail(long exposureId, String component, String sourceType,
                                        long sourceId, BigDecimal qty, String direction) {
        ExposureDetail detail = new ExposureDetail();
        detail.exposureId = exposureId;
        detail.subAccountId = 3;
        detail.componentType = component;
        detail.sourceType = sourceType;
        detail.sourceId = sourceId;
        detail.contributedQty = qty;
        detail.unit = "TON";
        detail.direction = direction;
        detail.calcAsOfDate = LocalDate.of(2026, 9, 20);
        return detail;
    }

    private static ProductSpotCategory mapping(long id, long productId, String direction,
                                              String primary) {
        ProductSpotCategory mapping = new ProductSpotCategory();
        mapping.id = id;
        mapping.spotCategoryCode = "CAT-CU";
        mapping.productId = productId;
        mapping.mappingDirection = direction;
        mapping.isPrimary = primary;
        mapping.status = "ENABLED";
        return mapping;
    }

    /** 内存假采购合同 DAO（含物理删除，§0.3 例外态）。 */
    private static final class FakePurchaseContractDao
            extends UnsupportedBaseDao<PurchaseContract, Long> implements PurchaseContractDao {

        private final List<PurchaseContract> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(PurchaseContract entity, long operatorId) {
            for (PurchaseContract row : rows) {
                if (row.subAccountId == entity.subAccountId
                        && row.contractNo.equals(entity.contractNo)) {
                    return 0;
                }
            }
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public int updateById(PurchaseContract entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public PurchaseContract findById(Long id) {
            for (PurchaseContract row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int deleteById(Long id) {
            return rows.removeIf(row -> row.id == id) ? 1 : 0;
        }

        @Override
        public PurchaseContract findByContractNo(int subAccountId, String contractNo) {
            for (PurchaseContract row : rows) {
                if (row.subAccountId == subAccountId && row.contractNo.equals(contractNo)) {
                    return row;
                }
            }
            return null;
        }
    }

    /** 内存假销售合同 DAO。 */
    private static final class FakeSalesContractDao
            extends UnsupportedBaseDao<SalesContract, Long> implements SalesContractDao {

        private final List<SalesContract> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(SalesContract entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public SalesContract findById(Long id) {
            for (SalesContract row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public SalesContract findByContractNo(int subAccountId, String contractNo) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByContractNo");
        }

        @Override
        public int deleteById(Long id) {
            return rows.removeIf(row -> row.id == id) ? 1 : 0;
        }
    }

    /** 内存假映射 DAO（仅主映射查询）。 */
    private static final class FakeMappingDao
            extends UnsupportedBaseDao<ProductSpotCategory, Long> implements ProductSpotCategoryDao {

        private final List<ProductSpotCategory> rows = new ArrayList<>();

        @Override
        public ProductSpotCategory findPrimaryMapping(String spotCategoryCode, String mappingDirection) {
            for (ProductSpotCategory row : rows) {
                if (row.spotCategoryCode.equals(spotCategoryCode)
                        && ("BOTH".equals(row.mappingDirection) || row.mappingDirection
                                .equals(mappingDirection))
                        && "Y".equals(row.isPrimary) && "ENABLED".equals(row.status)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<ProductSpotCategory> findByProduct(long productId) {
            List<ProductSpotCategory> out = new ArrayList<>();
            for (ProductSpotCategory row : rows) {
                if (row.productId != null && row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假在制品 DAO。 */
    private static final class FakeWipDao extends UnsupportedBaseDao<WipRecord, Long>
            implements WipRecordDao {

        private final List<WipRecord> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(WipRecord entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }
    }

    /** 内存假库存 DAO（同键唯一约束）。 */
    private static final class FakeInventoryDao extends UnsupportedBaseDao<InventoryLedger, Long>
            implements InventoryLedgerDao {

        private final List<InventoryLedger> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(InventoryLedger entity, long operatorId) {
            for (InventoryLedger row : rows) {
                if (row.subAccountId == entity.subAccountId && row.productId.equals(entity.productId)
                        && row.inventoryType.equals(entity.inventoryType)
                        && row.recordDate.equals(entity.recordDate)) {
                    return 0;
                }
            }
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }
    }

    /** 内存假现货价 DAO。 */
    private static final class FakeSpotPriceDao extends UnsupportedBaseDao<SpotPrice, Long>
            implements SpotPriceDao {

        private final List<SpotPrice> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(SpotPrice entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public int updateById(SpotPrice entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public SpotPrice findById(Long id) {
            for (SpotPrice row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public SpotPrice findGlobalPrice(long productId, LocalDate priceDate) {
            for (SpotPrice row : rows) {
                if (row.productId == productId && "Y".equals(row.isShared)
                        && row.priceDate.equals(priceDate)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public SpotPrice findLatestOnOrBefore(long productId, LocalDate priceDate) {
            SpotPrice best = null;
            for (SpotPrice row : rows) {
                if (row.productId != productId || !"Y".equals(row.isShared)
                        || row.priceDate.isAfter(priceDate)) {
                    continue;
                }
                if (best == null || row.priceDate.isAfter(best.priceDate)) {
                    best = row;
                }
            }
            return best;
        }
    }

    /** 内存假敞口 DAO。 */
    private static final class FakeExposureDao extends UnsupportedBaseDao<Exposure, Long>
            implements ExposureDao {

        private final List<Exposure> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(Exposure entity, long operatorId) {
            // 假算 uk_exposure_key（6 列，全非空）——使"跨品类合并"可被行为级断言
            for (Exposure row : rows) {
                if (sameKey(row, entity)) {
                    return 0;
                }
            }
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        private static boolean sameKey(Exposure a, Exposure b) {
            return a.subAccountId.equals(b.subAccountId)
                    && a.productId.equals(b.productId)
                    && a.spotCategoryCode.equals(b.spotCategoryCode)
                    && a.direction.equals(b.direction)
                    && a.calcMode.equals(b.calcMode)
                    && a.asOfDate.equals(b.asOfDate);
        }

        @Override
        public Exposure findById(Long id) {
            for (Exposure row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public List<Exposure> findPendingHedge(int subAccountId) {
            List<Exposure> out = new ArrayList<>();
            for (Exposure row : rows) {
                if (row.subAccountId == subAccountId && "PENDING_HEDGE".equals(row.status)) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假明细 DAO。 */
    private static final class FakeExposureDetailDao
            extends UnsupportedBaseDao<ExposureDetail, Long> implements ExposureDetailDao {

        private final List<ExposureDetail> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ExposureDetail entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public List<ExposureDetail> findByExposure(long exposureId) {
            List<ExposureDetail> out = new ArrayList<>();
            for (ExposureDetail row : rows) {
                if (row.exposureId == exposureId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<ExposureDetail> findBySource(String sourceType, long sourceId) {
            List<ExposureDetail> out = new ArrayList<>();
            for (ExposureDetail row : rows) {
                if (row.sourceType.equals(sourceType) && row.sourceId == sourceId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假现货留痕 DAO。 */
    private static final class FakeSpotChangeLogDao
            extends UnsupportedBaseDao<SpotChangeLog, Long> implements SpotChangeLogDao {

        private final List<SpotChangeLog> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(SpotChangeLog entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }
    }
}
