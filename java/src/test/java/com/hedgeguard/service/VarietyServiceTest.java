package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductChangeLogDao;
import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductQuotaDao;
import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.ProductUnitDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.SubInstrumentQuotaDao;
import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.entity.ProductQuota;
import com.hedgeguard.dao.entity.ProductSpotCategory;
import com.hedgeguard.dao.entity.ProductUnit;
import com.hedgeguard.dao.entity.SubInstrumentQuota;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VarietyService} 单元测试（{@code T-012} 品种管理与白名单 / 三层额度）。
 *
 * <p>覆盖四条验收：①品种增删改启停 + 换算 + 合约 + 现货映射；②白名单按子账户、额度按三层；
 * ③CSV **全量预校验后一次性插入**（无部分写入）；④导出可用；另加变更留痕断言。
 */
class VarietyServiceTest {

    private FakeProductDao productDao;
    private FakeProductUnitDao unitDao;
    private FakeProductContractDao contractDao;
    private FakeSpotCategoryDao mappingDao;
    private FakeWhitelistDao whitelistDao;
    private FakeQuotaDao quotaDao;
    private FakeInstrumentQuotaDao instrumentQuotaDao;
    private FakeChangeLogDao changeLogDao;
    private VarietyService service;

    @BeforeEach
    void setUp() {
        productDao = new FakeProductDao();
        unitDao = new FakeProductUnitDao();
        contractDao = new FakeProductContractDao();
        mappingDao = new FakeSpotCategoryDao();
        whitelistDao = new FakeWhitelistDao();
        quotaDao = new FakeQuotaDao();
        instrumentQuotaDao = new FakeInstrumentQuotaDao();
        changeLogDao = new FakeChangeLogDao();
        service = new VarietyService(FakeTx.newTemplate(), productDao, unitDao, contractDao, mappingDao,
                whitelistDao, quotaDao, instrumentQuotaDao, changeLogDao,
                // T-115：业务规则用例（非越权用例）——注入「可写子账户」的可见性替身
                // （3=白名单/层①/层②写入目标；9=removeWhitelist「无行」用例需先通过写入断言）。
                // 原注入哨兵 0（跨账户只读）在 T-115 后不再授予写权限；越权/fail-closed 语义
                // 由 VarietyT112VisibilityTest 覆盖。
                uid -> Arrays.asList(3, 9));
    }

    // ------------------------------------------------------------ 品种档案

    @Test
    @DisplayName("新增品种：强制境内场内 + CNY、状态 ENABLED，并留痕 CREATE")
    void createProductForcesInvariants() {
        Product product = product("CU", "铜", "COMMODITY");
        product.isDomesticExchange = "N";     // 试图绕过 → 必须被强制为 Y
        product.currency = "USD";
        product.status = "DISABLED";

        long id = service.createProduct(product, 1L);

        Product stored = productDao.findById(id);
        assertEquals("Y", stored.isDomesticExchange, "境外/场外永久排除（R-TRADE-16）");
        assertEquals("CNY", stored.currency, "本项目仅 CNY");
        assertEquals("ENABLED", stored.status);
        assertEquals(1, changeLogDao.rows.size());
        assertEquals("CREATE", changeLogDao.rows.get(0).changeType);
        assertEquals(id, changeLogDao.rows.get(0).productId);
    }

    @Test
    @DisplayName("新增品种：编码重复 10010 / 格式非法 10001 / 分类越域 10001")
    void createProductValidations() {
        service.createProduct(product("CU", "铜", "COMMODITY"), 1L);

        assertEquals(ErrorCode.UNIQUE_CONFLICT,
                assertThrows(BizException.class,
                        () -> service.createProduct(product("CU", "铜二号", "COMMODITY"), 1L)).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.createProduct(product("铜-1", "非法编码", "COMMODITY"), 1L))
                        .errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.createProduct(product("AL", "铝", "METAL"), 1L)).errorCode());
    }

    @Test
    @DisplayName("停用品种：状态置 DISABLED + 白名单级联移出（置 DISABLED/PENDING 并逐行留痕）")
    void disableCascadesWhitelist() {
        long productId = service.createProduct(product("CU", "铜", "COMMODITY"), 1L);
        whitelistDao.rows.add(whitelist(1, productId, "ENABLED"));
        whitelistDao.rows.add(whitelist(2, productId, "DISABLED"));   // 已停用者不重复处置
        int logsBefore = changeLogDao.rows.size();

        int removed = service.disableProduct(productId, "业务退出铜品种", 9L);

        assertEquals(1, removed, "仅级联移出启用中的白名单行");
        assertEquals("DISABLED", productDao.findById(productId).status);
        assertEquals("业务退出铜品种", productDao.findById(productId).disabledReason);
        assertEquals("DISABLED", findWhitelist(1, productId).status);
        assertEquals("PENDING", findWhitelist(1, productId).dispatchStatus, "变更须待下发（R-PRODUCT-9）");
        assertTrue(findWhitelist(1, productId).disabledReason.contains("级联"));
        assertEquals(2, changeLogDao.rows.size() - logsBefore, "品种停用 + 白名单移出各留痕一条");
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.disableProduct(productId, "重复", 9L)).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.disableProduct(999L, "不存在", 9L))
                        .errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.disableProduct(productId, " ", 9L))
                        .errorCode());
    }

    @Test
    @DisplayName("启用品种：缺生效换算关系时 10015 拒绝（R-PRODUCT-6）；维护后放行")
    void enableRequiresEffectiveUnit() {
        long productId = service.createProduct(product("CU", "铜", "COMMODITY"), 1L);
        service.disableProduct(productId, "临时停用", 1L);

        assertEquals(ErrorCode.DATA_INSUFFICIENT,
                assertThrows(BizException.class, () -> service.enableProduct(productId, 1L)).errorCode());

        unitDao.rows.add(unit(productId, new BigDecimal("5"), LocalDate.of(2026, 1, 1)));
        service.enableProduct(productId, 1L);
        assertEquals("ENABLED", productDao.findById(productId).status);
        assertNull(productDao.findById(productId).disabledReason, "启用后清空停用原因");
    }

    @Test
    @DisplayName("现货折算：取基准日生效换算关系；无换算关系拒绝、系数非正拒绝")
    void convertToFuturesUnits() {
        long productId = service.createProduct(product("CU", "铜", "COMMODITY"), 1L);
        unitDao.rows.add(unit(productId, new BigDecimal("5"), LocalDate.of(2026, 1, 1)));
        unitDao.rows.add(unit(productId, new BigDecimal("4"), LocalDate.of(2027, 1, 1)));

        assertEquals(0, new BigDecimal("20.000000").compareTo(
                        service.convertToFuturesUnits(productId, new BigDecimal("100"),
                                LocalDate.of(2026, 6, 1))),
                "2026-06-01 生效系数 5 → 100/5 = 20");
        assertEquals(0, new BigDecimal("25.000000").compareTo(
                        service.convertToFuturesUnits(productId, new BigDecimal("100"),
                                LocalDate.of(2027, 6, 1))),
                "2027-06-01 生效系数 4 → 不追溯重算历史");

        assertEquals(ErrorCode.DATA_INSUFFICIENT,
                assertThrows(BizException.class,
                        () -> service.convertToFuturesUnits(productId, new BigDecimal("10"),
                                LocalDate.of(2025, 1, 1))).errorCode(), "早于生效日无换算关系 → 拒绝");

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putUnit(unit(productId, BigDecimal.ZERO, LocalDate.of(2026, 2, 1)),
                                1L)).errorCode(), "系数 0 拒绝");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putUnit(unit(productId, new BigDecimal("-1"),
                                LocalDate.of(2026, 2, 1)), 1L)).errorCode(), "负系数拒绝");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putUnit(unit(999L, new BigDecimal("5"), LocalDate.of(2026, 2, 1)),
                                1L)).errorCode(), "品种不存在拒绝");
    }

    @Test
    @DisplayName("合约关联：期权三要素必填；合约类型必显式；境内强制 Y")
    void putContractOptionRequirements() {
        long productId = service.createProduct(product("CU", "铜", "COMMODITY"), 1L);
        ProductContract futures = contract(productId, "cu2612", "FUTURES");
        long id = service.putContract(futures, 1L);
        assertEquals("Y", contractDao.findById(id).isDomesticExchange);

        ProductContract option = contract(productId, "cu2612C70000", "OPTION");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.putContract(option, 1L)).errorCode(),
                "期权缺标的/行权价/类型应拒绝");
        option.underlyingInstrumentId = "cu2612";
        option.strikePrice = new BigDecimal("70000");
        option.optionType = "CALL";
        assertTrue(service.putContract(option, 1L) > 0);

        ProductContract bad = contract(productId, "cu2701", "SWAP");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.putContract(bad, 1L)).errorCode());
    }

    @Test
    @DisplayName("现货品类映射：同品类同方向已有主映射时不得再设主映射（10010）")
    void putSpotMappingPrimaryUnique() {
        long cu = service.createProduct(product("CU", "铜", "COMMODITY"), 1L);
        long al = service.createProduct(product("AL", "铝", "COMMODITY"), 1L);
        ProductSpotCategory first = mapping("CAT-CU-CATHODE", cu, "PURCHASE", "Y");
        service.putSpotMapping(first, 1L);

        ProductSpotCategory second = mapping("CAT-CU-CATHODE", al, "PURCHASE", "Y");
        assertEquals(ErrorCode.UNIQUE_CONFLICT,
                assertThrows(BizException.class, () -> service.putSpotMapping(second, 1L)).errorCode(),
                "须先取消原主映射（FR-PRODUCT-10 验收 3）");
        second.isPrimary = "N";
        assertTrue(service.putSpotMapping(second, 1L) > 0, "非主映射可共存");

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putSpotMapping(mapping("C", cu, "UP", "N"), 1L)).errorCode(),
                "映射方向越域应拒绝");
    }

    // ------------------------------------------------------------ 白名单与三层额度

    @Test
    @DisplayName("白名单：新增置 ENABLED/PENDING；重复加入拒绝；移出后重新加入（保留历史）")
    void whitelistLifecycle() {
        long productId = service.createProduct(product("CU", "铜", "COMMODITY"), 1L);
        service.putWhitelist(3, productId, "TRADE", 1L);
        SubProductWhitelist row = findWhitelist(3, productId);
        assertEquals("ENABLED", row.status);
        assertEquals("PENDING", row.dispatchStatus);
        assertEquals("TRADE", row.categoryOverride, "品种级分类覆盖（Q-2）");

        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class, () -> service.putWhitelist(3, productId, null, 1L))
                        .errorCode());

        service.removeWhitelist(3, productId, "指标调整", 1L);
        assertEquals("DISABLED", findWhitelist(3, productId).status);
        assertEquals("PENDING", findWhitelist(3, productId).dispatchStatus);
        assertEquals("指标调整", findWhitelist(3, productId).disabledReason);
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class, () -> service.removeWhitelist(3, productId, "重复", 1L))
                        .errorCode());

        service.putWhitelist(3, productId, null, 1L);
        assertEquals("ENABLED", findWhitelist(3, productId).status, "重新加入须复用同一行（保留历史）");
        assertNull(findWhitelist(3, productId).disabledReason);

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.putWhitelist(0, productId, null, 1L))
                        .errorCode(), "哨兵值 0 不得作为业务子账户");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.removeWhitelist(9, productId, "无行", 1L))
                        .errorCode());
    }

    @Test
    @DisplayName("额度：层①须真实子账户、层③强制哨兵 0、非负校验、单位取值域")
    void quotaLayerSentinel() {
        long productId = service.createProduct(product("CU", "铜", "COMMODITY"), 1L);
        LocalDate from = LocalDate.of(2026, 1, 1);

        service.putQuota(3, "SUB_PRODUCT", productId, new BigDecimal("100"), "LOT", from, 1L);
        assertEquals(3, quotaDao.rows.get(0).subAccountId);
        assertEquals("PENDING", quotaDao.rows.get(0).dispatchStatus);

        service.putQuota(7, "ACCOUNT_PRODUCT", productId, new BigDecimal("500"), "LOT", from, 1L);
        assertEquals(0, quotaDao.rows.get(1).subAccountId, "层③强制哨兵值 0（DC-020）");

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putQuota(0, "SUB_PRODUCT", productId, new BigDecimal("10"), "LOT",
                                from, 1L)).errorCode(), "层①不得用哨兵值 0");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putQuota(3, "SUB_PRODUCT", productId, new BigDecimal("-1"), "LOT",
                                from, 1L)).errorCode(), "额度不得为负");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putQuota(3, "SUB_PRODUCT", productId, new BigDecimal("1"), "KG",
                                from, 1L)).errorCode(), "单位越域");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putQuota(3, "SUB_INSTRUMENT", productId, new BigDecimal("1"), "LOT",
                                from, 1L)).errorCode(), "层②须走合约额度入口");

        // 层②：合约额度
        service.putInstrumentQuota(3, "cu2612", productId, new BigDecimal("20"), "LOT", from, 1L);
        assertEquals("cu2612", instrumentQuotaDao.rows.get(0).instrumentId);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.putInstrumentQuota(0, "cu2612", productId, new BigDecimal("1"),
                                "LOT", from, 1L)).errorCode());
    }

    @Test
    @DisplayName("三层限额全景：层①/层③/层②按品种过滤，且显式声明非权威源")
    void quotaPanorama() {
        long cu = service.createProduct(product("CU", "铜", "COMMODITY"), 1L);
        long al = service.createProduct(product("AL", "铝", "COMMODITY"), 1L);
        LocalDate from = LocalDate.of(2026, 1, 1);
        service.putQuota(3, "SUB_PRODUCT", cu, new BigDecimal("100"), "LOT", from, 1L);
        service.putQuota(3, "SUB_PRODUCT", al, new BigDecimal("50"), "LOT", from, 1L);
        service.putQuota(7, "ACCOUNT_PRODUCT", cu, new BigDecimal("500"), "LOT", from, 1L);
        service.putInstrumentQuota(3, "cu2612", cu, new BigDecimal("20"), "LOT", from, 1L);
        service.putInstrumentQuota(3, "al2612", al, new BigDecimal("10"), "LOT", from, 1L);

        Map<String, Object> panorama = service.quotaPanorama(3, cu);
        assertEquals(1, ((List<?>) panorama.get("layer1SubProduct")).size());
        assertEquals(1, ((List<?>) panorama.get("layer3AccountProduct")).size());
        assertEquals(1, ((List<?>) panorama.get("layer2SubInstrument")).size());
        assertTrue(String.valueOf(panorama.get("note")).contains("R-RISK-9"),
                "须显式声明库内投影非判定权威源");
    }

    // ------------------------------------------------------------ CSV 导入 / 导出

    @Test
    @DisplayName("CSV 导入：全部合格才一次性插入，且逐行留痕")
    void importAllOrNothing() {
        List<String> csv = Arrays.asList(
                "product_code,product_name,category,currency,remark",
                "CU,铜,COMMODITY,CNY,\"阴极铜,标准\"",
                "AL,铝,COMMODITY,,");
        int inserted = service.importProducts(csv, 1L);

        assertEquals(2, inserted);
        assertEquals(2, productDao.rows.size());
        assertEquals("CNY", productDao.findById(productDao.rows.get(0).id).currency);
        assertEquals(2, changeLogDao.rows.size(), "逐行留痕");
    }

    @Test
    @DisplayName("CSV 导入：缺列 / 分类越域 / 币种非 CNY / 文件内重复 / 库内重复 → 10019 且零写入")
    void importRejectsInvalidFileAtomically() {
        service.createProduct(product("AG", "白银", "COMMODITY"), 1L);
        int before = productDao.rows.size();

        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED,
                assertThrows(BizException.class, () -> service.importProducts(
                        Arrays.asList("product_code,product_name", "CU,铜"), 1L)).errorCode(),
                "缺列须拒绝");
        assertEquals(before, productDao.rows.size(), "缺列时不得写入");

        BizException mixed = assertThrows(BizException.class, () -> service.importProducts(Arrays.asList(
                "product_code,product_name,category,currency,remark",
                "CU,铜,METAL,CNY,分类越域",
                "AG,白银,COMMODITY,USD,币种非法",
                "CU,铜二号,COMMODITY,CNY,文件内重复",
                "PB,铅,COMMODITY,CNY,库内已存在"), 1L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, mixed.errorCode());
        assertTrue(mixed.getMessage().contains("分类越域"), "须逐行给出问题：" + mixed.getMessage());
        assertEquals(before, productDao.rows.size(), "任一不合格 → 不得产生部分写入");

        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED,
                assertThrows(BizException.class, () -> service.importProducts(
                        Arrays.asList("product_code,product_name,category,currency,remark",
                                "CU,铜,COMMODITY,CNY,,,"), 1L)).errorCode(), "列数不符须拒绝");
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED,
                assertThrows(BizException.class, () -> service.importProducts(
                        new ArrayList<>(), 1L)).errorCode(), "空文件须拒绝");
    }

    @Test
    @DisplayName("导出：含表头、按创建时间倒序（T-105，与 GET /product 缺省排序对齐）、含逗号字段加引号")
    void exportWorks() {
        // 先建 AA 后建 ZZ ⇒ 创建时间倒序时 ZZ 在前（若仍按编码升序会 AA 在前，故本断言可判方向）
        service.createProduct(product("AA", "铜", "COMMODITY"), 1L);
        Product zz = product("ZZ", "铝", "COMMODITY");
        zz.remark = "含,逗号";
        service.createProduct(zz, 1L);

        // 跨账户只读（哨兵 0）⇒ 不加过滤，走 findAllOrderByCreatedDesc 分支
        VarietyService exportService = new VarietyService(FakeTx.newTemplate(), productDao, unitDao,
                contractDao, mappingDao, whitelistDao, quotaDao, instrumentQuotaDao, changeLogDao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL));
        List<String> csv = exportService.exportProducts(1L);

        assertEquals(String.join(",", VarietyService.CSV_HEADER), csv.get(0));
        assertEquals(3, csv.size());
        assertTrue(csv.get(1).startsWith("ZZ,"), "按创建时间倒序：" + csv.get(1));
        assertTrue(csv.get(1).contains("\"含,逗号\""), "含逗号字段须加引号：" + csv.get(1));
        assertTrue(csv.get(2).startsWith("AA,"), "创建较早者排后：" + csv.get(2));
    }

    // ------------------------------------------------------------ 桩与数据

    private SubProductWhitelist findWhitelist(int subAccountId, long productId) {
        for (SubProductWhitelist row : whitelistDao.rows) {
            if (row.subAccountId == subAccountId && row.productId == productId) {
                return row;
            }
        }
        return null;
    }

    private static Product product(String code, String name, String category) {
        Product product = new Product();
        product.productCode = code;
        product.productName = name;
        product.category = category;
        return product;
    }

    private static ProductUnit unit(long productId, BigDecimal factor, LocalDate from) {
        ProductUnit unit = new ProductUnit();
        unit.productId = productId;
        unit.spotUnit = "TON";
        unit.futuresUnit = "LOT";
        unit.convertFactor = factor;
        unit.effectiveFrom = from;
        unit.status = "ENABLED";
        return unit;
    }

    private static ProductContract contract(long productId, String instrumentId, String type) {
        ProductContract contract = new ProductContract();
        contract.productId = productId;
        contract.instrumentId = instrumentId;
        contract.instrumentType = type;
        contract.exchangeCode = "SHFE";
        contract.contractMultiplier = new BigDecimal("5");
        contract.expiryDate = LocalDate.of(2026, 12, 15);
        return contract;
    }

    private static ProductSpotCategory mapping(String categoryCode, long productId, String direction,
                                              String primary) {
        ProductSpotCategory mapping = new ProductSpotCategory();
        mapping.spotCategoryCode = categoryCode;
        mapping.productId = productId;
        mapping.mappingDirection = direction;
        mapping.isPrimary = primary;
        return mapping;
    }

    private static SubProductWhitelist whitelist(int subAccountId, long productId, String status) {
        SubProductWhitelist row = new SubProductWhitelist();
        row.subAccountId = subAccountId;
        row.productId = productId;
        row.status = status;
        row.dispatchStatus = "PENDING";
        return row;
    }

    /** 内存假品种 DAO。 */
    private static final class FakeProductDao extends UnsupportedBaseDao<Product, Long>
            implements ProductDao {

        private final List<Product> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(Product entity, long operatorId) {
            if (entity.productCode != null && findByCode(entity.productCode) != null) {
                return 0;
            }
            entity.id = nextId++;
            // T-105：导出/列表面按 created_time 倒序，假 DAO 须给出可判定的创建时间
            entity.createdTime = java.time.LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(entity.id);
            rows.add(entity);
            return 1;
        }

        @Override
        public int updateById(Product entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public Product findById(Long id) {
            for (Product row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

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
            List<Product> out = new ArrayList<>(rows);
            out.sort((a, b) -> a.productCode.compareTo(b.productCode));
            return out;
        }

        // T-105：导出面按 created_time 倒序；列表 scoped 面本用例不驱动（隔离由专用用例覆盖）
        @Override
        public List<Product> findAllOrderByCreatedDesc() {
            List<Product> out = new ArrayList<>(rows);
            out.sort((a, b) -> b.createdTime.compareTo(a.createdTime));
            return out;
        }

        @Override
        public PageResult<Product> findPageScoped(PageQuery query, SubAccountScope scope,
                                                  String extraWhere, List<Object> extraArgs) {
            throw FakeDao.unsupported("findPageScoped");
        }

        @Override
        public List<Product> findAllScoped(SubAccountScope scope) {
            throw FakeDao.unsupported("findAllScoped");
        }
    }

    /** 内存假计量单位 DAO（{@code findEffective} 取基准日之前最近一份）。 */
    private static final class FakeProductUnitDao extends UnsupportedBaseDao<ProductUnit, Long>
            implements ProductUnitDao {

        private final List<ProductUnit> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ProductUnit entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public ProductUnit findEffective(long productId, LocalDate bizDate) {
            ProductUnit best = null;
            for (ProductUnit row : rows) {
                if (row.productId != productId || !"ENABLED".equals(row.status)) {
                    continue;
                }
                if (row.effectiveFrom == null || row.effectiveFrom.isAfter(bizDate)) {
                    continue;
                }
                if (best == null || row.effectiveFrom.isAfter(best.effectiveFrom)) {
                    best = row;
                }
            }
            return best;
        }
    }

    /** 内存假合约关联 DAO。 */
    private static final class FakeProductContractDao extends UnsupportedBaseDao<ProductContract, Long>
            implements ProductContractDao {

        private final List<ProductContract> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ProductContract entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public ProductContract findById(Long id) {
            for (ProductContract row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public ProductContract findByInstrumentId(String instrumentId) {
            for (ProductContract row : rows) {
                if (row.instrumentId.equals(instrumentId)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<ProductContract> findByProduct(long productId) {
            List<ProductContract> out = new ArrayList<>();
            for (ProductContract row : rows) {
                if (row.productId != null && row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假现货映射 DAO。 */
    private static final class FakeSpotCategoryDao
            extends UnsupportedBaseDao<ProductSpotCategory, Long> implements ProductSpotCategoryDao {

        private final List<ProductSpotCategory> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ProductSpotCategory entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public ProductSpotCategory findPrimaryMapping(String spotCategoryCode, String mappingDirection) {
            for (ProductSpotCategory row : rows) {
                if (!row.spotCategoryCode.equals(spotCategoryCode)
                        || !"ENABLED".equals(row.status)) {
                    continue;
                }
                boolean dirOk = row.mappingDirection.equals(mappingDirection)
                        || "BOTH".equals(row.mappingDirection);
                if (dirOk && "Y".equals(row.isPrimary)) {
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

    /** 内存假白名单 DAO。 */
    private static final class FakeWhitelistDao
            extends UnsupportedBaseDao<SubProductWhitelist, Long> implements SubProductWhitelistDao {

        private final List<SubProductWhitelist> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(SubProductWhitelist entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public int updateById(SubProductWhitelist entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public List<SubProductWhitelist> findBySubAccount(int subAccountId) {
            List<SubProductWhitelist> out = new ArrayList<>();
            for (SubProductWhitelist row : rows) {
                if (row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<SubProductWhitelist> findByProduct(long productId) {
            List<SubProductWhitelist> out = new ArrayList<>();
            for (SubProductWhitelist row : rows) {
                if (row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假层①/层③额度 DAO。 */
    private static final class FakeQuotaDao extends UnsupportedBaseDao<ProductQuota, Long>
            implements ProductQuotaDao {

        private final List<ProductQuota> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ProductQuota entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public List<ProductQuota> findByLayerAndScope(String limitLayer, int subAccountId) {
            List<ProductQuota> out = new ArrayList<>();
            for (ProductQuota row : rows) {
                if (limitLayer.equals(row.limitLayer) && row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假层②额度 DAO。 */
    private static final class FakeInstrumentQuotaDao
            extends UnsupportedBaseDao<SubInstrumentQuota, Long> implements SubInstrumentQuotaDao {

        private final List<SubInstrumentQuota> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(SubInstrumentQuota entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public List<SubInstrumentQuota> findBySubAccount(int subAccountId) {
            List<SubInstrumentQuota> out = new ArrayList<>();
            for (SubInstrumentQuota row : rows) {
                if (row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假变更留痕 DAO。 */
    private static final class FakeChangeLogDao extends UnsupportedBaseDao<ProductChangeLog, Long>
            implements ProductChangeLogDao {

        private final List<ProductChangeLog> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ProductChangeLog entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public List<ProductChangeLog> findByProduct(long productId) {
            List<ProductChangeLog> out = new ArrayList<>();
            for (ProductChangeLog row : rows) {
                if (row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }
    }
}
