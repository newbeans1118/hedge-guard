package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.InventoryLedgerDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.ProductionPlanDao;
import com.hedgeguard.dao.ProductionPlanLineDao;
import com.hedgeguard.dao.PurchaseContractDao;
import com.hedgeguard.dao.SalesContractDao;
import com.hedgeguard.dao.SpotChangeLogDao;
import com.hedgeguard.dao.SpotPriceDao;
import com.hedgeguard.dao.SubAccountDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.WipRecordDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductionPlan;
import com.hedgeguard.dao.entity.ProductionPlanLine;
import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.SpotChangeLog;
import com.hedgeguard.dao.entity.SpotPrice;
import com.hedgeguard.dao.entity.SubAccount;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-109} 独立复核退回项的修复验收（{@code TE-T109-001}/002/003/006）。
 *
 * <p>四项断言：
 * <ol>
 *   <li>{@code TE-T109-001}：{@code /spot/price/import} 按 {@code FR-SPOT-11/12} 的<b>文本块</b>输入契约
 *       （品种编码 / 表头列完整性 / 行号定位 / 来源标注含文件名与操作人）；</li>
 *   <li>{@code TE-T109-002}：{@code /spot/exposure/export} <b>越权读取</b>被拒（{@code NFR-SEC-7}）；
 *       并覆盖 {@code /spot/production}、{@code /spot/doc/sub-account} 写面同类强制；</li>
 *   <li>{@code TE-T109-003}：{@code /spot/production} 重叠提示<b>回传</b>（{@code FR-SPOT-8} 验收 3）；</li>
 *   <li>{@code TE-T109-006}：{@code /spot/doc/sub-account} 拒因按<b>计划</b>给出（验收 2）。</li>
 * </ol>
 *
 * <p>完全离线（{@code NFR-TEST-2}）：内存假 DAO 驱动，不含 SQL / 连接。
 */
class SpotT109FixTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");

    private FakeProductDao productDao;
    private FakeSpotPriceDao priceDao;
    private FakeExposureDao exposureDao;
    private FakeExposureDetailDao detailDao;
    private FakePurchaseContractDao purchaseDao;
    private FakeProductionPlanDao planDao;
    private FakeProductionPlanLineDao planLineDao;
    private FakeSubAccountDao subAccountDao;
    private FakeHedgePlanExposureDao bindDao;
    private FakeSpotChangeLogDao changeLogDao;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        productDao = new FakeProductDao();
        priceDao = new FakeSpotPriceDao();
        exposureDao = new FakeExposureDao();
        detailDao = new FakeExposureDetailDao();
        purchaseDao = new FakePurchaseContractDao();
        planDao = new FakeProductionPlanDao();
        planLineDao = new FakeProductionPlanLineDao();
        subAccountDao = new FakeSubAccountDao();
        bindDao = new FakeHedgePlanExposureDao();
        changeLogDao = new FakeSpotChangeLogDao();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ TE-T109-001 导入输入契约

    @Test
    @DisplayName("FR-SPOT-11 批量粘贴：5 行合法 → inserted=5，来源标注 BATCH_PASTE")
    void batchPasteImportsAllRows() {
        productDao.rows.add(product(5L, "CU"));
        List<String> lines = Arrays.asList(
                "CU,2026-09-14,70000", "CU,2026-09-15,70100", "CU,2026-09-16,70200",
                "CU,2026-09-17,70300", "CU,2026-09-18,70400");

        Map<String, Object> summary = service(uid -> Arrays.asList(1)).importSpotPrices(
                lines, 1, "BATCH_PASTE", null, false, 7L);

        assertEquals(5, summary.get("inserted"), "应导入 5 条");
        assertEquals(0, summary.get("updated"));
        assertEquals("BATCH_PASTE", summary.get("sourceType"));
        assertEquals("BATCH_PASTE", priceDao.rows.get(0).sourceType);
        assertEquals(7L, priceDao.rows.get(0).operatorId, "来源标注含操作人");
    }

    @Test
    @DisplayName("FR-SPOT-11 验收 2：第 3 行日期格式错误 → 整体不导入且定位第 3 行")
    void batchPasteLineNumberLocatedOnBadDate() {
        productDao.rows.add(product(5L, "CU"));
        List<String> lines = Arrays.asList(
                "CU,2026-09-14,70000", "CU,2026-09-15,70100", "CU,2026/09/16,70200",
                "CU,2026-09-17,70300");

        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(lines, 1, "BATCH_PASTE", null, false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertTrue(e.getMessage().contains("第 3 行"), "须定位到第 3 行：" + e.getMessage());
        assertTrue(priceDao.rows.isEmpty(), "整体不导入，库内不变");
    }

    @Test
    @DisplayName("FR-SPOT-11 验收 3：第 4 行品种编码不存在 → 错误清单给出该编码")
    void batchPasteUnknownCodeReportsCode() {
        productDao.rows.add(product(5L, "CU"));
        List<String> lines = Arrays.asList(
                "CU,2026-09-14,70000", "CU,2026-09-15,70100", "CU,2026-09-16,70200",
                "ZZ,2026-09-17,70300");

        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(lines, 1, "BATCH_PASTE", null, false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertTrue(e.getMessage().contains("第 4 行") && e.getMessage().contains("ZZ"),
                "须给出第 4 行且含品种编码 ZZ：" + e.getMessage());
    }

    @Test
    @DisplayName("FR-SPOT-11 验收 4：内容全为空行 → 明确提示，不产生记录")
    void batchPasteAllBlankLinesRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(Arrays.asList("", "   ", ""), 1, "BATCH_PASTE", null, false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertTrue(e.getMessage().contains("全为空行"), "须明确提示空行：" + e.getMessage());
        assertTrue(priceDao.rows.isEmpty());
    }

    @Test
    @DisplayName("FR-SPOT-12 验收 2：CSV 缺「现货价」列 → 表头列完整性校验拒绝并提示缺失列名")
    void csvImportMissingColumnReported() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(Arrays.asList("品种编码,日期", "CU,2026-09-18"), 1, "CSV_IMPORT",
                        "prices.csv", false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertTrue(e.getMessage().contains("表头") && e.getMessage().contains("缺少必需列：现货价"),
                "须由表头列完整性校验拒绝并提示缺失列名「现货价」：" + e.getMessage());
    }

    @Test
    @DisplayName("FR-SPOT-12 验收 3：导入后来源标注含导入文件名与操作人")
    void csvImportRecordsFileNameAndOperator() {
        productDao.rows.add(product(5L, "CU"));
        Map<String, Object> summary = service(uid -> Arrays.asList(1)).importSpotPrices(
                Arrays.asList("品种编码,日期,现货价", "CU,2026-09-18,70000"), 1, "CSV_IMPORT",
                "prices-2026Q3.csv", false, 7L);

        assertEquals(1, summary.get("inserted"));
        SpotPrice stored = priceDao.rows.get(0);
        assertEquals("CSV_IMPORT", stored.sourceType);
        assertEquals("prices-2026Q3.csv", stored.importFileName, "来源标注含导入文件名");
        assertEquals(7L, stored.operatorId, "来源标注含操作人");
    }

    // ---------------- CSV 表头行判空：仅 null 行 NPE→500；空串表头改前即按缺列走 10019

    @Test
    @DisplayName("FR-SPOT-12 表头行判空：lines=[null,数据行] ⇒ 10019 拒绝，不退化为 500/NPE")
    void csvImportNullHeaderLineRejected() {
        productDao.rows.add(product(5L, "CU"));
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(Arrays.asList((String) null, "CU,2026-09-18,70000"), 1,
                        "CSV_IMPORT", "prices.csv", false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertEquals(422, e.errorCode().httpStatus(), "须为可判定业务码而非 HTTP 500");
        assertTrue(e.getMessage().contains("表头"), "须明示表头问题：" + e.getMessage());
        assertTrue(priceDao.rows.isEmpty(), "不得产生记录");
    }

    @Test
    @DisplayName("FR-SPOT-12 表头行判空：lines=[null,null] ⇒ 10019 拒绝，不 500")
    void csvImportAllNullLinesRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(Arrays.asList((String) null, (String) null), 1,
                        "CSV_IMPORT", "prices.csv", false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertEquals(422, e.errorCode().httpStatus());
        assertTrue(priceDao.rows.isEmpty());
    }

    @Test
    @DisplayName("FR-SPOT-11/12 入参边界：lines=null ⇒ 10019（不 500）")
    void importNullLinesRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(null, 1, "BATCH_PASTE", null, false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertEquals(422, e.errorCode().httpStatus());
    }

    @Test
    @DisplayName("FR-SPOT-11/12 入参边界：lines=[] ⇒ 10019（不 500）")
    void importEmptyLinesRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(Collections.emptyList(), 1, "BATCH_PASTE", null, false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertEquals(422, e.errorCode().httpStatus());
    }

    @Test
    @DisplayName("FR-SPOT-12 首行为空串（非 null）但后续行合法 ⇒ 表头缺失 10019，改前即非 500")
    void csvImportBlankHeaderLineWithValidDataRejected() {
        productDao.rows.add(product(5L, "CU"));
        // 改前实测：空串表头不会 NPE —— splitCells("") 得 [""]，列校验判为缺列 ⇒ 10019；
        // 本用例锁定该既有语义（非本次 null 判空所新增；框架收窄：NPE→500 仅涉 null 行）。
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(Arrays.asList("", "品种编码,日期,现货价", "CU,2026-09-18,70000"),
                        1, "CSV_IMPORT", "prices.csv", false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertEquals(422, e.errorCode().httpStatus());
        assertTrue(priceDao.rows.isEmpty());
    }

    @Test
    @DisplayName("FR-SPOT-12 表头合法但数据行全为空行 ⇒ 明确提示，不产生记录")
    void csvImportHeaderOnlyAllBlankRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(1))
                .importSpotPrices(Arrays.asList("品种编码,日期,现货价", "", (String) null), 1,
                        "CSV_IMPORT", "prices.csv", false, 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertTrue(e.getMessage().contains("全为空行"), "须明确提示空行：" + e.getMessage());
        assertTrue(priceDao.rows.isEmpty());
    }

    @Test
    @DisplayName("FR-SPOT-11 对照：BATCH_PASTE 首行 null 被忽略（既有语义，不受影响）")
    void batchPasteNullFirstLineIgnored() {
        productDao.rows.add(product(5L, "CU"));
        Map<String, Object> summary = service(uid -> Arrays.asList(1)).importSpotPrices(
                Arrays.asList((String) null, "CU,2026-09-18,70000"), 1, "BATCH_PASTE",
                null, false, 7L);
        assertEquals(1, summary.get("inserted"));
    }

    // ------------------------------------------------------------ TE-T109-002 越权读取

    @Test
    @DisplayName("TE-T109-002 证伪：非该子账户 operator 导出他人敞口 ⇒ 10004（修复前红）")
    void exposureExportRejectsCrossSubAccount() {
        exposureDao.rows.add(exposure(1L, 8));

        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(7))
                .exportExposuresCsv(8, null, 999L));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(),
                "越权导出须按 10004 拒绝");
        assertEquals(403, ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED.httpStatus(), "对外表现 HTTP 403");
    }

    @Test
    @DisplayName("TE-T109-002 对照：可见子账户导出行 > 0；跨账户只读（哨兵）放行")
    void exposureExportAllowsVisibleSubAccount() {
        exposureDao.rows.add(exposure(1L, 8));
        List<String> lines = service(uid -> Arrays.asList(8)).exportExposuresCsv(8, null, 999L);
        assertFalse(lines.isEmpty(), "可见身份应拿到 CSV 行");

        exposureDao.rows.add(exposure(2L, 9));
        List<String> all = service(uid -> Collections.singletonList(0))
                .exportExposuresCsv(9, null, 999L);
        assertFalse(all.isEmpty(), "跨账户只读（哨兵 0）放行");
    }

    @Test
    @DisplayName("TE-T109-002 fail-closed：可见性入口缺位 ⇒ 10004（绝不退化为放行）")
    void exposureExportFailClosedWhenVisibilityMissing() {
        exposureDao.rows.add(exposure(1L, 8));
        SpotService noVisibility = new SpotService(FakeTx.newTemplate(), purchaseDao, null, null,
                null, null, priceDao, exposureDao, detailDao, changeLogDao, planDao, planLineDao,
                subAccountDao, productDao, null, bindDao);
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> noVisibility.exportExposuresCsv(8, null, 999L)).errorCode());
    }

    // ------------------------------------------------------------ TE-T109-003 重叠提示

    @Test
    @DisplayName("FR-SPOT-8 验收 3：计划期重叠 → 响应回传 overlapFlag=Y 与提示")
    void productionPlanReturnsOverlapHint() {
        planDao.rows.add(plan(1L, 7, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31)));
        Map<String, Object> result = service(uid -> Arrays.asList(7)).createProductionPlan(
                plan(null, 7, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30)),
                Collections.singletonList(line("FINISHED", 5L, "100")), 999L);

        assertEquals("Y", result.get("overlapFlag"));
        assertNotNull(result.get("id"));
        assertFalse(((String) result.get("overlapHint")).isEmpty(), "重叠须回传提示");
    }

    @Test
    @DisplayName("FR-SPOT-8：无重叠 → overlapFlag=N 且提示为空")
    void productionPlanNoOverlapHintEmpty() {
        Map<String, Object> result = service(uid -> Arrays.asList(7)).createProductionPlan(
                plan(null, 7, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
                Collections.singletonList(line("FINISHED", 5L, "100")), 999L);
        assertEquals("N", result.get("overlapFlag"));
        assertEquals("", result.get("overlapHint"));
    }

    @Test
    @DisplayName("TE-T109-002 同类：生产计划写面越权（他人子账户）⇒ 10004")
    void productionPlanCrossSubAccountRejected() {
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class, () -> service(uid -> Arrays.asList(7))
                        .createProductionPlan(plan(null, 8, LocalDate.of(2026, 10, 1),
                                        LocalDate.of(2026, 10, 31)),
                                Collections.singletonList(line("FINISHED", 5L, "100")), 999L)).errorCode());
    }

    // ------------------------------------------------------------ TE-T109-006 拒因列计划

    @Test
    @DisplayName("FR-SPOT-16 验收 2：被生效计划引用 → 拒因列「计划」（plans=[555]）")
    void docSubAccountRejectionListsPlans() {
        subAccountDao.rows.add(subAccount(9, "ENABLED"));
        purchaseDao.rows.add(contract(1L, 8));
        detailDao.rows.add(detail(100L, "PURCHASE_CONTRACT", 1L));
        exposureDao.rows.add(coveredExposure(100L, 8, "10"));
        bindDao.rows.add(bind(100L, 555L));

        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(8, 9))
                .changeDocSubAccount("PURCHASE_CONTRACT", 1L, 9, 999L));
        assertEquals(ErrorCode.REFERENCE_CONFLICT, e.errorCode());
        assertTrue(e.getMessage().contains("plans=[555]"),
                "拒因须列计划而非仅列敞口 ID：" + e.getMessage());
        assertFalse(e.getMessage().contains("exposureIds="), "不得再以 exposureIds 作拒因主口径");
    }

    @Test
    @DisplayName("FR-SPOT-16：未被引用 → 变更成功并留痕（from/to 回传）")
    void docSubAccountChangeSucceedsAndLogs() {
        subAccountDao.rows.add(subAccount(9, "ENABLED"));
        purchaseDao.rows.add(contract(1L, 8));

        Map<String, Object> result = service(uid -> Arrays.asList(8, 9))
                .changeDocSubAccount("PURCHASE_CONTRACT", 1L, 9, 999L);

        assertEquals(8, result.get("fromSubAccountId"));
        assertEquals(9, result.get("toSubAccountId"));
        assertEquals(9, purchaseDao.rows.get(0).subAccountId, "单据归属已落库");
        assertEquals(1, changeLogDao.rows.size(), "变更留痕");
    }

    @Test
    @DisplayName("TE-T109-002 同类：归属变更对源子账户越权 ⇒ 10004")
    void docSubAccountCrossSubAccountRejected() {
        subAccountDao.rows.add(subAccount(9, "ENABLED"));
        purchaseDao.rows.add(contract(1L, 8));

        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class, () -> service(uid -> Arrays.asList(9))
                        .changeDocSubAccount("PURCHASE_CONTRACT", 1L, 9, 999L)).errorCode(),
                "对不可见的源子账户（8）变更归属须拒");
    }

    // ------------------------------------------------------------ 辅助

    private SpotService service(SubAccountVisibility visibility) {
        return new SpotService(FakeTx.newTemplate(), purchaseDao, null, null, null, null, priceDao,
                exposureDao, detailDao, changeLogDao, planDao, planLineDao, subAccountDao, productDao,
                visibility, bindDao);
    }

    private static Product product(long id, String code) {
        Product p = new Product();
        p.id = id;
        p.productCode = code;
        return p;
    }

    private static Exposure exposure(long id, int subAccountId) {
        Exposure e = new Exposure();
        e.id = id;
        e.subAccountId = subAccountId;
        e.productId = 5L;
        e.spotCategoryCode = "CAT-CU";
        e.direction = "BUY";
        e.calcMode = "COMBINED";
        e.asOfDate = LocalDate.of(2026, 9, 18);
        e.exposureQty = new BigDecimal("100");
        e.coveredQty = BigDecimal.ZERO;
        e.unit = "TON";
        e.status = "PENDING_HEDGE";
        return e;
    }

    private static Exposure coveredExposure(long id, int subAccountId, String covered) {
        Exposure e = exposure(id, subAccountId);
        e.coveredQty = new BigDecimal(covered);
        return e;
    }

    private static ExposureDetail detail(long exposureId, String sourceType, long sourceId) {
        ExposureDetail d = new ExposureDetail();
        d.exposureId = exposureId;
        d.sourceType = sourceType;
        d.sourceId = sourceId;
        d.contributedQty = new BigDecimal("100");
        return d;
    }

    private static HedgePlanExposure bind(long exposureId, long planId) {
        HedgePlanExposure b = new HedgePlanExposure();
        b.exposureId = exposureId;
        b.hedgePlanId = planId;
        return b;
    }

    private static PurchaseContract contract(long id, int subAccountId) {
        PurchaseContract c = new PurchaseContract();
        c.id = id;
        c.subAccountId = subAccountId;
        c.contractNo = "PC-001";
        return c;
    }

    private static ProductionPlan plan(Long id, int subAccountId, LocalDate start, LocalDate end) {
        ProductionPlan p = new ProductionPlan();
        if (id != null) {
            p.id = id;
        }
        p.subAccountId = subAccountId;
        p.planNo = "PP-" + System.nanoTime();
        p.planPeriodStart = start;
        p.planPeriodEnd = end;
        return p;
    }

    private static ProductionPlanLine line(String type, Long productId, String qty) {
        ProductionPlanLine l = new ProductionPlanLine();
        l.lineType = type;
        l.productId = productId;
        l.quantity = new BigDecimal(qty);
        l.unit = "TON";
        return l;
    }

    private static SubAccount subAccount(int id, String status) {
        SubAccount s = new SubAccount();
        s.id = id;
        s.status = status;
        return s;
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakeProductDao extends UnsupportedBaseDao<Product, Long>
            implements ProductDao {

        private final List<Product> rows = new ArrayList<>();

        @Override
        public Product findByCode(String productCode) {
            for (Product row : rows) {
                if (row.productCode.equals(productCode)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<Product> findAllOrderByCode() {
            return new ArrayList<>(rows);
        }

        // T-105：ProductDao 新增读取面方法（本用例不驱动列表/导出，显式失败）
        @Override
        public com.hedgeguard.dao.PageResult<Product> findPageScoped(com.hedgeguard.dao.PageQuery query,
                com.hedgeguard.dao.SubAccountScope scope, String extraWhere, List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public List<Product> findAllScoped(com.hedgeguard.dao.SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findAllScoped");
        }

        @Override
        public List<Product> findAllOrderByCreatedDesc() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findAllOrderByCreatedDesc");
        }

        // T-106：ProductDao 新增单对象读取面方法（本用例不驱动详情，显式失败）
        @Override
        public Product findByIdScoped(long id, com.hedgeguard.dao.SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByIdScoped");
        }
    }

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
                if (row.productId != null && row.productId == productId && "Y".equals(row.isShared)
                        && row.priceDate != null && row.priceDate.equals(priceDate)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public SpotPrice findLatestOnOrBefore(long productId, LocalDate priceDate) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findLatestOnOrBefore");
        }
    }

    private static final class FakeExposureDao extends UnsupportedBaseDao<Exposure, Long>
            implements ExposureDao {

        private final List<Exposure> rows = new ArrayList<>();

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
        public PageResult<Exposure> findPage(PageQuery query, String where, List<Object> args) {
            List<Exposure> filtered = new ArrayList<>();
            for (Exposure row : rows) {
                if (args != null && !args.isEmpty() && args.get(0) instanceof Integer
                        && row.subAccountId != null && row.subAccountId.equals(args.get(0))) {
                    filtered.add(row);
                }
            }
            return new PageResult<>(filtered, filtered.size(), 1, filtered.size());
        }

        @Override
        public PageResult<Exposure> findPageScoped(PageQuery query, SubAccountScope scope,
                                                   String extraWhere, List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public Exposure findByIdScoped(long id, SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByIdScoped");
        }
    }

    private static final class FakeExposureDetailDao extends UnsupportedBaseDao<ExposureDetail, Long>
            implements ExposureDetailDao {

        private final List<ExposureDetail> rows = new ArrayList<>();

        @Override
        public int insert(ExposureDetail entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public List<ExposureDetail> findByExposure(long exposureId) {
            List<ExposureDetail> out = new ArrayList<>();
            for (ExposureDetail row : rows) {
                if (row.exposureId != null && row.exposureId == exposureId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<ExposureDetail> findBySource(String sourceType, long sourceId) {
            List<ExposureDetail> out = new ArrayList<>();
            for (ExposureDetail row : rows) {
                if (row.sourceType != null && row.sourceType.equals(sourceType)
                        && row.sourceId != null && row.sourceId == sourceId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    private static final class FakePurchaseContractDao
            extends UnsupportedBaseDao<PurchaseContract, Long> implements PurchaseContractDao {

        private final List<PurchaseContract> rows = new ArrayList<>();

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
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByContractNo");
        }
    }

    private static final class FakeProductionPlanDao
            extends UnsupportedBaseDao<ProductionPlan, Long> implements ProductionPlanDao {

        private final List<ProductionPlan> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ProductionPlan entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public ProductionPlan findById(Long id) {
            for (ProductionPlan row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public PageResult<ProductionPlan> findPage(PageQuery query, String where, List<Object> args) {
            List<ProductionPlan> filtered = new ArrayList<>();
            for (ProductionPlan row : rows) {
                if (args != null && !args.isEmpty() && args.get(0) instanceof Integer
                        && row.subAccountId != null && row.subAccountId.equals(args.get(0))
                        && !"CLOSED".equals(row.status)) {
                    filtered.add(row);
                }
            }
            return new PageResult<>(filtered, filtered.size(), 1, filtered.size());
        }

        @Override
        public int deleteById(Long id) {
            return rows.removeIf(row -> row.id == id) ? 1 : 0;
        }
    }

    private static final class FakeProductionPlanLineDao
            extends UnsupportedBaseDao<ProductionPlanLine, Long> implements ProductionPlanLineDao {

        private final List<ProductionPlanLine> rows = new ArrayList<>();

        @Override
        public int insert(ProductionPlanLine entity, long operatorId) {
            rows.add(entity);
            return 1;
        }
    }

    private static final class FakeSubAccountDao extends UnsupportedBaseDao<SubAccount, Integer>
            implements SubAccountDao {

        private final List<SubAccount> rows = new ArrayList<>();

        @Override
        public SubAccount findById(Integer id) {
            for (SubAccount row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public SubAccount findByCode(String code) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByCode");
        }

        @Override
        public List<SubAccount> findEnabled() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findEnabled");
        }
    }

    private static final class FakeHedgePlanExposureDao
            extends UnsupportedBaseDao<HedgePlanExposure, Long> implements HedgePlanExposureDao {

        private final List<HedgePlanExposure> rows = new ArrayList<>();

        @Override
        public PageResult<HedgePlanExposure> findPage(PageQuery query,
                                                      String where, List<Object> args) {
            List<HedgePlanExposure> filtered = new ArrayList<>();
            for (HedgePlanExposure row : rows) {
                if (args != null && !args.isEmpty() && row.exposureId != null
                        && row.exposureId.equals(((Number) args.get(0)).longValue())) {
                    filtered.add(row);
                }
            }
            return new PageResult<>(filtered, filtered.size(), 1, filtered.size());
        }

        @Override
        public List<HedgePlanExposure> findByPlan(long hedgePlanId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByPlan");
        }
        @Override
        public List<HedgePlanExposure> findByExposure(long exposureId) {
            List<HedgePlanExposure> out = new ArrayList<>();
            for (HedgePlanExposure row : rows) {
                if (row.exposureId != null && row.exposureId == exposureId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    private static final class FakeSpotChangeLogDao
            extends UnsupportedBaseDao<SpotChangeLog, Long> implements SpotChangeLogDao {

        private final List<SpotChangeLog> rows = new ArrayList<>();

        @Override
        public int insert(SpotChangeLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }
    }
}
