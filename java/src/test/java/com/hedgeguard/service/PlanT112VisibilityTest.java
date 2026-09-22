package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.CustomHedgeRuleDao;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.HedgeRatioCalcDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.CustomHedgeRule;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.HedgeRatioCalc;
import com.hedgeguard.dao.entity.PlanStateLog;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.handler.plan.PlanHandler;
import com.hedgeguard.web.handler.plan.PlanRatioHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-112}：PLAN 写面「新建 / 无既有计划可查」入口的子账户可见性强制的验收（{@code ③④}）。
 *
 * <p>本类针对<b>没有 {@code planId}、因而无法走 {@code requireWritablePlan} 的写入口</b>：
 * <ul>
 *   <li><b>③</b> 比率三档 {@code calcSimple}/{@code calcMinVariance}/{@code calcCustomRule}
 *       —— 写 {@code t_hedge_ratio_calc}（带 {@code sub_account_id}）；</li>
 *   <li><b>④</b> {@code createPlan} —— 新建计划，{@code t_hedge_plan.sub_account_id} 为该表的
 *       <b>行级业务归属列</b>。</li>
 * </ul>
 *
 * <p>三类语义：<b>越权拒绝</b>（{@code 10004} / HTTP 403 / 零写入）、<b>合法放行</b>、
 * <b>fail-closed</b>（入口缺位 / 可见集为空 ⇒ 拒绝，不得 NPE）。含一组<b>真实 {@code AuthService}</b>
 * 端到端探针（长 {@code R-AUTH-8} 留痕）。
 *
 * <p>既有计划（{@code submit}/{@code withdraw}/{@code freeze}/…）走统一入口 {@code requireWritablePlan}，
 * 已由 {@code PlanWritePathPermissionTest}（{@code T-102}）覆盖，本类不重复。完全离线（{@code NFR-TEST-2}）。
 */
class PlanT112VisibilityTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");
    private static final long OPERATOR = 999L;
    private static final int VISIBLE_SUB = 7;
    private static final int OTHER_SUB = 8;

    private FakeHedgePlanDao planDao;
    private FakeBindDao bindDao;
    private FakeRatioCalcDao ratioDao;
    private FakeCustomRuleDao ruleDao;
    private FakeExposureDao exposureDao;
    private FakePlanStateLogDao stateLogDao;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        planDao = new FakeHedgePlanDao();
        bindDao = new FakeBindDao();
        ratioDao = new FakeRatioCalcDao();
        ruleDao = new FakeCustomRuleDao();
        exposureDao = new FakeExposureDao();
        stateLogDao = new FakePlanStateLogDao();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ============================================================ ③④ 越权拒绝 + 零写入

    @Test
    @DisplayName("T-112 越权：createPlan 写他人子账户 ⇒ 10004 / HTTP 403 且零写入")
    void createPlanCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                        .createPlan(plan(OTHER_SUB), Arrays.asList(bind(1L, "80")), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertEquals(403, e.errorCode().httpStatus(), "对外表现 HTTP 403");
        assertTrue(planDao.rows.isEmpty(), "越权被拒时不得产生计划行");
        assertTrue(bindDao.rows.isEmpty(), "越权被拒时不得产生绑定行");
        assertTrue(stateLogDao.rows.isEmpty(), "越权被拒时不得留痕状态流转");
    }

    @Test
    @DisplayName("T-112 越权：calcSimple 写他人子账户 ⇒ 10004 且零写入")
    void calcSimpleCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                        .calcSimple(OTHER_SUB, 1L, new BigDecimal("60"), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(ratioDao.rows.isEmpty(), "越权被拒时不得写比率留痕");
    }

    @Test
    @DisplayName("T-112 越权：calcMinVariance 写他人子账户 ⇒ 10004 且零写入")
    void calcMinVarianceCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB)).calcMinVariance(OTHER_SUB, 1L,
                        Arrays.asList(new BigDecimal("1")), Arrays.asList(new BigDecimal("1")), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(ratioDao.rows.isEmpty());
    }

    @Test
    @DisplayName("T-112 越权：calcCustomRule 写他人子账户 ⇒ 10004 且零写入")
    void calcCustomRuleCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB)).calcCustomRule(OTHER_SUB, 5L, "BUY",
                        null, LocalDate.of(2026, 1, 1), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(ratioDao.rows.isEmpty());
    }

    // ============================================================ ③④ 合法放行

    @Test
    @DisplayName("T-112 放行：对可见子账户 createPlan（含绑定）成功")
    void createPlanVisibleSucceeds() {
        exposureDao.rows.add(exposure(1L, VISIBLE_SUB, "BUY", "100", "0"));
        long id = service(uid -> Arrays.asList(VISIBLE_SUB))
                .createPlan(plan(VISIBLE_SUB), Arrays.asList(bind(1L, "80")), OPERATOR);
        assertTrue(id > 0, "可见子账户建计划应成功");
        assertEquals(1, planDao.rows.size());
        assertEquals(VISIBLE_SUB, planDao.rows.get(0).subAccountId);
    }

    @Test
    @DisplayName("T-112 放行：对可见子账户 calcSimple 成功（比率 60/100 = 0.6）")
    void calcSimpleVisibleSucceeds() {
        exposureDao.rows.add(exposure(1L, VISIBLE_SUB, "BUY", "100", "0"));
        HedgeRatioCalc calc = service(uid -> Arrays.asList(VISIBLE_SUB))
                .calcSimple(VISIBLE_SUB, 1L, new BigDecimal("60"), OPERATOR);
        assertEquals("SUCCESS", calc.resultCode);
        assertEquals(0, new BigDecimal("0.6").compareTo(calc.outputRatio));
        assertEquals(1, ratioDao.rows.size());
    }

    @Test
    @DisplayName("T-112 放行：对可见子账户 calcCustomRule 成功（命中规则取上限）")
    void calcCustomRuleVisibleSucceeds() {
        ruleDao.rows.add(rule(1L, VISIBLE_SUB, "0.8"));
        HedgeRatioCalc calc = service(uid -> Arrays.asList(VISIBLE_SUB))
                .calcCustomRule(VISIBLE_SUB, 5L, "BUY", null, LocalDate.of(2026, 1, 1), OPERATOR);
        assertEquals("SUCCESS", calc.resultCode);
        assertEquals(1, ratioDao.rows.size());
    }

    // ============================================================ fail-closed

    @Test
    @DisplayName("T-112 fail-closed：可见性入口缺位（null）⇒ 10004 且零写入")
    void visibilityMissingFailsClosed() {
        PlanService noVisibility = service(null);
        BizException e = assertThrows(BizException.class,
                () -> noVisibility.createPlan(plan(VISIBLE_SUB), Arrays.asList(bind(1L, "80")), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(planDao.rows.isEmpty(), "入口缺位时不得退化为放行");
    }

    @Test
    @DisplayName("T-112 fail-closed：可见集为空（无任何授权）⇒ 10004")
    void emptyVisibleSetFailsClosed() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> new ArrayList<Integer>())
                        .calcCustomRule(VISIBLE_SUB, 5L, "BUY", null, LocalDate.of(2026, 1, 1), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(ratioDao.rows.isEmpty(), "空可见集不得退化为全可见");
    }

    @Test
    @DisplayName("T-115 写面拒绝：跨账户只读（哨兵 0）⇒ 对任意子账户 createPlan 拒 10004 且零写入")
    void crossReadonlySentinelRejected() {
        // T-115：跨子账户只读（CROSS_READONLY ⇒ 哨兵 0）在读面放行、**写面必须拒绝**
        // （FR-AUTH-13 验收 3 / R-AUTH-5 / DATA_MODEL.md：CROSS_READONLY 只读性由服务层保证）。
        // 旧断言（放行、id>0）编码的是错误口径，已由本用例订正。
        exposureDao.rows.add(exposure(1L, OTHER_SUB, "BUY", "100", "0"));
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL))
                        .createPlan(plan(OTHER_SUB), Arrays.asList(bind(1L, "80")), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(),
                "跨账户只读不得写（10004）");
        assertTrue(planDao.rows.isEmpty(), "越权被拒时不得产生计划行");
        assertTrue(bindDao.rows.isEmpty(), "越权被拒时不得产生绑定行");
        assertTrue(stateLogDao.rows.isEmpty(), "越权被拒时不得留痕状态流转");
    }

    @Test
    @DisplayName("T-112 缺省 subAccountId（primitive int ⇒ 0）：给出可判定业务码，不得 NPE / 500")
    void missingSubAccountIdNotNpe() {
        PlanService svc = service(uid -> Arrays.asList(VISIBLE_SUB));
        // createPlan：0 由 requireSubAccount 前置拦下 ⇒ PARAM_INVALID（10001）
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> svc.createPlan(plan(0), Arrays.asList(bind(1L, "80")), OPERATOR)).errorCode());
        // 比率三档：0 ⇒ 可见性 fail-closed ⇒ SUB_ACCOUNT_PERMISSION_DENIED（10004，可判定）
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> svc.calcSimple(0, 1L, new BigDecimal("1"), OPERATOR)).errorCode());
        assertTrue(planDao.rows.isEmpty() && ratioDao.rows.isEmpty(), "缺省 subAccountId 不得产生任何写入");
    }

    @Test
    @DisplayName("T-112 handler 级：POST /plan 缺省 subAccountId ⇒ 10001；/plan/ratio/custom 缺省 ⇒ 10004（均非 500）")
    void handlerMissingSubAccountIdYieldsBusinessCode() {
        PlanService svc = service(uid -> Arrays.asList(VISIBLE_SUB));

        // ④ createPlan 经真实 PlanHandler：缺省 ⇒ 0 ⇒ 10001（非 NPE/500）
        RequestContext createCtx = ctx(PlanHandler.PATH,
                "{\"planType\":\"COMMODITY\",\"productId\":\"5\",\"hedgeDirection\":\"SELL_HEDGE\","
                        + "\"planQty\":\"100\",\"unit\":\"TON\",\"termStartDate\":\"2026-09-01\","
                        + "\"termEndDate\":\"2026-12-31\",\"hedgeRatio\":\"0.8\",\"ratioMethod\":\"SIMPLE\","
                        + "\"calcBaseDate\":\"2026-09-01\",\"binds\":[{\"exposureId\":1,"
                        + "\"coveredQty\":\"80\"}]}");
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> new PlanHandler(svc).handle(createCtx)).errorCode());

        // ③ 比率三档经真实 PlanRatioHandler：缺省 ⇒ 0 ⇒ 10004（可见性 fail-closed，非 NPE/500）
        RequestContext ratioCtx = ctx(PlanRatioHandler.Method.CUSTOM_RULE.path(),
                "{\"productId\":\"5\",\"direction\":\"BUY\",\"bizDate\":\"2026-01-01\"}");
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> new PlanRatioHandler(svc, PlanRatioHandler.Method.CUSTOM_RULE)
                        .handle(ratioCtx)).errorCode());

        assertTrue(planDao.rows.isEmpty() && ratioDao.rows.isEmpty(), "缺省 subAccountId 不得产生任何写入");
    }

    // ============================================================ 真实 AuthService 端到端探针
    @Test
    @DisplayName("T-112 端到端（真实 AuthService）：越权 calcCustomRule 被拒 10004 + 留痕 + 零写入")
    void realAuthServiceRejectsCrossSubAccountRatio() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        permDao.rows.add(perm(OPERATOR, VISIBLE_SUB, AuthService.PERM_LIMITED));
        PlanService svc = new PlanService(FakeTx.newTemplate(), planDao, bindDao, ratioDao, ruleDao,
                null, null, stateLogDao, null, exposureDao, null, authService(permDao, attemptDao),
                null, null, null, null, null, null);

        BizException e = assertThrows(BizException.class,
                () -> svc.calcCustomRule(OTHER_SUB, 5L, "BUY", null, LocalDate.of(2026, 1, 1), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(ratioDao.rows.isEmpty(), "越权被拒时业务库零写入");
        assertFalse(attemptDao.rows.isEmpty(), "越权尝试须留痕（R-AUTH-8）");
        assertEquals(AuthService.ATTEMPT_SUB_ACCOUNT_DENIED, attemptDao.rows.get(0).attemptType);

        // 合法：写自己可见子账户 ⇒ 通过
        ruleDao.rows.add(rule(1L, VISIBLE_SUB, "0.8"));
        HedgeRatioCalc ok = svc.calcCustomRule(VISIBLE_SUB, 5L, "BUY", null,
                LocalDate.of(2026, 1, 1), OPERATOR);
        assertEquals("SUCCESS", ok.resultCode, "真实 AuthService 下对可见子账户应放行");
    }

    // ============================================================ 辅助

    private PlanService service(SubAccountVisibility visibility) {
        return new PlanService(FakeTx.newTemplate(), planDao, bindDao, ratioDao, ruleDao,
                null, null, stateLogDao, null, exposureDao, null, visibility,
                null, null, null, null, null, null);
    }

    /** 构造带会话的请求上下文（{@code currentUserId()=OPERATOR}），驱动真实 handler。 */
    private static RequestContext ctx(String path, String body) {
        RequestContext ctx = new RequestContext("POST", path, null, Collections.emptyMap(), body,
                "HGT112", "127.0.0.1");
        ctx.bindSession(new Session("S-112", OPERATOR, "u", 1L, null));
        return ctx;
    }

    private static AuthService authService(FakePermDao permDao, FakeAttemptDao attemptDao) {
        return new AuthService(FakeTx.newTemplate(), null, null, null, null, null, null, null,
                permDao, attemptDao, null, null, null, null, null);
    }

    private static HedgePlan plan(int subAccountId) {
        HedgePlan plan = new HedgePlan();
        plan.subAccountId = subAccountId;
        plan.planType = "COMMODITY";
        plan.productId = 5L;
        plan.hedgeDirection = "SELL_HEDGE";   // 反向敞口 = BUY
        plan.planQty = new BigDecimal("100");
        plan.unit = "TON";
        plan.termStartDate = LocalDate.of(2026, 9, 1);
        plan.termEndDate = LocalDate.of(2026, 12, 31);
        plan.hedgeRatio = new BigDecimal("0.8");
        plan.ratioMethod = "SIMPLE";
        plan.calcBaseDate = LocalDate.of(2026, 9, 1);
        return plan;
    }

    private static PlanService.BindSpec bind(long exposureId, String qty) {
        return new PlanService.BindSpec(exposureId, new BigDecimal(qty));
    }

    private static Exposure exposure(long id, int subAccountId, String direction, String qty,
                                     String covered) {
        Exposure e = new Exposure();
        e.id = id;
        e.subAccountId = subAccountId;
        e.productId = 5L;
        e.direction = direction;
        e.unmappedFlag = "N";
        e.exposureQty = new BigDecimal(qty);
        e.coveredQty = new BigDecimal(covered);
        return e;
    }

    private static CustomHedgeRule rule(long id, int subAccountId, String cap) {
        CustomHedgeRule r = new CustomHedgeRule();
        r.id = id;
        r.ruleCode = "R-" + id;
        r.ruleName = "内控 " + cap;
        r.basis = "内控";
        r.subAccountId = subAccountId;
        r.ratioCap = new BigDecimal(cap);
        r.status = "ENABLED";
        return r;
    }

    private static UserSubAccountPerm perm(long userId, int subAccountId, String permType) {
        UserSubAccountPerm perm = new UserSubAccountPerm();
        perm.userId = userId;
        perm.subAccountId = subAccountId;
        perm.permType = permType;
        perm.grantedBy = 1L;
        perm.effectiveFrom = LocalDate.of(2026, 1, 1);
        return perm;
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakeHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {

        private final List<HedgePlan> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(HedgePlan entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
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
        public int deleteById(Long id) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("deleteById");
        }
    }

    private static final class FakeBindDao extends UnsupportedBaseDao<HedgePlanExposure, Long>
            implements HedgePlanExposureDao {

        private final List<HedgePlanExposure> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(HedgePlanExposure entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public List<HedgePlanExposure> findByPlan(long hedgePlanId) {
            return new ArrayList<>();
        }
    }

    private static final class FakeRatioCalcDao extends UnsupportedBaseDao<HedgeRatioCalc, Long>
            implements HedgeRatioCalcDao {

        private final List<HedgeRatioCalc> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(HedgeRatioCalc entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }
    }

    private static final class FakeCustomRuleDao extends UnsupportedBaseDao<CustomHedgeRule, Long>
            implements CustomHedgeRuleDao {

        private final List<CustomHedgeRule> rows = new ArrayList<>();

        @Override
        public List<CustomHedgeRule> findEffective(int subAccountId, long productId, String direction,
                                                   LocalDate bizDate) {
            List<CustomHedgeRule> out = new ArrayList<>();
            for (CustomHedgeRule row : rows) {
                if (row.subAccountId != null && row.subAccountId == subAccountId) {
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
        public List<Exposure> findPendingHedge(int subAccountId) {
            return new ArrayList<>();
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

    private static final class FakePermDao extends UnsupportedBaseDao<UserSubAccountPerm, Long>
            implements UserSubAccountPermDao {

        private final List<UserSubAccountPerm> rows = new ArrayList<>();

        @Override
        public List<UserSubAccountPerm> findByUser(long userId) {
            List<UserSubAccountPerm> out = new ArrayList<>();
            for (UserSubAccountPerm row : rows) {
                if (row.userId == userId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    private static final class FakeAttemptDao
            extends UnsupportedBaseDao<UnauthorizedAttempt, Long> implements UnauthorizedAttemptDao {

        private final List<UnauthorizedAttempt> rows = new ArrayList<>();

        @Override
        public int insert(UnauthorizedAttempt entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public long countByTypeBetween(String attemptType, java.time.LocalDateTime from,
                                       java.time.LocalDateTime to) {
            return 0;
        }

        @Override
        public List<UnauthorizedAttempt> findByOperatorSince(long operatorId,
                                                            java.time.LocalDateTime from) {
            return new ArrayList<>(rows);
        }
    }
}
