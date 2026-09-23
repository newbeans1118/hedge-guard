package com.hedgeguard.web.handler.spot;

import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.service.SpotService;
import com.hedgeguard.service.SubAccountVisibility;
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
 * {@code GET /api/v1/spot/exposure} 读取面口径（{@code FR-SPOT-15} / {@code T-107} 验收 1/2）。
 *
 * <p><b>为何断言"方向"而不是"返回了列表"</b>：与 {@code BUG-001} 同源的坑 ——
 * {@code PageQuery} 在 {@code sort} 缺省时方向恒 {@code ASC}，若 handler 只传裸字段名，
 * "默认按敞口金额或数量倒序"会静默变成升序而用例全绿。故断言直接落在交给 DAO 的 {@link PageQuery} 上。
 *
 * <p>另断言 {@code GET /spot/exposure} 的响应 {@code data} 为 {@code {items,total,page,size}} 分页信封
 * （{@code §4.1.1} 冻结口径；此前为裸数组）。完全离线（假 DAO 记录型；{@code NFR-TEST-2}）。
 */
class SpotExposureListDefaultSortTest {

    @Test
    @DisplayName("不传 sort：缺省排序 = exposure_qty 且方向 DESC（倒序），且响应为 PageData 信封")
    void defaultSortIsExposureQtyDescWithPageEnvelope() {
        RecordingExposureDao dao = new RecordingExposureDao();
        SpotExposureHandler handler = new SpotExposureHandler(service(dao));

        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/spot/exposure"),
                "page=1&size=20", Collections.emptyMap(), "", "HGTEST", "127.0.0.1");
        HttpResult result = handler.handle(ctx);

        assertEquals(200, result.status);
        assertNotNull(dao.lastQuery, "handler 必须把分页查询交给 DAO（listPendingHedge → findPage）");
        assertEquals("exposure_qty", dao.lastQuery.sortField(), "缺省排序字段应为 exposure_qty");
        assertEquals("DESC", dao.lastQuery.direction(), "FR-SPOT-15 要求默认倒序（BUG-001）");
        assertFalse(dao.lastQuery.ascending(), "缺省必须为降序（ascending=false）");

        // 分页信封：data = {items,total,page,size}
        Map<?, ?> body = JsonCodec.fromJson(new String(result.body, StandardCharsets.UTF_8), Map.class);
        Object data = body.get("data");
        assertTrue(data instanceof Map, "响应 data 须为分页信封对象（非裸数组），实际=" + data);
        Map<?, ?> page = (Map<?, ?>) data;
        assertTrue(page.containsKey("items"), "信封须含 items：" + page);
        assertTrue(page.containsKey("total"), "信封须含 total：" + page);
        assertTrue(page.containsKey("page"), "信封须含 page：" + page);
        assertTrue(page.containsKey("size"), "信封须含 size：" + page);
    }

    @Test
    @DisplayName("显式 sort 仍被尊重：sort=exposure_qty,asc ⇒ exposure_qty + ASC（缺省方向不覆盖显式值）")
    void explicitSortIsRespected() {
        RecordingExposureDao dao = new RecordingExposureDao();
        SpotExposureHandler handler = new SpotExposureHandler(service(dao));

        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/spot/exposure"),
                "sort=exposure_qty,asc", Collections.emptyMap(), "", "HGTEST", "127.0.0.1");
        handler.handle(ctx);

        assertEquals("exposure_qty", dao.lastQuery.sortField());
        assertEquals("ASC", dao.lastQuery.direction());
    }

    /** 构造 SpotService：{@code listPendingHedge} 只用 exposureDao；可见集返回哨兵 0（跨账户只读）
     *  ⇒ 走"不加过滤"路径（{@code findPage}），本用例只验缺省排序方向与信封。 */
    private static SpotService service(ExposureDao dao) {
        SubAccountVisibility visibility =
                userId -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        return new SpotService(null, null, null, null, null, null, null, dao, null, null,
                null, null, null, null, visibility, null);
    }

    /** 记录型假 DAO：捕获交给 {@code findPage} 的 {@link PageQuery}。 */
    private static final class RecordingExposureDao extends UnsupportedBaseDao<Exposure, Long>
            implements ExposureDao {

        private PageQuery lastQuery;

        @Override
        public PageResult<Exposure> findPage(PageQuery query, String where, List<Object> whereArgs) {
            this.lastQuery = query;
            return new PageResult<>(new ArrayList<>(), 0L, query.page(), query.size());
        }

        @Override
        public PageResult<Exposure> findPageScoped(PageQuery query, SubAccountScope scope,
                                                   String extraWhere, List<Object> extraArgs) {
            throw FakeDao.unsupported("findPageScoped");
        }

        @Override
        public Exposure findByIdScoped(long id, SubAccountScope scope) {
            throw FakeDao.unsupported("findByIdScoped");
        }
    }
}
