package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductChangeLogDao;
import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.ProductUnitDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.entity.ProductSpotCategory;
import com.hedgeguard.dao.entity.ProductUnit;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-118}：<b>全局共享对象写面</b>功能权限强制的验收（{@code FR-AUTH-13} 处理规则 /
 * {@code NFR-SEC-7} / {@code R-AUTH-8} / {@code CH-012}）。
 *
 * <p><b>本单语义</b>：{@code VarietyService} 七个写方法
 * （{@code createProduct} / {@code disableProduct} / {@code enableProduct} / {@code putUnit} /
 * {@code putContract} / {@code putSpotMapping} / {@code importProducts}）的目标均为
 * <b>全局共享对象</b>（{@code R-PRODUCT-7}「品种一份」，无 {@code sub_account_id} 维度）⇒ 写面授权归
 * <b>功能权限</b>维度（{@code FR-AUTH-12}「数据权限与功能权限独立」），复用 {@code T-119} 的
 * {@link AccountLevelConfigAuthorizer}（主体 = 财务/风控岗 {@code FIN_RISK} 或系统管理员
 * {@code SYS_ADMIN}；失败码 {@code 10003} + {@code R-AUTH-8} 留痕）。
 *
 * <p>本类锁定四组语义（缺任一即未完成）：
 * <ol>
 *   <li><b>正例</b>：授权主体（{@code FIN_RISK} / {@code SYS_ADMIN}）写七个方法<b>均不得误拒</b>；</li>
 *   <li><b>反例</b>：无权主体写七个方法<b>逐个拒 {@code 10003} 且零业务写入</b>；</li>
 *   <li><b>fail-closed</b>：授权入口缺位（{@code null}）⇒ 一律拒 {@code 10003}，
 *       绝不退化为「不校验 = 放行」；</li>
 *   <li><b>非子账户维度</b>：注入「空可见集 / 调用即抛」的子账户可见性替身，七个方法仍须放行
 *       —— 证其<b>不</b>以子账户可见性替代（{@code FR-AUTH-13} 处理规则「不得套用子账户维度」）。</li>
 * </ol>
 *
 * <p>末组含真实 {@link AuthService} 端到端探针：越权在真实权限判定 + 留痕（{@code R-AUTH-8}）下被证伪。
 * 完全离线（{@code NFR-TEST-2}），内存假 DAO 驱动。
 */
class VarietyGlobalWriteAuthorityTest {

    private static final long OPERATOR = 999L;
    /** 目标子账户（默认为全局对象场景，仅用于满足构造装配）。 */
    private static final int VISIBLE_SUB = 7;
    private static final long PRODUCT_ID = 5L;

    private FakeProductDao productDao;
    private FakeProductUnitDao unitDao;
    private FakeProductContractDao contractDao;
    private FakeSpotCategoryDao mappingDao;
    private FakeWhitelistDao whitelistDao;
    private FakeChangeLogDao changeLogDao;

    @BeforeEach
    void setUp() {
        productDao = new FakeProductDao();
        unitDao = new FakeProductUnitDao();
        contractDao = new FakeProductContractDao();
        mappingDao = new FakeSpotCategoryDao();
        whitelistDao = new FakeWhitelistDao();
        changeLogDao = new FakeChangeLogDao();
        // 预置一个启用品种 + 生效换算关系（供 disable/enable/putUnit/putContract/putSpotMapping 合法前置）。
        productDao.rows.add(product(PRODUCT_ID, "CU", "ENABLED"));
        unitDao.rows.add(unit(PRODUCT_ID, new BigDecimal("5"), LocalDate.of(2026, 1, 1)));
    }

    // ============================================================ 一、正例：授权主体不得误拒

    @Test
    @DisplayName("T-118 正例：授权主体（恒放行替身）写七个全局对象方法均成功")
    void authorizedSubjectCanWriteAllSeven() {
        VarietyService svc = service(uid -> { });
        exerciseAllSeven(svc);   // 每个动作均断言成功（见辅助）
    }

    @Test
    @DisplayName("T-118 正例（真实 AuthService）：FIN_RISK 主体写七个方法均成功")
    void finRiskSubjectCanWriteAllSeven() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        FakeRoleDao roleDao = new FakeRoleDao();
        FakeUserRoleDao userRoleDao = new FakeUserRoleDao();
        roleDao.put(role(1L, AuthService.ROLE_FIN_RISK));
        userRoleDao.put(userRole(OPERATOR, 1L));
        AuthService auth = authService(permDao, attemptDao, roleDao, userRoleDao);

        VarietyService svc = service(auth, auth);
        exerciseAllSeven(svc);
        assertTrue(attemptDao.rows.isEmpty(), "合法写入不得产生越权留痕");
    }

    @Test
    @DisplayName("T-118 正例（真实 AuthService）：SYS_ADMIN 主体写七个方法均成功（CH-012 他选主体）")
    void sysAdminSubjectCanWriteAllSeven() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        FakeRoleDao roleDao = new FakeRoleDao();
        FakeUserRoleDao userRoleDao = new FakeUserRoleDao();
        roleDao.put(role(2L, AuthService.ROLE_SYS_ADMIN));
        userRoleDao.put(userRole(OPERATOR, 2L));
        AuthService auth = authService(permDao, attemptDao, roleDao, userRoleDao);

        VarietyService svc = service(auth, auth);
        exerciseAllSeven(svc);
        assertTrue(attemptDao.rows.isEmpty(), "合法写入不得产生越权留痕");
    }

    // ============================================================ 二、反例：逐方法拒绝 + 零写入

    @Test
    @DisplayName("T-118 反例（真实 AuthService）：无权主体写 createProduct ⇒ 10003/403 + 留痕 + 零写入")
    void unauthorizedCreateProductRejected() {
        Fixture f = unauthorizedFixture();
        BizException e = assertThrows(BizException.class,
                () -> f.svc.createProduct(product(0L, "AG", "ENABLED"), OPERATOR));
        assertDenied(e);
        assertEquals(1, productDao.rows.size(), "越权被拒不得新增品种（仅余预置行）");
        assertTraced(f);
    }

    @Test
    @DisplayName("T-118 反例（真实 AuthService）：无权主体写 disableProduct ⇒ 10003 + 留痕 + 零写入")
    void unauthorizedDisableProductRejected() {
        Fixture f = unauthorizedFixture();
        BizException e = assertThrows(BizException.class,
                () -> f.svc.disableProduct(PRODUCT_ID, "业务退出", OPERATOR));
        assertDenied(e);
        assertEquals("ENABLED", productDao.findById(PRODUCT_ID).status, "越权被拒不得改状态");
        assertTrue(changeLogDao.rows.isEmpty(), "越权被拒不得留痕业务变更");
        assertTraced(f);
    }

    @Test
    @DisplayName("T-118 反例（真实 AuthService）：无权主体写 enableProduct ⇒ 10003 + 留痕 + 零写入")
    void unauthorizedEnableProductRejected() {
        Fixture f = unauthorizedFixture();
        productDao.findById(PRODUCT_ID).status = "DISABLED";
        BizException e = assertThrows(BizException.class,
                () -> f.svc.enableProduct(PRODUCT_ID, OPERATOR));
        assertDenied(e);
        assertEquals("DISABLED", productDao.findById(PRODUCT_ID).status, "越权被拒不得启用");
        assertTrue(changeLogDao.rows.isEmpty());
        assertTraced(f);
    }

    @Test
    @DisplayName("T-118 反例（真实 AuthService）：无权主体写 putUnit ⇒ 10003 + 留痕 + 零写入")
    void unauthorizedPutUnitRejected() {
        Fixture f = unauthorizedFixture();
        BizException e = assertThrows(BizException.class,
                () -> f.svc.putUnit(unit(PRODUCT_ID, new BigDecimal("6"), LocalDate.of(2026, 3, 1)),
                        OPERATOR));
        assertDenied(e);
        assertEquals(1, unitDao.rows.size(), "越权被拒不得新增换算关系（仅余预置行）");
        assertTraced(f);
    }

    @Test
    @DisplayName("T-118 反例（真实 AuthService）：无权主体写 putContract ⇒ 10003 + 留痕 + 零写入")
    void unauthorizedPutContractRejected() {
        Fixture f = unauthorizedFixture();
        BizException e = assertThrows(BizException.class,
                () -> f.svc.putContract(contract(PRODUCT_ID, "cu2612", "FUTURES"), OPERATOR));
        assertDenied(e);
        assertTrue(contractDao.rows.isEmpty(), "越权被拒不得新增合约关联");
        assertTraced(f);
    }

    @Test
    @DisplayName("T-118 反例（真实 AuthService）：无权主体写 putSpotMapping ⇒ 10003 + 留痕 + 零写入")
    void unauthorizedPutSpotMappingRejected() {
        Fixture f = unauthorizedFixture();
        BizException e = assertThrows(BizException.class,
                () -> f.svc.putSpotMapping(mapping("CATHODE", PRODUCT_ID, "PURCHASE", "N"), OPERATOR));
        assertDenied(e);
        assertTrue(mappingDao.rows.isEmpty(), "越权被拒不得新增现货映射");
        assertTraced(f);
    }

    @Test
    @DisplayName("T-118 反例（真实 AuthService）：无权主体写 importProducts ⇒ 10003 + 留痕 + 零写入")
    void unauthorizedImportProductsRejected() {
        Fixture f = unauthorizedFixture();
        List<String> csv = Arrays.asList(
                "product_code,product_name,category,currency,remark", "AG,白银,COMMODITY,CNY,");
        BizException e = assertThrows(BizException.class, () -> f.svc.importProducts(csv, OPERATOR));
        assertDenied(e);
        assertEquals(1, productDao.rows.size(), "越权被拒不得产生任何导入写入（仅余预置行）");
        assertTrue(changeLogDao.rows.isEmpty());
        assertTraced(f);
    }

    // ============================================================ 三、fail-closed：入口缺位

    @Test
    @DisplayName("T-118 fail-closed：授权入口缺位（null）⇒ 七个方法逐个拒 10003 且零写入")
    void missingAuthorizerFailsClosedForAllSeven() {
        VarietyService svc = service((AccountLevelConfigAuthorizer) null);

        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc.createProduct(product(0L, "AG", "ENABLED"), OPERATOR)).errorCode());
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc.disableProduct(PRODUCT_ID, "停用", OPERATOR)).errorCode());
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc.enableProduct(PRODUCT_ID, OPERATOR)).errorCode());
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc.putUnit(unit(PRODUCT_ID, new BigDecimal("6"), LocalDate.of(2026, 3, 1)),
                        OPERATOR)).errorCode());
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc.putContract(contract(PRODUCT_ID, "cu2612", "FUTURES"), OPERATOR)).errorCode());
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc.putSpotMapping(mapping("CATHODE", PRODUCT_ID, "PURCHASE", "N"),
                        OPERATOR)).errorCode());
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc.importProducts(Arrays.asList("product_code,product_name,category,currency,remark",
                        "AG,白银,COMMODITY,CNY,"), OPERATOR)).errorCode());

        assertEquals(1, productDao.rows.size(), "入口缺位时不得退化为放行（零写入）");
        assertEquals(1, unitDao.rows.size());
        assertTrue(contractDao.rows.isEmpty() && mappingDao.rows.isEmpty());
        assertTrue(whitelistDao.rows.isEmpty() && changeLogDao.rows.isEmpty());
    }

    // ============================================================ 四、非子账户维度：不以可见性替代

    @Test
    @DisplayName("T-118 判据口径：可见性替身「调用即抛 + 空集」时七个方法仍放行（证不套用子账户维度）")
    void globalWritesDoNotConsultSubAccountVisibility() {
        // 若有人把这七个方法误接到 assertSubAccountVisible/Writable（FR-AUTH-13 处理规则禁项），
        // 下面这个「调用即抛」的替身会立即使用例以 IllegalStateException 失败 —— 断言有牙。
        SubAccountVisibility poison = uid -> {
            throw new IllegalStateException("全局对象写面不得查询子账户可见性（FR-AUTH-13 处理规则）");
        };
        VarietyService svc = new VarietyService(FakeTx.newTemplate(), productDao, unitDao, contractDao,
                mappingDao, whitelistDao, null, null, changeLogDao, poison, uid -> { });
        exerciseAllSeven(svc);
    }

    // ============================================================ 辅助

    /** 逐个执行七个写方法并断言成功（正例专用；任一被误拒即失败）。 */
    private void exerciseAllSeven(VarietyService svc) {
        long created = svc.createProduct(product(0L, "AG", "ENABLED"), OPERATOR);
        assertTrue(created > 0, "createProduct 应成功");
        assertTrue(svc.disableProduct(PRODUCT_ID, "业务退出", OPERATOR) >= 0, "disableProduct 应成功");
        unitDao.rows.add(unit(PRODUCT_ID, new BigDecimal("5"), LocalDate.of(2026, 1, 1)));
        svc.enableProduct(PRODUCT_ID, OPERATOR);
        assertEquals("ENABLED", productDao.findById(PRODUCT_ID).status, "enableProduct 应成功");
        assertTrue(svc.putUnit(unit(PRODUCT_ID, new BigDecimal("6"), LocalDate.of(2026, 3, 1)),
                OPERATOR) > 0, "putUnit 应成功");
        assertTrue(svc.putContract(contract(PRODUCT_ID, "cu2612", "FUTURES"), OPERATOR) > 0,
                "putContract 应成功");
        assertTrue(svc.putSpotMapping(mapping("CATHODE", PRODUCT_ID, "PURCHASE", "N"),
                OPERATOR) > 0, "putSpotMapping 应成功");
        assertTrue(svc.importProducts(Arrays.asList(
                "product_code,product_name,category,currency,remark", "PB,铅,COMMODITY,CNY,"),
                OPERATOR) > 0, "importProducts 应成功");
    }

    /**
     * 断言拒绝语义：错误码 {@code 10003} + HTTP 403 + 非空可读信息。
     *
     * <p>判据入口<b>复用</b> {@link AccountLevelConfigAuthorizer}（与 {@code T-119} 层③ 同族）
     * ⇒ 拒绝信息由该入口所有者 {@code AuthService.assertCanConfigureAccountLevel} 生成，其文案
     * 不携带各调用方的动作名（同族一致、避免口径分叉）。故此处只断言码 / HTTP / 非空，不断言动作名。
     */
    private static void assertDenied(BizException e) {
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode(),
                "全局对象写面无权须拒 10003（功能权限不足，非子账户 10004）");
        assertEquals(403, e.errorCode().httpStatus(), "对外表现 HTTP 403");
        assertNotNull(e.getMessage());
    }

    private static void assertTraced(Fixture f) {
        assertFalse(f.attemptDao.rows.isEmpty(), "越权尝试须留痕（R-AUTH-8）");
        assertEquals(AuthService.ATTEMPT_SUB_ACCOUNT_DENIED, f.attemptDao.rows.get(0).attemptType,
                "留痕类型取既有取值域内的 PERMISSION_DENIED");
    }

    /** 无权主体夹具：真实 {@link AuthService}（仅持子账户 LIMITED，无 FIN_RISK / SYS_ADMIN 角色）。 */
    private Fixture unauthorizedFixture() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        permDao.rows.add(perm(OPERATOR, VISIBLE_SUB, AuthService.PERM_LIMITED));
        AuthService auth = authService(permDao, attemptDao, new FakeRoleDao(), new FakeUserRoleDao());
        return new Fixture(service(auth, auth), attemptDao);
    }

    private static final class Fixture {
        final VarietyService svc;
        final FakeAttemptDao attemptDao;

        Fixture(VarietyService svc, FakeAttemptDao attemptDao) {
            this.svc = svc;
            this.attemptDao = attemptDao;
        }
    }

    private VarietyService service(AccountLevelConfigAuthorizer authorizer) {
        return service(uid -> Arrays.asList(VISIBLE_SUB), authorizer);
    }

    private VarietyService service(SubAccountVisibility visibility, AccountLevelConfigAuthorizer auth) {
        return new VarietyService(FakeTx.newTemplate(), productDao, unitDao, contractDao,
                mappingDao, whitelistDao, null, null, changeLogDao, visibility, auth);
    }

    /** 真实 {@link AuthService}（仅装配角色/用户角色/子账户/留痕所需 DAO；其余入参不被驱动）。 */
    private static AuthService authService(FakePermDao permDao, FakeAttemptDao attemptDao,
                                           FakeRoleDao roleDao, FakeUserRoleDao userRoleDao) {
        return new AuthService(FakeTx.newTemplate(), null, roleDao, userRoleDao, null, null, null, null,
                permDao, attemptDao, null, null, null, null, null);
    }

    private static Role role(long id, String roleCode) {
        Role r = new Role();
        r.id = id;
        r.roleCode = roleCode;
        r.roleName = roleCode;
        r.status = "ENABLED";
        return r;
    }

    private static UserRole userRole(long userId, long roleId) {
        UserRole ur = new UserRole();
        ur.userId = userId;
        ur.roleId = roleId;
        return ur;
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

    private static Product product(long id, String code, String status) {
        Product p = new Product();
        p.id = id;
        p.productCode = code;
        p.productName = code;
        p.category = "COMMODITY";
        p.status = status;
        return p;
    }

    private static ProductUnit unit(long productId, BigDecimal factor, LocalDate from) {
        ProductUnit u = new ProductUnit();
        u.productId = productId;
        u.spotUnit = "TON";
        u.futuresUnit = "LOT";
        u.convertFactor = factor;
        u.effectiveFrom = from;
        u.status = "ENABLED";
        return u;
    }

    private static ProductContract contract(long productId, String instrumentId, String type) {
        ProductContract c = new ProductContract();
        c.productId = productId;
        c.instrumentId = instrumentId;
        c.instrumentType = type;
        c.exchangeCode = "SHFE";
        c.contractMultiplier = new BigDecimal("5");
        c.expiryDate = LocalDate.of(2026, 12, 15);
        return c;
    }

    private static ProductSpotCategory mapping(String categoryCode, long productId, String direction,
                                               String primary) {
        ProductSpotCategory m = new ProductSpotCategory();
        m.spotCategoryCode = categoryCode;
        m.productId = productId;
        m.mappingDirection = direction;
        m.isPrimary = primary;
        return m;
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakeProductDao extends UnsupportedBaseDao<Product, Long>
            implements ProductDao {

        private final List<Product> rows = new ArrayList<>();
        private long nextId = 100;

        @Override
        public int insert(Product entity, long operatorId) {
            if (entity.productCode != null && findByCode(entity.productCode) != null) {
                return 0;
            }
            entity.id = nextId++;
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
                if (row.productCode != null && row.productCode.equals(productCode)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<Product> findAllOrderByCode() {
            return new ArrayList<>(rows);
        }

        @Override
        public List<Product> findAllOrderByCreatedDesc() {
            return new ArrayList<>(rows);
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

        @Override
        public Product findByIdScoped(long id, SubAccountScope scope) {
            throw FakeDao.unsupported("findByIdScoped");
        }
    }

    private static final class FakeProductUnitDao extends UnsupportedBaseDao<ProductUnit, Long>
            implements ProductUnitDao {

        private final List<ProductUnit> rows = new ArrayList<>();
        private long nextId = 100;

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

    private static final class FakeProductContractDao extends UnsupportedBaseDao<ProductContract, Long>
            implements ProductContractDao {

        private final List<ProductContract> rows = new ArrayList<>();
        private long nextId = 100;

        @Override
        public int insert(ProductContract entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
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
            return new ArrayList<>();
        }
    }

    private static final class FakeSpotCategoryDao
            extends UnsupportedBaseDao<ProductSpotCategory, Long> implements ProductSpotCategoryDao {

        private final List<ProductSpotCategory> rows = new ArrayList<>();
        private long nextId = 100;

        @Override
        public int insert(ProductSpotCategory entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public ProductSpotCategory findPrimaryMapping(String spotCategoryCode, String mappingDirection) {
            return null;
        }

        @Override
        public List<ProductSpotCategory> findByProduct(long productId) {
            return new ArrayList<>();
        }
    }

    private static final class FakeWhitelistDao
            extends UnsupportedBaseDao<SubProductWhitelist, Long> implements SubProductWhitelistDao {

        private final List<SubProductWhitelist> rows = new ArrayList<>();
        private long nextId = 100;

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
        public List<SubProductWhitelist> findByProduct(long productId) {
            List<SubProductWhitelist> out = new ArrayList<>();
            for (SubProductWhitelist row : rows) {
                if (row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<SubProductWhitelist> findBySubAccount(int subAccountId) {
            return new ArrayList<>();
        }
    }

    private static final class FakeChangeLogDao extends UnsupportedBaseDao<ProductChangeLog, Long>
            implements ProductChangeLogDao {

        private final List<ProductChangeLog> rows = new ArrayList<>();

        @Override
        public int insert(ProductChangeLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public List<ProductChangeLog> findByProduct(long productId) {
            return new ArrayList<>();
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

        @Override
        public List<Long> findUserIdsBySubAccount(int subAccountId) {
            return new ArrayList<>();
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

    private static final class FakeRoleDao extends UnsupportedBaseDao<Role, Long> implements RoleDao {

        private final List<Role> rows = new ArrayList<>();

        void put(Role role) {
            rows.add(role);
        }

        @Override
        public Role findById(Long id) {
            for (Role role : rows) {
                if (role.id == id) {
                    return role;
                }
            }
            return null;
        }

        @Override
        public Role findByCode(String roleCode) {
            for (Role role : rows) {
                if (roleCode.equals(role.roleCode)) {
                    return role;
                }
            }
            return null;
        }

        @Override
        public List<Role> findTradingRoles() {
            return new ArrayList<>();
        }

        @Override
        public List<Role> findByPositionGroup(int positionGroupId) {
            return new ArrayList<>();
        }
    }

    private static final class FakeUserRoleDao extends UnsupportedBaseDao<UserRole, Long>
            implements UserRoleDao {

        private final List<UserRole> rows = new ArrayList<>();

        void put(UserRole userRole) {
            rows.add(userRole);
        }

        @Override
        public List<UserRole> findByUser(long userId) {
            List<UserRole> out = new ArrayList<>();
            for (UserRole row : rows) {
                if (row.userId == userId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<UserRole> findByRole(long roleId) {
            return new ArrayList<>();
        }
    }
}
