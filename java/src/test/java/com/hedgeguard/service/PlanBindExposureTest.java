package com.hedgeguard.service;

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
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanService#bindExposure}（{@code FR-PLAN-19}；端点 {@code POST /api/v1/plan/{id}/bind-exposure}）
 * 的<b>核心规则</b>单元测试 —— {@code T-023} 补测（消除 {@code TE-T023-001} 覆盖盲区）。
 *
 * <p><b>为何单独成类</b>：该端点写 {@code t_hedge_plan_exposure} 并联动 {@code t_exposure.covered_qty/status}，
 * 承载 {@code FR-PLAN-19} 验收 3 的核心规则（"覆盖量之和不得超敞口未覆盖量"）。此前 {@code src/test}
 * 对它<b>零引用</b>（{@code grep -rn bindExposure java/src/test} = 0），致该规则可被整条删除而全量用例仍全绿
 * ——属"类级 {@code LINE%} ≥ 80% 掩盖零覆盖方法"的假绿盲区。
 *
 * <p>本类逐条覆盖：
 * <ul>
 *   <li>①<b>正常路径</b>：覆盖量 ≤ 未覆盖量 ⇒ 成功；{@code t_hedge_plan_exposure}（{@code status=BOUND}）与
 *       敞口 {@code coveredQty}/{@code status}（{@code PARTIALLY_COVERED} / {@code COVERED}）落库正确；</li>
 *   <li>②<b>{@code PlanService.java} 覆盖上限规则</b>：覆盖量 &gt; 未覆盖量 ⇒ 拒，且断言含<b>具名原因</b>
 *       （"覆盖数量超过敞口未覆盖数量" / "FR-PLAN-19 验收 3"），<b>非</b>仅 {@code assertThrows}；</li>
 *   <li>③<b>{@code PlanService.java} 正数规则</b>：覆盖量 ≤ 0（含 {@code null}）⇒ 拒，含具名原因；</li>
 *   <li>另含草稿态约束（{@code FR-PLAN-19} 验收 4）、方向相反（{@code R-PLAN-1}）、同品种（{@code R-PLAN-7}）、
 *       计划量上限、空 {@code binds}、边界等于未覆盖量等守卫。</li>
 * </ul>
 *
 * <p><b>承重性</b>：把 {@code PlanService} 覆盖上限判定改为恒假（{@code … > 0 && false}），
 * {@link #overCoverageRejectedWithNamedReason()} 必变红（详见改单回执的隔离副本变异自证）。
 *
 * <p>完全离线（{@code NFR-TEST-2}：不引 Mockito、不连 DM），撮合以内存假 DAO 替代真实 SQL。
 */
class PlanBindExposureTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");
    private static final int SUB = 3;
    private static final long PRODUCT = 5L;
    private static final long OPERATOR = 1L;
    private static final long PLAN_ID = 7L;

    private FakeHedgePlanDao planDao;
    private FakeBindDao bindDao;
    private FakePlanStateLogDao stateLogDao;
    private FakeExposureDao exposureDao;
    private PlanService service;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        planDao = new FakeHedgePlanDao();
        bindDao = new FakeBindDao();
        stateLogDao = new FakePlanStateLogDao();
        exposureDao = new FakeExposureDao();
        service = service(uid -> Collections.singletonList(SUB));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ============================================================ ① 正常路径

    @Test
    @DisplayName("① bindExposure 正常：覆盖量 ≤ 未覆盖量 ⇒ 成功；绑定行 + 敞口 coveredQty/status 落库正确")
    void normalPathBindsAndUpdatesExposure() {
        planDao.rows.add(draftPlan(PLAN_ID, "BUY_HEDGE"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        int bound = service.bindExposure(PLAN_ID,
                Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("80"))), OPERATOR);

        assertEquals(1, bound, "新增绑定条数 = 入参条数");
        assertEquals(1, bindDao.rows.size(), "须落一条 t_hedge_plan_exposure");
        HedgePlanExposure row = bindDao.rows.get(0);
        assertEquals("BOUND", row.status, "绑定行状态须为 BOUND");
        assertEquals(PLAN_ID, row.hedgePlanId);
        assertEquals(1L, row.exposureId);
        assertEquals(SUB, row.subAccountId);
        assertEquals(0, new BigDecimal("80").compareTo(row.coveredQty));
        assertEquals(Clocks.now(), row.bindTime);

        // 敞口联动：covered_qty += 80，未覆盖 = 100 − 80 = 20 > 0 ⇒ PARTIALLY_COVERED
        Exposure stored = exposureDao.findById(1L);
        assertEquals(0, new BigDecimal("80").compareTo(stored.coveredQty), "敞口已覆盖量须累加");
        assertEquals("PARTIALLY_COVERED", stored.status, "部分覆盖 ⇒ PARTIALLY_COVERED");

        // 状态流转留痕（FR-PLAN-19：草稿态增删绑定须留痕）
        assertEquals(1, stateLogDao.rows.size(), "绑定须写状态流转留痕");
        assertEquals("绑定实货敞口 1 条（FR-PLAN-19）", stateLogDao.rows.get(0).reason);
    }

    @Test
    @DisplayName("① bindExposure 正常：覆盖量 = 未覆盖量（边界）⇒ 敞口置 COVERED；二次绑定余量为 0 时任何正数被拒")
    void fullCoverageBoundaryThenNoRoom() {
        planDao.rows.add(draftPlan(PLAN_ID, "BUY_HEDGE", "200"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        int bound = service.bindExposure(PLAN_ID,
                Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("100"))), OPERATOR);
        assertEquals(1, bound);
        Exposure stored = exposureDao.findById(1L);
        assertEquals(0, new BigDecimal("100").compareTo(stored.coveredQty));
        assertEquals("COVERED", stored.status, "全覆盖 ⇒ COVERED");

        // 未覆盖量已为 0 ⇒ 再绑任何正数都越界（② 规则在余量 0 时的表现）
        BizException e = assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID,
                        Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("1"))), OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("覆盖数量超过敞口未覆盖数量"), e.getMessage());
        assertEquals(1, bindDao.rows.size(), "被拒不得新增绑定行");
    }

    @Test
    @DisplayName("① bindExposure 正常：多条 binds 一次绑定，合计与计划量校验通过")
    void multipleBindsInOneCall() {
        planDao.rows.add(draftPlan(PLAN_ID, "BUY_HEDGE"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));
        exposureDao.rows.add(exposure(2L, "SELL", "100"));

        int bound = service.bindExposure(PLAN_ID, Arrays.asList(
                new PlanService.BindSpec(1L, new BigDecimal("30")),
                new PlanService.BindSpec(2L, new BigDecimal("50"))), OPERATOR);

        assertEquals(2, bound);
        assertEquals(2, bindDao.rows.size());
        assertEquals(0, new BigDecimal("30").compareTo(exposureDao.findById(1L).coveredQty));
        assertEquals(0, new BigDecimal("50").compareTo(exposureDao.findById(2L).coveredQty));
        assertEquals("绑定实货敞口 2 条（FR-PLAN-19）", stateLogDao.rows.get(0).reason);
    }

    // ============================================================ ② 覆盖上限规则（承重）

    @Test
    @DisplayName("② bindExposure 覆盖上限规则：覆盖量 > 未覆盖量 ⇒ 拒 PARAM_INVALID 且含具名原因（FR-PLAN-19 验收 3），零写入")
    void overCoverageRejectedWithNamedReason() {
        // 计划量给足（1000 > 150），使唯一被违反的规则就是「覆盖量 > 未覆盖量」（隔离 :661，不被计划量上限规则代偿）
        planDao.rows.add(draftPlan(PLAN_ID, "BUY_HEDGE", "1000"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        BizException e = assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID,
                        Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("150"))), OPERATOR));

        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(), "越界须 10001");
        String msg = e.getMessage();
        assertTrue(msg.contains("覆盖数量超过敞口未覆盖数量"),
                "须给具名原因（不得只 assertThrows）：" + msg);
        assertTrue(msg.contains("FR-PLAN-19 验收 3"), "须点明需求依据：" + msg);
        assertTrue(msg.contains("150"), "须回显请求覆盖量：" + msg);
        assertTrue(msg.contains("100"), "须回显敞口未覆盖量：" + msg);

        assertTrue(bindDao.rows.isEmpty(), "被拒时不得写绑定行");
        assertTrue(stateLogDao.rows.isEmpty(), "被拒时不得留痕状态流转");
        assertEquals(0, BigDecimal.ZERO.compareTo(exposureDao.findById(1L).coveredQty),
                "被拒时不得改动敞口已覆盖量");
    }

    @Test
    @DisplayName("② bindExposure 覆盖上限规则：已覆盖部分时，按『剩余未覆盖量』判定（100 已覆盖 60 ⇒ 再绑 50 拒、绑 40 过）")
    void overCoverageUsesRemainingUncovered() {
        planDao.rows.add(draftPlan(PLAN_ID, "BUY_HEDGE"));
        Exposure ex = exposure(1L, "SELL", "100");
        ex.coveredQty = new BigDecimal("60");
        ex.status = "PARTIALLY_COVERED";
        exposureDao.rows.add(ex);

        BizException over = assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID,
                        Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("50"))), OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, over.errorCode());
        assertTrue(over.getMessage().contains("覆盖数量超过敞口未覆盖数量"), over.getMessage());
        assertEquals(0, new BigDecimal("60").compareTo(exposureDao.findById(1L).coveredQty),
                "被拒不得改动");

        int ok = service.bindExposure(PLAN_ID,
                Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("40"))), OPERATOR);
        assertEquals(1, ok, "剩余未覆盖量 40 应恰好允许");
        assertEquals(0, new BigDecimal("100").compareTo(exposureDao.findById(1L).coveredQty));
        assertEquals("COVERED", exposureDao.findById(1L).status);
    }

    // ============================================================ ③ 正数规则

    @Test
    @DisplayName("③ bindExposure 正数规则：覆盖量 ≤ 0（含 null）⇒ 拒 PARAM_INVALID 且含具名原因，零写入")
    void nonPositiveCoverageRejected() {
        planDao.rows.add(draftPlan(PLAN_ID, "BUY_HEDGE"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        for (BigDecimal bad : Arrays.asList(BigDecimal.ZERO, new BigDecimal("-5"), null)) {
            BizException e = assertThrows(BizException.class,
                    () -> service.bindExposure(PLAN_ID,
                            Arrays.asList(new PlanService.BindSpec(1L, bad)), OPERATOR),
                    "覆盖量=" + bad + " 应被拒");
            assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
            assertTrue(e.getMessage().contains("绑定覆盖数量必须 > 0"),
                    "须给具名原因：" + e.getMessage());
        }
        assertTrue(bindDao.rows.isEmpty(), "被拒时不得写绑定行");
        assertTrue(stateLogDao.rows.isEmpty(), "被拒时不得留痕");
        assertEquals(0, BigDecimal.ZERO.compareTo(exposureDao.findById(1L).coveredQty));
    }

    // ============================================================ 其它核心守卫（FR-PLAN-19 / R-PLAN-*）

    @Test
    @DisplayName("守卫：非草稿态不得直接增删绑定（FR-PLAN-19 验收 4 / R-PLAN-6）⇒ STATE_NOT_ALLOWED")
    void nonDraftPlanRejected() {
        HedgePlan plan = draftPlan(PLAN_ID, "BUY_HEDGE");
        plan.status = "APPROVED";
        planDao.rows.add(plan);
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        BizException e = assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID,
                        Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("10"))), OPERATOR));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode());
        assertTrue(e.getMessage().contains("FR-PLAN-19 验收 4"), e.getMessage());
        assertTrue(bindDao.rows.isEmpty());
    }

    @Test
    @DisplayName("守卫：空 binds / null 计划 / 方向不相反 / 跨子账户 / 品种不一致 均拒（R-PLAN-1 / R-PLAN-7）")
    void structuralGuardsRejected() {
        planDao.rows.add(draftPlan(PLAN_ID, "BUY_HEDGE"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));

        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID, new ArrayList<>(), OPERATOR)).errorCode(),
                "空 binds 应拒");
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID, null, OPERATOR)).errorCode(),
                "null binds 应拒");
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> service.bindExposure(999L,
                        Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("10"))), OPERATOR))
                .errorCode(), "计划不存在应拒");

        // 方向不相反：BUY_HEDGE 的敞口须 SELL，这里给 BUY
        exposureDao.rows.add(exposure(2L, "BUY", "100"));
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID,
                        Arrays.asList(new PlanService.BindSpec(2L, new BigDecimal("10"))), OPERATOR))
                .errorCode(), "方向须相反（R-PLAN-1）");

        // 跨子账户
        Exposure other = exposure(3L, "SELL", "100");
        other.subAccountId = 99;
        exposureDao.rows.add(other);
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID,
                        Arrays.asList(new PlanService.BindSpec(3L, new BigDecimal("10"))), OPERATOR))
                .errorCode(), "跨子账户须拒（R-PLAN-7）");

        // 品种不一致
        Exposure otherProduct = exposure(4L, "SELL", "100");
        otherProduct.productId = 77L;
        exposureDao.rows.add(otherProduct);
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID,
                        Arrays.asList(new PlanService.BindSpec(4L, new BigDecimal("10"))), OPERATOR))
                .errorCode(), "品种不一致须拒（R-PLAN-7）");

        assertTrue(bindDao.rows.isEmpty(), "全部被拒时零写入");
    }

    @Test
    @DisplayName("守卫：绑定合计不得超计划数量（FR-PLAN-1）；已有绑定计入合计")
    void totalBoundCannotExceedPlanQty() {
        // 计划量 80；已有绑定 50（BOUND）⇒ 再绑 40 合计 90 > 80 应拒；绑 30 过
        planDao.rows.add(draftPlan(PLAN_ID, "BUY_HEDGE"));
        exposureDao.rows.add(exposure(1L, "SELL", "100"));
        exposureDao.rows.add(exposure(2L, "SELL", "100"));
        HedgePlanExposure existing = new HedgePlanExposure();
        existing.id = 1L;
        existing.subAccountId = SUB;
        existing.hedgePlanId = PLAN_ID;
        existing.exposureId = 1L;
        existing.coveredQty = new BigDecimal("50");
        existing.status = "BOUND";
        bindDao.rows.add(existing);

        BizException e = assertThrows(BizException.class,
                () -> service.bindExposure(PLAN_ID,
                        Arrays.asList(new PlanService.BindSpec(2L, new BigDecimal("40"))), OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("绑定覆盖量合计不得超过计划数量"), e.getMessage());

        assertEquals(1, service.bindExposure(PLAN_ID,
                Arrays.asList(new PlanService.BindSpec(2L, new BigDecimal("30"))), OPERATOR),
                "合计 80 = 计划量应恰好允许");
    }

    // ============================================================ 辅助

    private PlanService service(SubAccountVisibility visibility) {
        return new PlanService(FakeTx.newTemplate(), planDao, bindDao, null, null,
                null, null, stateLogDao, null, exposureDao, null, visibility,
                null, null, null, null, null, null);
    }

    private static HedgePlan draftPlan(long id, String hedgeDirection) {
        return draftPlan(id, hedgeDirection, "80");
    }

    private static HedgePlan draftPlan(long id, String hedgeDirection, String planQty) {
        HedgePlan plan = new HedgePlan();
        plan.id = id;
        plan.subAccountId = SUB;
        plan.planType = "COMMODITY";
        plan.productId = PRODUCT;
        plan.hedgeDirection = hedgeDirection;
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
