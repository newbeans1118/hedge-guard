package com.hedgeguard.web.handler.plan;

import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.PlanStateLog;
import com.hedgeguard.service.FakeTx;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.service.SubAccountVisibility;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PUT /api/v1/plan/{id}}（{@link PlanDraftHandler}，{@code FR-PLAN-2}）与
 * {@code GET /api/v1/plan/{id}/doc}（{@link PlanDocReadHandler}）的端点级回归。
 *
 * <p>覆盖 {@code FR-PLAN-2} 四条验收 + 系统字段不可覆盖 + 子账户写/读隔离 +
 * 书面文件正文读取（含 CLOB 三要素与「尚未生成」）+ 路由注册/分派 +
 * {@code LocalDateTime} wire 形态（{@code T-235} 秒恒下发）。
 *
 * <p>完全离线（{@code NFR-TEST-2}）：内存假 DAO + {@link FakeTx}，不连 DM、不引 Mockito。
 */
class PlanDraftAndDocReadEndpointTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");
    private static final int SUB = 3;
    private static final long PRODUCT = 5L;
    private static final long OPERATOR = 1L;
    private static final long PLAN_ID = 7L;

    private FakeHedgePlanDao planDao;
    private FakeStateLogDao stateLogDao;
    private FakeHedgeDocDao docDao;
    private FakeBindDao bindDao;
    private FakeExposureDao exposureDao;
    private PlanService service;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        planDao = new FakeHedgePlanDao();
        stateLogDao = new FakeStateLogDao();
        docDao = new FakeHedgeDocDao();
        bindDao = new FakeBindDao();
        exposureDao = new FakeExposureDao();
        service = service(userId -> Collections.singletonList(SUB));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ============================================================ FR-PLAN-2 验收 1~4

    @Test
    @DisplayName("验收 1：只提交 planQty 修改草稿数量 ⇒ 成功，其余字段保持原值（部分更新）")
    void partialUpdateOnlyQuantity() {
        HedgePlan original = draftPlan("DRAFT");
        planDao.rows.add(original);

        HedgePlan patch = new HedgePlan();
        patch.planQty = new BigDecimal("120");
        assertEquals(PLAN_ID, service.updateDraft(PLAN_ID, patch, OPERATOR));

        HedgePlan stored = planDao.rows.get(0);
        assertEquals(0, new BigDecimal("120").compareTo(stored.planQty));
        assertEquals("COMMODITY", stored.planType, "未提交的 planType 须保持原值");
        assertEquals("BUY_HEDGE", stored.hedgeDirection);
        assertEquals("TON", stored.unit);
        assertEquals(LocalDate.of(2026, 9, 20), stored.termStartDate);
        assertEquals(LocalDate.of(2026, 11, 30), stored.termEndDate);
        assertEquals(0, new BigDecimal("0.8").compareTo(stored.hedgeRatio));
        assertEquals("SIMPLE", stored.ratioMethod);
        assertEquals(LocalDate.of(2026, 9, 20), stored.calcBaseDate);
        assertEquals("DRAFT", stored.status, "系统字段 status 不得被改");
        assertEquals("HP202609200001", stored.planNo, "系统字段 planNo 不得被改");
        assertEquals(OPERATOR, stored.applicantId.longValue(), "系统字段 applicantId 不得被改");
        assertEquals(SUB, stored.subAccountId.intValue(), "系统字段 subAccountId 不得被改");
    }

    @Test
    @DisplayName("验收 1 补充：变更按 TB-PLAN-7 写一条 DRAFT→DRAFT 留痕")
    void changeWritesStateLog() {
        planDao.rows.add(draftPlan("DRAFT"));
        HedgePlan patch = new HedgePlan();
        patch.unit = "KG";
        service.updateDraft(PLAN_ID, patch, OPERATOR);

        assertEquals(1, stateLogDao.rows.size(), "修改草稿须写一条变更留痕");
        PlanStateLog log = stateLogDao.rows.get(0);
        assertEquals("DRAFT", log.fromStatus);
        assertEquals("DRAFT", log.toStatus);
        assertEquals("NONE", log.quotaAction);
        assertTrue(log.reason.contains("FR-PLAN-2"), "留痕原因须标注 FR-PLAN-2：" + log.reason);
        assertEquals(SUB, log.subAccountId);
        assertEquals(OPERATOR, log.operatorId);
    }

    @Test
    @DisplayName("验收 2：PENDING_APPROVAL 直接修改被拒（10009），提示须先撤回")
    void pendingApprovalRejectedWithWithdrawHint() {
        planDao.rows.add(draftPlan("PENDING_APPROVAL"));
        HedgePlan patch = new HedgePlan();
        patch.planQty = new BigDecimal("120");

        BizException e = assertThrows(BizException.class,
                () -> service.updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode(), "状态门禁须用 10009");
        assertTrue(e.getMessage().contains("撤回"), "须提示先撤回（FR-PLAN-13）：" + e.getMessage());
        assertEquals("PENDING_APPROVAL", planDao.rows.get(0).status, "拒绝时不得改状态");
    }

    @Test
    @DisplayName("验收 3：APPROVED 修改被拒（10009），提示须终止后新建或走变更流程")
    void approvedRejectedWithChangeFlowHint() {
        planDao.rows.add(draftPlan("APPROVED"));
        HedgePlan patch = new HedgePlan();
        patch.planQty = new BigDecimal("120");

        BizException e = assertThrows(BizException.class,
                () -> service.updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode());
        assertTrue(e.getMessage().contains("变更流程") || e.getMessage().contains("终止后新建"),
                "须提示 R-PLAN-6：" + e.getMessage());
    }

    @Test
    @DisplayName("验收 4：改后期限超过起始日 + 12 个月 ⇒ 拒绝保存，不落库")
    void termRedlineViolationRejected() {
        planDao.rows.add(draftPlan("DRAFT"));
        HedgePlan patch = new HedgePlan();
        patch.termEndDate = LocalDate.of(2027, 10, 1);

        BizException e = assertThrows(BizException.class,
                () -> service.updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("12 个月") || e.getMessage().contains("期限"),
                "须给出期限红线具名原因：" + e.getMessage());
        assertEquals(LocalDate.of(2026, 11, 30), planDao.rows.get(0).termEndDate,
                "拒绝时不得落库");
        assertTrue(stateLogDao.rows.isEmpty(), "拒绝时不得写留痕");
    }

    @Test
    @DisplayName("计划数量 ≤ 0 / 比率越界 / 方向非法 ⇒ 拒绝（修改后重新校验）")
    void scalarValidationReapplied() {
        planDao.rows.add(draftPlan("DRAFT"));

        HedgePlan nonPositive = new HedgePlan();
        nonPositive.planQty = BigDecimal.ZERO;
        assertThrows(BizException.class, () -> service.updateDraft(PLAN_ID, nonPositive, OPERATOR));

        HedgePlan badRatio = new HedgePlan();
        badRatio.hedgeRatio = new BigDecimal("1.5");
        assertThrows(BizException.class, () -> service.updateDraft(PLAN_ID, badRatio, OPERATOR));

        HedgePlan badDirection = new HedgePlan();
        badDirection.hedgeDirection = "UNKNOWN";
        assertThrows(BizException.class, () -> service.updateDraft(PLAN_ID, badDirection, OPERATOR));
    }

    @Test
    @DisplayName("写面子账户隔离：不可写子账户 ⇒ 10004（跨账户只读不得写）")
    void writeSubAccountIsolation() {
        planDao.rows.add(draftPlan("DRAFT"));
        PlanService readonly = service(userId -> Collections.singletonList(
                SubAccountScope.ALL_ACCOUNT_SENTINEL));
        HedgePlan patch = new HedgePlan();
        patch.unit = "KG";

        BizException e = assertThrows(BizException.class,
                () -> readonly.updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
    }

    // ============================================================ GET /plan/{id}/doc

    @Test
    @DisplayName("读取当前书面文件正文：CLOB 三要素 + docNo/version/pendingExternalReview 齐备")
    void currentDocReturnsBody() {
        planDao.rows.add(draftPlan("DRAFT"));
        docDao.current = hedgeDoc();

        HedgeDoc doc = service.currentDoc(PLAN_ID, OPERATOR);
        assertNotNull(doc);
        assertEquals("DOC-HP-001", doc.docNo);
        assertEquals("① 实货敞口 EXP-1 / 合约 CU2611", doc.elemFormalDesignation);
        assertEquals("② 比率分析法", doc.elemEffectivenessMethod);
        assertEquals("③ 比率偏离 ±5% 触发再平衡", doc.elemRebalanceMechanism);
        assertEquals("Y", doc.pendingExternalReview);
    }

    @Test
    @DisplayName("尚无书面文件 ⇒ null（信封 data 省略，前端按「未生成」展示，非错误）")
    void currentDocNullWhenAbsent() {
        planDao.rows.add(draftPlan("DRAFT"));
        assertNull(service.currentDoc(PLAN_ID, OPERATOR));
    }

    @Test
    @DisplayName("读面子账户隔离：不可见的计划 ⇒ 与不存在同一错误（10001），不泄露存在性")
    void currentDocReadIsolation() {
        planDao.rows.add(draftPlan("DRAFT"));
        PlanService other = service(userId -> Collections.singletonList(999));
        docDao.current = hedgeDoc();

        BizException e = assertThrows(BizException.class, () -> other.currentDoc(PLAN_ID, OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("计划不存在"), "须为统一错误：" + e.getMessage());
    }

    // ============================================================ 端点注册 / 分派

    @Test
    @DisplayName("端点注册：PUT /plan/{id} → PlanDraftHandler；GET /plan/{id}/doc → PlanDocReadHandler")
    void endpointsRegistered() {
        Router router = new Router();
        PlanRoutes.register(router, null);

        Router.RouteMatch put = router.match("PUT", ApiRoutes.full("/plan/" + PLAN_ID));
        assertNotNull(put, "PUT /plan/{id} 未注册 ⇒ FR-PLAN-2 将 404/405");
        assertTrue(put.handler() instanceof PlanDraftHandler,
                "应由 PlanDraftHandler 处理，实际=" + put.handler().getClass().getName());

        Router.RouteMatch getDoc = router.match("GET",
                ApiRoutes.full("/plan/" + PLAN_ID + "/doc"));
        assertNotNull(getDoc, "GET /plan/{id}/doc 未注册 ⇒ 书面文件正文读取将 404/405");
        assertTrue(getDoc.handler() instanceof PlanDocReadHandler,
                "应由 PlanDocReadHandler 处理，实际=" + getDoc.handler().getClass().getName());

        // 方法级语义正交：既有 GET 详情 / POST 生成 / GET|POST trade-plans 不受影响
        assertNotNull(router.match("GET", ApiRoutes.full("/plan/" + PLAN_ID)));
        assertNotNull(router.match("POST", ApiRoutes.full("/plan/" + PLAN_ID + "/doc")));
        assertNotNull(router.match("GET", ApiRoutes.full("/plan/" + PLAN_ID + "/trade-plans")));
    }

    @Test
    @DisplayName("端点层：PUT 只传 planQty ⇒ 200/code=0，真实驱动 updateDraft 落库")
    void putEndpointHappyPath(@TempDir Path webRoot) {
        planDao.rows.add(draftPlan("DRAFT"));

        HttpResult result = dispatch(webRoot, "PUT",
                ApiRoutes.full("/plan/" + PLAN_ID), "{\"planQty\":\"120\"}");

        assertEquals(200, result.status, body(result));
        assertEquals(0, code(result), body(result));
        assertEquals(0, new BigDecimal("120").compareTo(planDao.rows.get(0).planQty),
                "端点须真实驱动 updateDraft 落库");
    }

    @Test
    @DisplayName("端点层：GET doc ⇒ 200，正文三要素为 camelCase 字段，generatedTime 为 ISO 字符串非对象")
    void getDocEndpointWireShape(@TempDir Path webRoot) {
        planDao.rows.add(draftPlan("DRAFT"));
        docDao.current = hedgeDoc();

        HttpResult result = dispatch(webRoot, "GET",
                ApiRoutes.full("/plan/" + PLAN_ID + "/doc"), "");

        assertEquals(200, result.status, body(result));
        assertEquals(0, code(result), body(result));
        String raw = body(result);
        assertTrue(raw.contains("\"elemFormalDesignation\":\"①"), "正文须为 camelCase 字段：" + raw);
        assertTrue(raw.contains("\"elemEffectivenessMethod\":\"②"), raw);
        assertTrue(raw.contains("\"elemRebalanceMechanism\":\"③"), raw);
        assertTrue(raw.contains("\"pendingExternalReview\":\"Y\""), raw);
        assertTrue(raw.contains("\"generatedTime\":\"2026-09-20T"),
                "generatedTime 须由 T-235 适配器写为 ISO 字符串（秒恒下发）：" + raw);
        assertTrue(!raw.contains("\"date\":{"), "不得残留 LocalDateTime 对象形态：" + raw);
    }

    @Test
    @DisplayName("端点层：GET doc 尚未生成 ⇒ 200/code=0 且 data 省略（非错误）")
    void getDocEndpointAbsent(@TempDir Path webRoot) {
        planDao.rows.add(draftPlan("DRAFT"));

        HttpResult result = dispatch(webRoot, "GET",
                ApiRoutes.full("/plan/" + PLAN_ID + "/doc"), "");

        assertEquals(200, result.status, body(result));
        assertEquals(0, code(result), body(result));
        assertTrue(!body(result).contains("\"data\""), "无文件时不携带 data：" + body(result));
    }

    // ============================================================ TE-P1PILOT-001 补正：绑定一致性复核

    @Test
    @DisplayName("补正 a：已绑定合法态改 hedgeDirection ⇒ 拒绝，且不落库、不写留痕")
    void directionChangeRejectedWhenBound() {
        planDao.rows.add(draftPlan("DRAFT"));
        bindDao.rows.add(boundRow(1L));
        exposureDao.rows.add(hedgeExposure(1L));

        HedgePlan patch = new HedgePlan();
        patch.hedgeDirection = "SELL_HEDGE";
        BizException e = assertThrows(BizException.class,
                () -> service.updateDraft(PLAN_ID, patch, OPERATOR));

        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("R-PLAN-1"), e.getMessage());
        assertEquals("BUY_HEDGE", planDao.rows.get(0).hedgeDirection, "拒绝时方向须保持原值");
        assertEquals(PRODUCT, planDao.rows.get(0).productId.longValue(), "拒绝时品种须保持原值");
        assertTrue(stateLogDao.rows.isEmpty(), "拒绝时不得写留痕");
    }

    @Test
    @DisplayName("补正 b：已绑定合法态改 productId ⇒ 拒绝（R-PLAN-7 同品种）")
    void productChangeRejectedWhenBound() {
        planDao.rows.add(draftPlan("DRAFT"));
        bindDao.rows.add(boundRow(1L));
        exposureDao.rows.add(hedgeExposure(1L));

        HedgePlan patch = new HedgePlan();
        patch.productId = 9L;
        BizException e = assertThrows(BizException.class,
                () -> service.updateDraft(PLAN_ID, patch, OPERATOR));

        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("R-PLAN-7"), e.getMessage());
        assertEquals(PRODUCT, planDao.rows.get(0).productId.longValue());
        assertTrue(stateLogDao.rows.isEmpty());
    }

    @Test
    @DisplayName("补正 c：已绑定合法态只改 planQty ⇒ 仍成功（未过度拦截）")
    void quantityOnlyUpdateStillAllowedWhenBound() {
        planDao.rows.add(draftPlan("DRAFT"));
        bindDao.rows.add(boundRow(1L));
        exposureDao.rows.add(hedgeExposure(1L));

        HedgePlan patch = new HedgePlan();
        patch.planQty = new BigDecimal("120");
        assertEquals(PLAN_ID, service.updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals(0, new BigDecimal("120").compareTo(planDao.rows.get(0).planQty));
        assertEquals(1, stateLogDao.rows.size(), "正常修改仍写变更留痕");
    }

    @Test
    @DisplayName("补正 d：无 BOUND 绑定行 ⇒ 改方向/品种放行（草稿尚未绑定敞口）")
    void directionChangeAllowedWhenNoBindings() {
        planDao.rows.add(draftPlan("DRAFT"));

        HedgePlan patch = new HedgePlan();
        patch.hedgeDirection = "SELL_HEDGE";
        patch.productId = 9L;
        assertEquals(PLAN_ID, service.updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals("SELL_HEDGE", planDao.rows.get(0).hedgeDirection);
        assertEquals(9L, planDao.rows.get(0).productId.longValue());
    }

    @Test
    @DisplayName("补正 e：绑定行存在但敞口取数返回 null ⇒ fail-closed 拒绝（不静默放行）")
    void missingExposureFailsClosedWhenChangingDirection() {
        planDao.rows.add(draftPlan("DRAFT"));
        bindDao.rows.add(boundRow(99L));
        // exposureDao.rows 为空 ⇒ findById 返回 null

        HedgePlan patch = new HedgePlan();
        patch.hedgeDirection = "SELL_HEDGE";
        BizException e = assertThrows(BizException.class,
                () -> service.updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(stateLogDao.rows.isEmpty());
    }

    @Test
    @DisplayName("补正 f：绑定取数入口缺失（装配缺件）⇒ fail-closed 拒绝，不假绿")
    void missingDaoFailsClosedWhenChangingDirection() {
        planDao.rows.add(draftPlan("DRAFT"));
        PlanService noDao = new PlanService(FakeTx.newTemplate(), planDao, null, null, null,
                docDao, null, stateLogDao, null, null, null,
                userId -> Collections.singletonList(SUB),
                null, null, null, null, null, null);
        HedgePlan patch = new HedgePlan();
        patch.hedgeDirection = "SELL_HEDGE";

        BizException e = assertThrows(BizException.class,
                () -> noDao.updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, e.errorCode());
    }

    // ============================================================ 工具

    private PlanService service(SubAccountVisibility visibility) {
        return new PlanService(FakeTx.newTemplate(), planDao, bindDao, null, null,
                docDao, null, stateLogDao, null, exposureDao, null, visibility,
                null, null, null, null, null, null);
    }

    private HttpResult dispatch(Path webRoot, String method, String path, String payload) {
        Router router = new Router();
        PlanRoutes.register(router, service);

        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-FRPLAN2", OPERATOR, "u-frplan2", 1L, Clocks.now().plusMinutes(30)));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));

        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-FRPLAN2");
        return dispatcher.dispatch(new RequestContext(method, path, null, headers, payload,
                "HG-FRPLAN2", "127.0.0.1"));
    }

    private static HedgePlan draftPlan(String status) {
        HedgePlan plan = new HedgePlan();
        plan.id = PLAN_ID;
        plan.subAccountId = SUB;
        plan.planNo = "HP202609200001";
        plan.planType = "COMMODITY";
        plan.productId = PRODUCT;
        plan.hedgeDirection = "BUY_HEDGE";
        plan.planQty = new BigDecimal("80");
        plan.unit = "TON";
        plan.termStartDate = LocalDate.of(2026, 9, 20);
        plan.termEndDate = LocalDate.of(2026, 11, 30);
        plan.hedgeRatio = new BigDecimal("0.8");
        plan.ratioMethod = "SIMPLE";
        plan.calcBaseDate = LocalDate.of(2026, 9, 20);
        plan.status = status;
        plan.applicantId = OPERATOR;
        return plan;
    }

    private static HedgeDoc hedgeDoc() {
        HedgeDoc doc = new HedgeDoc();
        doc.id = 11L;
        doc.subAccountId = SUB;
        doc.hedgePlanId = PLAN_ID;
        doc.docNo = "DOC-HP-001";
        doc.docVersion = 1;
        doc.isCurrent = "Y";
        doc.elemFormalDesignation = "① 实货敞口 EXP-1 / 合约 CU2611";
        doc.elemEffectivenessMethod = "② 比率分析法";
        doc.elemRebalanceMechanism = "③ 比率偏离 ±5% 触发再平衡";
        doc.hedgedItemExposureId = 1L;
        doc.hedgingInstrument = "CU2611";
        doc.pendingExternalReview = "Y";
        doc.generatedBy = OPERATOR;
        doc.generatedTime = LocalDateTime.of(2026, 9, 20, 10, 15, 0);
        return doc;
    }

    private static HedgePlanExposure boundRow(long exposureId) {
        HedgePlanExposure row = new HedgePlanExposure();
        row.id = exposureId;
        row.subAccountId = SUB;
        row.hedgePlanId = PLAN_ID;
        row.exposureId = exposureId;
        row.coveredQty = new BigDecimal("10");
        row.status = "BOUND";
        return row;
    }

    private static Exposure hedgeExposure(long exposureId) {
        Exposure exposure = new Exposure();
        exposure.id = exposureId;
        exposure.subAccountId = SUB;
        exposure.productId = PRODUCT;
        exposure.direction = "SELL";
        exposure.unmappedFlag = "N";
        exposure.exposureQty = new BigDecimal("100");
        exposure.coveredQty = new BigDecimal("10");
        return exposure;
    }

    private static String body(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private static int code(HttpResult result) {
        return JsonParser.parseString(body(result)).getAsJsonObject().get("code").getAsInt();
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakeHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {

        private final List<HedgePlan> rows = new ArrayList<>();

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
            for (HedgePlan row : rows) {
                if (row.id == id && scope.allowed().contains(row.subAccountId)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int updateById(HedgePlan entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public String findMaxPlanNoByPrefix(String planNoPrefix) {
            throw FakeDao.unsupported("findMaxPlanNoByPrefix");
        }

        @Override
        public HedgePlan findByPlanNo(String planNo) {
            throw FakeDao.unsupported("findByPlanNo");
        }

        @Override
        public PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            throw FakeDao.unsupported("findPageScoped");
        }

        @Override
        public int updateCurrentDocVersion(long planId, int version, long operatorId) {
            throw FakeDao.unsupported("updateCurrentDocVersion");
        }

        @Override
        public int deleteById(Long id) {
            throw FakeDao.unsupported("deleteById");
        }
    }

    private static final class FakeStateLogDao extends UnsupportedBaseDao<PlanStateLog, Long>
            implements PlanStateLogDao {

        private final List<PlanStateLog> rows = new ArrayList<>();

        @Override
        public int insert(PlanStateLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }
    }

    private static final class FakeHedgeDocDao extends UnsupportedBaseDao<HedgeDoc, Long>
            implements HedgeDocDao {

        private HedgeDoc current;

        @Override
        public HedgeDoc findCurrent(long hedgePlanId) {
            return current;
        }
    }

    private static final class FakeBindDao extends UnsupportedBaseDao<HedgePlanExposure, Long>
            implements HedgePlanExposureDao {

        private final List<HedgePlanExposure> rows = new ArrayList<>();

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
            return Collections.emptyList();
        }

        @Override
        public List<HedgePlanExposure> findBySubAccounts(List<Integer> subAccountIds) {
            throw FakeDao.unsupported("findBySubAccounts");
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
