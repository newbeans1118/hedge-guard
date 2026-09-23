package com.hedgeguard.web.handler.product;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ProductChangeLogDao;
import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.entity.ProductSpotCategory;
import com.hedgeguard.service.FakeTx;
import com.hedgeguard.service.SubAccountVisibility;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-151}：PRODUCT 读取面<b>同类残留</b>三端点的子账户数据隔离（{@code FR-PRODUCT-6} 处理规则 /
 * {@code FR-PRODUCT-14} / {@code NFR-SEC-7}；{@code ARCHITECTURE.md} §8.2③「其余读取面同须接同一过滤」）。
 *
 * <p><b>覆盖端点（穷举：PRODUCT 读面 7 个 GET 中，T-105/T-106 已收口 4 个，本单收口余 3 个）</b>：
 * <ul>
 *   <li>{@code GET /product/{id}/contracts}（{@code FR-PRODUCT-9}）；</li>
 *   <li>{@code GET /product/change-log?productId=}（{@code FR-PRODUCT-14}）；</li>
 *   <li>{@code GET /product/mapping?productId=}（{@code FR-PRODUCT-10}）。</li>
 * </ul>
 *
 * <p><b>口径</b>：三端点均<b>未显式给定「子账户目标」</b>（{@code productId} 是<b>产品</b>标识、非子账户标识）
 * ⇒ 一律走 {@code FR-PRODUCT-6} 处理规则的<b>统一错误</b>（{@code 10001}）：<b>不可见 ≡ 不存在</b>
 * （同一返回、不泄露存在性、不泄出对象标识）；同 {@code T-106} 详情面口径。
 *
 * <p><b>载体</b>：{@code t_product} 无 {@code sub_account_id} 列 ⇒ 可见性落 {@code t_sub_product_whitelist}
 * 子查询（{@link ProductDao#findByIdScoped}，{@code T-105}/{@code T-106} 同形态）。三端点均为「品种」的
 * 子资源（{@code product_id} 关联），故以<b>品种可见性门禁</b>承载；真实 SQL 形状与「顶层谓词仍生效」由
 * {@code DmProductDaoScopedSqlTest} 覆盖。完全离线（{@code NFR-TEST-2}）。
 */
class ProductReadScopeResidualTest {

    /** 操作者（会话身份）。 */
    private static final long OPERATOR = 42L;
    /** 会话 ID（身份 42 ⇒ 仅子账户 7）。 */
    private static final String SESSION = "S-42";
    /** 可见子账户。 */
    private static final int VISIBLE_SUB = 7;
    /** 不可见子账户（越权目标）。 */
    private static final int OTHER_SUB = 8;
    /** 可见子账户白名单覆盖的品种。 */
    private static final long PRODUCT_VISIBLE = 1L;
    /** 仅不可见子账户白名单覆盖的品种（越权读目标）。 */
    private static final long PRODUCT_OTHER = 2L;

    // ============================================================ 一、GET /product/{id}/contracts

    @Test
    @DisplayName("T-151 合约·越权：不可见品种 ⇒ 统一错误 10001，且零取数（不泄露合约明细）")
    void contractsInvisibleUnifiedError(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        HttpResult denied = get(dispatcher, "/product/" + PRODUCT_OTHER + "/contracts", null);

        assertEquals(400, denied.status, "不可见品种的合约面须被拒（统一错误 10001）");
        Map<String, Object> body = body(denied);
        assertEquals(10001, ((Number) body.get("code")).intValue());
        assertFalse(f.contract.queried, "越权须在取数前拦截（零取数，不泄露合约明细）");
        assertTrue(f.product.scopedTouched, "受限身份须走 scoped 路径（白名单子查询）");
        assertFalse(f.product.unscopedTouched, "不得走不加过滤路径");
        assertNoObjectIdentifiers((String) body.get("message"));
    }

    @Test
    @DisplayName("T-151 合约·一致：不可见与不存在<b>逐字节</b>同一（仅回显 id 不同，不泄露存在性）")
    void contractsInvisibleEqualsNonexistent(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        String invisible = message(dispatcher, "/product/" + PRODUCT_OTHER + "/contracts", null);
        String missing = message(dispatcher, "/product/9999/contracts", null);

        assertEquals(normalize(missing, 9999L), normalize(invisible, PRODUCT_OTHER),
                "统一模板（不区分「无权限」与「不存在」）：" + missing + " | " + invisible);
    }

    @Test
    @DisplayName("T-151 合约·可见者可读：白名单覆盖 ⇒ 200 并返回合约清单")
    void contractsVisibleReads(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        HttpResult ok = get(dispatcher, "/product/" + PRODUCT_VISIBLE + "/contracts", null);

        assertEquals(200, ok.status);
        List<?> data = data(ok);
        assertEquals(1, data.size(), "可见品种须返回其合约清单");
        assertEquals("cu2612", ((Map<?, ?>) data.get(0)).get("instrumentId"));
        assertTrue(f.contract.queried, "可见者须真正取数");
    }

    @Test
    @DisplayName("T-151 合约·跨账户只读（哨兵 0）：不加过滤、可见受审品种（不得误拒）")
    void contractsSentinelReadsAll(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = sentinelDispatcher(f, webRoot);

        HttpResult ok = get(dispatcher, "/product/" + PRODUCT_OTHER + "/contracts", null);

        assertEquals(200, ok.status, "哨兵 0 ⇒ 与改前等价（不加过滤），不得误拒");
        assertTrue(f.product.unscopedTouched, "哨兵 0 须走不加过滤路径");
        assertFalse(f.product.scopedTouched, "哨兵 0 不得构造 scope");
        assertEquals(1, data(ok).size());
    }

    // ============================================================ 二、GET /product/change-log?productId=

    @Test
    @DisplayName("T-151 变更历史·越权：不可见品种 ⇒ 统一错误 10001，且零取数（不泄露变更明细）")
    void changeLogInvisibleUnifiedError(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        HttpResult denied = get(dispatcher, "/product/change-log", "productId=" + PRODUCT_OTHER);

        assertEquals(400, denied.status, "不可见品种的变更历史面须被拒（统一错误 10001）");
        Map<String, Object> body = body(denied);
        assertEquals(10001, ((Number) body.get("code")).intValue());
        assertFalse(f.changeLog.queried, "越权须在取数前拦截（零取数，不泄露变更明细）");
        assertTrue(f.product.scopedTouched && !f.product.unscopedTouched, "须走 scoped 路径");
        assertNoObjectIdentifiers((String) body.get("message"));
    }

    @Test
    @DisplayName("T-151 变更历史·一致：不可见与不存在逐字节同一（仅回显 id 不同）")
    void changeLogInvisibleEqualsNonexistent(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        String invisible = message(dispatcher, "/product/change-log", "productId=" + PRODUCT_OTHER);
        String missing = message(dispatcher, "/product/change-log", "productId=9999");

        assertEquals(normalize(missing, 9999L), normalize(invisible, PRODUCT_OTHER),
                "统一模板：" + missing + " | " + invisible);
    }

    @Test
    @DisplayName("T-151 变更历史·可见者可读：白名单覆盖 ⇒ 200 并返回变更记录")
    void changeLogVisibleReads(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        HttpResult ok = get(dispatcher, "/product/change-log", "productId=" + PRODUCT_VISIBLE);

        assertEquals(200, ok.status);
        List<?> data = data(ok);
        assertEquals(1, data.size(), "可见品种须返回其变更历史");
        assertEquals("CREATE", ((Map<?, ?>) data.get(0)).get("changeType"));
        assertTrue(f.changeLog.queried);
    }

    @Test
    @DisplayName("T-151 变更历史·跨账户只读（哨兵 0）：可见受审品种（不得误拒）")
    void changeLogSentinelReadsAll(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = sentinelDispatcher(f, webRoot);

        HttpResult ok = get(dispatcher, "/product/change-log", "productId=" + PRODUCT_OTHER);

        assertEquals(200, ok.status, "哨兵 0 ⇒ 与改前等价，不得误拒");
        assertTrue(f.product.unscopedTouched && !f.product.scopedTouched, "哨兵 0 走不加过滤路径");
        assertEquals(1, data(ok).size());
    }

    // ============================================================ 三、GET /product/mapping?productId=

    @Test
    @DisplayName("T-151 现货映射·越权：不可见品种 ⇒ 统一错误 10001，且零取数（不泄露映射明细）")
    void mappingInvisibleUnifiedError(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        HttpResult denied = get(dispatcher, "/product/mapping", "productId=" + PRODUCT_OTHER);

        assertEquals(400, denied.status, "不可见品种的现货映射面须被拒（统一错误 10001）");
        Map<String, Object> body = body(denied);
        assertEquals(10001, ((Number) body.get("code")).intValue());
        assertFalse(f.mapping.queried, "越权须在取数前拦截（零取数，不泄露映射明细）");
        assertTrue(f.product.scopedTouched && !f.product.unscopedTouched, "须走 scoped 路径");
        assertNoObjectIdentifiers((String) body.get("message"));
    }

    @Test
    @DisplayName("T-151 现货映射·一致：不可见与不存在逐字节同一（仅回显 id 不同）")
    void mappingInvisibleEqualsNonexistent(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        String invisible = message(dispatcher, "/product/mapping", "productId=" + PRODUCT_OTHER);
        String missing = message(dispatcher, "/product/mapping", "productId=9999");

        assertEquals(normalize(missing, 9999L), normalize(invisible, PRODUCT_OTHER),
                "统一模板：" + missing + " | " + invisible);
    }

    @Test
    @DisplayName("T-151 现货映射·可见者可读：白名单覆盖 ⇒ 200 并返回映射清单")
    void mappingVisibleReads(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        HttpResult ok = get(dispatcher, "/product/mapping", "productId=" + PRODUCT_VISIBLE);

        assertEquals(200, ok.status);
        List<?> data = data(ok);
        assertEquals(1, data.size(), "可见品种须返回其映射清单");
        assertEquals("SPOT-CU", ((Map<?, ?>) data.get(0)).get("spotCategoryCode"));
        assertTrue(f.mapping.queried);
    }

    @Test
    @DisplayName("T-151 现货映射·跨账户只读（哨兵 0）：可见受审品种（不得误拒）")
    void mappingSentinelReadsAll(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = sentinelDispatcher(f, webRoot);

        HttpResult ok = get(dispatcher, "/product/mapping", "productId=" + PRODUCT_OTHER);

        assertEquals(200, ok.status, "哨兵 0 ⇒ 与改前等价，不得误拒");
        assertTrue(f.product.unscopedTouched && !f.product.scopedTouched, "哨兵 0 走不加过滤路径");
        assertEquals(1, data(ok).size());
    }

    // ============================================================ 四、三端点共享同一隔离面

    @Test
    @DisplayName("T-151 三端点同源：同一不可见品种在三端点均出<b>同一码同一模板</b>（隔离面一致）")
    void threeEndpointsShareSameIsolationFace(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(f, webRoot);

        String contracts = message(dispatcher, "/product/" + PRODUCT_OTHER + "/contracts", null);
        String changeLog = message(dispatcher, "/product/change-log", "productId=" + PRODUCT_OTHER);
        String mapping = message(dispatcher, "/product/mapping", "productId=" + PRODUCT_OTHER);

        assertEquals(contracts, changeLog, "合约面与变更历史面须同模板");
        assertEquals(contracts, mapping, "合约面与现货映射面须同模板");
    }

    @Test
    @DisplayName("T-151 fail-closed：无授权身份（可见集空）⇒ 三端点均拒（绝不退化为全可见）")
    void emptyGrantRejectsAll(@TempDir Path webRoot) {
        Fakes f = fixture();
        RequestDispatcher dispatcher = dispatcher(
                f, uid -> new ArrayList<Integer>(), webRoot);

        assertEquals(400, get(dispatcher, "/product/" + PRODUCT_VISIBLE + "/contracts", null).status);
        assertEquals(400, get(dispatcher, "/product/change-log",
                "productId=" + PRODUCT_VISIBLE).status);
        assertEquals(400, get(dispatcher, "/product/mapping",
                "productId=" + PRODUCT_VISIBLE).status);
        assertTrue(f.changeLog.queried == false && f.mapping.queried == false,
                "空集须恒假，绝不退化为全可见");
    }

    // ============================================================ 辅助

    /** 三端点共享的假 DAO 世界（品种 1 可见子账户 7；品种 2 仅子账户 8）。 */
    private static Fakes fixture() {
        Fakes f = new Fakes();
        f.product.add(PRODUCT_VISIBLE, "CU").add(PRODUCT_OTHER, "AL");
        f.product.white(PRODUCT_VISIBLE, VISIBLE_SUB).white(PRODUCT_OTHER, OTHER_SUB);

        ProductContract c1 = new ProductContract();
        c1.id = 11L;
        c1.productId = PRODUCT_VISIBLE;
        c1.instrumentId = "cu2612";
        c1.instrumentType = "FUTURES";
        c1.status = "ENABLED";
        ProductContract c2 = new ProductContract();
        c2.id = 22L;
        c2.productId = PRODUCT_OTHER;
        c2.instrumentId = "al2612";
        c2.instrumentType = "FUTURES";
        c2.status = "ENABLED";
        f.contract.rows.add(c1);
        f.contract.rows.add(c2);

        ProductSpotCategory m1 = new ProductSpotCategory();
        m1.id = 31L;
        m1.productId = PRODUCT_VISIBLE;
        m1.spotCategoryCode = "SPOT-CU";
        m1.mappingDirection = "BOTH";
        m1.status = "ENABLED";
        ProductSpotCategory m2 = new ProductSpotCategory();
        m2.id = 32L;
        m2.productId = PRODUCT_OTHER;
        m2.spotCategoryCode = "SPOT-AL";
        m2.mappingDirection = "BOTH";
        m2.status = "ENABLED";
        f.mapping.rows.add(m1);
        f.mapping.rows.add(m2);

        ProductChangeLog l1 = changeLog(PRODUCT_VISIBLE, "CREATE");
        ProductChangeLog l2 = changeLog(PRODUCT_OTHER, "UPDATE");
        f.changeLog.rows.add(l1);
        f.changeLog.rows.add(l2);
        return f;
    }

    private static ProductChangeLog changeLog(long productId, String type) {
        ProductChangeLog log = new ProductChangeLog();
        log.productId = productId;
        log.changeType = type;
        log.changedField = "UPDATE".equals(type) ? "product_name" : null;
        log.operatorId = 1L;
        log.changeTime = Clocks.now();
        return log;
    }

    /** 受限身份（可见 [7]）。 */
    private static RequestDispatcher dispatcher(Fakes f, Path webRoot) {
        return dispatcher(f, uid -> Arrays.asList(VISIBLE_SUB), webRoot);
    }

    /** 全量可见身份（哨兵 0，跨账户只读）。 */
    private static RequestDispatcher sentinelDispatcher(Fakes f, Path webRoot) {
        return dispatcher(f, uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL), webRoot);
    }

    private static RequestDispatcher dispatcher(Fakes f, SubAccountVisibility visibility,
                                                Path webRoot) {
        VarietyService service = new VarietyService(FakeTx.newTemplate(), f.product, null,
                f.contract, f.mapping, null, null, null, f.changeLog, visibility);
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session(SESSION, OPERATOR, "u", 1L, Clocks.now().plusMinutes(30)));
        Router router = new Router();
        ProductRoutes.register(router, service);
        return new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
    }

    private static HttpResult get(RequestDispatcher dispatcher, String path, String rawQuery) {
        return dispatcher.dispatch(new RequestContext("GET", ApiRoutes.full(path), rawQuery,
                cookie(SESSION), "", "HGT151", "127.0.0.1"));
    }

    private static Map<String, String> cookie(String sessionId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + sessionId);
        return headers;
    }

    private static Map<String, Object> body(HttpResult result) {
        return JsonCodec.fromJson(
                new String(result.body, StandardCharsets.UTF_8), Map.class);
    }

    private static String message(RequestDispatcher dispatcher, String path, String rawQuery) {
        HttpResult result = get(dispatcher, path, rawQuery);
        assertEquals(400, result.status, "错误响应状态须 400");
        return (String) body(result).get("message");
    }

    private static List<?> data(HttpResult result) {
        Object data = body(result).get("data");
        assertNotNull(data, "成功响应须含 data");
        return (List<?>) data;
    }

    /** 归一化错误模板：把回显的 id 替换为占位符（用于比对「不可见」与「不存在」逐字节一致）。 */
    private static String normalize(String message, long id) {
        return message.replace("id=" + id, "id=<ID>");
    }

    /** 统一错误消息<b>不得</b>泄出对象标识（品种编码 / 合约代码 / 现货品类编码）。 */
    private static void assertNoObjectIdentifiers(String message) {
        assertFalse(message.contains("AL") || message.contains("cu2612") || message.contains("al2612")
                        || message.contains("SPOT"),
                "统一错误不得泄出对象标识：" + message);
    }

    /** 三端点共享的假 DAO 世界。 */
    private static final class Fakes {

        final FakeProductDao product = new FakeProductDao();
        final FakeContractDao contract = new FakeContractDao();
        final FakeMappingDao mapping = new FakeMappingDao();
        final FakeChangeLogDao changeLog = new FakeChangeLogDao();
    }

    /** 内存假品种 DAO：忠实模拟 {@code t_sub_product_whitelist} 语义（空集 ⇒ 恒假），并记录路径。 */
    private static final class FakeProductDao extends UnsupportedBaseDao<Product, Long>
            implements ProductDao {

        private final List<Product> rows = new ArrayList<>();
        private final Map<Long, Set<Integer>> whitelist = new HashMap<>();
        private boolean scopedTouched;
        private boolean unscopedTouched;

        FakeProductDao add(long id, String code) {
            Product product = new Product();
            product.id = id;
            product.productCode = code;
            product.productName = code;
            product.category = "COMMODITY";
            product.status = "ENABLED";
            rows.add(product);
            return this;
        }

        FakeProductDao white(long productId, int subAccountId) {
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
        public com.hedgeguard.dao.PageResult<Product> findPageScoped(
                com.hedgeguard.dao.PageQuery query, SubAccountScope scope, String extraWhere,
                List<Object> extraArgs) {
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

    /** 内存假合约关联 DAO；记录是否被取数。 */
    private static final class FakeContractDao extends UnsupportedBaseDao<ProductContract, Long>
            implements ProductContractDao {

        private final List<ProductContract> rows = new ArrayList<>();
        private boolean queried;

        @Override
        public List<ProductContract> findByProduct(long productId) {
            queried = true;
            List<ProductContract> out = new ArrayList<>();
            for (ProductContract row : rows) {
                if (row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public ProductContract findByInstrumentId(String instrumentId) {
            throw FakeDao.unsupported("findByInstrumentId");
        }
    }

    /** 内存假现货映射 DAO；记录是否被取数。 */
    private static final class FakeMappingDao extends UnsupportedBaseDao<ProductSpotCategory, Long>
            implements ProductSpotCategoryDao {

        private final List<ProductSpotCategory> rows = new ArrayList<>();
        private boolean queried;

        @Override
        public List<ProductSpotCategory> findByProduct(long productId) {
            queried = true;
            List<ProductSpotCategory> out = new ArrayList<>();
            for (ProductSpotCategory row : rows) {
                if (row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public ProductSpotCategory findPrimaryMapping(String spotCategoryCode,
                                                      String mappingDirection) {
            throw FakeDao.unsupported("findPrimaryMapping");
        }
    }

    /** 内存假变更历史 DAO；记录是否被取数。 */
    private static final class FakeChangeLogDao extends UnsupportedBaseDao<ProductChangeLog, Long>
            implements ProductChangeLogDao {

        private final List<ProductChangeLog> rows = new ArrayList<>();
        private boolean queried;

        @Override
        public List<ProductChangeLog> findByProduct(long productId) {
            queried = true;
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
