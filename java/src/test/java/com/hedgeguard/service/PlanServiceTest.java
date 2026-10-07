package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.CustomHedgeRuleDao;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.HedgeRatioCalcDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PlanApprovalDao;
import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.entity.CustomHedgeRule;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.HedgeRatioCalc;
import com.hedgeguard.dao.entity.PlanApproval;
import com.hedgeguard.dao.entity.PlanStateLog;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.entity.TradePlan;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanService} 单元测试（{@code T-015} 比率三档 + {@code T-016} 计划编制 / 门禁 / 审批链 / 状态机）。
 *
 * <p>重点：①最小方差法样本 &lt; 30 <b>拒绝且不返回比率</b>；②自定义规则法取一算法确定性
 * （取最严 + 多套叠加不叠加计算）；③书面文件三要素门禁（{@code 10012} 四项齐备）；
 * ④发起人 ≠ 审批人；⑤额度随状态占用/释放（{@code t_plan_state_log}）。
 */
class PlanServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");

    private FakeHedgePlanDao planDao;
    private FakeHedgePlanExposureDao bindDao;
    private FakeRatioCalcDao ratioDao;
    private FakeCustomRuleDao ruleDao;
    private FakeHedgeDocDao docDao;
    private FakePlanApprovalDao approvalDao;
    private FakePlanStateLogDao stateLogDao;
    private FakeTradePlanDao tradePlanDao;
    private FakeExposureDao exposureDao;
    private FakeProductContractDao contractDao;
    private FakeAuthorizationDao authDao;
    private FakeSubProductWhitelistDao whitelistDao;
    private PlanService service;
    private long exposureSeq = 1L;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        planDao = new FakeHedgePlanDao();
        bindDao = new FakeHedgePlanExposureDao();
        ratioDao = new FakeRatioCalcDao();
        ruleDao = new FakeCustomRuleDao();
        docDao = new FakeHedgeDocDao();
        approvalDao = new FakePlanApprovalDao();
        stateLogDao = new FakePlanStateLogDao();
        tradePlanDao = new FakeTradePlanDao();
        exposureDao = new FakeExposureDao();
        contractDao = new FakeProductContractDao();
        authDao = new FakeAuthorizationDao();
        whitelistDao = new FakeSubProductWhitelistDao();
        // 默认给「发起人 1 / 子账户 3 / 品种 5」配一条有效操作授权与白名单行，使提交路径可通过 R-PLAN-3 六项。
        authDao.rows.add(authorization(1L, 3, 5L));
        whitelistDao.rows.add(whitelist(3, 5L));
        service = new PlanService(FakeTx.newTemplate(), planDao, bindDao, ratioDao, ruleDao, docDao,
                approvalDao, stateLogDao, tradePlanDao, exposureDao, contractDao,
                // T-115：业务规则用例（非越权用例）——注入「可写子账户 3」的可见性替身。
                // 原注入哨兵 0（跨账户只读）在 T-115 后**不再授予写权限**，故改为具体子账户 3。
                userId -> Collections.singletonList(3),
                authDao, whitelistDao, null, null, null, userId -> false);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
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

    // ------------------------------------------------------------ T-015 比率

    @Test
    @DisplayName("简单比率法：正常得比值；越界记 RATIO_OUT_OF_RANGE 且比率为 0（失败不返回有效比率）")
    void simpleRatio() {
        exposureDao.rows.add(exposure(1L, "SELL", "100"));
        HedgeRatioCalc ok = service.calcSimple(3, 1L, new BigDecimal("80"), 1L);
        assertEquals("SUCCESS", ok.resultCode);
        assertEquals(0, new BigDecimal("0.8").compareTo(ok.outputRatio));
        assertEquals("SIMPLE", ok.ratioMethod);
        assertNotNull(ok.inputJson, "输入快照须留痕（可复算）");

        HedgeRatioCalc over = service.calcSimple(3, 1L, new BigDecimal("150"), 1L);
        assertEquals("RATIO_OUT_OF_RANGE", over.resultCode);
        assertEquals(0, BigDecimal.ZERO.compareTo(over.outputRatio), "失败时比率为 0");
        assertNotNull(over.failDetail);
        assertEquals(2, ratioDao.rows.size(), "两次计算各留痕一条（含失败）");
    }

    @Test
    @DisplayName("最小方差法：样本 < 30 拒绝且不返回比率；样本充足且序列一致时 h = ρσS/σF = 1")
    void minVariance() {
        exposureDao.rows.add(exposure(1L, "SELL", "1000"));
        List<BigDecimal> tooShort = new ArrayList<>();
        for (int i = 0; i < PlanService.MIN_VARIANCE_MIN_SAMPLES - 1; i++) {
            tooShort.add(new BigDecimal(70000 + i));
        }
        HedgeRatioCalc insufficient = service.calcMinVariance(3, 1L, tooShort, tooShort, 1L);
        assertEquals("INSUFFICIENT_SAMPLE", insufficient.resultCode);
        assertEquals(0, BigDecimal.ZERO.compareTo(insufficient.outputRatio),
                "样本不足不得返回任何比率结果");
        assertTrue(insufficient.failDetail.contains(String.valueOf(tooShort.size())),
                "须列明实际样本数：" + insufficient.failDetail);

        assertEquals("INSUFFICIENT_SAMPLE",
                service.calcMinVariance(3, 1L, tooShort, new ArrayList<>(), 1L).resultCode,
                "序列长度不一致须拒绝（不得静默跳过缺失日）");

        List<BigDecimal> series = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            series.add(new BigDecimal(70000 + i * 10));
        }
        HedgeRatioCalc ok = service.calcMinVariance(3, 1L, series, series, 1L);
        assertEquals("SUCCESS", ok.resultCode, ok.failDetail);
        assertEquals(40, ok.sampleCount);
        assertEquals(0, BigDecimal.ONE.compareTo(ok.rho), "同序列 ρ = 1");
        assertEquals(0, BigDecimal.ONE.compareTo(ok.outputRatio), "ρ=1 且 σS=σF → h = 1");
    }

    @Test
    @DisplayName("自定义规则法：无规则拒绝；多套规则叠加取最严（不叠加计算）；超上限拒绝")
    void customRulePicksStrictest() {
        HedgeRatioCalc missing = service.calcCustomRule(3, 5L, "SELL", null,
                LocalDate.of(2026, 9, 20), 1L);
        assertEquals("RULE_MISSING", missing.resultCode);
        assertEquals(0, BigDecimal.ZERO.compareTo(missing.outputRatio), "无规则不得默认放行");

        // 模拟 DAO 已按 ratio_cap ↑ → priority ↑ → id ↑ 排序
        ruleDao.rows.add(rule(11L, "R-80", new BigDecimal("0.8000"), 100));
        ruleDao.rows.add(rule(12L, "R-60", new BigDecimal("0.6000"), 200));
        HedgeRatioCalc hit = service.calcCustomRule(3, 5L, "SELL", null, LocalDate.of(2026, 9, 20), 1L);
        assertEquals("SUCCESS", hit.resultCode);
        assertEquals(Long.valueOf(12L), hit.appliedRuleId, "取最严者（cap 最小）");
        assertEquals(0, new BigDecimal("0.600000").compareTo(hit.outputRatio));
        assertEquals(0, new BigDecimal("0.6000").compareTo(hit.appliedRatioCap));
        assertEquals(2, hit.hitRuleCount, "命中条数须留痕（>1 = 发生叠加）");
        assertTrue(hit.customRuleDesc.contains("R-60"));

        HedgeRatioCalc exceeds = service.calcCustomRule(3, 5L, "SELL", new BigDecimal("0.9"),
                LocalDate.of(2026, 9, 20), 1L);
        assertEquals("EXCEEDS_CUSTOM_CAP", exceeds.resultCode);
        assertEquals(0, BigDecimal.ZERO.compareTo(exceeds.outputRatio));
    }

    // ------------------------------------------------------------ T-016 编制与门禁

    @Test
    @DisplayName("编制计划：方向须与敞口相反、覆盖量不得超过未覆盖量、同子账户；落额度占用为 N")
    void createPlanRules() {
        exposureDao.rows.add(exposure(1L, "SELL", "100"));
        HedgePlan plan = plan("BUY_HEDGE");
        long id = service.createPlan(plan, Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("80"))), 1L);

        HedgePlan stored = planDao.findById(id);
        assertEquals("DRAFT", stored.status);
        assertEquals("N", stored.quotaOccupied);
        assertNotNull(stored.planNo);
        assertEquals(0, new BigDecimal("80").compareTo(exposureDao.findById(1L).coveredQty));
        assertEquals("PARTIALLY_COVERED", exposureDao.findById(1L).status, "敞口覆盖状态须联动");
        assertEquals(1, bindDao.rows.size());
        assertEquals(1, stateLogDao.rows.size());

        HedgePlan wrongDirection = plan("SELL_HEDGE");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.createPlan(wrongDirection,
                                Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("10"))), 1L))
                        .errorCode(), "方向须与敞口相反");

        HedgePlan tooMuch = plan("BUY_HEDGE");
        BizException e = assertThrows(BizException.class,
                () -> service.createPlan(tooMuch,
                        Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("150"))), 1L));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("未覆盖"), e.getMessage());

        HedgePlan noBind = plan("BUY_HEDGE");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.createPlan(noBind, new ArrayList<>(), 1L)).errorCode(),
                "计划必须从敞口出发");

        HedgePlan longTerm = plan("BUY_HEDGE");
        longTerm.termEndDate = longTerm.termStartDate.plusMonths(13);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.createPlan(longTerm,
                                Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("10"))), 1L))
                        .errorCode(), "期限红线 ≤ 12 个月");
    }

    @Test
    @DisplayName("取号重试：plan_no 唯一冲突重试至成功（§3.5 最多 3 次；缩短/关掉重试必红）")
    void retriesPlanNoConflictUpToThreeAttempts() {
        exposureDao.rows.add(exposure(1L, "SELL", "100"));
        planDao.failInsertsLeft = 2;             // 前两次 insert 抛 SqlState=23000
        planDao.planNoConflictOnFailure = true;  // 判为 plan_no 冲突 ⇒ 重试
        long id = service.createPlan(plan("BUY_HEDGE"),
                Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("80"))), 1L);
        assertTrue(id > 0, "重试后应成功落库");
        assertEquals(3, planDao.insertAttempts,
                "应取号重试至第 3 次成功（RETRY_MAX_ATTEMPTS=3）——把该常量改小本断言即红");
    }

    @Test
    @DisplayName("重试谓词收窄（O-2）：非 plan_no 唯一冲突不得重试（避免无谓重试与误报）")
    void doesNotRetryNonPlanNoConflict() {
        exposureDao.rows.add(exposure(1L, "SELL", "100"));
        planDao.failInsertsLeft = 1;
        planDao.planNoConflictOnFailure = false;  // 候选 plan_no 未被占用 ⇒ 其他唯一约束冲突
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createPlan(plan("BUY_HEDGE"),
                        Arrays.asList(new PlanService.BindSpec(1L, new BigDecimal("80"))), 1L));
        assertTrue(e.getMessage().contains("非计划编号"), e.getMessage());
        assertEquals(1, planDao.insertAttempts, "非 plan_no 唯一冲突只尝试 1 次（不重试）");
    }

    @Test
    @DisplayName("唯一冲突解包：23000 形态出 SQLException；null / 非 23xx 不误判重试")
    void unwrapsUniqueViolationCause() {
        assertNotNull(PlanService.uniqueViolationCause(
                        new IllegalStateException("包装", new java.sql.SQLException("dup", "23000"))),
                "23000 形态须解包出 SQLException（触发重试）");
        assertNull(PlanService.uniqueViolationCause(
                        new IllegalStateException("包装", new java.sql.SQLException("down", "08006"))),
                "非 23xx 不得判为唯一冲突");
        assertNull(PlanService.uniqueViolationCause(
                        new IllegalStateException("包装", new java.sql.SQLException("x", (String) null))),
                "SQLState=null（本环境引擎形态）不得判为唯一冲突 ⇒ 不重试");
        assertNull(PlanService.uniqueViolationCause(new IllegalStateException("无 cause")));
    }

    @Test
    @DisplayName("书面文件门禁：三要素缺任一 → 10012（四项齐备）；生成后可提交审批")
    void hedgeDocGate() {
        HedgePlan plan = draftPlanWithBind();
        BizException gate = assertThrows(BizException.class,
                () -> service.generateHedgeDoc(plan.id, "", "比率分析法", "偏离 >20% 再平衡", "cu2612", 1L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, gate.errorCode());
        assertEquals("R-PLAN-8", gate.ruleId());
        assertNotNull(gate.current());
        assertNotNull(gate.threshold());
        assertNotNull(gate.nextStep());
        assertTrue(gate.current().contains("要素①"), gate.current());

        int version = service.generateHedgeDoc(plan.id, "被套期项目=敞口 1；工具=cu2612",
                "比率分析法 |ΔV|∈[0.8,1.25]（月度）", "偏离 >20% 触发再平衡", "cu2612", 1L);
        assertEquals(1, version);
        assertEquals("Y", docDao.rows.get(0).pendingExternalReview, "恒为待外部复核（R-ACCT-10）");
        assertEquals(1, planDao.findById(plan.id).currentDocVersion);

        int second = service.generateHedgeDoc(plan.id, "改后指定", "改后方法", "改后机制", "cu2612", 1L);
        assertEquals(2, second, "要素变更须生成新版本");
        assertEquals("N", docDao.rows.get(0).isCurrent, "旧版本须置非当前");
        assertNotNull(docDao.rows.get(0).invalidatedTime);
        assertEquals("Y", docDao.rows.get(1).isCurrent);

        service.submitForApproval(plan.id, "{\"items\":[]}", 1L);
        assertEquals("PENDING_APPROVAL", planDao.findById(plan.id).status);
        assertEquals("SUBMIT", approvalDao.rows.get(0).nodeType);
    }

    @Test
    @DisplayName("提交审批门禁：无当前版本书面文件 → 10012（ruleId=R-PLAN-8）")
    void submitRequiresCurrentDoc() {
        HedgePlan plan = draftPlanWithBind();
        BizException e = assertThrows(BizException.class,
                () -> service.submitForApproval(plan.id, "{}", 1L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertTrue(e.current().contains("current_doc_version"), e.current());
        assertEquals("DRAFT", planDao.findById(plan.id).status, "被拒后状态不变");
    }

    // ------------------------------------------------------------ T-016 审批链与状态机

    @Test
    @DisplayName("审批：发起人 != 审批人（10005）；通过即占用额度并留痕 OCCUPY")
    void approveRequiresDifferentApprover() {
        HedgePlan plan = approvedCandidate();

        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class, () -> service.approve(plan.id, 1L, "自审")).errorCode(),
                "不得自审自批（R-PLAN-2）");

        service.approve(plan.id, 9L, "同意");
        HedgePlan stored = planDao.findById(plan.id);
        assertEquals("APPROVED", stored.status);
        assertEquals("Y", stored.quotaOccupied, "通过即占用额度（R-PLAN-5）");
        PlanStateLog log = stateLogDao.rows.get(stateLogDao.rows.size() - 1);
        assertEquals("OCCUPY", log.quotaAction);
        assertEquals(0, new BigDecimal("80").compareTo(log.quotaQty));
        assertEquals("PENDING", log.dispatchStatus, "额度变更待下发（R-PRODUCT-9）");
        assertEquals("APPROVE", approvalDao.rows.get(1).nodeType);
        assertEquals(Long.valueOf(9L), approvalDao.rows.get(1).approverId);
    }

    @Test
    @DisplayName("驳回：原因必填、回到草稿、不占额度；撤销：待审批 → 草稿")
    void rejectAndWithdraw() {
        HedgePlan plan = approvedCandidate();
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.reject(plan.id, 9L, " ")).errorCode());
        service.reject(plan.id, 9L, "敞口口径待复核");
        assertEquals("DRAFT", planDao.findById(plan.id).status);
        assertEquals("N", planDao.findById(plan.id).quotaOccupied);
        assertEquals("REJECT", approvalDao.rows.get(1).nodeType);
        assertEquals("敞口口径待复核", approvalDao.rows.get(1).rejectReason);

        HedgePlan plan2 = approvedCandidate();
        service.withdraw(plan2.id, 1L, "发起人主动撤回");
        assertEquals("DRAFT", planDao.findById(plan2.id).status);
        assertEquals("WITHDRAW", approvalDao.rows.get(approvalDao.rows.size() - 1).nodeType);
    }

    @Test
    @DisplayName("状态机：冻结（额度继续占用）→ 解冻；终止释放额度（RELEASE = 计划量 − 已成交量）")
    void freezeUnfreezeTerminate() {
        HedgePlan plan = approvedCandidate();
        service.approve(plan.id, 9L, "同意");

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.freeze(plan.id, 9L, " ")).errorCode());
        service.freeze(plan.id, 9L, "等待现货签约");
        assertEquals("FROZEN", planDao.findById(plan.id).status);
        assertEquals("Y", planDao.findById(plan.id).quotaOccupied, "冻结期间额度继续占用");

        service.unfreeze(plan.id, 9L);
        assertEquals("APPROVED", planDao.findById(plan.id).status);
        assertNull(planDao.findById(plan.id).freezeReason);

        planDao.findById(plan.id).executedQty = new BigDecimal("30");
        service.terminate(plan.id, 9L, "业务提前结束");
        HedgePlan stored = planDao.findById(plan.id);
        assertEquals("TERMINATED", stored.status);
        assertEquals("N", stored.quotaOccupied);
        PlanStateLog log = stateLogDao.rows.get(stateLogDao.rows.size() - 1);
        assertEquals("RELEASE", log.quotaAction);
        assertEquals(0, new BigDecimal("50").compareTo(log.quotaQty), "释放 = 80 − 30");
    }

    @Test
    @DisplayName("延期：仅已批准/冻结、新期限须更晚且仍受 12 个月红线约束、依据必填")
    void extendRules() {
        HedgePlan plan = approvedCandidate();
        service.approve(plan.id, 9L, "同意");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.extend(plan.id, LocalDate.of(2026, 12, 1), " ", 1L)).errorCode());

        service.extend(plan.id, LocalDate.of(2026, 12, 31), "实货合同期限延至 12-31", 1L);
        assertEquals(LocalDate.of(2026, 12, 31), planDao.findById(plan.id).termEndDate);
        assertEquals("实货合同期限延至 12-31", planDao.findById(plan.id).extendBasis);

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.extend(plan.id, LocalDate.of(2029, 1, 1), "超红线", 1L)).errorCode(),
                "延期后仍须 ≤ 起始日 + 12 个月");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.extend(plan.id, LocalDate.of(2026, 11, 1), "更早", 1L)).errorCode());
    }

    @Test
    @DisplayName("分解交易计划：仅已批准可分解；合约须属该品种；累计不得超过计划数量")
    void decomposeTradePlans() {
        HedgePlan plan = approvedCandidate();
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.decomposeTradePlans(plan.id,
                                Arrays.asList(trade("cu2612", new BigDecimal("40"))), 1L)).errorCode(),
                "未批准不得分解");

        service.approve(plan.id, 9L, "同意");
        contractDao.rows.add(contract(5L, "cu2612"));
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.decomposeTradePlans(plan.id,
                                Arrays.asList(trade("ag2612", new BigDecimal("40"))), 1L)).errorCode(),
                "合约未登记不得分解");

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.decomposeTradePlans(plan.id,
                                Arrays.asList(trade("cu2612", new BigDecimal("50")),
                                        trade("cu2612", new BigDecimal("50"))), 1L)).errorCode(),
                "累计超计划数量须拒绝（FR-PLAN-14 验收 2）");

        int n = service.decomposeTradePlans(plan.id,
                Arrays.asList(trade("cu2612", new BigDecimal("40")),
                        trade("cu2612", new BigDecimal("40"))), 1L);
        assertEquals(2, n);
        assertEquals(2, service.listTradePlans(plan.id, 1L,
                PageQuery.of(null, null, null, PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD), null)
                .items().size());
        assertEquals("BUY", tradePlanDao.rows.get(0).direction, "方向须与套保计划一致");
        assertNotNull(tradePlanDao.rows.get(0).tradePlanNo);
        assertEquals(plan.id, tradePlanDao.rows.get(0).hedgePlanId, "追溯链必挂套保计划");
    }

    // ------------------------------------------------------------ T-141 取号：当日最大序号 + 1

    @Test
    @DisplayName("交易计划取号（T-141）：seq = 当日最大序号 + 1（跨套保计划共享当日序号；批内自然递增）")
    void tradePlanNoIsDailyMaxPlusOneAcrossHedgePlans() {
        HedgePlan plan = approvedCandidate();
        service.approve(plan.id, 9L, "同意");
        contractDao.rows.add(contract(5L, "cu2612"));
        String day = BizNoGenerator.tradePlanDayPrefix(Clocks.today());
        // 他套保计划（id=9999）在同一业务日已有一条交易计划，占用当日序号 …00007
        TradePlan otherPlanRow = new TradePlan();
        otherPlanRow.id = 9001L;
        otherPlanRow.hedgePlanId = 9999L;
        otherPlanRow.subAccountId = 3;
        otherPlanRow.tradePlanNo = BizNoGenerator.tradePlan(Clocks.today(), 7L);
        tradePlanDao.rows.add(otherPlanRow);

        // 本套保计划分解 3 条（N=3）：编号须接当日最大序号 + 1（而非按本计划计数从 1 起）
        int n = service.decomposeTradePlans(plan.id,
                Arrays.asList(trade("cu2612", new BigDecimal("20")),
                        trade("cu2612", new BigDecimal("30")),
                        trade("cu2612", new BigDecimal("30"))), 1L);
        assertEquals(3, n);
        // rows = [他计划 …00007, 本计划 b1, b2, b3]
        assertEquals(day + "00008", tradePlanDao.rows.get(1).tradePlanNo,
                "首条 = 当日最大序号(7) + 1 —— 改回按 hedge_plan_id 计数（从 1 起）本断言即红");
        assertEquals(day + "00009", tradePlanDao.rows.get(2).tradePlanNo,
                "批内逐行重查前缀 MAX ⇒ 自然递增（同事务可见已插入行）");
        assertEquals(day + "00010", tradePlanDao.rows.get(3).tradePlanNo);
    }

    @Test
    @DisplayName("交易计划取号（T-141）：按业务日重置 —— 次日首个交易计划号重新从 00001 起")
    void tradePlanNoSeqResetsPerBusinessDay() {
        HedgePlan plan = approvedCandidate();
        service.approve(plan.id, 9L, "同意");
        contractDao.rows.add(contract(5L, "cu2612"));
        String day1 = BizNoGenerator.tradePlanDayPrefix(Clocks.today());
        service.decomposeTradePlans(plan.id,
                Arrays.asList(trade("cu2612", new BigDecimal("40"))), 1L);
        assertEquals(day1 + "00001", tradePlanDao.rows.get(0).tradePlanNo, "基准日首个 = 当日 00001");

        // 推进到次一业务日：seq 须【重置】为当日 00001（非累计前一日序号）
        Clocks.set(Clock.fixed(T0.plusSeconds(86400), Clocks.ZONE));
        String day2 = BizNoGenerator.tradePlanDayPrefix(Clocks.today());
        service.decomposeTradePlans(plan.id,
                Arrays.asList(trade("cu2612", new BigDecimal("40"))), 1L);
        assertEquals(day2 + "00001", tradePlanDao.rows.get(1).tradePlanNo,
                "次日首个应【重置】为当日 00001 —— 改回全局/累计取号本断言即红");
    }

    @Test
    @DisplayName("交易计划取号重试（T-141）：trade_plan_no 唯一冲突重试至成功（§3.5 最多 3 次；关掉重试必红）")
    void retriesTradePlanNoConflictUpToThreeAttempts() {
        HedgePlan plan = approvedCandidate();
        service.approve(plan.id, 9L, "同意");
        contractDao.rows.add(contract(5L, "cu2612"));
        tradePlanDao.failInsertsLeft = 2;             // 前两次 insert 抛 SqlState=23000
        tradePlanDao.tradePlanNoConflictOnFailure = true;  // 判为 trade_plan_no 冲突 ⇒ 重试
        int n = service.decomposeTradePlans(plan.id,
                Arrays.asList(trade("cu2612", new BigDecimal("40"))), 1L);
        assertEquals(1, n, "重试后应成功落库");
        assertEquals(3, tradePlanDao.insertAttempts,
                "应取号重试至第 3 次成功（RETRY_MAX_ATTEMPTS=3）——把该常量改小本断言即红");
    }

    @Test
    @DisplayName("交易计划取号重试收窄（T-141）：非 trade_plan_no 唯一冲突不得重试（避免无谓重试与误报）")
    void doesNotRetryNonTradePlanNoConflict() {
        HedgePlan plan = approvedCandidate();
        service.approve(plan.id, 9L, "同意");
        contractDao.rows.add(contract(5L, "cu2612"));
        tradePlanDao.failInsertsLeft = 1;
        tradePlanDao.tradePlanNoConflictOnFailure = false;  // 候选 trade_plan_no 未被占用 ⇒ 其他唯一约束冲突
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.decomposeTradePlans(plan.id,
                        Arrays.asList(trade("cu2612", new BigDecimal("40"))), 1L));
        assertTrue(e.getMessage().contains("非交易计划编号"), e.getMessage());
        assertEquals(1, tradePlanDao.insertAttempts, "非 trade_plan_no 唯一冲突只尝试 1 次（不重试）");
    }

    // ------------------------------------------------------------ 桩与数据

    private static HedgePlan plan(String hedgeDirection) {
        HedgePlan plan = new HedgePlan();
        plan.subAccountId = 3;
        plan.planType = "COMMODITY";
        plan.productId = 5L;
        plan.hedgeDirection = hedgeDirection;
        plan.planQty = new BigDecimal("80");
        plan.unit = "TON";
        plan.termStartDate = LocalDate.of(2026, 9, 20);
        plan.termEndDate = LocalDate.of(2026, 11, 30);
        plan.hedgeRatio = new BigDecimal("0.8");
        plan.ratioMethod = "SIMPLE";
        plan.calcBaseDate = LocalDate.of(2026, 9, 20);
        plan.applicantId = 1L;
        return plan;
    }

    /** 建一条草稿计划并绑定敞口（供门禁/审批用例复用）。 */
    private HedgePlan draftPlanWithBind() {
        long exposureId = exposureSeq++;
        exposureDao.rows.add(exposure(exposureId, "SELL", "100"));
        HedgePlan plan = plan("BUY_HEDGE");
        service.createPlan(plan,
                Arrays.asList(new PlanService.BindSpec(exposureId, new BigDecimal("80"))), 1L);
        return plan;
    }

    /** 已生成书面文件并提交审批的计划（供审批链用例复用）。 */
    private HedgePlan approvedCandidate() {
        HedgePlan plan = draftPlanWithBind();
        service.generateHedgeDoc(plan.id, "指定", "方法", "机制", "cu2612", 1L);
        service.submitForApproval(plan.id, "{\"items\":[]}", 1L);
        return plan;
    }

    private static Exposure exposure(long id, String direction, String qty) {
        Exposure exposure = new Exposure();
        exposure.id = id;
        exposure.subAccountId = 3;
        exposure.productId = 5L;
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
        exposure.snapshotId = "EXP-1";
        return exposure;
    }

    private static CustomHedgeRule rule(long id, String code, BigDecimal cap, int priority) {
        CustomHedgeRule rule = new CustomHedgeRule();
        rule.id = id;
        rule.ruleCode = code;
        rule.ruleName = code;
        rule.subAccountId = 3;
        rule.productId = 5L;
        rule.direction = "BOTH";
        rule.basis = "EXPOSURE_QTY";
        rule.ratioCap = cap;
        rule.priority = priority;
        rule.effectiveFrom = LocalDate.of(2026, 1, 1);
        rule.status = "EFFECTIVE";
        return rule;
    }

    private static TradePlan trade(String instrument, BigDecimal qty) {
        TradePlan plan = new TradePlan();
        plan.instrumentId = instrument;
        plan.instrumentType = "FUTURES";
        plan.direction = "BUY";
        plan.planQty = qty;
        plan.execDeadline = LocalDate.of(2026, 10, 31);
        return plan;
    }

    private static ProductContract contract(long productId, String instrumentId) {
        ProductContract contract = new ProductContract();
        contract.productId = productId;
        contract.instrumentId = instrumentId;
        contract.instrumentType = "FUTURES";
        return contract;
    }

    /** 内存假计划 DAO。 */
    private static final class FakeHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {

        private final List<HedgePlan> rows = new ArrayList<>();
        private long nextId = 1;

        /** T-137 取号重试守卫：前 {@code failInsertsLeft} 次 {@code insert} 抛「唯一约束冲突」。 */
        int failInsertsLeft;
        /** 模拟冲突是否属 plan_no（true ⇒ {@code findByPlanNo} 命中 ⇒ 判为 plan_no 冲突 ⇒ 重试）。 */
        boolean planNoConflictOnFailure = true;
        /** {@code insert} 实际调用次数（断言重试次数；关掉/缩短重试必红）。 */
        int insertAttempts;

        @Override
        public int insert(HedgePlan entity, long operatorId) {
            insertAttempts++;
            if (failInsertsLeft > 0) {
                failInsertsLeft--;
                // 模拟 TxTemplate 包装：IllegalStateException(cause = SQLException(SQLState=23000))
                throw new IllegalStateException("唯一约束冲突（模拟）",
                        new java.sql.SQLException("dup", "23000"));
            }
            entity.id = nextId++;
            rows.add(entity);
            return 1;
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
        public com.hedgeguard.dao.PageResult<HedgePlan> findPageScoped(
                com.hedgeguard.dao.PageQuery query, SubAccountScope scope, String extraWhere,
                List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public HedgePlan findByIdScoped(long id, SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByIdScoped");
        }

        @Override
        public HedgePlan findByPlanNo(String planNo) {
            // T-137 取号重试的 O-2 判别依赖此方法：候选 plan_no 是否已被占用。
            for (HedgePlan row : rows) {
                if (planNo.equals(row.planNo)) {
                    return row;
                }
            }
            // 模拟并发：冲突由「他笔事务已占用同名 plan_no」造成 ⇒ 视为命中一条同号行（仅用于判别）。
            if (planNoConflictOnFailure) {
                HedgePlan occupied = new HedgePlan();
                occupied.planNo = planNo;
                return occupied;
            }
            return null;
        }

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
        public int deleteById(Long id) {
            return rows.removeIf(row -> row.id == id) ? 1 : 0;
        }
    }

    /** 内存假绑定 DAO。 */
    private static final class FakeHedgePlanExposureDao
            extends UnsupportedBaseDao<HedgePlanExposure, Long> implements HedgePlanExposureDao {

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
            List<HedgePlanExposure> out = new ArrayList<>();
            for (HedgePlanExposure row : rows) {
                if (row.hedgePlanId == hedgePlanId) {
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

    /** 内存假比率留痕 DAO。 */
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

    /** 内存假自定义规则 DAO（返回已排序候选，模拟 DAO 的确定性排序）。 */
    private static final class FakeCustomRuleDao extends UnsupportedBaseDao<CustomHedgeRule, Long>
            implements CustomHedgeRuleDao {

        private final List<CustomHedgeRule> rows = new ArrayList<>();

        @Override
        public List<CustomHedgeRule> findEffective(int subAccountId, long productId, String direction,
                                                   LocalDate bizDate) {
            List<CustomHedgeRule> out = new ArrayList<>();
            for (CustomHedgeRule row : rows) {
                if (row.subAccountId == subAccountId && "EFFECTIVE".equals(row.status)
                        && (!row.effectiveFrom.isAfter(bizDate))
                        && (row.effectiveTo == null || !row.effectiveTo.isBefore(bizDate))) {
                    out.add(row);
                }
            }
            out.sort((a, b) -> {
                int byCap = a.ratioCap.compareTo(b.ratioCap);
                if (byCap != 0) {
                    return byCap;
                }
                int byPriority = Integer.compare(a.priority, b.priority);
                return byPriority != 0 ? byPriority : Long.compare(a.id, b.id);
            });
            return out;
        }
    }

    /** 内存假书面文件 DAO。 */
    private static final class FakeHedgeDocDao extends UnsupportedBaseDao<HedgeDoc, Long>
            implements HedgeDocDao {

        private final List<HedgeDoc> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(HedgeDoc entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public int updateById(HedgeDoc entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

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

    /** 内存假审批链 DAO。 */
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
        public java.util.List<PlanApproval> findByPlan(long hedgePlanId) {
            return new java.util.ArrayList<>();
        }
    }

    /** 内存假状态流转留痕 DAO。 */
    private static final class FakePlanStateLogDao extends UnsupportedBaseDao<PlanStateLog, Long>
            implements PlanStateLogDao {

        private final List<PlanStateLog> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(PlanStateLog entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }
    }

    /** 内存假交易计划 DAO。 */
    private static final class FakeTradePlanDao extends UnsupportedBaseDao<TradePlan, Long>
            implements TradePlanDao {

        private final List<TradePlan> rows = new ArrayList<>();
        private long nextId = 1;

        /** T-141 取号重试守卫：前 {@code failInsertsLeft} 次 {@code insert} 抛「唯一约束冲突」。 */
        int failInsertsLeft;
        /** 模拟冲突是否属 trade_plan_no（true ⇒ {@code findByTradePlanNo} 命中 ⇒ 判为编号冲突 ⇒ 重试）。 */
        boolean tradePlanNoConflictOnFailure = true;
        /** {@code insert} 实际调用次数（断言重试次数；关掉/缩短重试必红）。 */
        int insertAttempts;

        @Override
        public int insert(TradePlan entity, long operatorId) {
            insertAttempts++;
            if (failInsertsLeft > 0) {
                failInsertsLeft--;
                // 模拟 TxTemplate 包装：IllegalStateException(cause = SQLException(SQLState=23000))
                throw new IllegalStateException("唯一约束冲突（模拟）",
                        new java.sql.SQLException("dup", "23000"));
            }
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public List<TradePlan> findByHedgePlan(long hedgePlanId) {
            List<TradePlan> out = new ArrayList<>();
            for (TradePlan row : rows) {
                if (row.hedgePlanId == hedgePlanId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<TradePlan> findByHedgePlanScoped(long hedgePlanId, SubAccountScope scope) {
            List<TradePlan> out = new ArrayList<>();
            if (scope.isEmpty()) {
                return out;
            }
            for (TradePlan row : rows) {
                if (row.hedgePlanId == hedgePlanId && row.subAccountId != null
                        && scope.allowed().contains(row.subAccountId)) {
                    out.add(row);
                }
            }
            return out;
        }

        /** 分页（跨账户只读路径）：等价于 {@link #findByHedgePlan}，按分页参数截断。 */
        @Override
        public PageResult<TradePlan> findPage(PageQuery query, String where, List<Object> whereArgs) {
            long planId = whereArgs != null && !whereArgs.isEmpty() && whereArgs.get(0) instanceof Long
                    ? (Long) whereArgs.get(0) : -1L;
            List<TradePlan> hit = new ArrayList<>();
            for (TradePlan row : rows) {
                if (row.hedgePlanId == planId) {
                    hit.add(row);
                }
            }
            return page(hit, query);
        }

        /** 分页 + 子账户过滤（受限路径）：等价于 {@link #findByHedgePlanScoped}，按分页参数截断。 */
        @Override
        public PageResult<TradePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            long planId = extraArgs != null && !extraArgs.isEmpty() && extraArgs.get(0) instanceof Long
                    ? (Long) extraArgs.get(0) : -1L;
            List<TradePlan> hit = new ArrayList<>();
            if (!scope.isEmpty()) {
                for (TradePlan row : rows) {
                    if (row.hedgePlanId == planId && row.subAccountId != null
                            && scope.allowed().contains(row.subAccountId)) {
                        hit.add(row);
                    }
                }
            }
            return page(hit, query);
        }

        private static PageResult<TradePlan> page(List<TradePlan> hit, PageQuery query) {
            int from = Math.min(query.offset(), hit.size());
            int to = Math.min(from + query.size(), hit.size());
            return new PageResult<>(new ArrayList<>(hit.subList(from, to)), hit.size(),
                    query.page(), query.size());
        }

        @Override
        public TradePlan findByTradePlanNo(String tradePlanNo) {
            // T-141 取号重试的 O-2 判别依赖此方法：候选 trade_plan_no 是否已被占用。
            for (TradePlan row : rows) {
                if (tradePlanNo != null && tradePlanNo.equals(row.tradePlanNo)) {
                    return row;
                }
            }
            // 模拟并发：冲突由「他笔事务已占用同名 trade_plan_no」造成 ⇒ 视为命中一条同号行（仅用于判别）。
            if (tradePlanNoConflictOnFailure) {
                TradePlan occupied = new TradePlan();
                occupied.tradePlanNo = tradePlanNo;
                return occupied;
            }
            return null;
        }

        @Override
        public String findMaxTradePlanNoByPrefix(String tradePlanNoPrefix) {
            // 批内可见性：本方法逐行重查，每次返回当前 rows 的当日前缀最大编号（模拟同事务可见已插入行）。
            String max = null;
            for (TradePlan row : rows) {
                if (row.tradePlanNo != null && row.tradePlanNo.startsWith(tradePlanNoPrefix)
                        && (max == null || row.tradePlanNo.compareTo(max) > 0)) {
                    max = row.tradePlanNo;
                }
            }
            return max;
        }
    }

    /** 内存假敞口 DAO。 */
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

    /** 内存假合约 DAO。 */
    private static final class FakeProductContractDao
            extends UnsupportedBaseDao<ProductContract, Long> implements ProductContractDao {

        private final List<ProductContract> rows = new ArrayList<>();

        @Override
        public ProductContract findByInstrumentId(String instrumentId) {
            for (ProductContract row : rows) {
                if (row.instrumentId.equals(instrumentId)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<ProductContract> findByProduct(long productId) {
            List<ProductContract> out = new ArrayList<>();
            for (ProductContract row : rows) {
                if (row.productId != null && row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假操作授权 DAO（{@code R-PLAN-3/①}：按用户 + 业务日取有效授权）。 */
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

    /** 内存假子账户-品种白名单 DAO（{@code R-PLAN-3/②}）。 */
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
}
