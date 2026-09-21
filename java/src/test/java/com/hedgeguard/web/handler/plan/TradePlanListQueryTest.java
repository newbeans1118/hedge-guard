package com.hedgeguard.web.handler.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/v1/plan/{id}/trade-plans} 的<b>分页 / 筛选 / 排序</b>
 * （{@code T-103}；{@code FR-PLAN-15}「输入」列；{@code ARCHITECTURE.md} §4.1.1）。
 *
 * <p><b>为何要这组用例</b>：本端点此前返回<b>非分页</b> {@code List}、不接受分页参数 ——
 * {@code FR-PLAN-15} 的「输入」列明写<b>筛选（子账户 / 套保计划 / 合约 / 状态 / 执行期限）、
 * 排序、分页</b>，而「处理规则」只规定默认序与权限；缺陷正出在「输入」列未实现。
 * 本用例把断言直接落在<b>交给 DAO 的 {@link PageQuery} 与 {@code extraWhere}/{@code args}</b>
 * 以及<b>响应信封</b>上（而非"返回了列表"这种空档），并覆盖：
 * <ul>
 *   <li><b>默认排序</b> = {@code exec_deadline} <b>升序</b>（{@code FR-PLAN-15}「处理规则」）；</li>
 *   <li><b>分页透传</b>（{@code page/size/sort}）与<b>缺省</b>（页 1 / 每页 20）；</li>
 *   <li><b>{@code size} 白名单</b>（{@code ∈ {10,20,50,100}}）越界<b>拒绝</b>（{@code 10001}）；</li>
 *   <li><b>五类筛选</b>落到 {@code extraWhere} 占位符与按序绑定值；</li>
 *   <li><b>响应信封</b>为 {@code PageData{items,total,page,size}}。</li>
 * </ul>
 * 完全离线（假 DAO 记录型 + 假会话；{@code NFR-TEST-2}）。
 */
class TradePlanListQueryTest {

    private static final long PLAN_ID = 1L;

    @Test
    @DisplayName("不传 sort：缺省排序 = exec_deadline 且方向 ASC（FR-PLAN-15「处理规则」）")
    void defaultSortIsExecDeadlineAsc(@TempDir Path webRoot) {
        RecordingTradePlanDao dao = new RecordingTradePlanDao();

        HttpResult result = get(webRoot, dao, "");

        assertEquals(200, result.status);
        assertNotNull(dao.lastQuery, "handler 必须把分页查询交给 DAO（listTradePlans → findPage）");
        assertEquals("exec_deadline", dao.lastQuery.sortField(), "缺省排序字段应为执行期限");
        assertEquals("ASC", dao.lastQuery.direction(), "FR-PLAN-15 要求默认升序（与 PageQuery 缺省方向一致）");
        assertTrue(dao.lastQuery.ascending(), "缺省必须为升序（ascending=true）");
    }

    @Test
    @DisplayName("分页透传：page=2&size=10&sort=instrument_id,desc ⇒ 逐项落在 PageQuery")
    void explicitPaginationAndSortArePassedThrough(@TempDir Path webRoot) {
        RecordingTradePlanDao dao = new RecordingTradePlanDao();

        HttpResult result = get(webRoot, dao, "page=2&size=10&sort=instrument_id,desc");

        assertEquals(200, result.status);
        assertEquals(2, dao.lastQuery.page());
        assertEquals(10, dao.lastQuery.size());
        assertEquals("instrument_id", dao.lastQuery.sortField());
        assertEquals("DESC", dao.lastQuery.direction());
    }

    @Test
    @DisplayName("缺省分页：不传 page/size ⇒ 页 1 / 每页 20（§4.1.1）")
    void defaultPageAndSize(@TempDir Path webRoot) {
        RecordingTradePlanDao dao = new RecordingTradePlanDao();

        get(webRoot, dao, "");

        assertEquals(1, dao.lastQuery.page(), "缺省页为 1");
        assertEquals(20, dao.lastQuery.size(), "缺省每页 20");
    }

    @Test
    @DisplayName("size 白名单：10/20/50/100 接受；15/0/101 拒绝并映射 10001（不静默截断）")
    void sizeWhitelistEnforced(@TempDir Path webRoot) {
        for (int ok : new int[]{10, 20, 50, 100}) {
            RecordingTradePlanDao dao = new RecordingTradePlanDao();
            HttpResult r = get(webRoot, dao, "size=" + ok);
            assertEquals(200, r.status, "size=" + ok + " 应被接受");
            assertEquals(ok, dao.lastQuery.size());
        }
        for (int bad : new int[]{15, 0, 101}) {
            RecordingTradePlanDao dao = new RecordingTradePlanDao();
            HttpResult r = get(webRoot, dao, "size=" + bad);
            assertEquals(400, r.status, "size=" + bad + " 应被拒绝");
            assertEquals(10001, code(r), "size=" + bad + " 应映射为 10001（PARAM_INVALID）");
        }
    }

    @Test
    @DisplayName("五类筛选落到 extraWhere：子账户 / 套保计划 / 合约 / 状态 / 执行期限，按序绑定")
    void filtersAreTranslatedIntoWhereAndArgs(@TempDir Path webRoot) {
        RecordingTradePlanDao dao = new RecordingTradePlanDao();

        HttpResult result = get(webRoot, dao,
                "subAccountId=7&instrumentId=cu2612&status=PENDING"
                        + "&execDeadlineFrom=2026-01-01&execDeadlineTo=2026-12-31");

        assertEquals(200, result.status);
        assertEquals("hedge_plan_id = ? AND sub_account_id = ? AND instrument_id = ?"
                        + " AND status = ? AND exec_deadline >= ? AND exec_deadline <= ?",
                dao.lastWhere, "筛选须以占位符拼装（禁止拼接取值）");
        assertEquals(Arrays.asList((Object) PLAN_ID, 7, "cu2612", "PENDING",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                dao.lastArgs, "计划 ID 与五类筛选须按占位符顺序绑定");
    }

    @Test
    @DisplayName("非法状态取值：拒绝并映射 10001（取值域 = PENDING/EXECUTING/COMPLETED/CANCELLED）")
    void invalidStatusRejected(@TempDir Path webRoot) {
        RecordingTradePlanDao dao = new RecordingTradePlanDao();

        HttpResult r = get(webRoot, dao, "status=BOGUS");

        assertEquals(400, r.status);
        assertEquals(10001, code(r));
    }

    @Test
    @DisplayName("非法排序字段：不在白名单即拒绝并映射 10001（防无效列名 / 注入）")
    void invalidSortFieldRejected(@TempDir Path webRoot) {
        RecordingTradePlanDao dao = new RecordingTradePlanDao();

        HttpResult r = get(webRoot, dao, "sort=bogus,asc");

        assertEquals(400, r.status);
        assertEquals(10001, code(r));
    }

    @Test
    @DisplayName("响应信封为 PageData{items,total,page,size}（非裸数组）")
    void responseEnvelopeIsPageData(@TempDir Path webRoot) {
        RecordingTradePlanDao dao = new RecordingTradePlanDao();
        dao.itemsToReturn = 2;
        dao.totalToReturn = 5;

        HttpResult result = get(webRoot, dao, "");

        assertEquals(200, result.status);
        JsonObject root = JsonParser.parseString(body(result)).getAsJsonObject();
        assertEquals(0, root.get("code").getAsInt());
        JsonObject data = root.getAsJsonObject("data");
        assertNotNull(data, "data 须存在");
        assertEquals(2, data.get("items").getAsJsonArray().size(), "items 长度 = DAO 返回条数");
        assertEquals(5, data.get("total").getAsLong(), "total 须来自 DAO（过滤后总数）");
        assertEquals(1, data.get("page").getAsInt());
        assertEquals(20, data.get("size").getAsInt());
    }

    // ------------------------------------------------------------ 工具

    private static HttpResult get(Path webRoot, TradePlanDao dao, String rawQuery) {
        PlanService service = new PlanService(null, null, null, null, null, null, null, null, dao,
                null, null, userId -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL),
                null, null, null, null, null, null);
        Router router = new Router();
        router.register("GET", TradePlanHandler.PATH, new TradePlanHandler(service));

        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-T103", 42L, "u103", 1L, Clocks.now().plusMinutes(30)));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T103");
        return dispatcher.dispatch(new RequestContext("GET",
                ApiRoutes.full("/plan/" + PLAN_ID + "/trade-plans"),
                rawQuery.isEmpty() ? null : rawQuery, headers, "", "HGT103", "127.0.0.1"));
    }

    private static String body(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private static int code(HttpResult result) {
        return JsonParser.parseString(body(result)).getAsJsonObject().get("code").getAsInt();
    }

    /**
     * 记录型假交易计划 DAO：捕获交给 {@code findPage}/{@code findPageScoped} 的
     * {@link PageQuery} 与 {@code extraWhere}/{@code extraArgs}，并按要求返回 {@code PageResult}。
     */
    private static final class RecordingTradePlanDao extends UnsupportedBaseDao<TradePlan, Long>
            implements TradePlanDao {

        private PageQuery lastQuery;
        private String lastWhere;
        private List<Object> lastArgs;
        private int itemsToReturn = 0;
        private long totalToReturn = 0;

        @Override
        public PageResult<TradePlan> findPage(PageQuery query, String where, List<Object> whereArgs) {
            capture(query, where, whereArgs);
            return page(query);
        }

        @Override
        public PageResult<TradePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            capture(query, extraWhere, extraArgs);
            return page(query);
        }

        private void capture(PageQuery query, String where, List<Object> args) {
            this.lastQuery = query;
            this.lastWhere = where;
            this.lastArgs = args == null ? new ArrayList<>() : new ArrayList<>(args);
        }

        private PageResult<TradePlan> page(PageQuery query) {
            List<TradePlan> items = new ArrayList<>();
            for (int i = 0; i < itemsToReturn; i++) {
                TradePlan tp = new TradePlan();
                tp.id = i + 1;
                tp.tradePlanNo = "HG-TP-" + (i + 1);
                tp.hedgePlanId = PLAN_ID;
                tp.subAccountId = 7;
                tp.planQty = new BigDecimal("10");
                tp.orderedQty = BigDecimal.ZERO;
                items.add(tp);
            }
            return new PageResult<>(items, totalToReturn, query.page(), query.size());
        }

        @Override
        public List<TradePlan> findByHedgePlan(long hedgePlanId) {
            throw FakeDao.unsupported("findByHedgePlan");
        }

        @Override
        public List<TradePlan> findByHedgePlanScoped(long hedgePlanId, SubAccountScope scope) {
            throw FakeDao.unsupported("findByHedgePlanScoped");
        }

        @Override
        public TradePlan findByTradePlanNo(String tradePlanNo) {
            throw FakeDao.unsupported("findByTradePlanNo");
        }
    }
}
