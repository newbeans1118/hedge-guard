package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.InventoryLedgerDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.PurchaseContractDao;
import com.hedgeguard.dao.SalesContractDao;
import com.hedgeguard.dao.SpotChangeLogDao;
import com.hedgeguard.dao.SpotPriceDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.WipRecordDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.entity.InventoryLedger;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductSpotCategory;
import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.SalesContract;
import com.hedgeguard.dao.entity.SpotChangeLog;
import com.hedgeguard.dao.entity.SpotPrice;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-110}：SPOT 写面（{@code T-013} 范围）子账户可见性强制的验收。
 *
 * <p>三类语义分开锁定（一刀切会引入新的误拒缺陷）：
 * <ul>
 *   <li><b>业务归属类</b>（合同 / 库存 / 在制品 / 敞口 / 生产计划；及按主键的合同动作）
 *       ⇒ <b>必须</b>强制调用者对目标子账户可见，越权 {@code 10004} 且<b>不产生任何写入</b>；</li>
 *   <li><b>录入归属类</b>（现货价：{@code putSpotPrice} / {@code importSpotPrices}）
 *       ⇒ <b>不得</b>按业务归属强制（{@code CH-003} / {@code R-SPOT-2} 注：共享市场数据，
 *       行内子账户仅"由谁录入"），本用例锁定该既有语义，防止后人"顺手"补校验而误拒共享价录入；</li>
 *   <li><b>fail-closed</b>（可见性入口缺位 / 可见集为空 / 目标无子账户标识）⇒ 一律拒绝，
 *       <b>绝不</b>退化为"不过滤 = 放行"。</li>
 * </ul>
 *
 * <p>含一组<b>真实 {@code AuthService}</b> 端到端探针（非替身）：证明越权在真实权限判定 +
 * 留痕（{@code R-AUTH-8}）下被证伪，且越权被拒时业务库零写入。
 *
 * <p>完全离线（{@code NFR-TEST-2}）：内存假 DAO 驱动，不含 SQL / 连接。
 */
class SpotT110VisibilityTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");

    /** 操作者只对 {@link #VISIBLE_SUB} 可见。 */
    private static final long OPERATOR = 999L;
    /** 可见子账户。 */
    private static final int VISIBLE_SUB = 7;
    /** 不可见子账户（越权目标）。 */
    private static final int OTHER_SUB = 8;

    private FakePurchaseContractDao purchaseDao;
    private FakeSalesContractDao salesDao;
    private FakeMappingDao mappingDao;
    private FakeWipDao wipDao;
    private FakeInventoryDao inventoryDao;
    private FakeSpotPriceDao priceDao;
    private FakeExposureDao exposureDao;
    private FakeExposureDetailDao detailDao;
    private FakeSpotChangeLogDao changeLogDao;
    private FakeProductDao productDao;

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
        productDao = new FakeProductDao();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ============================================================ 一、业务归属类：越权拒绝 + 零写入

    @Test
    @DisplayName("T-110 越权：createPurchaseContract 写他人子账户 ⇒ 10004 且零写入")
    void createPurchaseCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                .createPurchaseContract(purchase("PC-001", OTHER_SUB), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertEquals(403, e.errorCode().httpStatus(), "对外表现 HTTP 403");
        assertTrue(purchaseDao.rows.isEmpty(), "越权被拒时不得产生任何写入");
        assertTrue(changeLogDao.rows.isEmpty(), "越权被拒时不得留痕任何业务变更");
    }

    @Test
    @DisplayName("T-110 越权：createSalesContract 写他人子账户 ⇒ 10004 且零写入")
    void createSalesCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                .createSalesContract(sales("SC-001", OTHER_SUB), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(salesDao.rows.isEmpty(), "越权被拒时不得产生任何写入");
    }

    @Test
    @DisplayName("T-110 越权：createInventory 写他人子账户 ⇒ 10004 且零写入")
    void createInventoryCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                .createInventory(inventory(OTHER_SUB, new BigDecimal("10")), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(inventoryDao.rows.isEmpty(), "越权被拒时不得产生任何写入");
    }

    @Test
    @DisplayName("T-110 越权：createWip 写他人子账户 ⇒ 10004 且零写入")
    void createWipCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                .createWip(wip(OTHER_SUB, new BigDecimal("0.5")), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(wipDao.rows.isEmpty(), "越权被拒时不得产生任何写入");
    }

    @Test
    @DisplayName("T-110 越权：computeExposure 写他人子账户敞口 ⇒ 10004 且零写入")
    void computeExposureCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                .computeExposure(exposure(OTHER_SUB), new ArrayList<>(), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(exposureDao.rows.isEmpty(), "越权被拒时不得产生敞口快照");
        assertTrue(detailDao.rows.isEmpty(), "越权被拒时不得产生明细");
    }

    @Test
    @DisplayName("T-110 越权：activatePurchaseContract（按 ID，他人单据）⇒ 10004 且状态不变")
    void activateCrossSubAccountRejected() {
        PurchaseContract c = purchase("PC-002", OTHER_SUB);
        c.id = 1L;
        c.status = "DRAFT";
        purchaseDao.rows.add(c);

        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                .activatePurchaseContract(1L, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertEquals("DRAFT", purchaseDao.findById(1L).status, "越权被拒时状态不得改变");
    }

    @Test
    @DisplayName("T-110 越权：voidPurchaseContract（按 ID，他人单据）⇒ 10004 且状态不变")
    void voidCrossSubAccountRejected() {
        PurchaseContract c = purchase("PC-003", OTHER_SUB);
        c.id = 2L;
        c.status = "ACTIVE";
        purchaseDao.rows.add(c);

        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                .voidPurchaseContract(2L, "价格不利", OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertEquals("ACTIVE", purchaseDao.findById(2L).status, "越权被拒时状态不得改变");
    }

    @Test
    @DisplayName("T-110 越权：deleteDraftPurchaseContract（按 ID，他人草稿）⇒ 10004 且行未被删")
    void deleteDraftCrossSubAccountRejected() {
        PurchaseContract c = purchase("PC-004", OTHER_SUB);
        c.id = 3L;
        c.status = "DRAFT";
        purchaseDao.rows.add(c);

        BizException e = assertThrows(BizException.class, () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                .deleteDraftPurchaseContract(3L, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(purchaseDao.findById(3L) != null, "越权被拒时草稿不得被物理删除");
    }

    // ============================================================ 二、业务归属类：合法放行

    @Test
    @DisplayName("T-110 放行：对可见子账户写入合同 / 库存 / 在制品 / 敞口均成功")
    void visibleWritesSucceed() {
        SpotService svc = service(uid -> Arrays.asList(VISIBLE_SUB));

        long pc = svc.createPurchaseContract(purchase("PC-100", VISIBLE_SUB), OPERATOR);
        assertTrue(pc > 0, "可见子账户采购合同应建档成功");

        long sc = svc.createSalesContract(sales("SC-100", VISIBLE_SUB), OPERATOR);
        assertTrue(sc > 0, "可见子账户销售合同应建档成功");

        long inv = svc.createInventory(inventory(VISIBLE_SUB, new BigDecimal("10")), OPERATOR);
        assertTrue(inv > 0, "可见子账户库存应录入成功");

        long wip = svc.createWip(wip(VISIBLE_SUB, new BigDecimal("0.5")), OPERATOR);
        assertTrue(wip > 0, "可见子账户在制品应录入成功");

        Exposure stored = svc.computeExposure(exposure(VISIBLE_SUB), new ArrayList<>(), OPERATOR);
        assertTrue(stored.id > 0, "可见子账户敞口应落库成功");
        assertEquals(VISIBLE_SUB, stored.subAccountId);
    }

    @Test
    @DisplayName("T-110 放行：对可见子账户按 ID 动作（提交/作废/删除草稿）均成功")
    void visibleIdActionsSucceed() {
        SpotService svc = service(uid -> Arrays.asList(VISIBLE_SUB));
        mappingDao.mapping = mapping(5L, "CAT-CU");   // 使提交为有效可通过映射前置

        long id = svc.createPurchaseContract(purchase("PC-101", VISIBLE_SUB), OPERATOR);
        svc.activatePurchaseContract(id, OPERATOR);
        assertEquals("ACTIVE", purchaseDao.findById(id).status);
        svc.voidPurchaseContract(id, "价格不利", OPERATOR);
        assertEquals("VOID", purchaseDao.findById(id).status);

        long draft = svc.createPurchaseContract(purchase("PC-102", VISIBLE_SUB), OPERATOR);
        svc.deleteDraftPurchaseContract(draft, OPERATOR);
        assertNull(purchaseDao.findById(draft), "可见子账户草稿应被物理删除");
    }

    @Test
    @DisplayName("T-115 写面拒绝：跨账户只读授权（哨兵 0）对任意子账户 createPurchaseContract 拒 10004")
    void crossReadonlySentinelRejected() {
        // T-115：跨子账户只读（哨兵 0）读面放行、写面必须拒绝（FR-AUTH-13 验收 3）。
        // 旧断言（放行、pc>0）编码的是错误口径，已订正为「拒 + 零写入」。
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Collections.singletonList(0))
                        .createPurchaseContract(purchase("PC-200", OTHER_SUB), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(),
                "跨账户只读不得写（10004）");
        assertTrue(purchaseDao.rows.isEmpty(), "跨账户只读被拒时不得产生写入");
    }

    // ============================================================ 三、fail-closed

    @Test
    @DisplayName("T-110 fail-closed：可见性入口缺位（null）⇒ 10004 且零写入")
    void visibilityMissingFailsClosed() {
        SpotService noVisibility = new SpotService(FakeTx.newTemplate(), purchaseDao, salesDao,
                mappingDao, wipDao, inventoryDao, priceDao, exposureDao, detailDao, changeLogDao,
                null, null, null, productDao, null, null);
        BizException e = assertThrows(BizException.class,
                () -> noVisibility.createInventory(inventory(VISIBLE_SUB, new BigDecimal("10")), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(inventoryDao.rows.isEmpty(), "入口缺位时不得退化为放行");
    }

    @Test
    @DisplayName("T-110 fail-closed：可见集为空（无任何授权）⇒ 10004")
    void emptyVisibleSetFailsClosed() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> new ArrayList<Integer>())
                        .createWip(wip(VISIBLE_SUB, new BigDecimal("0.5")), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(wipDao.rows.isEmpty(), "空可见集不得退化为全可见");
    }

    @Test
    @DisplayName("T-110 fail-closed：按 ID 动作的目标行无子账户标识 ⇒ 10004（不 NPE / 不放行）")
    void nullSubAccountOnByIdRejected() {
        PurchaseContract orphan = purchase("PC-300", OTHER_SUB);
        orphan.id = 9L;
        orphan.status = "DRAFT";
        orphan.subAccountId = null;   // 数据异常：DB NOT NULL 约束被破坏
        purchaseDao.rows.add(orphan);

        BizException e = assertThrows(BizException.class, () -> service(
                uid -> Collections.singletonList(0)).activatePurchaseContract(9L, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertEquals("DRAFT", purchaseDao.findById(9L).status, "异常行不得被写入");
    }

    // ============================================================ 四、录入归属类：不得按业务归属强制

    @Test
    @DisplayName("T-110 录入归属：putSpotPrice 对不可见子账户仍成功（CH-003：共享市场数据，勿误拒）")
    void putSpotPriceNotBusinessScoped() {
        SpotService svc = service(uid -> Arrays.asList(VISIBLE_SUB));
        long id = svc.putSpotPrice(price(OTHER_SUB), false, OPERATOR);
        assertTrue(id > 0, "现货价属全账户共享数据，不得按业务归属强制（否则误拒正常录入）");
        assertEquals(OTHER_SUB, priceDao.rows.get(0).subAccountId, "行内子账户仅为录入归属");
        assertEquals("Y", priceDao.rows.get(0).isShared, "GLOBAL 口径恒共享");
    }

    @Test
    @DisplayName("T-110 录入归属：importSpotPrices 对不可见子账户仍成功（CH-003）")
    void importSpotPricesNotBusinessScoped() {
        productDao.rows.add(product(5L, "CU"));
        SpotService svc = service(uid -> Arrays.asList(VISIBLE_SUB));
        Map<String, Object> summary = svc.importSpotPrices(
                Arrays.asList("CU,2026-09-18,70000"), OTHER_SUB, "BATCH_PASTE", null, false, OPERATOR);
        assertEquals(1, summary.get("inserted"), "共享价导入不得按业务归属强制");
        assertFalse(priceDao.rows.isEmpty());
    }

    @Test
    @DisplayName("T-110 对照：同一操作者对同一子账户写库存被拒、写现货价放行（分类边界）")
    void categoryBoundarySameOperator() {
        SpotService svc = service(uid -> Arrays.asList(VISIBLE_SUB));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class, () -> svc.createInventory(
                        inventory(OTHER_SUB, new BigDecimal("10")), OPERATOR)).errorCode(),
                "库存属业务归属 ⇒ 强制");
        assertTrue(svc.putSpotPrice(price(OTHER_SUB), false, OPERATOR) > 0,
                "现货价属录入归属 ⇒ 不强制");
    }

    // ============================================================ 五、真实 AuthService 端到端探针

    @Test
    @DisplayName("T-110 端到端（真实 AuthService）：越权写合同被拒 10004 + 留痕 + 业务库零写入")
    void realAuthServiceRejectsCrossSubAccountWrite() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        AuthService auth = authService(permDao, attemptDao);
        permDao.rows.add(perm(OPERATOR, VISIBLE_SUB, AuthService.PERM_LIMITED));

        // 注入真实 AuthService 作为可见性实现
        SpotService svc = new SpotService(FakeTx.newTemplate(), purchaseDao, salesDao, mappingDao,
                wipDao, inventoryDao, priceDao, exposureDao, detailDao, changeLogDao,
                null, null, null, productDao, auth, null);

        // 越权：写他人子账户 ⇒ 真实权限判定拒绝
        BizException e = assertThrows(BizException.class,
                () -> svc.createPurchaseContract(purchase("PC-900", OTHER_SUB), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(purchaseDao.rows.isEmpty(), "越权被拒时业务库零写入");
        assertFalse(attemptDao.rows.isEmpty(), "越权尝试须留痕（R-AUTH-8）");
        assertEquals(AuthService.ATTEMPT_SUB_ACCOUNT_DENIED, attemptDao.rows.get(0).attemptType);

        // 合法：写自己可见子账户 ⇒ 通过
        long id = svc.createPurchaseContract(purchase("PC-901", VISIBLE_SUB), OPERATOR);
        assertTrue(id > 0, "真实 AuthService 下对可见子账户应放行");
    }

    @Test
    @DisplayName("T-110 端到端（真实 AuthService）：现货价录入归属不受业务归属限制")
    void realAuthServiceAllowsSpotPriceRecording() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        AuthService auth = authService(permDao, attemptDao);
        permDao.rows.add(perm(OPERATOR, VISIBLE_SUB, AuthService.PERM_LIMITED));

        SpotService svc = new SpotService(FakeTx.newTemplate(), purchaseDao, salesDao, mappingDao,
                wipDao, inventoryDao, priceDao, exposureDao, detailDao, changeLogDao,
                null, null, null, productDao, auth, null);

        assertTrue(svc.putSpotPrice(price(OTHER_SUB), false, OPERATOR) > 0,
                "现货价录入归属不参与业务归属判定，真实权限下亦放行");
        assertTrue(attemptDao.rows.isEmpty(), "合法录入不得产生越权留痕");
    }

    // ============================================================ 辅助

    private SpotService service(SubAccountVisibility visibility) {
        return new SpotService(FakeTx.newTemplate(), purchaseDao, salesDao, mappingDao, wipDao,
                inventoryDao, priceDao, exposureDao, detailDao, changeLogDao, null, null, null,
                productDao, visibility, null);
    }

    /** 真实 {@link AuthService}（仅装配可见性判定所需的两个 DAO；其余入参不被驱动 → 传 {@code null}）。 */
    private static AuthService authService(FakePermDao permDao, FakeAttemptDao attemptDao) {
        return new AuthService(FakeTx.newTemplate(), null, null, null, null, null, null, null,
                permDao, attemptDao, null, null, null, null, null);
    }

    private static UserSubAccountPerm perm(long userId, int subAccountId, String permType) {
        UserSubAccountPerm perm = new UserSubAccountPerm();
        perm.userId = userId;
        perm.subAccountId = subAccountId;
        perm.permType = permType;
        perm.grantedBy = 1L;
        perm.effectiveFrom = LocalDate.of(2026, 1, 1);
        return perm;
    }

    private static PurchaseContract purchase(String no, int subAccountId) {
        PurchaseContract c = new PurchaseContract();
        c.subAccountId = subAccountId;
        c.contractNo = no;
        c.spotCategoryCode = "CAT-CU";
        c.totalQty = new BigDecimal("100");
        c.contractStartDate = LocalDate.of(2026, 1, 1);
        c.contractEndDate = LocalDate.of(2026, 12, 31);
        return c;
    }

    private static SalesContract sales(String no, int subAccountId) {
        SalesContract c = new SalesContract();
        c.subAccountId = subAccountId;
        c.contractNo = no;
        c.spotCategoryCode = "CAT-CU";
        c.totalQty = new BigDecimal("80");
        c.contractStartDate = LocalDate.of(2026, 1, 1);
        c.contractEndDate = LocalDate.of(2026, 12, 31);
        return c;
    }

    private static InventoryLedger inventory(int subAccountId, BigDecimal qty) {
        InventoryLedger l = new InventoryLedger();
        l.subAccountId = subAccountId;
        l.productId = 5L;
        l.inventoryType = "RAW_MATERIAL";
        l.quantity = qty;
        l.negativeConfirmed = "N";
        l.unit = "TON";
        l.recordDate = LocalDate.of(2026, 9, 20);
        l.exposureDirection = "BUY";
        return l;
    }

    private static WipRecord wip(int subAccountId, BigDecimal ratio) {
        WipRecord r = new WipRecord();
        r.subAccountId = subAccountId;
        r.productId = 5L;
        r.wipQty = new BigDecimal("20");
        r.completionRatio = ratio;
        r.exposureDirection = "BUY";
        r.recordDate = LocalDate.of(2026, 9, 20);
        r.unit = "TON";
        return r;
    }

    private static SpotPrice price(int subAccountId) {
        SpotPrice p = new SpotPrice();
        p.subAccountId = subAccountId;
        p.productId = 5L;
        p.priceDate = Clocks.today();
        p.spotPrice = new BigDecimal("71000");
        p.sourceType = "MANUAL";
        p.priceUnit = "CNY/TON";
        return p;
    }

    /** 未映射敞口（productId=哨兵 0）：绕开估值取价，聚焦"越权/放行"判定。 */
    private static Exposure exposure(int subAccountId) {
        Exposure e = new Exposure();
        e.subAccountId = subAccountId;
        e.productId = 0L;
        e.unmappedFlag = "Y";
        e.spotCategoryCode = "CAT-UNMAPPED";
        e.direction = "BUY";
        e.calcMode = "CONTRACT_UNEXECUTED";
        e.contractUnexecutedQty = new BigDecimal("10");
        e.inventoryQty = BigDecimal.ZERO;
        e.productionPlanQty = BigDecimal.ZERO;
        e.exposureQty = new BigDecimal("10");
        e.unit = "TON";
        e.asOfDate = LocalDate.of(2026, 9, 20);
        return e;
    }

    private static Product product(long id, String code) {
        Product p = new Product();
        p.id = id;
        p.productCode = code;
        return p;
    }

    private static ProductSpotCategory mapping(long productId, String category) {
        ProductSpotCategory m = new ProductSpotCategory();
        m.spotCategoryCode = category;
        m.productId = productId;
        m.mappingDirection = "PURCHASE";
        m.isPrimary = "Y";
        m.status = "ENABLED";
        return m;
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakePurchaseContractDao
            extends UnsupportedBaseDao<PurchaseContract, Long> implements PurchaseContractDao {

        private final List<PurchaseContract> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(PurchaseContract entity, long operatorId) {
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
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByContractNo");
        }
    }

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
        public SalesContract findByContractNo(int subAccountId, String contractNo) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByContractNo");
        }

        @Override
        public int deleteById(Long id) {
            return rows.removeIf(row -> row.id == id) ? 1 : 0;
        }
    }

    private static final class FakeMappingDao
            extends UnsupportedBaseDao<ProductSpotCategory, Long> implements ProductSpotCategoryDao {

        private ProductSpotCategory mapping;

        @Override
        public ProductSpotCategory findPrimaryMapping(String spotCategoryCode,
                                                      String mappingDirection) {
            return mapping;
        }

        @Override
        public List<ProductSpotCategory> findByProduct(long productId) {
            return new ArrayList<>();
        }
    }

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

    private static final class FakeInventoryDao extends UnsupportedBaseDao<InventoryLedger, Long>
            implements InventoryLedgerDao {

        private final List<InventoryLedger> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(InventoryLedger entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
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
        private long nextId = 1;

        @Override
        public int insert(Exposure entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
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
                if (row.subAccountId != null && row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    private static final class FakeExposureDetailDao extends UnsupportedBaseDao<ExposureDetail, Long>
            implements ExposureDetailDao {

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

    private static final class FakeSpotChangeLogDao
            extends UnsupportedBaseDao<SpotChangeLog, Long> implements SpotChangeLogDao {

        private final List<SpotChangeLog> rows = new ArrayList<>();

        @Override
        public int insert(SpotChangeLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }
    }

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
    }

    private static final class FakePermDao extends UnsupportedBaseDao<UserSubAccountPerm, Long>
            implements UserSubAccountPermDao {

        private final List<UserSubAccountPerm> rows = new ArrayList<>();

        @Override
        public List<UserSubAccountPerm> findByUser(long userId) {
            List<UserSubAccountPerm> out = new ArrayList<>();
            for (UserSubAccountPerm row : rows) {
                if (row.userId == userId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    private static final class FakeAttemptDao
            extends UnsupportedBaseDao<UnauthorizedAttempt, Long> implements UnauthorizedAttemptDao {

        private final List<UnauthorizedAttempt> rows = new ArrayList<>();

        @Override
        public int insert(UnauthorizedAttempt entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public long countByTypeBetween(String attemptType, java.time.LocalDateTime from,
                                       java.time.LocalDateTime to) {
            return 0;
        }

        @Override
        public List<UnauthorizedAttempt> findByOperatorSince(long operatorId,
                                                            java.time.LocalDateTime from) {
            return new ArrayList<>(rows);
        }
    }
}
