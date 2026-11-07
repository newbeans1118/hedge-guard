package com.hedgeguard.web.handler.product;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductContract;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-132}：{@code GET|POST /api/v1/product/quota} 的<b>金额 / 数量字段字符串化十进制</b>契约守卫。
 *
 * <p><b>依据</b>：{@code ARCHITECTURE.md} §4.1.1「金额 / 数量 = 字符串化十进制（避免 JS {@code Number}
 * 精度丢失），如 {@code "1234.5600"}」+ §4.2.8.9 {@code G-2}「{@code quota_limit / used / frozen / limit /
 * released_quota} 一律 {@code string}（十进制）」，与 {@code DATA_MODEL.md} {@code DECIMAL(20,4)} 列交叉核对。
 *
 * <p><b>为何要这组用例</b>：{@code GET /product/quota} 曾把 {@code quotaPanorama} 的<b>实体直出</b>，
 * {@code BigDecimal} 经 Gson 默认 {@code BIG_DECIMAL} 适配器序列化为 JSON <b>{@code number}</b>
 * ⇒ 前端 {@code JSON.parse} 后即 {@code double}，{@code DECIMAL(20,4)} 的尾零与超 2^53 的有效位<b>在
 * 解析那一刻已丢失</b>（前端 {@code format.decimal} 兜底只掩盖症状）。本用例断言的是
 * <b>线上 JSON 词法类型</b>（{@code type == string}），不是「看起来对不对」。
 *
 * <p><b>判据取向：真实 DAO + 等价引擎 + 真实分发链</b>（与 {@code ProductWriteFaceT131Test} 同构）：
 * 建表脚本喂 {@link InMemoryDmEngine}，经真实 {@code Dm*Dao} / {@link VarietyService} /
 * {@code Router} / {@code RequestDispatcher} 走完整 HTTP 语义。假 DAO 直出实体时会把
 * 「列精度（{@code DECIMAL(20,4)} 尾零）」一并隐藏，故不用。
 *
 * <p><b>覆盖边界</b>：本类只覆盖 {@code /product/quota} 这一响应点；<b>全仓</b>同类点由
 * {@code com.hedgeguard.web.BigDecimalWireContractTest}（实体包反射穷举）+ 静态清单共同守卫。
 */
class T132QuotaDecimalStringContractTest {

    /** 建表脚本（相对仓库根）。 */
    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql");

    /** 会话身份。 */
    private static final long OPERATOR = 42L;

    /** 会话 ID。 */
    private static final String SESSION = "S-T132";

    /** 额度上限（{@code DECIMAL(20,4)} 全 4 位小数的代表值）。 */
    private static final String QUOTA_LIMIT = "1234.5600";

    /** 已占用（含尾零）。 */
    private static final String USED = "12.3400";

    /** 已冻结（全零但须保留 4 位小数）。 */
    private static final String FROZEN = "0.0000";

    /** 金额 / 数量类字段（{@code G-2} 点名 + {@code DATA_MODEL} {@code DECIMAL(20,4)} 列）。 */
    private static final String[] DECIMAL_FIELDS = {"quotaLimit", "usedQty", "frozenQty"};

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

    /** 调用者可见子账户：含哨兵 0（可读全部）与目标子账户（可写，{@code NFR-SEC-7} 写面断言）。 */
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
        insertContract("cu2612");
        visibleSubAccounts.add(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        visibleSubAccounts.add(subAccountId);
    }

    // ============================================================ 一、GET 响应：type == string

    @Test
    @DisplayName("T-132 验收 1：GET /product/quota 三层额度的金额/数量字段一律 JSON string（尾零不丢）")
    void getQuotaPanoramaEmitsStringDecimals(@TempDir Path webRoot) {
        seedThreeLayers();
        JsonObject data = data(get(webRoot));

        JsonObject layer1 = only(data, "layer1SubProduct");
        JsonObject layer3 = only(data, "layer3AccountProduct");
        JsonObject layer2 = only(data, "layer2SubInstrument");
        assertDecimalStrings(layer1, "layer1SubProduct");
        assertDecimalStrings(layer3, "layer3AccountProduct");
        assertDecimalStrings(layer2, "layer2SubInstrument");

        assertEquals(QUOTA_LIMIT, layer1.get("quotaLimit").getAsString(),
                "额度的 4 位小数尾零必须原样保留（字符串承载）：" + layer1);
        assertEquals(USED, layer1.get("usedQty").getAsString(), "已占用须原样保留尾零：" + layer1);
        assertEquals(FROZEN, layer1.get("frozenQty").getAsString(), "全零额度也须保留 4 位小数：" + layer1);
    }

    @Test
    @DisplayName("T-132 验收 1：GET /product/quota 的响应体不得出现任何金额/数量类 JSON number")
    void getQuotaPanoramaJsonLiteralsCarryDecimalsAsStrings(@TempDir Path webRoot) {
        seedThreeLayers();
        HttpResult result = get(webRoot);
        String raw = new String(result.body, StandardCharsets.UTF_8);

        for (String field : DECIMAL_FIELDS) {
            assertTrue(!raw.contains("\"" + field + "\":" + QUOTA_LIMIT)
                            && !raw.contains("\"" + field + "\":" + USED),
                    "响应原文不得以 JSON number 承载 " + field + "（词法层守卫）：" + raw);
        }
        assertTrue(raw.contains("\"quotaLimit\":\"" + QUOTA_LIMIT + "\""),
                "额度须以带引号的十进制字符串出现：" + raw);
    }

    // ============================================================ 二、POST 路径：请求 / 响应同样核

    @Test
    @DisplayName("T-132 验收 1：POST /product/quota 接受字符串化十进制并原样落库（GET 回读仍为 string）")
    void postAcceptsStringDecimalAndRoundTripsThroughGet(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);
        HttpResult result = dispatch(dispatcher, "POST", "/product/quota", null,
                quotaBody("SUB_PRODUCT", subAccountId, QUOTA_LIMIT));

        assertEquals(200, result.status, "字符串化十进制额度应被接受："
                + new String(result.body, StandardCharsets.UTF_8));
        Map<?, ?> body = body(result);
        assertEquals(0, ((Number) body.get("code")).intValue());
        Object id = body.get("data");
        assertNotNull(id, "成功响应须含 data（新行主键）");
        assertTrue(id instanceof Number, "data = 自增主键（计数类整数，不属于 §4.1.1 的金额/数量口径）");

        ProductQuota stored = quotaDao.findById(((Number) id).longValue());
        assertNotNull(stored, "额度行须落库");
        assertEquals(0, new BigDecimal(QUOTA_LIMIT).compareTo(stored.quotaLimit),
                "落库额度数值须等于提交值：" + stored.quotaLimit);
        assertEquals(4, stored.quotaLimit.scale(), "落库须保留 DECIMAL(20,4) 的 4 位小数：" + stored.quotaLimit);

        JsonObject layer1 = only(data(get(webRoot)), "layer1SubProduct");
        assertDecimalStrings(layer1, "POST 后 GET 回读的 layer1SubProduct");
        assertEquals(QUOTA_LIMIT, layer1.get("quotaLimit").getAsString(),
                "POST → GET 往返后额度文本不得变形：" + layer1);
    }

    @Test
    @DisplayName("T-132 验收 1：POST 层②（合约额度）同样接受字符串化十进制，GET 回读为 string")
    void postInstrumentQuotaKeepsDecimalString(@TempDir Path webRoot) {
        RequestDispatcher dispatcher = dispatcher(webRoot);
        HttpResult result = dispatch(dispatcher, "POST", "/product/quota", null,
                "{\"limitLayer\":\"SUB_INSTRUMENT\",\"instrumentId\":\"cu2612\",\"subAccountId\":"
                        + subAccountId + ",\"productId\":" + productId + ",\"quotaLimit\":\"" + QUOTA_LIMIT
                        + "\",\"quotaUnit\":\"LOT\",\"effectiveFrom\":\"2026-01-01\"}");
        assertEquals(200, result.status, "层②额度应成功："
                + new String(result.body, StandardCharsets.UTF_8));

        JsonObject layer2 = only(data(get(webRoot)), "layer2SubInstrument");
        assertDecimalStrings(layer2, "层② 合约额度");
        assertEquals(QUOTA_LIMIT, layer2.get("quotaLimit").getAsString(), "合约额度文本须原样：" + layer2);
    }

    // ============================================================ 工具：断言

    /**
     * 断言一行额度视图的金额/数量字段<b>词法类型为 string</b>。
     *
     * <p>三个断言各自独立给出可读失败信息（改前红 / 改后绿的证据即来自此方法）。
     *
     * @param row   额度视图行
     * @param where 定位描述
     */
    private static void assertDecimalStrings(JsonObject row, String where) {
        assertNotNull(row, where + " 缺失");
        for (String field : DECIMAL_FIELDS) {
            JsonElement element = row.get(field);
            assertNotNull(element, where + " 缺字段 " + field + "（§4.1.1 / G-2 要求必下发）：" + row);
            assertTrue(element.isJsonPrimitive(), where + "." + field + " 须为 JSON 基元：" + row);
            assertTrue(element.getAsJsonPrimitive().isString(),
                    where + "." + field + " 须为字符串化十进制（type == string），实际 token = "
                            + (element.getAsJsonPrimitive().isNumber() ? "number" : element.toString())
                            + "；依据 ARCHITECTURE §4.1.1（金额/数量）与 §4.2.8.9 G-2：" + row);
            assertTrue(!element.getAsJsonPrimitive().isNumber(),
                    where + "." + field + " 不得为 JSON number：" + row);
        }
    }

    private static JsonObject only(JsonObject data, String key) {
        assertNotNull(data.get(key), "三层全景须含 " + key + "：" + data);
        assertTrue(data.get(key).isJsonArray(), key + " 须为数组：" + data);
        assertEquals(1, data.getAsJsonArray(key).size(), key + " 夹具应恰 1 条：" + data);
        return data.getAsJsonArray(key).get(0).getAsJsonObject();
    }

    private static JsonObject data(HttpResult result) {
        assertEquals(200, result.status, "应成功：" + new String(result.body, StandardCharsets.UTF_8));
        JsonObject root = JsonParser.parseString(new String(result.body, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(0, root.get("code").getAsInt(), "业务码应为 0：" + root);
        return root.getAsJsonObject("data");
    }

    // ============================================================ 工具：装配

    private VarietyService service() {
        SubAccountVisibility visibility = uid -> visibleSubAccounts;
        return new VarietyService(tx, productDao, unitDao, contractDao, null, null, quotaDao,
                instrumentQuotaDao, changeLogDao, visibility, authorizer);
    }

    private RequestDispatcher dispatcher(Path webRoot) {
        Router router = new Router();
        ProductRoutes.register(router, service());
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session(SESSION, OPERATOR, "t132", 1L, Clocks.now().plusMinutes(30)));
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
                requestBody == null ? "" : requestBody, "HGT132", "127.0.0.1"));
    }

    private static Map<?, ?> body(HttpResult result) {
        return com.hedgeguard.web.JsonCodec.fromJson(
                new String(result.body, StandardCharsets.UTF_8), Map.class);
    }

    private String quotaBody(String layer, int subAccount, String limit) {
        return "{\"limitLayer\":\"" + layer + "\",\"subAccountId\":" + subAccount
                + ",\"productId\":" + productId + ",\"quotaLimit\":\"" + limit
                + "\",\"quotaUnit\":\"LOT\",\"effectiveFrom\":\"2026-01-01\"}";
    }

    // ============================================================ 工具：夹具

    /** 三层额度各一行，金额字段带 4 位小数尾零（层③ 走账户哨兵 0）。 */
    private void seedThreeLayers() {
        long layer1Id = insertQuota("SUB_PRODUCT", subAccountId);
        insertQuota("ACCOUNT_PRODUCT", SubAccountScope.ALL_ACCOUNT_SENTINEL);

        ProductQuota layer1 = quotaDao.findById(layer1Id);
        layer1.usedQty = new BigDecimal(USED);
        layer1.frozenQty = new BigDecimal(FROZEN);
        assertEquals(1, quotaDao.updateById(layer1, OPERATOR), "已占用/已冻结写入失败");

        com.hedgeguard.dao.entity.SubInstrumentQuota layer2 =
                new com.hedgeguard.dao.entity.SubInstrumentQuota();
        layer2.subAccountId = subAccountId;
        layer2.instrumentId = "cu2612";
        layer2.productId = productId;
        layer2.quotaLimit = new BigDecimal(QUOTA_LIMIT);
        layer2.quotaUnit = "LOT";
        layer2.usedQty = new BigDecimal(USED);
        layer2.frozenQty = new BigDecimal(FROZEN);
        layer2.effectiveFrom = LocalDate.of(2026, 1, 1);
        layer2.dispatchStatus = "PENDING";
        assertEquals(1, instrumentQuotaDao.insert(layer2, OPERATOR), "层②夹具写入失败");
    }

    private long insertQuota(String layer, int subAccount) {
        ProductQuota row = new ProductQuota();
        row.subAccountId = subAccount;
        row.limitLayer = layer;
        row.productId = productId;
        row.quotaLimit = new BigDecimal(QUOTA_LIMIT);
        row.quotaUnit = "LOT";
        row.usedQty = new BigDecimal(USED);
        row.frozenQty = new BigDecimal(FROZEN);
        row.effectiveFrom = LocalDate.of(2026, 1, 1);
        row.dispatchStatus = "PENDING";
        assertEquals(1, quotaDao.insert(row, OPERATOR), "额度夹具写入失败：" + layer);
        return row.id;
    }

    private int seedSubAccount(DmSubAccountDao dao, int seq) {
        SubAccount row = new SubAccount();
        row.subAccountCode = "SA-T132-" + seq;
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

    /** 合约夹具（层② 额度 {@code instrument_id} 的外键载体，{@code fk_sub_instr_quota_instr}）。 */
    private void insertContract(String instrumentId) {
        ProductContract row = new ProductContract();
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
