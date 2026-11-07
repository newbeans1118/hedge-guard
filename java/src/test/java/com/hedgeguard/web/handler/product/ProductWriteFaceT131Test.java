package com.hedgeguard.web.handler.product;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.entity.ProductUnit;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.impl.DmProductChangeLogDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmSubAccountDao;
import com.hedgeguard.dao.impl.DmSubProductWhitelistDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AccountLevelConfigAuthorizer;
import com.hedgeguard.service.SubAccountVisibility;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-131} PRODUCT **写面** REST 端点补齐（{@code FR-PRODUCT-2} 修改 / {@code FR-PRODUCT-3} 停用 /
 * {@code FR-PRODUCT-4} 启用 / {@code FR-PRODUCT-13} 计量单位 + {@code FR-PRODUCT-6} 白名单读取）。
 *
 * <p><b>判据取向：真实 DAO + 等价引擎 + 真实分发链</b>。本用例把
 * {@code docs/DEPLOY/schema/m1_config_auth.sql} + {@code m1_product.sql} 喂给 {@link InMemoryDmEngine}
 * 建表，经<b>真实</b> {@code Dm*Dao} + <b>真实</b> {@link VarietyService} + <b>真实</b>
 * {@code Router} / {@code RequestDispatcher} / {@code SessionAuthFilter} 走完整 HTTP 语义
 * （路径占位符绑定、会话身份 {@code ctx.currentUserId()}、响应信封 {@code {code,message,data,traceId}}）。
 * 假 DAO 会把「漏留痕 / 漏级联 / 漏权限」一并隐藏，故不用。
 *
 * <p><b>权限口径（{@code T-118}）不重复实现、只验证接线</b>：写面授权判定唯一落在服务层
 * {@code requireGlobalObjectWriteAuthority}；本用例以 lambda 注入 {@link AccountLevelConfigAuthorizer}
 * 的「放行 / 拒绝」两种实现，断言<b>拒绝即 {@code 10003}</b>（fail-closed），
 * 从而证「新端点确实经过该强制点」，而非在新层另造一套判定（{@code CH-012} 口径）。
 *
 * <p><b>本用例不覆盖</b>：白名单子查询的 SQL 形状与谓词生效性（{@code T-151}/{@code T-152} 的
 * {@code DmProductDaoScopedSqlTest} 等）；本引擎实测<b>不解析</b> {@code id IN (SELECT …)} 子查询，
 * 故可见性断言只用「返回行集合」而不涉及其 SQL 形态。
 */
class ProductWriteFaceT131Test {

    /** 建表脚本（相对仓库根）。 */
    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql");

    /** 会话身份。 */
    private static final long OPERATOR = 42L;

    /** 会话 ID。 */
    private static final String SESSION = "S-T131";

    private InMemoryDmEngine engine;
    private DmProductDao productDao;
    private DmProductUnitDao unitDao;
    private DmSubProductWhitelistDao whitelistDao;
    private DmProductChangeLogDao changeLogDao;
    private TxTemplate tx;

    /** 调用者可见子账户（默认哨兵 0 = 跨账户只读 ⇒ 读面不加过滤）。 */
    private List<Integer> visibleSubAccounts = new ArrayList<>(
            Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL));

    /** 写面授权（默认放行；拒绝路径由用例替换）。 */
    private AccountLevelConfigAuthorizer authorizer = uid -> { };

    private int sa1;
    private int sa2;
    private long cuId;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        productDao = new DmProductDao(tx);
        unitDao = new DmProductUnitDao(tx);
        whitelistDao = new DmSubProductWhitelistDao(tx);
        changeLogDao = new DmProductChangeLogDao(tx);

        DmSubAccountDao subAccountDao = new DmSubAccountDao(tx);
        sa1 = seedSubAccount(subAccountDao, 1);
        sa2 = seedSubAccount(subAccountDao, 2);
        cuId = insertProduct("CU", "沪铜", "COMMODITY", "ENABLED");
    }

    // ============================================================ 一、路由注册（方法级）

    @Test
    @DisplayName("验收 1：(方法,路径) 5 对全部注册且命中预期 Handler —— PUT /{id}、POST /{id}/{disable|enable|unit}、GET /whitelist")
    void writeSurfaceRoutesAreRegistered() {
        Router router = new Router();
        ProductRoutes.register(router, service());

        assertHandler(router, "PUT", "/product/" + cuId, "ProductHandler");
        assertHandler(router, "POST", "/product/" + cuId + "/disable", "ProductActionHandler");
        assertHandler(router, "POST", "/product/" + cuId + "/enable", "ProductActionHandler");
        assertHandler(router, "POST", "/product/" + cuId + "/unit", "ProductActionHandler");
        assertHandler(router, "GET", "/product/whitelist", "WhitelistHandler");

        // 声明面同步：ApiRoutes 的对级清单须含这 5 对（T-097 等值判据的另一半）；
        // 路径须落在 §4.1.1 v1.5.27 已声明集合内（ModuleRoutesConsistencyTest 机械守卫 pair ⊆ path）
        List<String> declared = ApiRoutes.allRoutes();
        for (String pair : Arrays.asList("PUT /api/v1/product/{id}",
                "POST /api/v1/product/{id}/disable", "POST /api/v1/product/{id}/enable",
                "POST /api/v1/product/{id}/unit", "GET /api/v1/product/whitelist")) {
            assertTrue(declared.contains(pair), "ApiRoutes 未声明：" + pair);
        }
        for (String path : Arrays.asList("/product/{id}", "/product/{id}/disable",
                "/product/{id}/enable", "/product/{id}/unit", "/product/whitelist")) {
            assertTrue(ApiRoutes.byModule().get("PRODUCT").contains(path),
                    "路径级声明（§4.1.1 镜像）须含 " + path);
        }

        // 注册序守卫（T-151）：新增静态 2 段 GET 不得抢占 / 也不得被 /product/{id} 抢占
        Router.RouteMatch quota = router.match("GET", ApiRoutes.full("/product/quota"));
        assertNotNull(quota, "GET /product/quota 须仍可达");
        assertEquals("QuotaHandler", quota.handler().getClass().getSimpleName());
    }

    @Test
    @DisplayName("动作契约：未声明的动作子路径 ⇒ 404（无臆造动作；品种不允许物理删除 R-PRODUCT-1）")
    void undeclaredActionPathIsNotRouted(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult delete = dispatch(dispatcher, "POST", "/product/" + cuId + "/delete", "{}");
        assertEquals(404, delete.status, "未声明子路径不得被占位符吞掉（/product/{id}/contracts 等同段数亦然）");
        assertEquals("ENABLED", productDao.findById(cuId).status);

        // 反向守卫：POST /product/{id}（无动作段）**不**应是可写入口（避免「一资源一路径混多语义」）
        HttpResult bare = dispatch(dispatcher, "POST", "/product/" + cuId, "{}");
        assertEquals(405, bare.status, "POST /product/{id} 未注册 ⇒ 405（路径在、方法不匹配）");
    }

    // ============================================================ 二、修改（FR-PRODUCT-2）

    @Test
    @DisplayName("FR-PRODUCT-2 验收 1：PUT 改名称 ⇒ 落库 + 留痕（前后值）+ 编码不变")
    void updateProductNamePersistsAndLogs(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "PUT", "/product/" + cuId,
                "{\"productName\":\"沪铜（新）\",\"remark\":\"改口径\"}");

        assertEquals(200, result.status, "修改应成功：" + new String(result.body, StandardCharsets.UTF_8));
        Map<?, ?> data = (Map<?, ?>) body(result).get("data");
        assertEquals("沪铜（新）", data.get("productName"));
        assertEquals("CU", data.get("productCode"), "编码不得被修改（R-PRODUCT-3）");

        Product stored = productDao.findById(cuId);
        assertEquals("沪铜（新）", stored.productName);
        assertEquals("改口径", stored.remark);

        List<ProductChangeLog> logs = changeLogDao.findByProduct(cuId);
        ProductChangeLog nameLog = logOf(logs, "product_name");
        assertNotNull(nameLog, "须留痕 product_name 变更：实际=" + types(logs));
        assertEquals("沪铜", nameLog.beforeValue, "留痕须含变更前值（FR-PRODUCT-14）");
        assertEquals("沪铜（新）", nameLog.afterValue, "留痕须含变更后值");
        assertEquals("UPDATE", nameLog.changeType);
        assertEquals(OPERATOR, nameLog.operatorId, "留痕须含操作人");
        assertNotNull(nameLog.changeTime, "留痕须含时间");
    }

    @Test
    @DisplayName("FR-PRODUCT-2 验收 2：请求体塞 productCode ⇒ 无该入口，编码不被改写（不静默接受）")
    void updateProductCodeIsNotWritable(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "PUT", "/product/" + cuId,
                body("{\"productCode\":\"HACKED\",\"productName\":\"沪铜二号\"}"));

        assertEquals(200, result.status);
        Product stored = productDao.findById(cuId);
        assertEquals("CU", stored.productCode, "编码一经使用不得修改（R-PRODUCT-3 验收 2）");
        assertEquals("沪铜二号", stored.productName, "其余可改属性仍生效");
    }

    @Test
    @DisplayName("FR-PRODUCT-2/CH-001：分类变更 ⇒ 10012 阻断类，且 ruleId/current/threshold/nextStep 四项齐全")
    void updateCategoryChangeIsBlockedWithFourFields(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "PUT", "/product/" + cuId,
                body("{\"productName\":\"沪铜\",\"category\":\"TRADE\"}"));

        assertEquals(412, result.status, "分类变更属合规门禁 ⇒ 10012（HTTP 412）");
        Map<?, ?> envelope = body(result);
        assertEquals(10012, ((Number) envelope.get("code")).intValue());
        assertEquals("R-PRODUCT-4", envelope.get("ruleId"), "阻断类须带 ruleId");
        assertEquals("COMMODITY", envelope.get("current"), "须带现值");
        assertNotNull(envelope.get("threshold"));
        assertNotNull(envelope.get("nextStep"));
        assertEquals("COMMODITY", productDao.findById(cuId).category, "被拒绝的变更不得落库");
    }

    @Test
    @DisplayName("FR-PRODUCT-2 验收 4：名称置空 ⇒ 10001（必填校验）")
    void updateRejectsBlankName(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "PUT", "/product/" + cuId,
                body("{\"productName\":\"   \"}"));

        assertEquals(400, result.status);
        assertEquals(10001, ((Number) body(result).get("code")).intValue());
        assertEquals("沪铜", productDao.findById(cuId).productName);
    }

    @Test
    @DisplayName("权限（T-118 强制点）：授权入口拒绝 ⇒ 10003，且不落库、不留痕")
    void writeSurfaceHonoursAuthorizer(@TempDir Path webRoot) {
        authorizer = uid -> {
            throw BizException.of(ErrorCode.FUNCTION_PERMISSION_DENIED, "T-131 夹具：无全局对象配置权");
        };
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult update = dispatch(dispatcher, "PUT", "/product/" + cuId,
                "{\"productName\":\"越权改名\"}");
        HttpResult disable = dispatch(dispatcher, "POST", "/product/" + cuId + "/disable",
                "{\"reason\":\"越权停用\"}");
        HttpResult enable = dispatch(dispatcher, "POST", "/product/" + cuId + "/enable", "{}");
        HttpResult unit = dispatch(dispatcher, "POST", "/product/" + cuId + "/unit",
                "{\"spotUnit\":\"吨\",\"futuresUnit\":\"手\",\"convertFactor\":\"5\","
                        + "\"effectiveFrom\":\"" + LocalDate.now().toString() + "\"}");

        for (HttpResult denied : Arrays.asList(update, disable, enable, unit)) {
            assertEquals(403, denied.status, "越权写面须 10003（HTTP 403）");
            assertEquals(10003, ((Number) body(denied).get("code")).intValue());
        }
        assertEquals("沪铜", productDao.findById(cuId).productName, "越权不得落库");
        assertEquals("ENABLED", productDao.findById(cuId).status);
        assertTrue(changeLogDao.findByProduct(cuId).isEmpty(), "越权不得产生留痕（判定在写入前）");
    }

    // ============================================================ 三、停用 / 启用（FR-PRODUCT-3/4）

    @Test
    @DisplayName("FR-PRODUCT-3：停用 ⇒ 状态 DISABLED + 级联移出在册白名单 + 返回移出行数 + 留痕")
    void disableProductCascadesWhitelist(@TempDir Path webRoot) {
        insertWhitelist(sa1, cuId, "ENABLED");
        insertWhitelist(sa2, cuId, "ENABLED");
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "POST", "/product/" + cuId + "/disable",
                "{\"reason\":\"不再套保\"}");

        assertEquals(200, result.status, new String(result.body, StandardCharsets.UTF_8));
        Map<?, ?> data = (Map<?, ?>) body(result).get("data");
        assertEquals("DISABLED", data.get("status"));
        assertEquals(2, ((Number) data.get("removedWhitelistRows")).intValue(), "须回显级联移出行数");

        Product stored = productDao.findById(cuId);
        assertEquals("DISABLED", stored.status);
        assertEquals("不再套保", stored.disabledReason);
        for (SubProductWhitelist row : whitelistDao.findByProduct(cuId)) {
            assertEquals("DISABLED", row.status, "停用须级联移出白名单（保留历史行）");
            assertTrue(row.disabledReason != null && row.disabledReason.contains("不再套保"));
            assertEquals("PENDING", row.dispatchStatus, "变更须待下发（R-PRODUCT-9）");
        }
        assertNotNull(logOf(changeLogDao.findByProduct(cuId), "status"), "停用须留痕 status");
    }

    @Test
    @DisplayName("FR-PRODUCT-3 输入列：停用原因为必填 ⇒ 缺失即 10001（不静默放行）")
    void disableRequiresReason(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "POST", "/product/" + cuId + "/disable", "{}");

        assertEquals(400, result.status);
        assertEquals(10001, ((Number) body(result).get("code")).intValue());
        assertEquals("ENABLED", productDao.findById(cuId).status, "缺原因不得停用");
    }

    @Test
    @DisplayName("R-PRODUCT-1：状态机无幂等重复边 —— 已停用再停用 ⇒ 10009")
    void disableTwiceRejected(@TempDir Path webRoot) {
        Product row = productDao.findById(cuId);
        row.status = "DISABLED";
        row.disabledReason = "夹具已停用";
        assertEquals(1, productDao.updateById(row, 1L));
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "POST", "/product/" + cuId + "/disable",
                "{\"reason\":\"重复停用\"}");

        assertEquals(409, result.status, "状态不允许 ⇒ 10009（HTTP 409）");
        assertEquals(10009, ((Number) body(result).get("code")).intValue());
    }

    @Test
    @DisplayName("FR-PRODUCT-4 验收 3 / R-PRODUCT-6：缺生效换算关系 ⇒ 启用被拒 10015（不降级放行）")
    void enableWithoutUnitRejected(@TempDir Path webRoot) {
        markDisabled(cuId);
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "POST", "/product/" + cuId + "/enable", "{}");

        assertEquals(409, result.status);
        assertEquals(10015, ((Number) body(result).get("code")).intValue());
        assertEquals("DISABLED", productDao.findById(cuId).status, "被拒绝的启用不得落库");
    }

    @Test
    @DisplayName("FR-PRODUCT-4 验收 1：先补换算关系（FR-PRODUCT-13 端点）再启用 ⇒ ENABLED + 留痕")
    void unitThenEnable(@TempDir Path webRoot) {
        markDisabled(cuId);
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult unit = dispatch(dispatcher, "POST", "/product/" + cuId + "/unit",
                "{\"spotUnit\":\"吨\",\"futuresUnit\":\"手\",\"convertFactor\":\"5.0000\","
                        + "\"convertDesc\":\"1 手 = 5 吨\",\"effectiveFrom\":\""
                        + LocalDate.now().minusDays(1).toString() + "\"}");
        assertEquals(200, unit.status, new String(unit.body, StandardCharsets.UTF_8));
        assertTrue(((Number) ((Map<?, ?>) body(unit).get("data")).get("unitId")).longValue() > 0,
                "换算关系须落库");
        ProductUnit effective = unitDao.findEffective(cuId, Clocks.today());
        assertNotNull(effective, "新换算关系须成为当前生效行");
        assertEquals(0, effective.convertFactor.compareTo(new java.math.BigDecimal("5.0000")));

        HttpResult enable = dispatch(dispatcher, "POST", "/product/" + cuId + "/enable", "{}");
        assertEquals(200, enable.status, new String(enable.body, StandardCharsets.UTF_8));
        assertEquals("ENABLED", productDao.findById(cuId).status);
        assertNull(productDao.findById(cuId).disabledReason, "启用须清空停用原因");
        assertNotNull(logOf(changeLogDao.findByProduct(cuId), "status"), "启用须留痕");
    }

    // ============================================================ 四、计量单位（FR-PRODUCT-13）

    @Test
    @DisplayName("FR-PRODUCT-13 验收 2：换算系数 0 / 负数 ⇒ 10001；格式非法亦 10001（不静默取 0）")
    void unitRejectsNonPositiveFactor(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);
        String date = LocalDate.now().toString();

        HttpResult zero = dispatch(dispatcher, "POST", "/product/" + cuId + "/unit",
                "{\"spotUnit\":\"吨\",\"futuresUnit\":\"手\",\"convertFactor\":\"0\","
                        + "\"effectiveFrom\":\"" + date + "\"}");
        assertEquals(400, zero.status);
        assertEquals(10001, ((Number) body(zero).get("code")).intValue());

        HttpResult negative = dispatch(dispatcher, "POST", "/product/" + cuId + "/unit",
                "{\"spotUnit\":\"吨\",\"futuresUnit\":\"手\",\"convertFactor\":\"-1\","
                        + "\"effectiveFrom\":\"" + date + "\"}");
        assertEquals(10001, ((Number) body(negative).get("code")).intValue());

        HttpResult notNumber = dispatch(dispatcher, "POST", "/product/" + cuId + "/unit",
                "{\"spotUnit\":\"吨\",\"futuresUnit\":\"手\",\"convertFactor\":\"五\","
                        + "\"effectiveFrom\":\"" + date + "\"}");
        assertEquals(10001, ((Number) body(notNumber).get("code")).intValue(),
                "字符串化数值口径下非数字须明确报错");
        assertNull(unitDao.findEffective(cuId, Clocks.today()), "被拒绝的换算关系不得落库");
    }

    @Test
    @DisplayName("FR-PRODUCT-13：生效起始日必填且格式 yyyy-MM-dd（换算变更不追溯重算历史）")
    void unitRequiresEffectiveFrom(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult missing = dispatch(dispatcher, "POST", "/product/" + cuId + "/unit",
                "{\"spotUnit\":\"吨\",\"futuresUnit\":\"手\",\"convertFactor\":\"5\"}");
        assertEquals(400, missing.status);
        assertEquals(10001, ((Number) body(missing).get("code")).intValue());

        HttpResult badFormat = dispatch(dispatcher, "POST", "/product/" + cuId + "/unit",
                "{\"spotUnit\":\"吨\",\"futuresUnit\":\"手\",\"convertFactor\":\"5\","
                        + "\"effectiveFrom\":\"2026/01/01\"}");
        assertEquals(10001, ((Number) body(badFormat).get("code")).intValue());
    }

    // ============================================================ 五、白名单读取（FR-PRODUCT-6）

    @Test
    @DisplayName("FR-PRODUCT-6：GET /product/whitelist?productId= ⇒ 完整白名单（含已移出历史行），可按 status 收窄")
    void whitelistReadReturnsFullRows(@TempDir Path webRoot) {
        insertWhitelist(sa1, cuId, "ENABLED");
        insertWhitelist(sa2, cuId, "DISABLED", "COMMODITY");
        RequestDispatcher dispatcher = dispatcher(webRoot);

        List<?> all = data(dispatch(dispatcher, "GET", "/product/whitelist",
                "productId=" + cuId, null));
        assertEquals(2, all.size(), "「完整」须含 ENABLED 与已移出（DISABLED 保留历史）两类行");
        assertEquals(Arrays.asList(sa1, sa2), subAccountIds(all));

        List<?> enabled = data(dispatch(dispatcher, "GET", "/product/whitelist",
                "productId=" + cuId + "&status=ENABLED", null));
        assertEquals(Collections.singletonList(sa1), subAccountIds(enabled), "status 收窄须生效");

        List<?> disabled = data(dispatch(dispatcher, "GET", "/product/whitelist",
                "productId=" + cuId + "&status=DISABLED", null));
        assertEquals(Collections.singletonList(sa2), subAccountIds(disabled));

        Map<?, ?> row = (Map<?, ?>) all.get(0);
        assertTrue(row.containsKey("dispatchStatus"), "读面须暴露下发状态（R-PRODUCT-9）");
        assertEquals("PENDING", row.get("dispatchStatus"));
        boolean overrideSeen = false;
        for (Object each : all) {
            Map<?, ?> item = (Map<?, ?>) each;
            assertTrue(item.containsKey("id") && item.containsKey("productId"), "读面行须含主键与品种：" + item);
            if ("COMMODITY".equals(item.get("categoryOverride"))) {
                overrideSeen = true;
            }
        }
        assertTrue(overrideSeen, "读面须暴露品种级分类覆盖（Q-2）：" + all);
    }

    @Test
    @DisplayName("FR-PRODUCT-6 + FR-PRODUCT-12：按 subAccountId 反向查；subAccountId=0（哨兵）与两参皆缺 ⇒ 10001")
    void whitelistReadBySubAccount(@TempDir Path webRoot) {
        insertWhitelist(sa1, cuId, "ENABLED");
        RequestDispatcher dispatcher = dispatcher(webRoot);

        List<?> bySub = data(dispatch(dispatcher, "GET", "/product/whitelist",
                "subAccountId=" + sa1, null));
        assertEquals(Collections.singletonList(cuId), productIds(bySub));

        HttpResult sentinel = dispatch(dispatcher, "GET", "/product/whitelist", "subAccountId=0", null);
        assertEquals(400, sentinel.status);
        assertEquals(10001, ((Number) body(sentinel).get("code")).intValue(),
                "哨兵 0 不是白名单行 ⇒ 取值域外（DATA_MODEL §0.2）");

        HttpResult none = dispatch(dispatcher, "GET", "/product/whitelist", null, null);
        assertEquals(10001, ((Number) body(none).get("code")).intValue(),
                "两参皆缺须明确报错（不得返回全量白名单）");
    }

    @Test
    @DisplayName("NFR-SEC-7 读面隔离：受限身份只看到可见子账户的行；空可见集 ⇒ 恒空（严禁退化为全量）")
    void whitelistReadRespectsVisibility(@TempDir Path webRoot) {
        insertWhitelist(sa1, cuId, "ENABLED");
        insertWhitelist(sa2, cuId, "ENABLED");

        visibleSubAccounts = new ArrayList<>(Collections.singletonList(sa1));
        RequestDispatcher restricted = dispatcher(webRoot);
        assertEquals(Collections.singletonList(sa1),
                subAccountIds(data(dispatch(restricted, "GET", "/product/whitelist",
                        "productId=" + cuId, null))),
                "受限身份只可见自己子账户的白名单行");

        visibleSubAccounts = new ArrayList<>();
        RequestDispatcher noScope = dispatcher(webRoot);
        assertEquals(Collections.emptyList(),
                data(dispatch(noScope, "GET", "/product/whitelist", "productId=" + cuId, null)),
                "空可见集 ⇒ 恒空（fail-closed，严禁退化为不过滤）");
    }

    @Test
    @DisplayName("NFR-SEC-7：显式给定不可见子账户 ⇒ 10004（读面断言，与 GET /product/quota 同口径）")
    void whitelistReadDeniesInvisibleSubAccount(@TempDir Path webRoot) {
        insertWhitelist(sa2, cuId, "ENABLED");
        visibleSubAccounts = new ArrayList<>(Collections.singletonList(sa1));
        RequestDispatcher dispatcher = dispatcher(webRoot);

        HttpResult result = dispatch(dispatcher, "GET", "/product/whitelist",
                "subAccountId=" + sa2, null);

        assertEquals(403, result.status, "越权读显式目标 ⇒ 10004（HTTP 403）");
        assertEquals(10004, ((Number) body(result).get("code")).intValue());
    }

    // ------------------------------------------------------------ 工具：装配

    /** 用当前 {@code visibleSubAccounts} / {@code authorizer} 组装服务。 */
    private VarietyService service() {
        SubAccountVisibility visibility = uid -> visibleSubAccounts;
        return new VarietyService(tx, productDao, unitDao, null, null, whitelistDao, null, null,
                changeLogDao, visibility, authorizer);
    }

    /** 生产同构分发链（Router + ProductRoutes + 会话过滤器）。 */
    private RequestDispatcher dispatcher(Path webRoot) {
        Router router = new Router();
        ProductRoutes.register(router, service());
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session(SESSION, OPERATOR, "t131", 1L, Clocks.now().plusMinutes(30)));
        return new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
    }

    private static HttpResult dispatch(RequestDispatcher dispatcher, String method, String path,
                                       String query, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + SESSION);
        headers.put("Content-Type", "application/json; charset=UTF-8");
        return dispatcher.dispatch(new RequestContext(method, ApiRoutes.full(path), query, headers,
                body == null ? "" : body, "HGT131", "127.0.0.1"));
    }

    private static HttpResult dispatch(RequestDispatcher dispatcher, String method, String path,
                                       String body) {
        return dispatch(dispatcher, method, path, null, body);
    }

    private static String body(String json) {
        return json;
    }

    private static Map<?, ?> body(HttpResult result) {
        return JsonCodec.fromJson(new String(result.body, StandardCharsets.UTF_8), Map.class);
    }

    private static List<?> data(HttpResult result) {
        assertEquals(200, result.status, "应成功：" + new String(result.body, StandardCharsets.UTF_8));
        Object data = body(result).get("data");
        assertNotNull(data, "成功响应须含 data");
        return (List<?>) data;
    }

    private static List<Integer> subAccountIds(List<?> rows) {
        List<Integer> out = new ArrayList<>();
        for (Object row : rows) {
            Object value = ((Map<?, ?>) row).get("subAccountId");
            assertNotNull(value, "白名单行须含 subAccountId：" + row);
            out.add(((Number) value).intValue());
        }
        Collections.sort(out);
        return out;
    }

    private static List<Long> productIds(List<?> rows) {
        List<Long> out = new ArrayList<>();
        for (Object row : rows) {
            out.add(((Number) ((Map<?, ?>) row).get("productId")).longValue());
        }
        Collections.sort(out);
        return out;
    }

    private static void assertHandler(Router router, String method, String path,
                                      String expectedSimpleName) {
        Router.RouteMatch match = router.match(method, ApiRoutes.full(path));
        assertNotNull(match, method + " " + path + " 未注册");
        assertEquals(expectedSimpleName, match.handler().getClass().getSimpleName(),
                method + " " + path + " 须命中 " + expectedSimpleName + "，实际="
                        + match.handler().getClass().getSimpleName());
    }

    private static ProductChangeLog logOf(List<ProductChangeLog> logs, String changedField) {
        for (ProductChangeLog log : logs) {
            if (changedField.equals(log.changedField)) {
                return log;
            }
        }
        return null;
    }

    private static List<String> types(List<ProductChangeLog> logs) {
        List<String> out = new ArrayList<>();
        for (ProductChangeLog log : logs) {
            out.add(log.changeType + ":" + log.changedField);
        }
        return out;
    }

    // ------------------------------------------------------------ 工具：夹具

    private void markDisabled(long productId) {
        Product row = productDao.findById(productId);
        row.status = "DISABLED";
        row.disabledReason = "T-131 夹具停用";
        assertEquals(1, productDao.updateById(row, 1L));
    }

    private long insertProduct(String code, String name, String category, String status) {
        Product product = new Product();
        product.productCode = code;
        product.productName = name;
        product.category = category;
        product.categoryManaged = "Y";
        product.status = status;
        product.isDomesticExchange = "Y";
        product.currency = "CNY";
        if ("DISABLED".equals(status)) {
            product.disabledReason = "T-131 夹具停用";
        }
        assertEquals(1, productDao.insert(product, 1L), "品种写入失败：" + code);
        return product.id;
    }

    private void insertWhitelist(int subAccountId, long productId, String status) {
        insertWhitelist(subAccountId, productId, status, null);
    }

    private void insertWhitelist(int subAccountId, long productId, String status, String categoryOverride) {
        SubProductWhitelist row = new SubProductWhitelist();
        row.subAccountId = subAccountId;
        row.productId = productId;
        row.status = status;
        row.categoryOverride = categoryOverride;
        row.dispatchStatus = "PENDING";
        if ("DISABLED".equals(status)) {
            row.disabledReason = "T-131 夹具移出";
        }
        assertEquals(1, whitelistDao.insert(row, 1L), "白名单写入失败");
    }

    private int seedSubAccount(DmSubAccountDao dao, int seq) {
        SubAccount row = new SubAccount();
        row.subAccountCode = "SA-T131-" + seq;
        row.subAccountName = "子账户" + seq;
        row.status = "ENABLED";
        row.category = "COMMODITY";
        row.isDefault = "N";
        assertEquals(1, dao.insert(row, 1L), "子账户写入失败：" + seq);
        return row.id;
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test，当前="
                + Paths.get("").toAbsolutePath());
    }
}
