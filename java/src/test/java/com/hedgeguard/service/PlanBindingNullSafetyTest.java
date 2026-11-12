package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.PlanApprovalDao;
import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.PurchaseContractDao;
import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-236}：{@link PlanService} 全部 {@code findByPlan} 调用点（6 处）在
 * 取数返回 {@code null} 时的统一收口回归。
 *
 * <p>生产 DAO 恒返回非 null（{code DmHedgePlanExposureDao.findByPlan} →
 * {@code AbstractDmDao.queryList} 恒 {@code new ArrayList<>()}），本类用动态代理假 DAO
 * 令 {@code findByPlan} 返回 null，模拟离线桩 / 自定义代理，验证：
 * ① 5 处聚合 / 展示 / 只读路径经 {@code bindingsOf} 规范化为「无绑定行」、不抛 NPE；
 * ② 安全校验入口 {@code requireBoundExposureConsistency} 仍按 O-1 独立复核形态
 * fail-closed 抛 {@code DATA_INSUFFICIENT}（未被 helper 破坏）。
 *
 * <p>完全离线（{@code NFR-TEST-2}）：{@link FakeTx} + 动态代理假 DAO，不连 DM、不引 Mockito。
 */
class PlanBindingNullSafetyTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");
    private static final int SUB = 3;
    private static final long PRODUCT = 5L;
    private static final long OPERATOR = 1L;
    private static final long PLAN_ID = 7L;

    private HedgePlan plan;
    private HedgeDoc doc;
    private Exposure exposure;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        plan = new HedgePlan();
        plan.id = PLAN_ID;
        plan.subAccountId = SUB;
        plan.planNo = "HP-T236";
        plan.planType = "COMMODITY";
        plan.productId = PRODUCT;
        plan.hedgeDirection = "BUY_HEDGE";
        plan.planQty = new BigDecimal("80");
        plan.unit = "TON";
        plan.status = "DRAFT";
        plan.termStartDate = LocalDate.of(2026, 9, 20);
        plan.termEndDate = LocalDate.of(2026, 11, 30);
        plan.hedgeRatio = new BigDecimal("0.8");
        plan.ratioMethod = "SIMPLE";
        plan.calcBaseDate = LocalDate.of(2026, 9, 20);
        plan.currentDocVersion = 1;
        plan.applicantId = OPERATOR;

        doc = new HedgeDoc();
        doc.id = 11L;
        doc.docNo = "DOC-T236";
        doc.docVersion = 1;
        doc.isCurrent = "Y";
        doc.elemFormalDesignation = "正式指定";
        doc.elemEffectivenessMethod = "有效性评估方法";
        doc.elemRebalanceMechanism = "再平衡机制";
        doc.pendingExternalReview = "Y";
        doc.generatedTime = LocalDateTime.of(2026, 9, 20, 10, 0);

        exposure = new Exposure();
        exposure.id = 1L;
        exposure.subAccountId = SUB;
        exposure.productId = PRODUCT;
        exposure.direction = "SELL";
        exposure.unmappedFlag = "N";
        exposure.exposureQty = new BigDecimal("100");
        exposure.coveredQty = BigDecimal.ZERO;
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ============================================================ 6 处收口

    @Test
    @DisplayName(":1890 listBindings（FR-PLAN-5 展示）findByPlan=null ⇒ 空列表，不 NPE")
    void listBindingsNormalizesNull() {
        List<HedgePlanExposure> result = service().listBindings(PLAN_ID);
        assertNotNull(result, "须规范化为非 null 列表");
        assertTrue(result.isEmpty(), "null 应视为「无绑定行」");
    }

    @Test
    @DisplayName(":1036 generateHedgeDoc（CAS 24 书面文件）findByPlan=null ⇒ 受控 PARAM_INVALID，不 NPE")
    void generateHedgeDocRejectsWhenNull() {
        BizException e = assertThrows(BizException.class,
                () -> service().generateHedgeDoc(PLAN_ID, "正式指定", "有效性评估方法",
                        "再平衡机制", "CU2611", OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("未绑定敞口"), e.getMessage());
    }

    @Test
    @DisplayName(":955 bindExposure（FR-PLAN-19 写面）findByPlan=null ⇒ 不 NPE，正常完成")
    void bindExposureNoNpeWhenNull() {
        assertDoesNotThrow(() -> service().bindExposure(PLAN_ID,
                Collections.singletonList(new PlanService.BindSpec(1L, new BigDecimal("10"))),
                OPERATOR));
    }

    @Test
    @DisplayName(":1299 contractHorizon / :1328 requireQuantity（六项预校验）findByPlan=null ⇒ 受控拒绝，不 NPE")
    void submitForApprovalNoNpeWhenNull() {
        // 走到 R-PLAN-3/⑤ 才说明 ④ 的 contractHorizon 与 ⑤ 的 requireQuantity 两处
        // bindingsOf 都生效；若任何一处直接 for-each(null)，此处会是 NullPointerException
        // 而非 BizException（assertThrows 会因异常类型不符而失败）。
        BizException e = assertThrows(BizException.class,
                () -> service().submitForApproval(PLAN_ID, null, OPERATOR));
        assertTrue(e.getMessage().contains("R-PLAN-3/⑤"), e.getMessage());
    }

    @Test
    @DisplayName(":836 requireBoundExposureConsistency（O-1 回归）findByPlan=null ⇒ 仍 DATA_INSUFFICIENT")
    void safetyGateStillFailsClosedWhenNull() {
        HedgePlan patch = new HedgePlan();
        patch.hedgeDirection = "SELL_HEDGE";
        BizException e = assertThrows(BizException.class,
                () -> service().updateDraft(PLAN_ID, patch, OPERATOR));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, e.errorCode(),
                "helper 不得把安全校验入口的 fail-closed 语义改成放行");
    }

    @Test
    @DisplayName("OBS-2 收口：DAO 引用为 null ⇒ 受控 DATA_INSUFFICIENT（不 NPE、不静默放行）")
    void bindingsOfFailsClosedWhenDaoMissing() {
        BizException e = assertThrows(BizException.class,
                () -> service(false).listBindings(PLAN_ID));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, e.errorCode());
        assertTrue(e.getMessage().contains("未装配"), e.getMessage());
    }

    @Test
    @DisplayName("OBS-2 收口：generateHedgeDoc 在 DAO 缺件时不误判为「计划未绑定敞口」")
    void generateHedgeDocFailsClosedWhenDaoMissing() {
        BizException e = assertThrows(BizException.class,
                () -> service(false).generateHedgeDoc(PLAN_ID, "正式指定", "有效性评估方法",
                        "再平衡机制", "CU2611", OPERATOR));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, e.errorCode());
        assertTrue(e.getMessage().contains("未装配"), e.getMessage());
    }

    // ============================================================ 装配 / 假件

    private PlanService service() {
        return service(true);
    }

    private PlanService service(boolean withBindDao) {
        HedgePlanDao planDao = stub(HedgePlanDao.class,
                overrides("findByIdScoped", plan, "findById", plan, "updateById", 1));
        HedgePlanExposureDao bindDao = withBindDao
                ? stub(HedgePlanExposureDao.class, overrides("findByPlan", null)) : null;
        HedgeDocDao docDao = stub(HedgeDocDao.class,
                overrides("findCurrent", doc));
        PlanApprovalDao approvalDao = stub(PlanApprovalDao.class, Collections.emptyMap());
        PlanStateLogDao stateLogDao = stub(PlanStateLogDao.class, Collections.emptyMap());
        ExposureDao exposureDao = stub(ExposureDao.class,
                overrides("findById", exposure, "updateById", 1));
        AuthorizationDao authDao = stub(AuthorizationDao.class,
                overrides("findEffective", Collections.singletonList(authorization())));
        SubProductWhitelistDao whitelistDao = stub(SubProductWhitelistDao.class,
                overrides("findBySubAccount", Collections.singletonList(whitelist())));
        ExposureDetailDao detailDao = stub(ExposureDetailDao.class, Collections.emptyMap());
        PurchaseContractDao purchaseDao = stub(PurchaseContractDao.class, Collections.emptyMap());

        return new PlanService(FakeTx.newTemplate(), planDao, bindDao, null, null, docDao,
                approvalDao, stateLogDao, null, exposureDao, null,
                userId -> Collections.singletonList(SUB),
                authDao, whitelistDao, detailDao, purchaseDao, null, userId -> false);
    }

    private static Authorization authorization() {
        Authorization a = new Authorization();
        a.subAccountId = SUB;
        a.productId = PRODUCT;
        a.status = "EFFECTIVE";
        return a;
    }

    private static SubProductWhitelist whitelist() {
        SubProductWhitelist w = new SubProductWhitelist();
        w.subAccountId = SUB;
        w.productId = PRODUCT;
        w.status = "ENABLED";
        return w;
    }

    private static Map<String, Object> overrides(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    /**
     * 通用动态代理假 DAO：{@code findByPlan} 按 overrides 返回（本单恒 null）；
     * 集合类返回空列表、{@code int} 返回 1（写操作成功）、其余返回 null。
     */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Object> byMethod) {
        return (T) Proxy.newProxyInstance(
                PlanBindingNullSafetyTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "toString":
                                return "stub:" + type.getSimpleName();
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return null;
                        }
                    }
                    if (byMethod.containsKey(method.getName())) {
                        return byMethod.get(method.getName());
                    }
                    Class<?> rt = method.getReturnType();
                    if (List.class.isAssignableFrom(rt)) {
                        return new ArrayList<>();
                    }
                    if (rt == int.class) {
                        return 1;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }
}
