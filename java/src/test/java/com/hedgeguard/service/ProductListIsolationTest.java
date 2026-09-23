package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.handler.product.ProductHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * {@code GET /api/v1/product} / {@code GET /api/v1/product/export} 跨子账户数据隔离
 * （{@code FR-PRODUCT-5} 处理规则 / {@code FR-PRODUCT-8} / {@code NFR-SEC-7} / {@code FR-AUTH-13}）。
 *
 * <p><b>为何要这组用例</b>：{@code VarietyService.listProducts}/{@code exportProducts} 此前无用户级
 * scope，返回<b>全部</b>品种（跨子账户越权读取）。本组把断言落在<b>服务层施加的过滤路径与可见集合</b>上，
 * 覆盖四类 + 反向（防误拒）：
 * <ul>
 *   <li><b>有权限（LIMITED）</b>：只见自己子账户白名单覆盖的品种；</li>
 *   <li><b>无任何授权（空集）</b>：<b>返回空，绝不返回全部</b>（最易实现错、最危险的一条）；</li>
 *   <li><b>跨账户只读（含哨兵 {@code 0}）</b>：不加过滤，可见全部（读面放行，{@code T-098}）；</li>
 *   <li><b>跨子账户</b>：他人子账户白名单的品种不可见；</li>
 *   <li><b>取数入口缺位（{@code null}）</b>：按空集 fail-closed。</li>
 * </ul>
 *
 * <p>假 DAO 忠实模拟 {@code t_sub_product_whitelist} 语义（可见品种 = 可见子账户白名单覆盖的品种之并集；
 * 空集 ⇒ 恒假），该语义本身另由 {@code SubAccountScopeTest} / {@code DmProductDaoScopedSqlTest} 覆盖。
 * 完全离线（{@code NFR-TEST-2}）。
 */
class ProductListIsolationTest {

    private static final PageQuery QUERY =
            PageQuery.of("1", "20", "created_time,desc", VarietyService.DEFAULT_SORT_FIELD);

    @Test
    @DisplayName("有权限（LIMITED）：只见自己子账户白名单覆盖的品种，且走 scoped 路径、scope 为自己子账户")
    void limitedUserSeesOnlyOwnSubAccountWhitelisted() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(1L, "CU"));
        dao.rows.add(product(2L, "AL"));
        dao.white(1L, 7);   // 子账户 7 白名单含品种 1
        dao.white(2L, 8);   // 子账户 8 白名单含品种 2

        PageResult<Product> page = service(dao, uid -> Arrays.asList(7)).listProducts(1L, QUERY);

        assertEquals(1, page.items().size(), "仅可见子账户 7 白名单覆盖的品种");
        assertEquals("CU", page.items().get(0).productCode);
        assertEquals(1L, page.total(), "total 亦按过滤后计");
        assertTrue(dao.lastListScoped, "有权限用户须走 scoped 路径");
        assertEquals(Arrays.asList(7), dao.lastScope.allowed(), "scope 集合 = 可见子账户");
    }

    @Test
    @DisplayName("反向（防误拒）：合法用户对自己可见子账户的品种照常可见")
    void legitUserSeesOwnVisibleProducts() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(1L, "CU"));
        dao.rows.add(product(2L, "AL"));
        dao.white(1L, 7);
        dao.white(2L, 7);

        PageResult<Product> page = service(dao, uid -> Arrays.asList(7)).listProducts(1L, QUERY);

        assertEquals(2, page.items().size(), "本子账户白名单内的品种须全部可见（不得误拒）");
    }

    @Test
    @DisplayName("无任何授权（空集）：返回空，绝不返回全部（安全默认）")
    void userWithNoGrantSeesNothingNotAll() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(1L, "CU"));
        dao.rows.add(product(2L, "AL"));
        dao.white(1L, 7);
        dao.white(2L, 8);

        PageResult<Product> page =
                service(dao, uid -> Collections.emptyList()).listProducts(1L, QUERY);

        assertTrue(page.items().isEmpty(), "无授权 ⇒ 空集（严禁退化为不过滤 = 全可见）");
        assertEquals(0L, page.total(), "总数也须为 0");
        assertTrue(dao.lastListScoped, "空集须走 scoped 路径（恒假条件），不得走不加过滤路径");
        assertTrue(dao.lastScope.isEmpty(), "scope 须为空集（恒假）");

        // 反退化对照：同一 DAO 的"不加过滤"路径确实返回全部 —— 证明上面的空集并非数据集本身为空
        assertEquals(2, dao.findPage(QUERY, null, Collections.emptyList()).items().size(),
                "对照基线：不加过滤时数据集含 2 条");
    }

    @Test
    @DisplayName("跨账户只读（含哨兵 0）：不加过滤、可见全部（读面放行）")
    void crossReadonlySeesAllWithoutScope() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(1L, "CU"));
        dao.rows.add(product(2L, "AL"));
        dao.white(1L, 7);
        dao.white(2L, 8);

        PageResult<Product> page = service(dao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL)).listProducts(1L, QUERY);

        assertEquals(2, page.items().size(), "跨账户只读可见全部子账户");
        assertFalse(dao.lastListScoped, "跨账户只读 ⇒ 不加过滤（走 findPage）");
        assertNull(dao.lastScope, "跨账户只读不得构造 scope（哨兵 0 不可进 IN 列表）");
    }

    @Test
    @DisplayName("跨子账户：他人子账户白名单的品种不可见")
    void otherSubAccountProductInvisible() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(9L, "RB"));
        dao.white(9L, 8);   // 仅子账户 8 可见

        PageResult<Product> page = service(dao, uid -> Arrays.asList(7)).listProducts(1L, QUERY);

        assertTrue(page.items().isEmpty(), "子账户 7 不可见他方白名单品种");
    }

    @Test
    @DisplayName("fail-closed：取数入口缺位（null）时按空集处理（谁都不见）")
    void nullVisibilityFailsClosed() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(1L, "CU"));
        dao.white(1L, 7);

        assertTrue(service(dao, null).listProducts(1L, QUERY).items().isEmpty(),
                "入口缺位 ⇒ 列表空");
        assertTrue(service(dao, null).exportProducts(1L).size() == 1,
                "入口缺位 ⇒ 导出仅表头（1 行）");
    }

    @Test
    @DisplayName("导出：同受可见集过滤（仅表头当且仅当无可导出数据）")
    void exportIsScoped() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(1L, "CU"));
        dao.rows.add(product(2L, "AL"));
        dao.white(1L, 7);
        dao.white(2L, 8);

        List<String> csv = service(dao, uid -> Arrays.asList(7)).exportProducts(1L);

        assertEquals(2, csv.size(), "仅表头 + 子账户 7 的一条品种");
        assertTrue(csv.get(1).startsWith("CU,"), "仅导出可见品种：" + csv.get(1));
    }

    @Test
    @DisplayName("T-096：多键排序须逐键过白名单 —— 品种第 2 键越域亦 10001（仅校验首键会漏）")
    void rejectsIllegalSecondSortKey() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(1L, "CU"));
        dao.white(1L, 7);
        PageQuery query = PageQuery.of(null, null, "created_time,desc,evil_column,asc",
                VarietyService.DEFAULT_SORT_FIELD);

        BizException e = assertThrows(BizException.class,
                () -> service(dao, uid -> Arrays.asList(7)).listProducts(1L, query));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertFalse(dao.lastListScoped, "校验失败不得触碰 DAO");
    }

    @Test
    @DisplayName("端点接线：handler 用会话身份解析隔离（同数据不同身份结果不同）")
    void handlerThreadsSessionIdentity() {
        ScopedProductDao dao = new ScopedProductDao();
        dao.rows.add(product(1L, "CU"));
        dao.rows.add(product(2L, "AL"));
        dao.white(1L, 7);
        dao.white(2L, 8);

        // 身份 42 ⇒ 仅子账户 7；其余身份 ⇒ 哨兵（可见全部）
        SubAccountVisibility visibility = uid -> uid == 42L
                ? Arrays.asList(7) : Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        ProductHandler handler = new ProductHandler(service(dao, visibility));

        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/product"), "page=1&size=20",
                Collections.emptyMap(), "", "HGISO", "127.0.0.1");
        ctx.bindSession(new Session("s-42", 42L, "u42", 1L, null));

        HttpResult result = handler.handle(ctx);

        assertEquals(200, result.status);
        assertTrue(dao.lastListScoped, "身份 42 应触发 scoped 过滤");
        assertEquals(Arrays.asList(7), dao.lastScope.allowed(), "过滤集合须来自会话身份（而非请求体）");
    }

    // ------------------------------------------------------------ 工具

    private static VarietyService service(ProductDao dao, SubAccountVisibility visibility) {
        // listProducts/exportProducts 只用 productDao 与可见性入口；其余依赖构造仅赋值（可传 null）。
        return new VarietyService(null, dao, null, null, null, null, null, null, null, visibility);
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

    /**
     * 内存假 DAO：忠实模拟 {@code t_sub_product_whitelist(sub_account_id, product_id)} 语义
     * （可见品种 = 可见子账户白名单覆盖的品种之并集），并记录最近一次列表调用所走的路径。
     *
     * <p>{@code findPage}（不加过滤）= 返回全部；{@code findPageScoped}/{@code findAllScoped} =
     * 空集 ⇒ 恒假（空），否则取并集。
     */
    private static final class ScopedProductDao extends UnsupportedBaseDao<Product, Long>
            implements ProductDao {

        private final List<Product> rows = new ArrayList<>();
        private final Map<Long, Set<Integer>> whitelist = new HashMap<>();
        private boolean lastListScoped;
        private SubAccountScope lastScope;

        void white(long productId, int subAccountId) {
            whitelist.computeIfAbsent(productId, k -> new HashSet<>()).add(subAccountId);
        }

        private List<Product> visible(SubAccountScope scope) {
            List<Product> hit = new ArrayList<>();
            if (scope.isEmpty()) {
                return hit;
            }
            for (Product row : rows) {
                Set<Integer> subs = whitelist.get(row.id);
                if (subs == null) {
                    continue;
                }
                for (Integer s : subs) {
                    if (scope.allowed().contains(s)) {
                        hit.add(row);
                        break;
                    }
                }
            }
            return hit;
        }

        @Override
        public PageResult<Product> findPage(PageQuery query, String where, List<Object> whereArgs) {
            this.lastListScoped = false;
            this.lastScope = null;
            return new PageResult<>(new ArrayList<>(rows), rows.size(), query.page(), query.size());
        }

        @Override
        public PageResult<Product> findPageScoped(PageQuery query, SubAccountScope scope,
                                                  String extraWhere, List<Object> extraArgs) {
            this.lastListScoped = true;
            this.lastScope = scope;
            List<Product> hit = visible(scope);
            return new PageResult<>(hit, hit.size(), query.page(), query.size());
        }

        @Override
        public List<Product> findAllScoped(SubAccountScope scope) {
            return visible(scope);
        }

        @Override
        public List<Product> findAllOrderByCreatedDesc() {
            return new ArrayList<>(rows);
        }

        @Override
        public Product findByCode(String productCode) {
            throw FakeDao.unsupported("findByCode");
        }

        @Override
        public List<Product> findAllOrderByCode() {
            return new ArrayList<>(rows);
        }

        // T-106：单对象读取面（忠实模拟白名单子查询：不可见 ≡ 不存在，同一返回 null）
        @Override
        public Product findByIdScoped(long id, SubAccountScope scope) {
            for (Product row : visible(scope)) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }
    }
}
