package com.hedgeguard.web.handler.plan;

import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.PlanStateLog;
import com.hedgeguard.service.FakeTx;
import com.hedgeguard.service.PlanService;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /api/v1/plan/{id}/bind-exposure} <b>端点层</b>对接测试
 * （{@code FR-PLAN-19}；{@code T-023} 补测 ④）。
 *
 * <p>与前一类 {@code com.hedgeguard.service.PlanBindExposureTest}（服务层 ①②③）互补：本类走<b>真实
 * {@link RequestDispatcher} + {@link Router} + {@link BindExposureHandler}</b>，验证
 * <ul>
 *   <li>路由已注册、由 {@link BindExposureHandler} 处理（端点缺失 ⇒ 前端 {@code 404/405}）；</li>
 *   <li>{@code planId} 存在性（{@code pathParam("id")}）经服务层原样判定；</li>
 *   <li>成功信封为 {@code {"code":0,...,"data":{"bound":N}}}；</li>
 *   <li>核心规则越界（覆盖量 &gt; 未覆盖量）在端点层表现为 {@code 400/10001} 且信封含具名原因。</li>
 * </ul>
 * 完全离线（假 DAO + 假会话；{@code NFR-TEST-2}）。
 */
class BindExposureHandlerTest {

    private static final int SUB = 3;
    private static final long PRODUCT = 5L;
    private static final long OPERATOR = 42L;
    private static final long PLAN_ID = 1L;

    @Test
    @DisplayName("④ 端点层：POST /api/v1/plan/{id}/bind-exposure 已注册并由 BindExposureHandler 处理")
    void endpointIsRegistered() {
        Router router = new Router();
        PlanRoutes.register(router, service(new FakeHedgePlanDao(), new FakeBindDao(),
                new FakeExposureDao(), new FakePlanStateLogDao()));

        Router.RouteMatch match = router.match("POST",
                ApiRoutes.full("/plan/" + PLAN_ID + "/bind-exposure"));
        assertNotNull(match, "端点未注册 —— FR-PLAN-19 绑定敞口将 404/405");
        assertTrue(match.handler() instanceof BindExposureHandler,
                "应由 BindExposureHandler 处理，实际=" + match.handler().getClass().getName());
    }

    @Test
    @DisplayName("④ 端点层：成功路径 ⇒ 200 + code=0 + data.bound=1，驱动真实 bindExposure 落库")
    void endpointHappyPath(@TempDir Path webRoot) {
        FakeHedgePlanDao planDao = new FakeHedgePlanDao();
        FakeBindDao bindDao = new FakeBindDao();
        FakeExposureDao exposureDao = new FakeExposureDao();
        planDao.rows.add(draftPlan("80"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        HttpResult result = post(webRoot, service(planDao, bindDao, exposureDao, new FakePlanStateLogDao()),
                PLAN_ID, "{\"binds\":[{\"exposureId\":1,\"coveredQty\":\"80\"}]}");

        assertEquals(200, result.status, body(result));
        assertEquals(0, code(result), body(result));
        assertTrue(body(result).contains("\"bound\":1"), "返回体须含 data.bound=1：" + body(result));
        assertEquals(1, bindDao.rows.size(), "端点须真实驱动 bindExposure 落库");
        assertEquals(0, new BigDecimal("80").compareTo(exposureDao.findById(1L).coveredQty));
    }

    @Test
    @DisplayName("④ 端点层：planId 不存在 ⇒ 400/10001（planId 存在性由服务层判定）")
    void endpointPlanIdMissing(@TempDir Path webRoot) {
        FakeHedgePlanDao planDao = new FakeHedgePlanDao();
        FakeExposureDao exposureDao = new FakeExposureDao();
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        HttpResult result = post(webRoot, service(planDao, new FakeBindDao(), exposureDao,
                new FakePlanStateLogDao()), 999L,
                "{\"binds\":[{\"exposureId\":1,\"coveredQty\":\"10\"}]}");

        assertEquals(400, result.status, body(result));
        assertEquals(10001, code(result), body(result));
        assertTrue(body(result).contains("套保计划不存在"), body(result));
    }

    @Test
    @DisplayName("④ 端点层：覆盖越界（> 未覆盖量）⇒ 400/10001 且信封含具名原因（FR-PLAN-19 验收 3）")
    void endpointSurfacesOverCoverageRule(@TempDir Path webRoot) {
        FakeHedgePlanDao planDao = new FakeHedgePlanDao();
        FakeBindDao bindDao = new FakeBindDao();
        FakeExposureDao exposureDao = new FakeExposureDao();
        // 计划量给足（1000 > 150），隔离出「覆盖量 > 未覆盖量」这一条规则（不被计划量上限规则代偿）
        planDao.rows.add(draftPlan("1000"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        HttpResult result = post(webRoot, service(planDao, bindDao, exposureDao, new FakePlanStateLogDao()),
                PLAN_ID, "{\"binds\":[{\"exposureId\":1,\"coveredQty\":\"150\"}]}");

        assertEquals(400, result.status, body(result));
        assertEquals(10001, code(result), body(result));
        assertTrue(body(result).contains("覆盖数量超过敞口未覆盖数量"), body(result));
        assertTrue(body(result).contains("FR-PLAN-19 验收 3"), body(result));
        assertTrue(bindDao.rows.isEmpty(), "越界被拒时端点不得落库");
    }

    // ------------------------------------------------------------ 工具

    private static PlanService service(HedgePlanDao planDao, HedgePlanExposureDao bindDao,
                                       ExposureDao exposureDao, PlanStateLogDao stateLogDao) {
        return new PlanService(FakeTx.newTemplate(), planDao, bindDao, null, null,
                null, null, stateLogDao, null, exposureDao, null,
                userId -> Collections.singletonList(SUB), null, null, null, null, null, null);
    }

    private static HttpResult post(Path webRoot, PlanService planService, long planId, String payload) {
        Router router = new Router();
        PlanRoutes.register(router, planService);

        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-T023", OPERATOR, "u023", 1L, Clocks.now().plusMinutes(30)));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T023");
        return dispatcher.dispatch(new RequestContext("POST",
                ApiRoutes.full("/plan/" + planId + "/bind-exposure"), null, headers, payload,
                "HGT023", "127.0.0.1"));
    }

    private static String body(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private static int code(HttpResult result) {
        return JsonParser.parseString(body(result)).getAsJsonObject().get("code").getAsInt();
    }

    private static HedgePlan draftPlan(String planQty) {
        HedgePlan plan = new HedgePlan();
        plan.id = PLAN_ID;
        plan.subAccountId = SUB;
        plan.planType = "COMMODITY";
        plan.productId = PRODUCT;
        plan.hedgeDirection = "BUY_HEDGE";   // 反向敞口 = SELL
        plan.planQty = new BigDecimal(planQty);
        plan.unit = "TON";
        plan.status = "DRAFT";
        plan.termStartDate = LocalDate.of(2026, 9, 20);
        plan.termEndDate = LocalDate.of(2026, 11, 30);
        plan.hedgeRatio = new BigDecimal("0.8");
        plan.ratioMethod = "SIMPLE";
        plan.calcBaseDate = LocalDate.of(2026, 9, 20);
        plan.applicantId = OPERATOR;
        return plan;
    }

    private static Exposure exposure(long id, String direction, String qty) {
        Exposure exposure = new Exposure();
        exposure.id = id;
        exposure.subAccountId = SUB;
        exposure.productId = PRODUCT;
        exposure.spotCategoryCode = "CAT-CU";
        exposure.direction = direction;
        exposure.calcMode = "COMBINED";
        exposure.contractUnexecutedQty = new BigDecimal(qty);
        exposure.inventoryQty = BigDecimal.ZERO;
        exposure.productionPlanQty = BigDecimal.ZERO;
        exposure.componentsEnabled = "CONTRACT_UNEXECUTED";
        exposure.combinedComponents = "CONTRACT_UNEXECUTED";
        exposure.exposureQty = new BigDecimal(qty);
        exposure.coveredQty = BigDecimal.ZERO;
        exposure.unit = "TON";
        exposure.status = "PENDING_HEDGE";
        exposure.unmappedFlag = "N";
        exposure.snapshotId = "EXP-" + id;
        return exposure;
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakeHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {

        private final List<HedgePlan> rows = new ArrayList<>();

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
        public HedgePlan findById(Long id) {
            for (HedgePlan row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public HedgePlan findByPlanNo(String planNo) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByPlanNo");
        }

        @Override
        public PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public HedgePlan findByIdScoped(long id, SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByIdScoped");
        }

        @Override
        public int updateCurrentDocVersion(long planId, int version, long operatorId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("updateCurrentDocVersion");
        }

        @Override
        public int deleteById(Long id) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("deleteById");
        }
    }

    private static final class FakeBindDao extends UnsupportedBaseDao<HedgePlanExposure, Long>
            implements HedgePlanExposureDao {

        private final List<HedgePlanExposure> rows = new ArrayList<>();
        private long nextId = 100L;

        @Override
        public int insert(HedgePlanExposure entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public List<HedgePlanExposure> findByPlan(long hedgePlanId) {
            List<HedgePlanExposure> out = new ArrayList<>();
            for (HedgePlanExposure row : rows) {
                if (row.hedgePlanId != null && row.hedgePlanId == hedgePlanId) {
                    out.add(row);
                }
            }
            return out;
        }
        @Override
        public List<HedgePlanExposure> findByExposure(long exposureId) {
            List<HedgePlanExposure> out = new ArrayList<>();
            for (HedgePlanExposure row : rows) {
                if (row.exposureId != null && row.exposureId == exposureId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    private static final class FakeExposureDao extends UnsupportedBaseDao<Exposure, Long>
            implements ExposureDao {

        private final List<Exposure> rows = new ArrayList<>();

        @Override
        public Exposure findById(Long id) {
            for (Exposure row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int updateById(Exposure entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public PageResult<Exposure> findPageScoped(PageQuery query, SubAccountScope scope,
                                                   String extraWhere, List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public Exposure findByIdScoped(long id, SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByIdScoped");
        }
    }

    private static final class FakePlanStateLogDao extends UnsupportedBaseDao<PlanStateLog, Long>
            implements PlanStateLogDao {

        private final List<PlanStateLog> rows = new ArrayList<>();

        @Override
        public int insert(PlanStateLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }
    }
}
