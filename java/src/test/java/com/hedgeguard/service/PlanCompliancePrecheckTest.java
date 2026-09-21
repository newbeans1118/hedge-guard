package com.hedgeguard.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PlanApprovalDao;
import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.PurchaseContractDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.PlanApproval;
import com.hedgeguard.dao.entity.PlanStateLog;
import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.SubProductWhitelist;
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
import com.hedgeguard.web.handler.plan.PlanActionHandler;
import com.hedgeguard.web.handler.plan.PlanHandler;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-016} / {@code CR-002}（严重）**提交前阻断式合规预校验「服务端强制」**的验收用例。
 *
 * <p>依据：{@code FR-PLAN-10}（{@code docs/REQUIREMENTS/PLAN.md:240-255}）、{@code R-PLAN-3}（{:416}）、
 * {@code ARCHITECTURE.md} §6.4「过渡口径与字段级判定」、{@code java/CONTRACTS.md} §1.5。
 *
 * <p>覆盖：
 * <ol>
 *   <li>M1 强制 6 项（{@code ①②④⑤⑥⑦}）<b>各自独立</b>「不满足 ⇒ 阻断」，断言四项载荷与错误码；</li>
 *   <li>短路顺序（多项同时不满足 ⇒ 只报最先一项）；</li>
 *   <li>③ 规模红线<b>不拒单</b> + 三项可见性 (a) 提交响应 / (b) 落库 / (c) 审批接口响应；</li>
 *   <li>忽略客户端串（空串不拒单；伪造全 PASS 仍被阻断；伪造全 FAIL 仍通过）；</li>
 *   <li>反退化：逐项移除重算 / 去掉 unverified / 恢复「空串即拒单」⇒ 用例必红（见本类断言与提交记录）。</li>
 * </ol>
 *
 * <p>完全离线（{@code NFR-TEST-2}）：内存假 DAO，无 SQL / 连接。
 */
class PlanCompliancePrecheckTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");
    private static final long PLAN_ID = 1L;
    private static final int SUB_ACCOUNT = 3;
    private static final long PRODUCT = 5L;
    private static final long APPLICANT = 1L;

    private FakeHedgePlanDao planDao;
    private FakeHedgePlanExposureDao bindDao;
    private FakeHedgeDocDao docDao;
    private FakePlanApprovalDao approvalDao;
    private FakePlanStateLogDao stateLogDao;
    private FakeExposureDao exposureDao;
    private FakeAuthorizationDao authDao;
    private FakeSubProductWhitelistDao whitelistDao;
    private FakeExposureDetailDao detailDao;
    private FakePurchaseContractDao purchaseDao;
    private boolean trader;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        planDao = new FakeHedgePlanDao();
        bindDao = new FakeHedgePlanExposureDao();
        docDao = new FakeHedgeDocDao();
        approvalDao = new FakePlanApprovalDao();
        stateLogDao = new FakePlanStateLogDao();
        exposureDao = new FakeExposureDao();
        authDao = new FakeAuthorizationDao();
        whitelistDao = new FakeSubProductWhitelistDao();
        detailDao = new FakeExposureDetailDao();
        purchaseDao = new FakePurchaseContractDao();
        trader = false;
        seedSatisfied();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    /** 预置一份「六项全通过」的草稿计划场景，供各用例按需打破单项。 */
    private void seedSatisfied() {
        planDao.rows.add(plan("DRAFT", "80", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 11, 30)));
        docDao.rows.add(doc("Y", "正式指定", "有效性评估方法", "再平衡机制"));
        bindDao.rows.add(bind(1L, new BigDecimal("80")));
        exposureDao.rows.add(exposure(1L, "100", "80"));
        authDao.rows.add(authorization(APPLICANT, SUB_ACCOUNT, PRODUCT));
        whitelistDao.rows.add(whitelist(SUB_ACCOUNT, PRODUCT));
    }

    private PlanService service() {
        return new PlanService(FakeTx.newTemplate(), planDao, bindDao, null, null, docDao,
                approvalDao, stateLogDao, null, exposureDao, null,
                // T-115：业务规则用例——注入「可写子账户 SUB_ACCOUNT」的可见性替身。
                // 原注入哨兵 0（跨账户只读）在 T-115 后不再授予写权限，故改为具体子账户。
                userId -> Collections.singletonList(SUB_ACCOUNT),
                authDao, whitelistDao, detailDao, purchaseDao, null, userId -> trader);
    }

    // ------------------------------------------------------------ 1) 六项各自独立阻断

    @Test
    @DisplayName("前置：非草稿态 ⇒ 10009（状态机，非 R-PLAN-3 编号项）")
    void preconditionStatusNotDraft() {
        planDao.rows.get(0).status = "APPROVED";
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode());
    }

    @Test
    @DisplayName("① 操作授权：无子账户维度授权 ⇒ 10004 + 四项载荷（ruleId=R-PLAN-3/①）")
    void authSubAccountMissing() {
        authDao.rows.clear();
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertFourPayloads(e, PlanService.RULE_AUTH);
        assertTrue(e.current().contains("subAccountId=" + SUB_ACCOUNT), e.current());
    }

    @Test
    @DisplayName("① 操作授权：有子账户无品种维度授权 ⇒ 10003 + 四项载荷（ruleId=R-PLAN-3/①）")
    void authProductMissing() {
        authDao.rows.clear();
        authDao.rows.add(authorization(APPLICANT, SUB_ACCOUNT, 999L));
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode());
        assertFourPayloads(e, PlanService.RULE_AUTH);
        assertTrue(e.current().contains("productId=" + PRODUCT), e.current());
    }

    @Test
    @DisplayName("② 白名单：品种不在子账户白名单 ⇒ 10003 + 四项载荷（ruleId=R-PLAN-3/②）")
    void whitelistMissing() {
        whitelistDao.rows.clear();
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode());
        assertFourPayloads(e, PlanService.RULE_WHITELIST);
    }

    @Test
    @DisplayName("④ 期限红线：期限止超「建仓日+12 个月」⇒ 10012 + 四项载荷（ruleId=R-PLAN-3/④）")
    void termRedlineByPlan() {
        planDao.rows.get(0).termEndDate = LocalDate.of(2028, 1, 1);
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertFourPayloads(e, PlanService.RULE_TERM);
        assertTrue(e.current().contains("term_end_date=2028-01-01"), e.current());
        assertTrue(e.threshold().contains("2027-09-20"), e.threshold());
    }

    @Test
    @DisplayName("④ 期限红线：期限止超「实货合同到期日+30 天」（取较早者）⇒ 10012（ruleId=R-PLAN-3/④）")
    void termRedlineByContract() {
        // 合同 2026-10-01 到期 + 30 天 = 2026-10-31 < 计划止 2026-11-30 ⇒ 阻断
        ExposureDetail detail = new ExposureDetail();
        detail.exposureId = 1L;
        detail.componentType = "CONTRACT_UNEXECUTED";
        detail.sourceType = "PURCHASE_CONTRACT";
        detail.sourceId = 77L;
        detailDao.byExposure.add(detail);
        purchaseDao.rows.add(purchaseContract(77L, LocalDate.of(2026, 10, 1)));
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertFourPayloads(e, PlanService.RULE_TERM);
        assertTrue(e.threshold().contains("2026-10-31"), "上限须取合同到期 + 30 天：" + e.threshold());
    }

    @Test
    @DisplayName("⑤ 数量：计划数量 > 绑定敞口未覆盖量 ⇒ 10012 + 四项载荷（ruleId=R-PLAN-3/⑤）")
    void quantityExceedsUncovered() {
        planDao.rows.get(0).planQty = new BigDecimal("200");
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertFourPayloads(e, PlanService.RULE_QTY);
        assertTrue(e.current().contains("planQty=200"), e.current());
    }

    @Test
    @DisplayName("⑥ 书面文件：三要素不齐备 ⇒ 10012 + 四项载荷（ruleId=R-PLAN-3/⑥）")
    void docElementsMissing() {
        docDao.rows.get(0).elemRebalanceMechanism = "  ";
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertFourPayloads(e, PlanService.RULE_DOC);
        assertTrue(e.current().contains("current_doc_version"), e.current());
    }

    @Test
    @DisplayName("⑦ 岗位分离：发起人具交易岗权限 ⇒ 10005 + 四项载荷（ruleId=R-PLAN-3/⑦）")
    void positionSeparation() {
        trader = true;
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, APPLICANT));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
        assertFourPayloads(e, PlanService.RULE_POSITION_SEPARATION);
    }

    // ------------------------------------------------------------ 2) 短路顺序

    @Test
    @DisplayName("短路：书面文件(⑥) 与 岗位分离(⑦) 同时不满足 ⇒ 只报 ⑥（法留存痕先判）")
    void shortCircuitDocBeforePositionSeparation() {
        docDao.rows.clear();
        trader = true;
        assertEquals(PlanService.RULE_DOC,
                assertThrows(BizException.class,
                        () -> service().submitForApproval(PLAN_ID, null, APPLICANT)).ruleId());
    }

    @Test
    @DisplayName("短路：岗位分离(⑦) 与 授权(①) 同时不满足 ⇒ 只报 ⑦")
    void shortCircuitPositionSeparationBeforeAuth() {
        trader = true;
        authDao.rows.clear();
        assertEquals(PlanService.RULE_POSITION_SEPARATION,
                assertThrows(BizException.class,
                        () -> service().submitForApproval(PLAN_ID, null, APPLICANT)).ruleId());
    }

    @Test
    @DisplayName("短路：授权(①) 与 数量(⑤) 同时不满足 ⇒ 只报 ①（资源类后判）")
    void shortCircuitAuthBeforeQuantity() {
        authDao.rows.clear();
        planDao.rows.get(0).planQty = new BigDecimal("9999");
        assertEquals(PlanService.RULE_AUTH,
                assertThrows(BizException.class,
                        () -> service().submitForApproval(PLAN_ID, null, APPLICANT)).ruleId());
    }

    @Test
    @DisplayName("短路：白名单(②) 与 期限(④) 同时不满足 ⇒ 只报 ②")
    void shortCircuitWhitelistBeforeTerm() {
        whitelistDao.rows.clear();
        planDao.rows.get(0).termEndDate = LocalDate.of(2028, 1, 1);
        assertEquals(PlanService.RULE_WHITELIST,
                assertThrows(BizException.class,
                        () -> service().submitForApproval(PLAN_ID, null, APPLICANT)).ruleId());
    }

    // ------------------------------------------------------------ 3) ③ 不拒单 + 三项可见性

    @Test
    @DisplayName("③ 规模红线不拒单：六项全满足 ⇒ 通过；快照 unverified 恒含 R-PLAN-3/③（(a) 服务端返回 + (b) 落库）")
    void scaleRedlineNotBlockingAndVisibleInSnapshot() {
        PlanService.PrecheckResult result = service().submitForApproval(PLAN_ID, null, APPLICANT);

        assertEquals("PENDING_APPROVAL", planDao.findById(PLAN_ID).status, "③ 不得拒单 ⇒ 计划进入待审批");
        assertEquals(Collections.singletonList(PlanService.RULE_SCALE), result.unverified,
                "(a) 服务端快照 unverified 须含 R-PLAN-3/③");
        // (b) 落库
        String persisted = approvalDao.rows.get(0).precheckResult;
        assertNotNull(persisted, "提交须落 t_plan_approval.precheck_result");
        assertTrue(persisted.contains("\"unverified\":[\"R-PLAN-3/③\"]"),
                "(b) 落库快照 unverified 须含 R-PLAN-3/③：" + persisted);
        assertEquals(6, approvalDao.rows.get(0).precheckResult.split("\"rule\"").length - 1,
                "快照 items 须含 6 项已校验规则");
        assertFalse(persisted.contains("\"verdict\":\"FAIL\""), "成功路径不应含 FAIL 项");
    }

    @Test
    @DisplayName("(a) 提交响应体（HTTP）含 unverified 字段且含 R-PLAN-3/③")
    void submitResponseBodyCarriesUnverified(@TempDir Path webRoot) {
        HttpResult result = dispatchSubmit(webRoot, "{}");
        assertEquals(200, result.status);
        JsonObject data = JsonParser.parseString(body(result)).getAsJsonObject().getAsJsonObject("data");
        assertNotNull(data, "成功信封须含 data");
        JsonArray unverified = data.getAsJsonArray("unverified");
        assertNotNull(unverified, "(a) 提交响应体须含 unverified 字段");
        assertTrue(containsString(unverified, PlanService.RULE_SCALE),
                "(a) unverified 须含 R-PLAN-3/③：" + unverified);
        assertNotNull(data.getAsJsonObject("precheckResult"), "data.precheckResult 须为服务端快照对象");
    }

    @Test
    @DisplayName("(c) 审批接口响应（GET /plan/{id} 详情）含预校验快照与 unverified ③ 标记")
    void approvalInterfaceResponseCarriesMarker(@TempDir Path webRoot) {
        service().submitForApproval(PLAN_ID, null, APPLICANT);   // 先产生落库快照

        Router router = new Router();
        router.register("GET", PlanHandler.PATH_BY_ID, new PlanHandler(service()));
        HttpResult result = dispatch(router, webRoot, "GET", ApiRoutes.full("/plan/" + PLAN_ID), null);

        assertEquals(200, result.status);
        JsonObject data = JsonParser.parseString(body(result)).getAsJsonObject().getAsJsonObject("data");
        assertNotNull(data, "详情响应须含 data");
        JsonObject snapshot = data.getAsJsonObject("precheckResult");
        assertNotNull(snapshot, "(c) 审批接口响应须含 precheckResult 快照");
        assertTrue(containsString(snapshot.getAsJsonArray("unverified"), PlanService.RULE_SCALE),
                "(c) 审批接口响应须展示 R-PLAN-3/③ 未校验标记");
    }

    // ------------------------------------------------------------ 4) 忽略客户端串（CR-002 核心）

    @Test
    @DisplayName("客户端串为空 ⇒ 不拒单（走服务端重算并通过）")
    void emptyClientPrecheckNotRejected() {
        for (String client : new String[]{null, "", "   ", "{}"}) {
            resetScenario();
            PlanService.PrecheckResult r = service().submitForApproval(PLAN_ID, client, APPLICANT);
            assertEquals("PENDING_APPROVAL", planDao.findById(PLAN_ID).status,
                    "客户端串=[" + client + "] 缺失/为空不得拒单");
            assertTrue(!r.unverified.isEmpty(), "服务端快照照常生成");
        }
    }

    @Test
    @DisplayName("伪造「全 PASS」客户端串，但实际 ② 白名单不满足 ⇒ 仍被阻断（CR-002 核心反例）")
    void forgedAllPassStillBlocked() {
        whitelistDao.rows.clear();
        String forged = "{\"items\":[{\"rule\":\"R-PLAN-3/②\",\"current\":\"x\",\"threshold\":\"y\","
                + "\"verdict\":\"PASS\",\"source\":\"client\"}],\"unverified\":[]}";
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, forged, APPLICANT));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode(),
                "客户端伪造 PASS 不得成为通过依据");
        assertEquals(PlanService.RULE_WHITELIST, e.ruleId());
        assertEquals("DRAFT", planDao.findById(PLAN_ID).status, "被拒后状态不变");
    }

    @Test
    @DisplayName("伪造「全 FAIL」客户端串，但六项实际全满足 ⇒ 应通过（服务端不采信客户端串）")
    void forgedAllFailStillPasses() {
        String forged = "{\"items\":[{\"rule\":\"R-PLAN-3/②\",\"verdict\":\"FAIL\"}],\"unverified\":[]}";
        PlanService.PrecheckResult r = service().submitForApproval(PLAN_ID, forged, APPLICANT);
        assertEquals("PENDING_APPROVAL", planDao.findById(PLAN_ID).status);
        assertEquals(Collections.singletonList(PlanService.RULE_SCALE), r.unverified);
    }

    // ------------------------------------------------------------ 内部

    private void resetScenario() {
        planDao.rows.clear();
        docDao.rows.clear();
        bindDao.rows.clear();
        exposureDao.rows.clear();
        authDao.rows.clear();
        whitelistDao.rows.clear();
        approvalDao.rows.clear();
        seedSatisfied();
    }

    private static void assertFourPayloads(BizException e, String expectedRuleId) {
        assertEquals(expectedRuleId, e.ruleId(), "ruleId 须精确等于 " + expectedRuleId);
        assertNotNull(e.current(), "current 必填");
        assertFalse(e.current().isEmpty(), "current 不得为空");
        assertNotNull(e.threshold(), "threshold 必填");
        assertFalse(e.threshold().isEmpty(), "threshold 不得为空");
        assertNotNull(e.nextStep(), "nextStep 必填");
        assertFalse(e.nextStep().isEmpty(), "nextStep 不得为空");
    }

    private static boolean containsString(JsonArray array, String value) {
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.size(); i++) {
            if (value.equals(array.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String body(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private HttpResult dispatchSubmit(Path webRoot, String body) {
        Router router = new Router();
        router.register("POST", PlanActionHandler.Action.SUBMIT.path(),
                new PlanActionHandler(service(), PlanActionHandler.Action.SUBMIT));
        return dispatch(router, webRoot, "POST",
                ApiRoutes.full("/plan/" + PLAN_ID + "/submit"), body);
    }

    private static HttpResult dispatch(Router router, Path webRoot, String method, String path,
                                       String body) {
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-T016", APPLICANT, "u1", 1L, Clocks.now().plusMinutes(30)));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T016");
        return dispatcher.dispatch(new RequestContext(method, path, null, headers,
                body == null ? "" : body, "HGT016", "127.0.0.1"));
    }

    // ------------------------------------------------------------ 场景构造

    private static HedgePlan plan(String status, String planQty, LocalDate start, LocalDate end) {
        HedgePlan p = new HedgePlan();
        p.id = PLAN_ID;
        p.subAccountId = SUB_ACCOUNT;
        p.planNo = "HP20260920001";
        p.planType = "COMMODITY";
        p.productId = PRODUCT;
        p.hedgeDirection = "BUY_HEDGE";
        p.planQty = new BigDecimal(planQty);
        p.unit = "吨";
        p.termStartDate = start;
        p.termEndDate = end;
        p.hedgeRatio = new BigDecimal("0.8");
        p.ratioMethod = "SIMPLE";
        p.calcBaseDate = start;
        p.status = status;
        p.quotaOccupied = "N";
        p.executedQty = BigDecimal.ZERO;
        p.orderedQty = BigDecimal.ZERO;
        p.currentDocVersion = 1;
        p.applicantId = APPLICANT;
        return p;
    }

    private static HedgeDoc doc(String current, String designation, String method, String rebalance) {
        HedgeDoc d = new HedgeDoc();
        d.id = 1L;
        d.subAccountId = SUB_ACCOUNT;
        d.hedgePlanId = PLAN_ID;
        d.docNo = "HD-HP20260920001-V1";
        d.docVersion = 1;
        d.isCurrent = current;
        d.elemFormalDesignation = designation;
        d.elemEffectivenessMethod = method;
        d.elemRebalanceMechanism = rebalance;
        d.pendingExternalReview = "Y";
        return d;
    }

    private static HedgePlanExposure bind(long exposureId, BigDecimal coveredQty) {
        HedgePlanExposure b = new HedgePlanExposure();
        b.id = exposureId;
        b.subAccountId = SUB_ACCOUNT;
        b.hedgePlanId = PLAN_ID;
        b.exposureId = exposureId;
        b.coveredQty = coveredQty;
        b.status = "BOUND";
        return b;
    }

    private static Exposure exposure(long id, String exposureQty, String coveredQty) {
        Exposure e = new Exposure();
        e.id = id;
        e.subAccountId = SUB_ACCOUNT;
        e.productId = PRODUCT;
        e.spotCategoryCode = "CAT-CU";
        e.direction = "SELL";
        e.calcMode = "COMBINED";
        e.exposureQty = new BigDecimal(exposureQty);
        e.coveredQty = new BigDecimal(coveredQty);
        e.status = "PARTIALLY_COVERED";
        return e;
    }

    private static Authorization authorization(long userId, int subAccountId, long productId) {
        Authorization a = new Authorization();
        a.userId = userId;
        a.subAccountId = subAccountId;
        a.productId = productId;
        a.quotaLimit = new BigDecimal("1000000");
        a.effectiveFrom = LocalDate.of(2026, 1, 1);
        a.effectiveTo = null;
        a.status = "EFFECTIVE";
        return a;
    }

    private static SubProductWhitelist whitelist(int subAccountId, long productId) {
        SubProductWhitelist w = new SubProductWhitelist();
        w.subAccountId = subAccountId;
        w.productId = productId;
        w.status = "ENABLED";
        return w;
    }

    private static PurchaseContract purchaseContract(long id, LocalDate contractEndDate) {
        PurchaseContract c = new PurchaseContract();
        c.id = id;
        c.subAccountId = SUB_ACCOUNT;
        c.productId = PRODUCT;
        c.contractEndDate = contractEndDate;
        c.status = "ACTIVE";
        return c;
    }

    // ------------------------------------------------------------ 假 DAO

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
        public int insert(HedgePlan entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public HedgePlan findByIdScoped(long id, SubAccountScope scope) {
            return findById(id);
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
        public int deleteById(Long id) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("deleteById");
        }

        @Override
        public PageResult<HedgePlan> findPage(PageQuery query, String where, List<Object> whereArgs) {
            return new PageResult<>(new ArrayList<>(rows), rows.size(), query.page(), query.size());
        }
    }

    private static final class FakeHedgePlanExposureDao
            extends UnsupportedBaseDao<HedgePlanExposure, Long> implements HedgePlanExposureDao {
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
        public int insert(HedgePlanExposure entity, long operatorId) {
            rows.add(entity);
            return 1;
        }
    }

    private static final class FakeHedgeDocDao extends UnsupportedBaseDao<HedgeDoc, Long>
            implements HedgeDocDao {
        private final List<HedgeDoc> rows = new ArrayList<>();

        @Override
        public HedgeDoc findCurrent(long hedgePlanId) {
            for (HedgeDoc row : rows) {
                if (row.hedgePlanId == hedgePlanId && "Y".equals(row.isCurrent)) {
                    return row;
                }
            }
            return null;
        }
    }

    private static final class FakePlanApprovalDao extends UnsupportedBaseDao<PlanApproval, Long>
            implements PlanApprovalDao {
        private final List<PlanApproval> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(PlanApproval entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public PageResult<PlanApproval> findPage(PageQuery query, String where, List<Object> whereArgs) {
            long planId = whereArgs != null && !whereArgs.isEmpty() && whereArgs.get(0) instanceof Long
                    ? (Long) whereArgs.get(0) : -1L;
            List<PlanApproval> hit = new ArrayList<>();
            for (PlanApproval row : rows) {
                if (row.hedgePlanId != null && row.hedgePlanId == planId) {
                    hit.add(row);
                }
            }
            hit.sort((a, b) -> Integer.compare(b.nodeSeq == null ? 0 : b.nodeSeq,
                    a.nodeSeq == null ? 0 : a.nodeSeq));
            return new PageResult<>(hit, hit.size(), query.page(), query.size());
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
        public List<Exposure> findPendingHedge(int subAccountId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPendingHedge");
        }
    }

    private static final class FakeAuthorizationDao extends UnsupportedBaseDao<Authorization, Long>
            implements AuthorizationDao {
        private final List<Authorization> rows = new ArrayList<>();

        @Override
        public List<Authorization> findEffective(long userId, LocalDate onDate) {
            List<Authorization> out = new ArrayList<>();
            for (Authorization row : rows) {
                if (row.userId == userId && "EFFECTIVE".equals(row.status)
                        && (row.effectiveFrom == null || !row.effectiveFrom.isAfter(onDate))
                        && (row.effectiveTo == null || !row.effectiveTo.isBefore(onDate))) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<Authorization> findBySubAccount(int subAccountId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findBySubAccount");
        }

        @Override
        public int updateDispatchStatus(long id, String status, Long version) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("updateDispatchStatus");
        }
    }

    private static final class FakeSubProductWhitelistDao
            extends UnsupportedBaseDao<SubProductWhitelist, Long> implements SubProductWhitelistDao {
        private final List<SubProductWhitelist> rows = new ArrayList<>();

        @Override
        public List<SubProductWhitelist> findBySubAccount(int subAccountId) {
            List<SubProductWhitelist> out = new ArrayList<>();
            for (SubProductWhitelist row : rows) {
                if (row.subAccountId != null && row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<SubProductWhitelist> findByProduct(long productId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByProduct");
        }
    }

    /** 假敞口明细 DAO：按敞口返回预置明细；并登记「合同到期日」映射以供期限红线合同分支。 */
    private static final class FakeExposureDetailDao extends UnsupportedBaseDao<ExposureDetail, Long>
            implements ExposureDetailDao {
        private final List<ExposureDetail> byExposure = new ArrayList<>();

        @Override
        public List<ExposureDetail> findByExposure(long exposureId) {
            List<ExposureDetail> out = new ArrayList<>();
            for (ExposureDetail row : byExposure) {
                if (row.exposureId != null && row.exposureId == exposureId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<ExposureDetail> findBySource(String sourceType, long sourceId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findBySource");
        }
    }

    /** 假采购合同 DAO（{@code R-PLAN-3/④} 期限红线的实货合同到期日来源）。 */
    private static final class FakePurchaseContractDao extends UnsupportedBaseDao<PurchaseContract, Long>
            implements PurchaseContractDao {
        private final List<PurchaseContract> rows = new ArrayList<>();

        @Override
        public PurchaseContract findById(Long id) {
            for (PurchaseContract row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public PurchaseContract findByContractNo(int subAccountId, String contractNo) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByContractNo");
        }

        @Override
        public int deleteById(Long id) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("deleteById");
        }
    }
}
