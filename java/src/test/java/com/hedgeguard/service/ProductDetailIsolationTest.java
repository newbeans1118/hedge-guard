package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductQuotaDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.SubInstrumentQuotaDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductQuota;
import com.hedgeguard.dao.entity.SubInstrumentQuota;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.handler.product.ProductHandler;
import com.hedgeguard.web.handler.product.QuotaHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-106}：PRODUCT 单对象读取面（{@code GET /product/{id}} 详情、{@code GET /product/quota} 三层全景）
 * 的子账户数据隔离（{@code FR-PRODUCT-6} 验收 3 / {@code FR-PRODUCT-11} / {@code NFR-SEC-7} / {@code FR-AUTH-13}）。
 *
 * <p><b>为何要这组用例</b>：列表面已由 {@code T-105} 收口，但<b>单对象面</b>此前无用户级 scope ——
 * {@code GET /product/{id}} 走 {@code findByld}（不加过滤）、{@code GET /product/quota} 仅校验
 * {@code subAccountId > 0}，故任意调用者可凭 id / {@code subAccountId} 直接读取不可见子账户的品种档案与额度配置
 * （读取面越权）。
 *
 * <p><b>两处结构不同 ⇒ 两套口径（均已对齐本项目既有范式）</b>：
 * <ul>
 *   <li>{@code GET /product/{id}}：路径<b>无</b>子账户目标（品种全局共享、无 {@code sub_account_id} 列）
 *       ⇒ 走 {@code ProductDao#findByIdScoped} 的白名单子查询，<b>不可见 ≡ 不存在</b>（同一返回
 *       {@code null} ⇒ 统一错误，不泄露存在性；{@code FR-PRODUCT-6} 处理规则「统一错误」/ 同
 *       {@code T-100}/{@code T-107} 口径）；</li>
 *   <li>{@code GET /product/quota}：{@code subAccountId} 为<b>显式目标</b> ⇒ 走
 *       {@code SubAccountVisibility#assertSubAccountVisible}（读面断言，越权 {@code 10004}；同
 *       {@code SpotService#exportExposuresCsv} 口径）。</li>
 * </ul>
 *
 * <p>假 DAO 忠实模拟 {@code t_sub_product_whitelist} 语义（可见品种 = 可见子账户白名单覆盖之并集；
 * 空集 ⇒ 恒假）；真实 SQL 形状由 {@code DmProductDaoScopedSqlTest} 覆盖（{@code t_product} 走子查询，
 * {@code InMemoryDmEngine} 不支持 {@code id IN (SELECT …)} ⇒ 该面用记录型假 JDBC 断言 SQL 形状）。
 * 完全离线（{@code NFR-TEST-2}）。
 */
class ProductDetailIsolationTest {

    /** 操作者（会话身份）。 */
    private static final long OPERATOR = 999L;
    /** 可见子账户。 */
    private static final int VISIBLE_SUB = 7;
    /** 不可见子账户（越权目标）。 */
    private static final int OTHER_SUB = 8;
    /** 可见子账户白名单覆盖的品种。 */
    private static final long PRODUCT_VISIBLE = 1L;
    /** 仅不可见子账户白名单覆盖的品种（越权读目标）。 */
    private static final long PRODUCT_OTHER = 2L;

    // ============================================================ 一、GET /product/{id} 详情隔离

    @Test
    @DisplayName("T-106 详情·可见者可读：白名单含可见子账户 ⇒ 返回品种（不得误拒）")
    void visibleSubscriberReadsDetail() {
        ScopedProductDao dao = fixture();

        Product product = svc(dao, uid -> Arrays.asList(VISIBLE_SUB))
                .getProduct(PRODUCT_VISIBLE, OPERATOR);

        assertNotNull(product, "可见子账户白名单覆盖的品种须可见");
        assertEquals("CU", product.productCode);
        assertTrue(dao.scopedTouched, "受限身份须走 scoped 路径");
        assertEquals(Arrays.asList(VISIBLE_SUB), dao.lastScope.allowed(), "scope 集合 = 可见子账户");
    }

    @Test
    @DisplayName("T-106 详情·越权：不可见子账户白名单的品种 ⇒ 与「不存在」同一返回（不泄露存在性）")
    void invisibleProductIsAbsent() {
        ScopedProductDao dao = fixture();

        // 身份可见 [7]，品种 2 仅由子账户 8 白名单覆盖 ⇒ 不可见
        assertNull(svc(dao, uid -> Arrays.asList(VISIBLE_SUB)).getProduct(PRODUCT_OTHER, OPERATOR),
                "不可见品种须返回 null（不可见 ≡ 不存在，不泄露存在性）");
        assertTrue(dao.scopedTouched, "受限身份须走 scoped 路径（而非 findByld）");
        assertFalse(dao.unscopedTouched, "不得走不加过滤路径");
    }

    @Test
    @DisplayName("T-106 详情·对照组：真·不存在与不可见返回完全一致（同一码、同一模板，统一错误）")
    void nonexistentEqualsInvisibleError(@TempDir Path webRoot) {
        ScopedProductDao dao = fixture();
        RequestDispatcher dispatcher = detailDispatcher(dao, webRoot);
        Map<String, Object> missing = errorBody(dispatcher, "9999", "S-42");
        Map<String, Object> invisible = errorBody(dispatcher, String.valueOf(PRODUCT_OTHER), "S-42");

        assertEquals(10001, ((Number) missing.get("code")).intValue(), "不存在 ⇒ 10001");
        assertEquals(missing.get("code"), invisible.get("code"), "不可见与不存在须<b>同码</b>（统一错误）");
        String miss = (String) missing.get("message");
        String inv = (String) invisible.get("message");
        assertTrue(miss.startsWith("品种不存在或无权访问：id=") && inv.startsWith("品种不存在或无权访问：id="),
                "统一模板（不区分「无权限」与「不存在」）：" + miss + " | " + inv);
        assertEquals(miss.replace("id=9999", "id=<ID>"),
                inv.replace("id=" + PRODUCT_OTHER, "id=<ID>"),
                "不可见与不存在<b>仅回显 id 不同、模板一致</b>（不泄露存在性）");
    }

    @Test
    @DisplayName("T-106 详情·跨账户只读（哨兵 0）：不加过滤、可见全部（读面放行）")
    void crossReadonlyReadsAllDetails() {
        ScopedProductDao dao = fixture();

        Product product = svc(dao, uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL))
                .getProduct(PRODUCT_OTHER, OPERATOR);

        assertNotNull(product, "跨账户只读可见全部子账户（含受审品种）");
        assertTrue(dao.unscopedTouched, "哨兵 0 ⇒ 走不加过滤路径");
        assertFalse(dao.scopedTouched, "哨兵 0 不得构造 scope");
    }

    @Test
    @DisplayName("T-106 详情·无授权（空集）：返回 null，绝不返回全部（安全默认）")
    void noGrantDetailIsAbsent() {
        ScopedProductDao dao = fixture();

        assertNull(svc(dao, uid -> new ArrayList<Integer>()).getProduct(PRODUCT_VISIBLE, OPERATOR),
                "无授权 ⇒ 恒假（不得退化为全可见）");
        assertTrue(dao.scopedTouched && dao.lastScope.isEmpty(), "空集须走 scoped 恒假路径");
    }

    @Test
    @DisplayName("T-106 详情·fail-closed：可见性入口缺位（null）⇒ 按空集处理（谁都不见）")
    void nullVisibilityDetailFailsClosed() {
        ScopedProductDao dao = fixture();

        assertNull(svc(dao, null).getProduct(PRODUCT_VISIBLE, OPERATOR),
                "入口缺位 ⇒ 按空集 fail-closed");
    }

    @Test
    @DisplayName("T-106 详情·端点接线：同数据不同会话身份结果不同（隔离身份取自会话，非路径/请求体）")
    void detailHandlerThreadsSessionIdentity(@TempDir Path webRoot) {
        ScopedProductDao dao = fixture();
        RequestDispatcher dispatcher = detailDispatcher(dao, webRoot);

        // 身份 42：可读自己可见子账户白名单覆盖的品种 ⇒ 200
        HttpResult ok = dispatcher.dispatch(get("/product/" + PRODUCT_VISIBLE, "S-42"));
        assertEquals(200, ok.status, "可见品种详情须 200");

        // 身份 42：读他子账户白名单品种 ⇒ 400 统一错误（不可见 ≡ 不存在，不返回数据）
        HttpResult denied = dispatcher.dispatch(get("/product/" + PRODUCT_OTHER, "S-42"));
        assertEquals(400, denied.status, "不可见品种详情须被拒（统一错误 10001）");

        // 同一 DAO，另一身份（哨兵 0 语义）⇒ 可见全部（对照身份差异确实来自会话）
        dao.unscopedTouched = false;
        RequestDispatcher allSee = dispatcher(ProductHandler.PATH_BY_ID,
                new ProductHandler(svc(dao, uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL))),
                webRoot, "S-OTHER", 7L);
        HttpResult cross = allSee.dispatch(get("/product/" + PRODUCT_OTHER, "S-OTHER"));
        assertEquals(200, cross.status, "跨账户只读身份可见受审品种（对照）");
        assertTrue(dao.unscopedTouched, "哨兵 0 身份走不加过滤路径");
    }

    // ============================================================ 二、GET /product/quota 三层全景隔离

    @Test
    @DisplayName("T-106 额度·可见者可读：对可见子账户取全景，层①/②/③均按品种返回")
    void visibleSubscriberReadsPanorama() {
        ScopedProductDao productDao = fixture();
        FakeQuotaDao quotaDao = quotaFixture();
        FakeInstrumentQuotaDao instrumentDao = instrumentFixture();

        Map<String, Object> panorama = svc(productDao, quotaDao, instrumentDao,
                uid -> Arrays.asList(VISIBLE_SUB))
                .quotaPanorama(VISIBLE_SUB, PRODUCT_VISIBLE, OPERATOR);

        assertEquals(1, ((List<?>) panorama.get("layer1SubProduct")).size(), "层①（该子账户+品种）");
        assertEquals(1, ((List<?>) panorama.get("layer3AccountProduct")).size(), "层③（账户级+品种）");
        assertEquals(1, ((List<?>) panorama.get("layer2SubInstrument")).size(), "层②（该子账户+合约）");
        assertTrue(String.valueOf(panorama.get("note")).contains("R-RISK-9"), "显式声明非权威源");
    }

    @Test
    @DisplayName("T-106 额度·越权：对不可见子账户取全景 ⇒ 10004 且<b>零取数</b>（不得泄露任何额度行）")
    void invisibleSubAccountQuotaRejected() {
        ScopedProductDao productDao = fixture();
        FakeQuotaDao quotaDao = quotaFixture();
        FakeInstrumentQuotaDao instrumentDao = instrumentFixture();

        BizException e = assertThrows(BizException.class,
                () -> svc(productDao, quotaDao, instrumentDao, uid -> Arrays.asList(VISIBLE_SUB))
                        .quotaPanorama(OTHER_SUB, PRODUCT_VISIBLE, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(), "越权须 10004");
        assertEquals(403, e.errorCode().httpStatus());
        assertFalse(quotaDao.queried, "越权须在取数前拦截（零取数）");
        assertFalse(instrumentDao.queried, "越权须在取数前拦截（零取数）");
    }

    @Test
    @DisplayName("T-106 额度·跨账户只读（哨兵 0）：可读任意子账户全景（读面放行）")
    void crossReadonlyReadsAnyQuota() {
        ScopedProductDao productDao = fixture();
        FakeQuotaDao quotaDao = quotaFixture();
        FakeInstrumentQuotaDao instrumentDao = instrumentFixture();

        Map<String, Object> panorama = svc(productDao, quotaDao, instrumentDao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL))
                .quotaPanorama(OTHER_SUB, PRODUCT_VISIBLE, OPERATOR);

        assertNotNull(panorama, "跨账户只读可查看全部子账户（含导出/详情）");
        assertTrue(quotaDao.queried, "放行后须真正取数");
    }

    @Test
    @DisplayName("T-106 额度·无授权（空集）：拒 10004，零取数（安全默认）")
    void noGrantQuotaRejected() {
        ScopedProductDao productDao = fixture();
        FakeQuotaDao quotaDao = quotaFixture();
        FakeInstrumentQuotaDao instrumentDao = instrumentFixture();

        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc(productDao, quotaDao, instrumentDao, uid -> new ArrayList<Integer>())
                        .quotaPanorama(VISIBLE_SUB, PRODUCT_VISIBLE, OPERATOR)).errorCode());
        assertFalse(quotaDao.queried, "空集不得退化为全可见");
    }

    @Test
    @DisplayName("T-106 额度·fail-closed：可见性入口缺位（null）⇒ 10004，零取数")
    void nullVisibilityQuotaFailsClosed() {
        ScopedProductDao productDao = fixture();
        FakeQuotaDao quotaDao = quotaFixture();
        FakeInstrumentQuotaDao instrumentDao = instrumentFixture();

        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc(productDao, quotaDao, instrumentDao, null)
                        .quotaPanorama(VISIBLE_SUB, PRODUCT_VISIBLE, OPERATOR)).errorCode());
        assertFalse(quotaDao.queried, "入口缺位不得退化为放行");
    }

    @Test
    @DisplayName("T-106 额度·层③不误拒：层③（账户哨兵 0）不按子账户维度过滤，可见子账户取全景时照常返回")
    void layer3AccountLevelNotSubAccountGated() {
        ScopedProductDao productDao = fixture();
        FakeQuotaDao quotaDao = quotaFixture();
        FakeInstrumentQuotaDao instrumentDao = instrumentFixture();

        // 层③行归属账户哨兵 0（非某子账户）；若误按「子账户维度」过滤会把它滤掉 ⇒ 本用例即红。
        Map<String, Object> panorama = svc(productDao, quotaDao, instrumentDao,
                uid -> Arrays.asList(VISIBLE_SUB))
                .quotaPanorama(VISIBLE_SUB, PRODUCT_VISIBLE, OPERATOR);

        List<?> layer3 = (List<?>) panorama.get("layer3AccountProduct");
        assertEquals(1, layer3.size(), "层③为账户级配置（哨兵 0），不得按子账户可见性误拒");
        assertEquals(0, ((ProductQuota) layer3.get(0)).subAccountId,
                "层③行归属哨兵值 0（DC-020）");
    }

    @Test
    @DisplayName("T-106 额度·端点接线：同数据不同会话身份结果不同（越权 403 / 合法 200）")
    void quotaHandlerThreadsSessionIdentity(@TempDir Path webRoot) {
        ScopedProductDao productDao = fixture();
        FakeQuotaDao quotaDao = quotaFixture();
        FakeInstrumentQuotaDao instrumentDao = instrumentFixture();
        SubAccountVisibility visibility = uid -> uid == 42L
                ? Arrays.asList(VISIBLE_SUB) : Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        RequestDispatcher dispatcher = dispatcher(QuotaHandler.PATH,
                new QuotaHandler(svc(productDao, quotaDao, instrumentDao, visibility)), webRoot,
                "S-42", 42L);

        HttpResult ok = dispatcher.dispatch(new RequestContext("GET", ApiRoutes.full("/product/quota"),
                "subAccountId=" + VISIBLE_SUB + "&productId=" + PRODUCT_VISIBLE, cookie("S-42"), "",
                "HGISO", "127.0.0.1"));
        assertEquals(200, ok.status, "可见子账户全景须 200");

        HttpResult denied = dispatcher.dispatch(new RequestContext("GET", ApiRoutes.full("/product/quota"),
                "subAccountId=" + OTHER_SUB + "&productId=" + PRODUCT_VISIBLE, cookie("S-42"), "",
                "HGISO", "127.0.0.1"));
        assertEquals(403, denied.status, "不可见子账户全景须 403（10004）");
    }

    // ============================================================ 三、真实 AuthService 端到端探针

    @Test
    @DisplayName("T-106 端到端（真实 AuthService）：权限判定 + 越权留痕；对不可见子账户取全景被拒")
    void realAuthServiceRejectsCrossSubAccountQuota() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        permDao.rows.add(perm(OPERATOR, VISIBLE_SUB, AuthService.PERM_LIMITED));
        AuthService auth = authService(permDao, attemptDao);

        VarietyService service = svc(fixture(), quotaFixture(), instrumentFixture(), auth);

        // 越权：读他人子账户全景 ⇒ 真实权限判定拒绝
        BizException e = assertThrows(BizException.class,
                () -> service.quotaPanorama(OTHER_SUB, PRODUCT_VISIBLE, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertFalse(attemptDao.rows.isEmpty(), "越权尝试须留痕（R-AUTH-8）");
        assertEquals(AuthService.ATTEMPT_SUB_ACCOUNT_DENIED, attemptDao.rows.get(0).attemptType);

        // 合法：读自己可见子账户全景 ⇒ 通过
        Map<String, Object> panorama = service.quotaPanorama(VISIBLE_SUB, PRODUCT_VISIBLE, OPERATOR);
        assertNotNull(panorama, "真实 AuthService 下对可见子账户应放行");
    }

    @Test
    @DisplayName("T-106 端到端（真实 AuthService）：详情面 CROSS_READONLY 放行全部子账户")
    void realAuthServiceCrossReadonlyReadsDetail() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        permDao.rows.add(perm(OPERATOR, SubAccountScope.ALL_ACCOUNT_SENTINEL,
                AuthService.PERM_CROSS_READONLY));
        AuthService auth = authService(permDao, attemptDao);

        ScopedProductDao dao = fixture();
        Product product = svc(dao, quotaFixture(), instrumentFixture(), auth)
                .getProduct(PRODUCT_OTHER, OPERATOR);

        assertNotNull(product, "跨子账户只读可见全部子账户（读面放行，FR-AUTH-13 验收 1）");
        assertTrue(attemptDao.rows.isEmpty(), "合法读取不得产生越权留痕");
    }

    // ============================================================ 辅助

    private static ScopedProductDao fixture() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.add(PRODUCT_VISIBLE, "CU");
        dao.add(PRODUCT_OTHER, "AL");
        dao.white(PRODUCT_VISIBLE, VISIBLE_SUB);   // 品种 1 白名单含子账户 7
        dao.white(PRODUCT_OTHER, OTHER_SUB);       // 品种 2 白名单仅含子账户 8
        return dao;
    }

    private static FakeQuotaDao quotaFixture() {
        FakeQuotaDao dao = new FakeQuotaDao();
        dao.rows.add(quota(VISIBLE_SUB, "SUB_PRODUCT", PRODUCT_VISIBLE));
        dao.rows.add(quota(SubAccountScope.ALL_ACCOUNT_SENTINEL, "ACCOUNT_PRODUCT", PRODUCT_VISIBLE));
        return dao;
    }

    private static FakeInstrumentQuotaDao instrumentFixture() {
        FakeInstrumentQuotaDao dao = new FakeInstrumentQuotaDao();
        dao.rows.add(instrument(VISIBLE_SUB, PRODUCT_VISIBLE));
        return dao;
    }

    private static VarietyService svc(ProductDao productDao, SubAccountVisibility visibility) {
        return new VarietyService(FakeTx.newTemplate(), productDao, null, null, null, null, null, null,
                null, visibility);
    }

    private static VarietyService svc(ProductDao productDao, ProductQuotaDao quotaDao,
                                      SubInstrumentQuotaDao instrumentDao,
                                      SubAccountVisibility visibility) {
        return new VarietyService(FakeTx.newTemplate(), productDao, null, null, null, null, quotaDao,
                instrumentDao, null, visibility);
    }

    private static Product product(long id, String code) {
        Product product = new Product();
        product.id = id;
        product.productCode = code;
        product.productName = code;
        product.category = "COMMODITY";
        product.status = "ENABLED";
        return product;
    }

    private static ProductQuota quota(int subAccountId, String layer, long productId) {
        ProductQuota q = new ProductQuota();
        q.subAccountId = subAccountId;
        q.limitLayer = layer;
        q.productId = productId;
        q.quotaLimit = new BigDecimal("100");
        q.quotaUnit = "LOT";
        q.effectiveFrom = LocalDate.of(2026, 1, 1);
        q.dispatchStatus = "PENDING";
        return q;
    }

    private static SubInstrumentQuota instrument(int subAccountId, long productId) {
        SubInstrumentQuota q = new SubInstrumentQuota();
        q.subAccountId = subAccountId;
        q.instrumentId = "cu2612";
        q.productId = productId;
        q.quotaLimit = new BigDecimal("20");
        q.quotaUnit = "LOT";
        q.effectiveFrom = LocalDate.of(2026, 1, 1);
        q.dispatchStatus = "PENDING";
        return q;
    }

    /** 构造详情端点分发器：身份 42 ⇒ 仅子账户 7；其余身份 ⇒ 哨兵（可见全部）。 */
    private static RequestDispatcher detailDispatcher(ScopedProductDao dao, Path webRoot) {
        SubAccountVisibility visibility = uid -> uid == 42L
                ? Arrays.asList(VISIBLE_SUB) : Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        return dispatcher(ProductHandler.PATH_BY_ID, new ProductHandler(svc(dao, visibility)),
                webRoot, "S-42", 42L);
    }

    /** 构造「会话 → 可见集」由 {@code userId} 决定的分发器（仅注册单条 GET 路由）。 */
    private static RequestDispatcher dispatcher(String path, RouteHandler handler, Path webRoot,
                                                String sessionId, long userId) {
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session(sessionId, userId, "u", 1L, Clocks.now().plusMinutes(30)));
        Router router = new Router();
        router.register("GET", path, handler);
        return new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
    }

    /** 分发 {@code /product/{id}} 并解析错误响应体（{@link com.hedgeguard.web.ErrorInfo} JSON）。 */
    private static Map<String, Object> errorBody(RequestDispatcher dispatcher, String id,
                                                 String sessionId) {
        HttpResult result = dispatcher.dispatch(
                new RequestContext("GET", ApiRoutes.full("/product/" + id), null, cookie(sessionId),
                        "", "HGT106", "127.0.0.1"));
        String body = new String(result.body, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(400, result.status, "错误响应状态须 400：" + body);
        return JsonCodec.fromJson(body, Map.class);
    }

    private static RequestContext get(String path, String sessionId) {
        return new RequestContext("GET", ApiRoutes.full(path), null, cookie(sessionId), "",
                "HGT106", "127.0.0.1");
    }

    private static Map<String, String> cookie(String sessionId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + sessionId);
        return headers;
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

    /** 真实 {@link AuthService}（仅装配可见性判定所需的两个 DAO；其余入参不被驱动 → 传 {@code null}）。 */
    private static AuthService authService(UserSubAccountPermDao permDao,
                                           UnauthorizedAttemptDao attemptDao) {
        return new AuthService(FakeTx.newTemplate(), null, null, null, null, null, null, null,
                permDao, attemptDao, null, null, null, null, null);
    }

    // ------------------------------------------------------------ 假 DAO

    /**
     * 内存假品种 DAO：忠实模拟 {@code t_sub_product_whitelist} 语义
     * （可见品种 = 可见子账户白名单覆盖之并集；空集 ⇒ 恒假）。
     */
    private static final class ScopedProductDao extends UnsupportedBaseDao<Product, Long>
            implements ProductDao {

        private final List<Product> rows = new ArrayList<>();
        private final Map<Long, Set<Integer>> whitelist = new HashMap<>();
        private boolean scopedTouched;
        private boolean unscopedTouched;
        private SubAccountScope lastScope;

        ScopedProductDao add(long id, String code) {
            rows.add(product(id, code));
            return this;
        }

        ScopedProductDao white(long productId, int subAccountId) {
            whitelist.computeIfAbsent(productId, k -> new HashSet<>()).add(subAccountId);
            return this;
        }

        @Override
        public Product findById(Long id) {
            unscopedTouched = true;
            for (Product row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public Product findByIdScoped(long id, SubAccountScope scope) {
            scopedTouched = true;
            lastScope = scope;
            if (scope == null || scope.isEmpty()) {
                return null;
            }
            Set<Integer> subs = whitelist.get(id);
            if (subs == null) {
                return null;
            }
            for (Integer s : subs) {
                if (scope.allowed().contains(s)) {
                    for (Product row : rows) {
                        if (row.id == id) {
                            return row;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public Product findByCode(String productCode) {
            throw FakeDao.unsupported("findByCode");
        }

        @Override
        public List<Product> findAllOrderByCode() {
            throw FakeDao.unsupported("findAllOrderByCode");
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
        public List<Product> findAllOrderByCreatedDesc() {
            throw FakeDao.unsupported("findAllOrderByCreatedDesc");
        }
    }

    /** 内存假层①/③额度 DAO（按层级 + 作用域过滤）；记录是否被取数。 */
    private static final class FakeQuotaDao extends UnsupportedBaseDao<ProductQuota, Long>
            implements ProductQuotaDao {

        private final List<ProductQuota> rows = new ArrayList<>();
        private boolean queried;

        @Override
        public List<ProductQuota> findByLayerAndScope(String limitLayer, int subAccountId) {
            queried = true;
            List<ProductQuota> out = new ArrayList<>();
            for (ProductQuota row : rows) {
                if (limitLayer.equals(row.limitLayer) && row.subAccountId != null
                        && row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假层②合约额度 DAO（按子账户过滤）；记录是否被取数。 */
    private static final class FakeInstrumentQuotaDao extends UnsupportedBaseDao<SubInstrumentQuota, Long>
            implements SubInstrumentQuotaDao {

        private final List<SubInstrumentQuota> rows = new ArrayList<>();
        private boolean queried;

        @Override
        public List<SubInstrumentQuota> findBySubAccount(int subAccountId) {
            queried = true;
            List<SubInstrumentQuota> out = new ArrayList<>();
            for (SubInstrumentQuota row : rows) {
                if (row.subAccountId != null && row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
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
            List<Long> out = new ArrayList<>();
            for (UserSubAccountPerm row : rows) {
                if (row.subAccountId == subAccountId && !out.contains(row.userId)) {
                    out.add(row.userId);
                }
            }
            return out;
        }
    }

    private static final class FakeAttemptDao extends UnsupportedBaseDao<UnauthorizedAttempt, Long>
            implements UnauthorizedAttemptDao {

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
