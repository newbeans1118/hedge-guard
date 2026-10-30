package com.hedgeguard.web.handler.plan;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FR-PLAN-15}「处理规则」两项：**剩余可下单量** + **已达可下单量上限标记**（{@code T-104}）。
 *
 * <p><b>为何要这组用例</b>：{@code FR-PLAN-15} 明确要求「每条交易计划须展示剩余可下单量
 * （= 数量 − 已下达指令量）；已生效但已达可下单量上限的交易计划须明确标记」，而列表端点此前
 * 只下发 {@code planQty/executedQty/orderedQty} 三个原始量，剩余量与上限标记**由前端自行派生**
 * （且派生公式与下单前置校验不一致的风险已在代码注释中披露）。
 *
 * <p><b>口径</b>：{@code remainingQty} = 计划数量 − **已成交数量** − **已下达指令量**。
 * 成交回报会把占用从 {@code ordered_qty} 转入 {@code executed_qty}，故两项都须扣减；
 * 该定义 = {@link PlanService#remainingOrderableQty}（**唯一计算源**，下单前置校验同源）。
 *
 * <p>完全离线（假 DAO + 假会话；{@code NFR-TEST-2}）。
 */
class TradePlanRemainingQtyTest {

    private static final long PLAN_ID = 1L;

    @Test
    @DisplayName("剩余可下单量 = 计划数量 − 已成交 − 已下达指令量（逐字段下发）")
    void remainingQtySubtractsBothExecutedAndOrdered(@TempDir Path webRoot) {
        RowDao dao = new RowDao();
        dao.rows.add(row("100.0000", "30.0000", "20.0000", "EXECUTING"));

        JsonObject item = firstItem(get(webRoot, dao));

        assertEquals("100.0000", item.get("planQty").getAsString(), "原始计划数量仍下发");
        assertEquals("30.0000", item.get("executedQty").getAsString());
        assertEquals("20.0000", item.get("orderedQty").getAsString());
        assertEquals("50.0000", item.get("remainingQty").getAsString(),
                "剩余量须同时扣减已成交与已下达（成交后占用转入 executed）");
        assertFalse(item.get("limitReached").getAsBoolean(), "剩余 50 > 0 ⇒ 未达上限");
    }

    @Test
    @DisplayName("验收 2：已下达量等于计划数量（且无成交）⇒ 标记已达上限")
    void limitReachedWhenFullyOrdered(@TempDir Path webRoot) {
        RowDao dao = new RowDao();
        dao.rows.add(row("100.0000", "0", "100.0000", "PENDING"));

        JsonObject item = firstItem(get(webRoot, dao));

        assertEquals(0, BigDecimal.ZERO.compareTo(
                        new BigDecimal(item.get("remainingQty").getAsString())), "剩余量为 0");
        assertTrue(item.get("limitReached").getAsBoolean(), "剩余量 0 ⇒ 已达上限（FR-PLAN-15 验收 2）");
    }

    @Test
    @DisplayName("成交 + 在途合计等于计划数量 ⇒ 已达上限（两项合并口径，非只看 ordered）")
    void limitReachedWhenExecutedPlusOrderedReachesPlan(@TempDir Path webRoot) {
        RowDao dao = new RowDao();
        dao.rows.add(row("100.0000", "60.0000", "40.0000", "EXECUTING"));

        JsonObject item = firstItem(get(webRoot, dao));

        assertEquals(0, BigDecimal.ZERO.compareTo(
                        new BigDecimal(item.get("remainingQty").getAsString())), "剩余量为 0");
        assertTrue(item.get("limitReached").getAsBoolean(),
                "仅看 ordered=40 会误判为未达上限；合并口径须为 0");
    }

    @Test
    @DisplayName("超额（数据异常）⇒ 剩余量为负且标记已达上限（原样下发，便于定位）")
    void negativeRemainingIsSurfacedAndFlagged(@TempDir Path webRoot) {
        RowDao dao = new RowDao();
        dao.rows.add(row("100.0000", "80.0000", "30.0000", "EXECUTING"));

        JsonObject item = firstItem(get(webRoot, dao));

        assertEquals("-10.0000", item.get("remainingQty").getAsString(), "负值原样下发（不静默归零）");
        assertTrue(item.get("limitReached").getAsBoolean());
    }

    @Test
    @DisplayName("空量字段按 0 计（planQty 有值、executed/ordered 为 null ⇒ 剩余 = planQty）")
    void nullQuantitiesTreatedAsZero(@TempDir Path webRoot) {
        RowDao dao = new RowDao();
        TradePlan tp = new TradePlan();
        tp.id = 1L;
        tp.tradePlanNo = "HG-TP-1";
        tp.hedgePlanId = PLAN_ID;
        tp.subAccountId = 7;
        tp.planQty = new BigDecimal("25");
        tp.executedQty = null;
        tp.orderedQty = null;
        tp.status = "PENDING";
        dao.rows.add(tp);

        JsonObject item = firstItem(get(webRoot, dao));

        assertEquals("25", item.get("remainingQty").getAsString());
        assertFalse(item.get("limitReached").getAsBoolean());
    }

    @Test
    @DisplayName("已生效且量用尽 ⇒ limitReached=true；已完成/已撤销同为数据事实（展示端按状态收窄）")
    void limitReachedIsDataFactRegardlessOfStatus(@TempDir Path webRoot) {
        RowDao dao = new RowDao();
        dao.rows.add(row("10", "10", "0", "COMPLETED"));
        dao.rows.add(row("10", "0", "10", "CANCELLED"));
        dao.rows.add(row("10", "0", "10", "PENDING"));

        JsonArray items = get(webRoot, dao).getAsJsonObject("data").getAsJsonArray("items");

        assertTrue(items.get(0).getAsJsonObject().get("limitReached").getAsBoolean(),
                "COMPLETED 剩余 0 ⇒ 数据事实为已达上限（前端不展示为可下单任务）");
        assertTrue(items.get(1).getAsJsonObject().get("limitReached").getAsBoolean());
        assertTrue(items.get(2).getAsJsonObject().get("limitReached").getAsBoolean(), "PENDING 剩余 0 ⇒ 应当标记");
    }

    @Test
    @DisplayName("静态口径：PlanService.remainingOrderableQty / isOrderLimitReached（唯一计算源）")
    void sharedHelperSemantics() {
        TradePlan tp = new TradePlan();
        tp.planQty = new BigDecimal("100");
        tp.executedQty = new BigDecimal("30");
        tp.orderedQty = new BigDecimal("20");

        assertEquals(0, new BigDecimal("50").compareTo(PlanService.remainingOrderableQty(tp)));
        assertFalse(PlanService.isOrderLimitReached(tp));

        tp.orderedQty = new BigDecimal("70");
        assertEquals(0, BigDecimal.ZERO.compareTo(PlanService.remainingOrderableQty(tp)));
        assertTrue(PlanService.isOrderLimitReached(tp), "剩余恰为 0 ⇒ 已达上限（边界含等号）");

        assertEquals(0, BigDecimal.ZERO.compareTo(PlanService.remainingOrderableQty(null)),
                "null 计划返回 0（防御，不抛 NPE）");
    }

    // ------------------------------------------------------------ 工具

    private static TradePlan row(String planQty, String executedQty, String orderedQty, String status) {
        TradePlan tp = new TradePlan();
        tp.planQty = new BigDecimal(planQty);
        tp.executedQty = new BigDecimal(executedQty);
        tp.orderedQty = new BigDecimal(orderedQty);
        tp.status = status;
        return tp;
    }

    private static JsonObject firstItem(JsonObject root) {
        assertEquals(0, root.get("code").getAsInt(), "业务码应为 0");
        JsonArray items = root.getAsJsonObject("data").getAsJsonArray("items");
        assertEquals(1, items.size(), "仅 1 条用例数据");
        return items.get(0).getAsJsonObject();
    }

    private static JsonObject get(Path webRoot, TradePlanDao dao) {
        PlanService service = new PlanService(null, null, null, null, null, null, null, null, dao,
                null, null, userId -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL),
                null, null, null, null, null, null);
        Router router = new Router();
        router.register("GET", TradePlanHandler.PATH, new TradePlanHandler(service));

        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-T104", 42L, "u104", 1L, Clocks.now().plusMinutes(30)));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T104");
        HttpResult result = dispatcher.dispatch(new RequestContext("GET",
                ApiRoutes.full("/plan/" + PLAN_ID + "/trade-plans"),
                null, headers, "", "HGT104", "127.0.0.1"));
        assertEquals(200, result.status, "列表查询应成功");
        return JsonParser.parseString(new String(result.body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 返回预置行的假 DAO（不记录查询参数——那条链路由 {@link TradePlanListQueryTest} 覆盖）。 */
    private static final class RowDao extends UnsupportedBaseDao<TradePlan, Long> implements TradePlanDao {

        private final List<TradePlan> rows = new ArrayList<>();

        @Override
        public PageResult<TradePlan> findPage(PageQuery query, String where, List<Object> whereArgs) {
            return page(query);
        }

        @Override
        public PageResult<TradePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            return page(query);
        }

        private PageResult<TradePlan> page(PageQuery query) {
            List<TradePlan> items = new ArrayList<>();
            int seq = 1;
            for (TradePlan tp : rows) {
                tp.id = seq;
                tp.tradePlanNo = "HG-TP-" + seq;
                tp.hedgePlanId = PLAN_ID;
                tp.subAccountId = 7;
                items.add(tp);
                seq++;
            }
            return new PageResult<>(items, items.size(), query.page(), query.size());
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

        @Override
        public String findMaxTradePlanNoByPrefix(String tradePlanNoPrefix) {
            throw FakeDao.unsupported("findMaxTradePlanNoByPrefix");
        }
    }
}
