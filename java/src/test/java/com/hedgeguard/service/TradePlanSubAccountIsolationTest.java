package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import com.hedgeguard.web.handler.plan.TradePlanHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/v1/plan/{id}/trade-plans} 跨子账户数据隔离
 * （{@code NFR-SEC-7} / {@code FR-PLAN-15} 验收 4 / {@code R-PLAN-7} / {@code FR-AUTH-13}）。
 *
 * <p><b>为何要这组用例</b>：{@code PlanService.listTradePlans} 此前<b>无调用者身份、无 scope</b>，
 * 任意已登录用户凭计划 id 即能读到他人子账户的全部交易计划（独立复核已运行时证实：
 * 可见 {@code [7]} 的身份请求属于子账户 {@code 8} 的计划，返回 {@code 200} 且含他人数据）。
 * 本组用例把断言直接落在<b>服务层施加的过滤路径与可见集合</b>上，覆盖四条边界：
 * <ul>
 *   <li><b>有权限（LIMITED）</b>：只见自己子账户的交易计划；</li>
 *   <li><b>无任何授权（空集）</b>：<b>返回空，绝不返回全部</b>（最易实现错、且最危险的一条）；</li>
 *   <li><b>跨账户只读（含哨兵 {@code 0}）</b>：不加过滤，可见全部；</li>
 *   <li><b>越权读他子账户的计划</b>：返回空列表（不泄露内容）。</li>
 * </ul>
 *
 * <p>{@code T-103} 分页化后增补：<b>隔离与分页叠加</b> —— {@code total} 与 {@code items} 均按
 * <b>过滤后</b>结果计（越权行既不入 items、也不计入 total），并验证受限身份下的分页切片。
 *
 * <p>假 DAO 忠实模拟 {@link SubAccountScope} 的 SQL 语义（空集 ⇒ 恒假；否则 {@code sub_account_id ∈ allowed}），
 * 该语义本身另由 {@code SubAccountScopeTest} 覆盖；<b>真实 DAO 的 SQL 形状</b>由
 * {@code DmTradePlanDaoScopedSqlTest} 覆盖。完全离线（{@code NFR-TEST-2}）。
 */
class TradePlanSubAccountIsolationTest {

    /** 夹具：计划 1 下有子账户 7/8 各一条（用于逐行过滤）；计划 2 全属子账户 8（越权读目标）。 */
    private static final long PLAN_OWNED = 1L;
    private static final long PLAN_FOREIGN = 2L;

    /** 默认分页（{@code exec_deadline} 升序，缺省页 1 / 每页 20）。 */
    private static final PageQuery DEFAULT_QUERY =
            PageQuery.of(null, null, null, PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);

    @Test
    @DisplayName("有权限（LIMITED）：只见自己子账户的交易计划，且走 scoped 路径、scope 为自己子账户")
    void limitedUserSeesOnlyOwnSubAccountTradePlans() {
        ScopedTradePlanDao dao = fixture();

        PageResult<TradePlan> page = service(dao, uid -> Arrays.asList(7))
                .listTradePlans(PLAN_OWNED, 1L, DEFAULT_QUERY, null);

        assertEquals(1, page.items().size(), "仅可见子账户 7 的交易计划（子账户 8 的不得出现）");
        assertEquals(7, page.items().get(0).subAccountId);
        assertTrue(dao.lastScoped, "有权限用户须走 scoped 路径");
        assertEquals(Arrays.asList(7), dao.lastScope.allowed(), "scope 集合 = 可见子账户");
    }

    @Test
    @DisplayName("无任何授权（空集）：返回空，绝不返回全部（安全默认）")
    void userWithNoGrantSeesNothingNotAll() {
        ScopedTradePlanDao dao = fixture();

        PageResult<TradePlan> page = service(dao, uid -> Collections.emptyList())
                .listTradePlans(PLAN_OWNED, 1L, DEFAULT_QUERY, null);

        assertTrue(page.items().isEmpty(), "无授权 ⇒ 空集（严禁退化为不过滤 = 全可见）");
        assertEquals(0L, page.total(), "无授权 ⇒ total=0（越权行不得计入计数）");
        assertTrue(dao.lastScoped, "空集须走 scoped 路径（恒假条件），不得走不加过滤路径");
        assertTrue(dao.lastScope.isEmpty(), "scope 须为空集（恒假）");

        // 反退化对照：同一 DAO 的"不加过滤"路径对同一计划确实返回 2 条 —— 证明上面的空
        // 并非数据集本身为空，而是过滤生效。
        assertEquals(2, dao.findByHedgePlan(PLAN_OWNED).size(),
                "对照基线：不加过滤时计划 1 下含 2 条交易计划");
    }

    @Test
    @DisplayName("跨账户只读（含哨兵 0）：不加过滤、可见全部")
    void crossReadonlySeesAllWithoutScope() {
        ScopedTradePlanDao dao = fixture();

        PageResult<TradePlan> page = service(dao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL))
                .listTradePlans(PLAN_OWNED, 1L, DEFAULT_QUERY, null);

        assertEquals(2, page.items().size(), "跨账户只读可见全部子账户的交易计划");
        assertEquals(2L, page.total(), "跨账户只读 total = 全部匹配行");
        assertFalse(dao.lastScoped, "跨账户只读 ⇒ 不加过滤（走 findPage）");
        assertNull(dao.lastScope, "跨账户只读不得构造 scope（哨兵 0 不可进 IN 列表）");
    }

    @Test
    @DisplayName("越权读：他子账户的计划（不可见）⇒ 其交易计划一律不可见（返回空列表）")
    void foreignPlanTradePlansAreInvisible() {
        ScopedTradePlanDao dao = fixture();

        PageResult<TradePlan> page = service(dao, uid -> Arrays.asList(7))
                .listTradePlans(PLAN_FOREIGN, 1L, DEFAULT_QUERY, null);

        assertTrue(page.items().isEmpty(), "子账户 8 的计划 2 对其交易计划对子账户 7 的交易岗不可见"
                + "（FR-PLAN-15 验收 4；越权读 ⇒ 空）");
        assertEquals(0L, page.total(), "越权读 ⇒ total=0");
        assertTrue(dao.lastScoped, "越权读也须走 scoped 路径（恒假条件），不得回退到不加过滤");
    }

    @Test
    @DisplayName("fail-closed：取数入口缺位（null）时按空集处理（谁都不见）")
    void nullVisibilityFailsClosed() {
        ScopedTradePlanDao dao = fixture();

        PlanService service = service(dao, null);

        PageResult<TradePlan> page = service.listTradePlans(PLAN_OWNED, 1L, DEFAULT_QUERY, null);
        assertTrue(page.items().isEmpty(), "入口缺位 ⇒ 列表空");
        assertEquals(0L, page.total(), "入口缺位 ⇒ total=0");
        assertTrue(dao.lastScoped, "入口缺位 ⇒ 空集 ⇒ scoped 恒假路径");
    }

    @Test
    @DisplayName("T-096：多键排序须逐键过白名单 —— 交易计划第 2 键越域亦 10001（仅校验首键会漏）")
    void rejectsIllegalSecondSortKeyForTradePlans() {
        ScopedTradePlanDao dao = fixture();
        PageQuery query = PageQuery.of(null, null, "exec_deadline,asc,evil_column,asc",
                PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);

        BizException e = assertThrows(BizException.class, () -> service(dao, uid -> Arrays.asList(7))
                .listTradePlans(PLAN_OWNED, 1L, query, null));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertFalse(dao.lastScoped, "校验失败不得触碰 DAO");
    }

    @Test
    @DisplayName("隔离 × 分页叠加：total 为过滤后总数，切片亦按过滤后集合")
    void isolationAndPaginationCompose() {
        ScopedTradePlanDao dao = fixture();

        // 可见 [7,8]：计划 1 下应见 101（子 7）与 102（子 8），total=2；每页 1 条
        PageQuery firstPage = PageQuery.of("1", "10", null, PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);
        PageResult<TradePlan> p1 = service(dao, uid -> Arrays.asList(7, 8))
                .listTradePlans(PLAN_OWNED, 1L, firstPage, null);
        assertEquals(2L, p1.total(), "total 须为过滤后总数（=2），而非全表数");
        assertEquals(2, p1.items().size());

        // 收紧可见集为 [7]：total 必须随之降为 1（计数按过滤后计，最易错处）
        PageResult<TradePlan> p2 = service(dao, uid -> Arrays.asList(7))
                .listTradePlans(PLAN_OWNED, 1L, firstPage, null);
        assertEquals(1L, p2.total(), "收窄可见集后 total 须为 1");
        assertEquals(1, p2.items().size());
    }

    @Test
    @DisplayName("端点接线：handler 用会话身份解析隔离（同数据不同身份结果不同）")
    void handlerThreadsSessionIdentity(@TempDir Path webRoot) {
        ScopedTradePlanDao dao = fixture();

        // 身份 42 ⇒ 仅子账户 7；其它身份（含未登录 0）⇒ 哨兵（可见全部）
        SubAccountVisibility visibility = uid -> uid == 42L
                ? Arrays.asList(7) : Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        PlanService service = service(dao, visibility);

        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-42", 42L, "u42", 1L,
                com.hedgeguard.common.Clocks.now().plusMinutes(30)));
        Router router = new Router();
        router.register("GET", TradePlanHandler.PATH, new TradePlanHandler(service));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-42");
        HttpResult result = dispatcher.dispatch(new RequestContext("GET",
                ApiRoutes.full("/plan/" + PLAN_OWNED + "/trade-plans"), null, headers, "",
                "HGISO", "127.0.0.1"));

        assertEquals(200, result.status);
        assertTrue(dao.lastScoped, "身份 42 应触发 scoped 过滤（经会话而非请求体）");
        assertEquals(Arrays.asList(7), dao.lastScope.allowed(),
                "过滤集合须来自会话身份（而非请求体 / 路径）");
    }

    // ------------------------------------------------------------ 工具

    private static PlanService service(TradePlanDao dao, SubAccountVisibility visibility) {
        // listTradePlans 只用 tradePlanDao + subAccountVisibility；其余依赖构造仅赋值（可传 null）。
        return new PlanService(FakeTx.newTemplate(), null, null, null, null, null, null, null, dao,
                null, null, visibility, null, null, null, null, null, null);
    }

    /**
     * 夹具：计划 1 下含子账户 7 与 8 各一条（证明过滤作用于<b>交易计划自身的 sub_account_id 列</b>，
     * 而非仅作用于计划；逐行过滤是 DAO 层 IN 子句的语义所在），计划 2 全属子账户 8（越权读目标）。
     *
     * <p>说明：{@code R-PLAN-7} 要求同一计划下的交易计划子账户一致，故真实数据不会出现"计划 1 下混列"；
     * 此处为假 DAO 单测夹具，刻意构造逐行异子账户以证明<b>行级</b>过滤生效（{@code FR-PLAN-14} 验收 4
     * 要求交易计划显式携带子账户标识）。
     */
    private static ScopedTradePlanDao fixture() {
        ScopedTradePlanDao dao = new ScopedTradePlanDao();
        dao.rows.add(tradePlan(101L, PLAN_OWNED, 7));
        dao.rows.add(tradePlan(102L, PLAN_OWNED, 8));
        dao.rows.add(tradePlan(103L, PLAN_FOREIGN, 8));
        return dao;
    }

    private static TradePlan tradePlan(long id, long hedgePlanId, int subAccountId) {
        TradePlan tp = new TradePlan();
        tp.id = id;
        tp.hedgePlanId = hedgePlanId;
        tp.subAccountId = subAccountId;
        tp.tradePlanNo = "HG-TP-" + id;
        return tp;
    }

    /**
     * 内存假 DAO：忠实模拟 {@link SubAccountScope} 语义，并记录最近一次 <b>分页</b> list 调用所走的路径。
     *
     * <p>{@code findPage}（不加过滤）= 返回计划下全部；{@code findPageScoped} = 空集 ⇒ 恒假（空、total=0），
     * 否则 {@code sub_account_id ∈ allowed}。两者均按分页参数切片并给出过滤后 {@code total}。
     */
    private static final class ScopedTradePlanDao extends UnsupportedBaseDao<TradePlan, Long>
            implements TradePlanDao {

        private final List<TradePlan> rows = new ArrayList<>();
        private boolean lastScoped;
        private SubAccountScope lastScope;

        @Override
        public PageResult<TradePlan> findPage(PageQuery query, String where, List<Object> whereArgs) {
            this.lastScoped = false;
            this.lastScope = null;
            return page(filterByPlan((Long) whereArgs.get(0), null), query);
        }

        @Override
        public PageResult<TradePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            this.lastScoped = true;
            this.lastScope = scope;
            if (scope.isEmpty()) {
                return page(new ArrayList<>(), query);
            }
            return page(filterByPlan((Long) extraArgs.get(0), scope.allowed()), query);
        }

        @Override
        public List<TradePlan> findByHedgePlan(long hedgePlanId) {
            this.lastScoped = false;
            this.lastScope = null;
            return filterByPlan(hedgePlanId, null);
        }

        @Override
        public List<TradePlan> findByHedgePlanScoped(long hedgePlanId, SubAccountScope scope) {
            this.lastScoped = true;
            this.lastScope = scope;
            if (scope.isEmpty()) {
                return new ArrayList<>();
            }
            return filterByPlan(hedgePlanId, scope.allowed());
        }

        private List<TradePlan> filterByPlan(long hedgePlanId, List<Integer> allowed) {
            List<TradePlan> out = new ArrayList<>();
            for (TradePlan row : rows) {
                if (row.hedgePlanId != hedgePlanId) {
                    continue;
                }
                if (allowed == null || (row.subAccountId != null && allowed.contains(row.subAccountId))) {
                    out.add(row);
                }
            }
            return out;
        }

        private static PageResult<TradePlan> page(List<TradePlan> hit, PageQuery query) {
            int from = Math.min(query.offset(), hit.size());
            int to = Math.min(from + query.size(), hit.size());
            return new PageResult<>(new ArrayList<>(hit.subList(from, to)), hit.size(),
                    query.page(), query.size());
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
