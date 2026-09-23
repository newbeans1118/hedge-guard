package com.hedgeguard.web.handler.plan;

import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code BUG-001} 回归：{@code GET /api/v1/plan} 在<b>不传 {@code sort}</b> 时，
 * 缺省排序必须是 {@code created_time} <b>倒序</b>（{@code FR-PLAN-4} / {@code REQUIREMENTS/PLAN.md:155}）。
 *
 * <p><b>为何断言"方向"而不是"返回了列表"</b>：本缺陷此前穿过的正是这个空档 ——
 * 在"缺省参数"下产生的行为（{@code PageQuery} 缺省方向恒 {@code ASC}）没有任何用例校验，
 * 于是"绿测掩护了与需求相反的排序"。故本用例把断言**直接落在交给 DAO 的 {@link PageQuery}** 上。
 *
 * <p>完全离线（假 DAO 记录型；不接触数据库；{@code NFR-TEST-2}）。
 */
class PlanListDefaultSortTest {

    @Test
    @DisplayName("不传 sort：缺省排序 = created_time 且方向 DESC（倒序），而非 PageQuery 缺省的 ASC")
    void defaultSortIsCreatedTimeDesc() {
        RecordingHedgePlanDao dao = new RecordingHedgePlanDao();
        PlanHandler handler = new PlanHandler(service(dao));

        // 仅有 page/size，**无 sort**
        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/plan"),
                "page=1&size=20", Collections.emptyMap(), "", "HGTEST", "127.0.0.1");
        HttpResult result = handler.handle(ctx);

        assertEquals(200, result.status);
        assertNotNull(dao.lastQuery, "handler 必须把分页查询交给 DAO（listPlans → findPage）");
        assertEquals("created_time", dao.lastQuery.sortField(), "缺省排序字段应为 created_time");
        assertEquals("DESC", dao.lastQuery.direction(), "FR-PLAN-4 要求默认倒序（BUG-001）");
        assertFalse(dao.lastQuery.ascending(), "缺省必须为降序（ascending=false）");
    }

    @Test
    @DisplayName("显式 sort 仍被尊重：sort=plan_no,asc ⇒ plan_no + ASC（缺省方向不覆盖显式值）")
    void explicitSortIsRespected() {
        RecordingHedgePlanDao dao = new RecordingHedgePlanDao();
        PlanHandler handler = new PlanHandler(service(dao));

        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/plan"),
                "sort=plan_no,asc", Collections.emptyMap(), "", "HGTEST", "127.0.0.1");
        handler.handle(ctx);

        assertEquals("plan_no", dao.lastQuery.sortField());
        assertEquals("ASC", dao.lastQuery.direction());
    }

    /** 构造 PlanService：{@code listPlans} 只用 hedgePlanDao，其余依赖构造仅赋值（可传 null）。
     *  可见集返回哨兵 0（跨账户只读）⇒ 走"不加过滤"路径，本用例只验缺省排序方向。 */
    private static PlanService service(HedgePlanDao dao) {
        return new PlanService(null, dao, null, null, null, null, null, null, null, null, null,
                userId -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL),
                null, null, null, null, null, null);
    }

    /** 记录型假 DAO：捕获交给 {@code findPage} 的 {@link PageQuery}，仅实现该方法。 */
    private static final class RecordingHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {

        private PageQuery lastQuery;

        @Override
        public String findMaxPlanNoByPrefix(String planNoPrefix) {
            throw FakeDao.unsupported("findMaxPlanNoByPrefix");
        }

        @Override
        public PageResult<HedgePlan> findPage(PageQuery query, String where, List<Object> whereArgs) {
            this.lastQuery = query;
            return new PageResult<>(new ArrayList<>(), 0L, query.page(), query.size());
        }

        @Override
        public PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                String extraWhere, List<Object> extraArgs) {
            throw FakeDao.unsupported("findPageScoped");
        }

        @Override
        public HedgePlan findByIdScoped(long id, SubAccountScope scope) {
            throw FakeDao.unsupported("findByIdScoped");
        }

        @Override
        public HedgePlan findByPlanNo(String planNo) {
            throw FakeDao.unsupported("findByPlanNo");
        }

        @Override
        public int deleteById(Long id) {
            throw FakeDao.unsupported("deleteById");
        }
    }
}
