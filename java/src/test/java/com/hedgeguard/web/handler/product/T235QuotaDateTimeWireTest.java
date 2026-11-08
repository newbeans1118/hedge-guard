package com.hedgeguard.web.handler.product;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductQuota;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.dao.impl.DmProductChangeLogDao;
import com.hedgeguard.dao.impl.DmProductContractDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmProductQuotaDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmSubAccountDao;
import com.hedgeguard.dao.impl.DmSubInstrumentQuotaDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AccountLevelConfigAuthorizer;
import com.hedgeguard.service.SubAccountVisibility;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-235} 验收 2：<b>线上词法</b>守卫——{@code GET|POST /api/v1/product/quota} 的日期 / 时间字段
 * 必须是 ISO 字符串（{@code string}），不是 Gson 反射产物对象。
 *
 * <p><b>为何选这条端点</b>：{@code GET /product/quota} 把 {@code ProductQuota} /
 * {@code SubInstrumentQuota} <b>实体直出</b>（{@code VarietyService#quotaPanorama} 以
 * {@code Map<String,Object>} 装实体），故一次响应同时覆盖两类列：
 * <ul>
 *   <li>{@code LocalDate} —— {@code effectiveFrom} / {@code effectiveTo}（{@code DATA_MODEL} {@code DATE}）；</li>
 *   <li>{@code LocalDateTime} —— {@code AuditedEntity.createdTime} / {@code updatedTime}（{@code TIMESTAMP(6)}）。</li>
 * </ul>
 * 改前二者分别是 {@code {"year":2026,"month":1,"day":1}} 与 {@code {"date":{...},"time":{...}}}，
 * 违反 {@code ARCHITECTURE.md} §4.1.1「时间 = ISO-8601；纯日期 = yyyy-MM-dd」（{@code T-132} 复核
 * {@code O-1} 实测，非 {@code T-132} 引入）。本类断言的是<b>响应原文词法</b>，不是「看起来对不对」。
 *
 * <p><b>判据取向：真实 DAO + 等价引擎 + 真实分发链</b>（与 {@code T132QuotaDecimalStringContractTest}
 * 同构）：建表脚本喂 {@link InMemoryDmEngine}，经真实 {@code Dm*Dao} / {@link VarietyService} /
 * {@link Router} / {@link RequestDispatcher} 走完整 HTTP 语义（假 DAO 直出实体会把「列精度 / 时间戳由
 * 应用层写入」一并隐藏，故不用）。
 *
 * <p><b>覆盖边界</b>：本类只覆盖该响应点；<b>全仓</b>日期面由
 * {@code com.hedgeguard.web.DateTimeWireContractTest}（实体包反射穷举）自动守卫。
 *
 * <p>归属：{@code T-235}（{@code #java2}）。
 */
class T235QuotaDateTimeWireTest {

    /** 建表脚本（相对仓库根）。 */
    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql");

    /** 会话身份。 */
    private static final long OPERATOR = 42L;

    /** 会话 ID。 */
    private static final String SESSION = "S-T235";

    /** §4.1.1 纯日期形态。 */
    private static final Pattern DATE_WIRE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** §4.1.1 时间形态（ISO-8601，无时区；小数秒可选）。 */
    private static final Pattern DATE_TIME_WIRE =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$");

    /** 夹具生效起始日（{@code DATE} 列）。 */
    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2026, 1, 1);

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmProductDao productDao;
    private DmProductContractDao contractDao;
    private DmProductUnitDao unitDao;
    private DmProductChangeLogDao changeLogDao;
    private DmProductQuotaDao quotaDao;
    private DmSubInstrumentQuotaDao instrumentQuotaDao;

    private int subAccountId;
    private long productId;

    /** 调用者可见子账户：哨兵 0（全部）+ 目标子账户（{@code NFR-SEC-7} 读取面断言）。 */
    private final List<Integer> visibleSubAccounts = new ArrayList<>();

    private final AccountLevelConfigAuthorizer authorizer = uid -> { };

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
        contractDao = new DmProductContractDao(tx);
        unitDao = new DmProductUnitDao(tx);
        changeLogDao = new DmProductChangeLogDao(tx);
        quotaDao = new DmProductQuotaDao(tx);
        instrumentQuotaDao = new DmSubInstrumentQuotaDao(tx);

        M1E2eSeed.reservedSubAccountZero(engine);
        subAccountId = seedSubAccount(new DmSubAccountDao(tx), 1);
        productId = insertProduct("CU", "沪铜");
        visibleSubAccounts.add(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        visibleSubAccounts.add(subAccountId);
    }

    // ============================================================ 一、GET 实体直出

    @Test
    @DisplayName("T-235 验收 2：GET /product/quota（实体直出）日期 / 时间字段一律为 ISO 字符串")
    void getQuotaPanoramaEmitsIsoDateStrings(@TempDir Path webRoot) {
        insertQuota("SUB_PRODUCT", subAccountId, EFFECTIVE_FROM);
        HttpResult result = get(webRoot);

        JsonObject layer1 = only(data(result), "layer1SubProduct");
        assertIsoDate(layer1, "effectiveFrom");
        assertEquals("2026-01-01", layer1.get("effectiveFrom").getAsString(),
                "纯日期必须以 yyyy-MM-dd 字符串下发（§4.1.1）；对象形态 {\"year\":...} 即 T-235 缺陷：" + layer1);

        JsonElement created = layer1.get("createdTime");
        assertNotNull(created, "审计列 createdTime 须下发（DATA_MODEL §0.3 " + "每张表都有" + "）：" + layer1);
        assertTrue(created.isJsonPrimitive() && created.getAsJsonPrimitive().isString(),
                "createdTime 必须是 ISO-8601 字符串，实际 token = " + created
                        + "（对象 {\"date\":{...},\"time\":{...}} 即 Gson 反射 java.time 私有字段的缺陷形态）");
        assertIsoDateTime(layer1, "createdTime");

        String raw = new String(result.body, StandardCharsets.UTF_8);
        assertTrue(!raw.contains("\"year\":"), "响应原文不得含 {\"year\":...} 对象形态：" + raw);
        assertTrue(!raw.contains("\"date\":{"), "响应原文不得含 {\"date\":{...}} 对象形态：" + raw);
        assertTrue(raw.contains("\"effectiveFrom\":\"2026-01-01\""),
                "纯日期须以带引号的 yyyy-MM-dd 出现（词法层守卫）：" + raw);
    }

    @Test
    @DisplayName("T-235 验收 2：GET /product/quota（层② 合约额度实体直出）同样为 ISO 字符串")
    void getQuotaPanoramaLayer2EmitsIsoDateStrings(@TempDir Path webRoot) {
        insertContract("cu2612");
        insertInstrumentQuota("cu2612", EFFECTIVE_FROM);
        JsonObject layer2 = only(data(get(webRoot)), "layer2SubInstrument");

        assertIsoDate(layer2, "effectiveFrom");
        assertEquals("2026-01-01", layer2.get("effectiveFrom").getAsString(),
                "层② 合约额度的纯日期同样须为 yyyy-MM-dd 字符串：" + layer2);
        assertIsoDateTime(layer2, "createdTime");
    }

    // ============================================================ 二、POST → GET 往返

    @Test
    @DisplayName("T-235 验收 2：POST /product/quota 写入字符串日期，GET 回读仍为 ISO 字符串（往返不变形）")
    void postThenGetRoundTripsDatesAsStrings(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);
        HttpResult written = dispatch(dispatcher, "POST", "/product/quota", null,
                quotaBody("SUB_PRODUCT", subAccountId, "100.0000", "2026-03-15"));
        assertEquals(200, written.status, "字符串日期应被接受："
                + new String(written.body, StandardCharsets.UTF_8));
        assertEquals(0, body(written).get("code").getAsInt(), "业务码应为 0：" + written.body);

        ProductQuota stored = findQuotaByFrom(LocalDate.of(2026, 3, 15));
        assertNotNull(stored, "额度行须落库（effectiveFrom=2026-03-15）");
        assertEquals(LocalDate.of(2026, 3, 15), stored.effectiveFrom,
                "落库纯日期须等于提交值（DATE 列，无时区漂移）：" + stored.effectiveFrom);

        JsonObject layer1 = only(data(get(webRoot)), "layer1SubProduct");
        assertIsoDate(layer1, "effectiveFrom");
        assertEquals("2026-03-15", layer1.get("effectiveFrom").getAsString(),
                "POST → GET 往返后纯日期文本不得变形（更不得变成 {\"year\":...} 对象）：" + layer1);
        assertIsoDateTime(layer1, "createdTime");
    }

    // ============================================================ 工具

    private static void assertIsoDate(JsonObject row, String field) {
        JsonElement element = row.get(field);
        assertNotNull(element, "缺字段 " + field + "：" + row);
        assertTrue(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
                field + " 须为 JSON string（§4.1.1 纯日期），实际 = " + element + "：" + row);
        assertTrue(DATE_WIRE.matcher(element.getAsString()).matches(),
                field + " 须形如 yyyy-MM-dd，实际 = \"" + element.getAsString() + "\"：" + row);
    }

    private static void assertIsoDateTime(JsonObject row, String field) {
        JsonElement element = row.get(field);
        assertNotNull(element, "缺字段 " + field + "：" + row);
        assertTrue(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
                field + " 须为 JSON string（§4.1.1 时间 = ISO-8601），实际 = " + element + "：" + row);
        assertTrue(DATE_TIME_WIRE.matcher(element.getAsString()).matches(),
                field + " 须形如 yyyy-MM-ddTHH:mm:ss[.fraction]，实际 = \"" + element.getAsString() + "\"：" + row);
    }

    private static JsonObject only(JsonObject data, String key) {
        assertNotNull(data.get(key), "全景须含 " + key + "：" + data);
        JsonArray array = data.getAsJsonArray(key);
        assertEquals(1, array.size(), key + " 夹具应恰 1 条：" + data);
        return array.get(0).getAsJsonObject();
    }

    private static JsonObject data(HttpResult result) {
        assertEquals(200, result.status, "应成功：" + new String(result.body, StandardCharsets.UTF_8));
        JsonObject root = JsonParser.parseString(new String(result.body, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(0, root.get("code").getAsInt(), "业务码应为 0：" + root);
        return root.getAsJsonObject("data");
    }

    private static JsonObject body(HttpResult result) {
        return JsonParser.parseString(new String(result.body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private VarietyService service() {
        SubAccountVisibility visibility = uid -> visibleSubAccounts;
        return new VarietyService(tx, productDao, unitDao, contractDao, null, null, quotaDao,
                instrumentQuotaDao, changeLogDao, visibility, authorizer);
    }

    private RequestDispatcher dispatcher(Path webRoot) {
        Router router = new Router();
        ProductRoutes.register(router, service());
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session(SESSION, OPERATOR, "t235", 1L, Clocks.now().plusMinutes(30)));
        return new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
    }

    private HttpResult get(Path webRoot) {
        return dispatch(dispatcher(webRoot), "GET", "/product/quota",
                "subAccountId=" + subAccountId + "&productId=" + productId, null);
    }

    private static HttpResult dispatch(RequestDispatcher dispatcher, String method, String path,
                                       String query, String requestBody) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + SESSION);
        headers.put("Content-Type", "application/json; charset=UTF-8");
        return dispatcher.dispatch(new RequestContext(method, ApiRoutes.full(path), query, headers,
                requestBody == null ? "" : requestBody, "HGT235", "127.0.0.1"));
    }

    private String quotaBody(String layer, int subAccount, String limit, String effectiveFrom) {
        return "{\"limitLayer\":\"" + layer + "\",\"subAccountId\":" + subAccount
                + ",\"productId\":" + productId + ",\"quotaLimit\":\"" + limit
                + "\",\"quotaUnit\":\"LOT\",\"effectiveFrom\":\"" + effectiveFrom + "\"}";
    }

    /** 按生效起始日取刚写入的额度行（避开自增主键猜测）。 */
    private ProductQuota findQuotaByFrom(LocalDate from) {
        for (ProductQuota row : quotaDao.findByLayerAndScope("SUB_PRODUCT", subAccountId)) {
            if (from.equals(row.effectiveFrom)) {
                return row;
            }
        }
        return null;
    }

    // ============================================================ 工具：夹具

    private long insertQuota(String layer, int subAccount, LocalDate from) {
        ProductQuota row = new ProductQuota();
        row.subAccountId = subAccount;
        row.limitLayer = layer;
        row.productId = productId;
        row.quotaLimit = new BigDecimal("1234.5600");
        row.quotaUnit = "LOT";
        row.usedQty = new BigDecimal("12.3400");
        row.frozenQty = new BigDecimal("0.0000");
        row.effectiveFrom = from;
        row.dispatchStatus = "PENDING";
        assertEquals(1, quotaDao.insert(row, OPERATOR), "额度夹具写入失败：" + layer);
        return row.id;
    }

    private void insertInstrumentQuota(String instrumentId, LocalDate from) {
        com.hedgeguard.dao.entity.SubInstrumentQuota row =
                new com.hedgeguard.dao.entity.SubInstrumentQuota();
        row.subAccountId = subAccountId;
        row.instrumentId = instrumentId;
        row.productId = productId;
        row.quotaLimit = new BigDecimal("1234.5600");
        row.quotaUnit = "LOT";
        row.usedQty = new BigDecimal("12.3400");
        row.frozenQty = new BigDecimal("0.0000");
        row.effectiveFrom = from;
        row.dispatchStatus = "PENDING";
        assertEquals(1, instrumentQuotaDao.insert(row, OPERATOR), "层② 夹具写入失败：" + instrumentId);
    }

    /** 合约夹具（层② 额度 {@code instrument_id} 的外键载体，{@code fk_sub_instr_quota_instr}）。 */
    private void insertContract(String instrumentId) {
        com.hedgeguard.dao.entity.ProductContract row = new com.hedgeguard.dao.entity.ProductContract();
        row.productId = productId;
        row.instrumentId = instrumentId;
        row.instrumentType = "FUTURES";
        row.exchangeCode = "SHFE";
        row.contractMultiplier = new BigDecimal("5.0000");
        row.expiryDate = LocalDate.of(2026, 12, 15);
        row.status = "ENABLED";
        row.isDomesticExchange = "Y";
        assertEquals(1, contractDao.insert(row, 1L), "合约夹具写入失败：" + instrumentId);
    }

    private int seedSubAccount(DmSubAccountDao dao, int seq) {
        SubAccount row = new SubAccount();
        row.subAccountCode = "SA-T235-" + seq;
        row.subAccountName = "子账户" + seq;
        row.status = "ENABLED";
        row.category = "COMMODITY";
        row.isDefault = "N";
        assertEquals(1, dao.insert(row, 1L), "子账户写入失败：" + seq);
        return row.id;
    }

    private long insertProduct(String code, String name) {
        Product product = new Product();
        product.productCode = code;
        product.productName = name;
        product.category = "COMMODITY";
        product.categoryManaged = "Y";
        product.status = "ENABLED";
        product.isDomesticExchange = "Y";
        product.currency = "CNY";
        assertEquals(1, productDao.insert(product, 1L), "品种写入失败：" + code);
        return product.id;
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
