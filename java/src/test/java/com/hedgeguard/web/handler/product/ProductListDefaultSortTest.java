package com.hedgeguard.web.handler.product;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/v1/product} 读取面口径（{@code FR-PRODUCT-5} / {@code T-105} 验收 1/2/6）。
 *
 * <p><b>为何断言"方向"而不是"返回了列表"</b>：与 {@code BUG-001} 同源的坑 ——
 * {@code PageQuery} 在 {@code sort} 缺省时方向恒 {@code ASC}，若 handler 只传裸字段名，
 * "默认创建时间倒序"会静默变成升序而用例全绿。故断言直接落在交给 DAO 的 {@link PageQuery} 上。
 *
 * <p>另断言 {@code GET /product} 的响应 {@code data} 为 {@code {items,total,page,size}} 分页信封
 * （{@code §4.1.1} 冻结口径）。完全离线（假 DAO 记录型；{@code NFR-TEST-2}）。
 */
class ProductListDefaultSortTest {

    @Test
    @DisplayName("不传 sort：缺省排序 = created_time 且方向 DESC（倒序），且响应为 PageData 信封")
    void defaultSortIsCreatedTimeDescWithPageEnvelope() {
        RecordingProductDao dao = new RecordingProductDao();
        ProductHandler handler = new ProductHandler(service(dao));

        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/product"),
                "page=1&size=20", Collections.emptyMap(), "", "HGTEST", "127.0.0.1");
        HttpResult result = handler.handle(ctx);

        assertEquals(200, result.status);
        assertNotNull(dao.lastQuery, "handler 必须把分页查询交给 DAO（listProducts → findPage）");
        assertEquals("created_time", dao.lastQuery.sortField(), "缺省排序字段应为 created_time");
        assertEquals("DESC", dao.lastQuery.direction(), "FR-PRODUCT-5 要求默认倒序（BUG-001）");
        assertFalse(dao.lastQuery.ascending(), "缺省必须为降序（ascending=false）");

        // 分页信封：data = {items,total,page,size}
        Map<?, ?> body = JsonCodec.fromJson(new String(result.body, StandardCharsets.UTF_8), Map.class);
        Object data = body.get("data");
        assertTrue(data instanceof Map, "响应 data 须为分页信封对象，实际=" + data);
        Map<?, ?> page = (Map<?, ?>) data;
        assertTrue(page.containsKey("items"), "信封须含 items：" + page);
        assertTrue(page.containsKey("total"), "信封须含 total：" + page);
        assertTrue(page.containsKey("page"), "信封须含 page：" + page);
        assertTrue(page.containsKey("size"), "信封须含 size：" + page);
    }

    @Test
    @DisplayName("显式 sort 仍被尊重：sort=product_code,asc ⇒ product_code + ASC（缺省方向不覆盖显式值）")
    void explicitSortIsRespected() {
        RecordingProductDao dao = new RecordingProductDao();
        ProductHandler handler = new ProductHandler(service(dao));

        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/product"),
                "sort=product_code,asc", Collections.emptyMap(), "", "HGTEST", "127.0.0.1");
        handler.handle(ctx);

        assertEquals("product_code", dao.lastQuery.sortField());
        assertEquals("ASC", dao.lastQuery.direction());
    }

    /** 构造 VarietyService：{@code listProducts} 只用 productDao；可见集返回哨兵 0（跨账户只读）
     *  ⇒ 走"不加过滤"路径（{@code findPage}），本用例只验缺省排序方向与信封。 */
    private static VarietyService service(ProductDao dao) {
        return new VarietyService(null, dao, null, null, null, null, null, null, null,
                userId -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL));
    }

    /** 记录型假 DAO：捕获交给 {@code findPage} 的 {@link PageQuery}。 */
    private static final class RecordingProductDao extends UnsupportedBaseDao<Product, Long>
            implements ProductDao {

        private PageQuery lastQuery;

        @Override
        public PageResult<Product> findPage(PageQuery query, String where, List<Object> whereArgs) {
            this.lastQuery = query;
            return new PageResult<>(new ArrayList<>(), 0L, query.page(), query.size());
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

        @Override
        public Product findByCode(String productCode) {
            throw FakeDao.unsupported("findByCode");
        }

        @Override
        public List<Product> findAllOrderByCode() {
            throw FakeDao.unsupported("findAllOrderByCode");
        }

        // T-106：ProductDao 新增单对象读取面方法（本用例只验列表缺省排序，详情面显式失败）
        @Override
        public Product findByIdScoped(long id, SubAccountScope scope) {
            throw FakeDao.unsupported("findByIdScoped");
        }
    }
}
