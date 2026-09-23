package com.hedgeguard.service;

import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.handler.plan.PlanHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/v1/plan} 跨子账户数据隔离（{@code NFR-SEC-7} / {@code FR-PLAN-4} 验收 4 /
 * {@code FR-AUTH-13} / {@code FR-PLAN-5}）。
 *
 * <p><b>为何要这组用例</b>：{@code PlanService.listPlans}/{@code getPlan} 此前<b>无用户级 scope</b>，
 * 省略 {@code subAccountId} 即返回全部子账户计划（跨账户越权读取）。本组用例把断言直接落在
 * <b>服务层施加的过滤路径与可见集合</b>上，覆盖三条边界：
 * <ul>
 *   <li><b>有权限（LIMITED）</b>：只见自己子账户的计划；</li>
 *   <li><b>无任何授权（空集）</b>：<b>返回空，绝不返回全部</b>（最易实现错、且最危险的一条）；</li>
 *   <li><b>跨账户只读（含哨兵 {@code 0}）</b>：不加过滤，可见全部。</li>
 * </ul>
 *
 * <p>假 DAO 忠实模拟 {@link SubAccountScope} 的 SQL 语义（空集 ⇒ 恒假；否则 {@code sub_account_id ∈ allowed}），
 * 该语义本身另由 {@code SubAccountScopeTest} 覆盖。完全离线（{@code NFR-TEST-2}）。
 */
class PlanSubAccountIsolationTest {

    private static final PageQuery QUERY =
            PageQuery.of("1", "20", "created_time,desc", PlanService.DEFAULT_SORT_FIELD);

    @Test
    @DisplayName("有权限（LIMITED）：只见自己子账户的计划，且走 scoped 路径、scope 为自己子账户")
    void limitedUserSeesOnlyOwnSubAccount() {
        ScopedHedgePlanDao dao = new ScopedHedgePlanDao();
        dao.rows.add(plan(1L, 7));
        dao.rows.add(plan(2L, 8));

        PageResult<HedgePlan> page = service(dao, uid -> Arrays.asList(7)).listPlans(1L, QUERY, null);

        assertEquals(1, page.items().size(), "仅可见子账户 7 的计划");
        assertEquals(7, page.items().get(0).subAccountId);
        assertTrue(dao.lastListScoped, "有权限用户须走 scoped 路径");
        assertEquals(Arrays.asList(7), dao.lastScope.allowed(), "scope 集合 = 可见子账户");
    }

    @Test
    @DisplayName("无任何授权（空集）：返回空，绝不返回全部（安全默认）")
    void userWithNoGrantSeesNothingNotAll() {
        ScopedHedgePlanDao dao = new ScopedHedgePlanDao();
        dao.rows.add(plan(1L, 7));
        dao.rows.add(plan(2L, 8));

        PageResult<HedgePlan> page = service(dao, uid -> Collections.emptyList())
                .listPlans(1L, QUERY, null);

        assertTrue(page.items().isEmpty(), "无授权 ⇒ 空集（严禁退化为不过滤 = 全可见）");
        assertEquals(0L, page.total(), "总数也须为 0");
        assertTrue(dao.lastListScoped, "空集须走 scoped 路径（恒假条件），不得走不加过滤路径");
        assertTrue(dao.lastScope.isEmpty(), "scope 须为空集（恒假）");

        // 反退化对照：同一 DAO 的"不加过滤"路径确实返回全部 —— 证明上面的空集并非数据集本身为空
        assertEquals(2, dao.findPage(QUERY, null, Collections.emptyList()).items().size(),
                "对照基线：不加过滤时数据集含 2 条");
    }

    @Test
    @DisplayName("跨账户只读（含哨兵 0）：不加过滤、可见全部")
    void crossReadonlySeesAllWithoutScope() {
        ScopedHedgePlanDao dao = new ScopedHedgePlanDao();
        dao.rows.add(plan(1L, 7));
        dao.rows.add(plan(2L, 8));

        PageResult<HedgePlan> page = service(dao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL)).listPlans(1L, QUERY, null);

        assertEquals(2, page.items().size(), "跨账户只读可见全部子账户");
        assertFalse(dao.lastListScoped, "跨账户只读 ⇒ 不加过滤（走 findPage）");
        assertNull(dao.lastScope, "跨账户只读不得构造 scope（哨兵 0 不可进 IN 列表）");
    }

    @Test
    @DisplayName("详情 getPlan：不可见子账户的计划被拒（返回不存在），可见的可读")
    void detailRejectedForInvisibleSubAccount() {
        ScopedHedgePlanDao dao = new ScopedHedgePlanDao();
        dao.rows.add(plan(1L, 7));
        dao.rows.add(plan(2L, 8));

        PlanService service = service(dao, uid -> Arrays.asList(7));

        assertNotNull(service.getPlan(1L, 1L), "本子账户的计划可读");
        assertNull(service.getPlan(2L, 1L), "他人子账户的计划 ⇒ 拒绝/返回不存在，不得返回内容");
    }

    @Test
    @DisplayName("详情 getPlan：无任何授权 ⇒ 全部不可读（含本可读的 id）")
    void detailDeniedWhenNoGrant() {
        ScopedHedgePlanDao dao = new ScopedHedgePlanDao();
        dao.rows.add(plan(1L, 7));

        PlanService service = service(dao, uid -> Collections.emptyList());

        assertNull(service.getPlan(1L, 1L), "无授权 ⇒ 恒不可读");
    }

    @Test
    @DisplayName("详情 getPlan：跨账户只读可读取任意子账户")
    void detailAllowedForCrossReadonly() {
        ScopedHedgePlanDao dao = new ScopedHedgePlanDao();
        dao.rows.add(plan(2L, 8));

        PlanService service = service(dao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL));

        assertNotNull(service.getPlan(2L, 1L), "跨账户只读可读他人子账户");
    }

    @Test
    @DisplayName("fail-closed：取数入口缺位（null）时按空集处理（谁都不见）")
    void nullVisibilityFailsClosed() {
        ScopedHedgePlanDao dao = new ScopedHedgePlanDao();
        dao.rows.add(plan(1L, 7));

        PlanService service = service(dao, null);

        assertTrue(service.listPlans(1L, QUERY, null).items().isEmpty(), "入口缺位 ⇒ 列表空");
        assertNull(service.getPlan(1L, 1L), "入口缺位 ⇒ 详情不可读");
    }

    @Test
    @DisplayName("端点接线：handler 用会话身份解析隔离（同数据不同身份结果不同）")
    void handlerThreadsSessionIdentity() {
        ScopedHedgePlanDao dao = new ScopedHedgePlanDao();
        dao.rows.add(plan(1L, 7));
        dao.rows.add(plan(2L, 8));

        // 身份 42 ⇒ 仅子账户 7；其它身份（含未登录 0）⇒ 哨兵（可见全部）
        SubAccountVisibility visibility = uid -> uid == 42L
                ? Arrays.asList(7) : Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        PlanHandler handler = new PlanHandler(service(dao, visibility));

        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/plan"), "page=1&size=20",
                Collections.emptyMap(), "", "HGISO", "127.0.0.1");
        ctx.bindSession(new Session("s-42", 42L, "u42", 1L, null));

        HttpResult result = handler.handle(ctx);

        assertEquals(200, result.status);
        assertTrue(dao.lastListScoped, "身份 42 应触发 scoped 过滤");
        assertEquals(Arrays.asList(7), dao.lastScope.allowed(), "过滤集合须来自会话身份（而非请求体）");
    }

    private static PlanService service(HedgePlanDao dao, SubAccountVisibility visibility) {
        // listPlans/getPlan 只用 hedgePlanDao；其余依赖构造仅赋值（可传 null）。
        return new PlanService(FakeTx.newTemplate(), dao, null, null, null, null, null, null, null, null,
                null, visibility, null, null, null, null, null, null);
    }

    private static HedgePlan plan(long id, int subAccountId) {
        HedgePlan plan = new HedgePlan();
        plan.id = id;
        plan.subAccountId = subAccountId;
        plan.planNo = "HG-PLAN-" + id;
        plan.currentDocVersion = 0;
        return plan;
    }

    /**
     * 内存假 DAO：忠实模拟 {@link SubAccountScope} 语义，并记录最近一次列表调用所走的路径。
     *
     * <p>{@code findPage}（不加过滤）= 返回全部；{@code findPageScoped}= 空集 ⇒ 恒假（空）、
     * 否则 {@code sub_account_id ∈ allowed}。
     */
    private static final class ScopedHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {

        private final List<HedgePlan> rows = new ArrayList<>();
        private boolean lastListScoped;
        private SubAccountScope lastScope;

        @Override
        public String findMaxPlanNoByPrefix(String planNoPrefix) {
            String max = null;
            for (HedgePlan row : rows) {
                if (row.planNo != null && row.planNo.startsWith(planNoPrefix)
                        && (max == null || row.planNo.compareTo(max) > 0)) {
                    max = row.planNo;
                }
            }
            return max;
        }

        @Override
        public PageResult<HedgePlan> findPage(PageQuery query, String where, List<Object> whereArgs) {
            this.lastListScoped = false;
            this.lastScope = null;
            return new PageResult<>(new ArrayList<>(rows), rows.size(), query.page(), query.size());
        }

        @Override
        public PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            this.lastListScoped = true;
            this.lastScope = scope;
            List<HedgePlan> hit = new ArrayList<>();
            if (!scope.isEmpty()) {
                for (HedgePlan row : rows) {
                    if (row.subAccountId != null && scope.allowed().contains(row.subAccountId)) {
                        hit.add(row);
                    }
                }
            }
            return new PageResult<>(hit, hit.size(), query.page(), query.size());
        }

        @Override
        public HedgePlan findById(Long id) {
            for (HedgePlan row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public HedgePlan findByIdScoped(long id, SubAccountScope scope) {
            if (scope.isEmpty()) {
                return null;
            }
            for (HedgePlan row : rows) {
                if (row.id == id && row.subAccountId != null
                        && scope.allowed().contains(row.subAccountId)) {
                    return row;
                }
            }
            return null;
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
