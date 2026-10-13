package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.EffectivenessAssess;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.impl.DmEffectivenessAssessDao;
import com.hedgeguard.dao.impl.DmHedgeDocDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgeRelationDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.T077AcctSeed;
import com.hedgeguard.dao.testsupport.TransactionAwareInMemory;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-077} 套期关系与有效性评估服务单测（真实 DAO + {@code InMemoryDmEngine} 等价执行）。
 *
 * <p>形态先例：{@code M4AcctDaoMappingAndWriteTest}（T-076，本环境无 DM8 ⇒ 引擎等价执行）。
 * 播种件：{@link T077AcctSeed}（SQL 集中于 {@code dao} 命名空间，E5 纪律）。
 *
 * <p>覆盖：FR-ACCT-1 三要素门禁（含缺失并列）、R-ACCT-10 待外部复核标注、FR-ACCT-1 验收 4
 * 历史版本可查看、FR-ACCT-2/4 三项定性观察点判定 + 观察点缺失拒绝 + 量化监测仅提示不判定 +
 * 幂等 + 更正链留痕 + 未评估提醒（FR-ACCT-2 验收 4）、子账户隔离（读面过滤 / 写面拒绝）、
 * O-4 全系统取号（跨业务日前缀 max+1）、「80%–125% 不存在」结构性证据（结论只由三项观察点决定）。
 */
class AcctServiceTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m4_acct.sql";

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TxTemplate tx;
    private AcctService svc;
    /** 可见子账户集合（默认 LIMITED 写授权 [1]；含哨兵 0 = 跨账户只读；测试逐用例改写）。 */
    private List<Integer> visible = List.of(1);
    private String monitorCfg = AcctService.CFG_MONITOR_THRESHOLD_DEFAULT;
    private DmHedgeRelationDao relationDao;
    private DmEffectivenessAssessDao assessDao;
    private DmHedgeDocDao docDao;

    /** 时间敏感断言的固定运行日（TE-T077-002：时钟 pin 后断言与运行日解耦）。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() throws Exception {
        // TE-T077-002（NFR-TEST-4）：固定时钟，日期相关判定（termDeviationDays 等）与运行日解耦
        com.hedgeguard.common.Clocks.set(
                Clock.fixed(TODAY.atStartOfDay(com.hedgeguard.common.Clocks.ZONE).toInstant(),
                        com.hedgeguard.common.Clocks.ZONE));
        engine = new InMemoryDmEngine();
        engine.executeScript(Files.readString(repoRoot().resolve(SCRIPT), StandardCharsets.UTF_8));
        T077AcctSeed.createParentTables(engine);
        T077AcctSeed.seedChains(engine);
        // TE-T077-001：以事务感知包装承载等价引擎的 commit/rollback 语义
        //（引擎裸连接对回滚为 no-op，已披露不覆盖；判例 SettleCheckT048bFixTest.Store 同型）
        pool = new DataSourcePool(new TransactionAwareInMemory(engine),
                new PoolConfig(0, 4, 500, 2));
        tx = new TxTemplate(pool);
        relationDao = new DmHedgeRelationDao(tx);
        assessDao = new DmEffectivenessAssessDao(tx);
        docDao = new DmHedgeDocDao(tx);
        svc = new AcctService(tx,
                (uid) -> visible,
                (key) -> AcctService.CFG_MONITOR_THRESHOLD.equals(key) ? monitorCfg : null,
                new DmHedgePlanDao(tx), docDao, relationDao, assessDao);
    }

    @AfterEach
    void tearDown() {
        com.hedgeguard.common.Clocks.reset();
        if (pool != null) {
            pool.close();
        }
    }

    // ------------------------------------------------------------------ 验收 1：套期关系生成（三要素门禁）

    @Test
    @DisplayName("验收1：三要素齐备时生成套期关系（DESIGNATED + 关联书面文件 + 待外部复核标注）")
    void generateHedgeRelationWithThreeElements() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612 持仓", 7L);
        assertEquals("DESIGNATED", r.status);
        assertEquals("HR0001", r.relationNo, "全系统前缀 max+1：首条为 HR0001");
        assertEquals(21L, r.hedgeDocId, "三要素来源=当前版本书面文件");
        assertEquals(1, r.subAccountId, "子账户随计划");

        // 「待外部复核」标注：关联文档恒 Y（R-ACCT-10，DB CHECK 承载，标注不可移除）
        HedgeDoc doc = docDao.findById(r.hedgeDocId);
        assertEquals("Y", doc.pendingExternalReview, "R-ACCT-10：文档恒标注待外部复核");
    }

    @Test
    @DisplayName("验收1+3：三要素缺失不得生成并列出缺失项（缺再平衡机制 ⇒ 10012 阻断）")
    void missingRebalanceMechanismRejectedWithMissingList() {
        // 文档 22 缺要素③（空串载体，T077AcctSeed 播种）；12 号计划属子账户 2 ⇒ 视角切到 [2]
        visible = List.of(2);
        BizException ex = assertThrows(BizException.class,
                () -> svc.generateHedgeRelation(12L, 22L, "OPTION", "cu2601P 期权持仓", 7L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, ex.errorCode());
        assertTrue(ex.nextStep().contains("要素③再平衡机制"),
                "缺失项须点名再平衡机制（FR-ACCT-1 验收 3）：" + ex.nextStep());
        assertEquals("R-ACCT-1", ex.ruleId());
        assertEquals(0, relationDao.findByHedgePlan(12L).size(), "拒绝后不得落任何套期关系行");
    }

    @Test
    @DisplayName("验收1+4：计划要素变更后历史版本文档仍可查看（历史版本 hedgeDocId 保留在关系行）")
    void historicalDocRemainViewable() {
        HedgeRelation r1 = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // PLAN 模块生成 v2 文档（is_current='Y' 切换）；v1 行仍在（idx_hedge_doc_plan_version）
        T077AcctSeed.promoteDocV2(engine);
        HedgeRelation r2 = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612 新仓", 7L);
        assertEquals(21L, r1.hedgeDocId, "v1 关系行仍指向 v1 文档（历史版本可查看）");
        assertEquals(23L, r2.hedgeDocId, "新关系行指向当前版本文档");
        assertNotNull(docDao.findById(21L), "v1 文档行未删除");
    }

    // ------------------------------------------------------------------ 取号（O-4 教训）

    @Test
    @DisplayName("O-4 取号：全系统前缀 max+1（编号无日期段、跨业务日不重置；HR0009 ⇒ HR0010）")
    void relationNoIsSystemWideMaxPlusOne() {
        T077AcctSeed.seedLegacyRelation(engine);
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        assertEquals("HR0010", r.relationNo,
                "取号须跨全系统前缀 max+1（O-4：非「当日最大 +1」，勿照搬 T-137/T-141 形态）");
    }

    // ------------------------------------------------------------------ 验收 2/3：有效性评估

    @Test
    @DisplayName("验收2：三项观察点均满足 ⇒ EFFECTIVE（结论由观察点布尔合取得出）")
    void threeObserversSatisfiedMeansEffective() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        EffectivenessAssess ea = svc.assessEffectiveness(
                req(r.id, "2026-09", "Y", "Y", "Y", LocalDate.of(2026, 9, 20), null), 7L);
        assertEquals(AcctService.CONCLUSION_EFFECTIVE, ea.conclusion);
        assertEquals("N", ea.needActionFlag);
        assertEquals(AcctService.METHOD_QUALITATIVE, ea.assessMethod);
        assertEquals("N", ea.monitorReviewFlag);
        assertEquals("EFFECTIVE", relationDao.findById(r.id).status, "状态机：有效 → EFFECTIVE");
        assertTrue(ea.observerDetail.contains("R-ACCT-2"),
                "判定过程留痕（FR-ACCT-4 验收 3：第三方可复现）：" + ea.observerDetail);
        assertTrue(ea.observerDetail.contains("monitorAffectsConclusion"),
                "判定过程须显式记录监测不影响结论");
    }

    @Test
    @DisplayName("验收2：任一观察点不满足 ⇒ INEFFECTIVE + 需终止或再平衡标注（状态 → NEED_REBALANCE）")
    void anyObserverUnsatisfiedMeansIneffective() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // 期限偏离 120 天 > 90 天 ⇒ 观察点③ = N
        EffectivenessAssess ea = svc.assessEffectiveness(
                req(r.id, "2026-09", "Y", "Y", "N", LocalDate.of(2027, 1, 20), null), 7L);
        assertEquals(AcctService.CONCLUSION_INEFFECTIVE, ea.conclusion);
        assertEquals("Y", ea.needActionFlag, "FR-ACCT-2 验收 2：无效时标注需终止或再平衡");
        assertTrue(ea.actionNote.contains("需终止或再平衡"));
        assertTrue(ea.actionNote.contains("不得自动修改会计处理"));
        assertEquals("NEED_REBALANCE", relationDao.findById(r.id).status);
        assertEquals((int) java.time.temporal.ChronoUnit.DAYS.between(TODAY, LocalDate.of(2027, 1, 20)),
                ea.termDeviationDays,
                "期限偏离天数留痕（固定时钟 " + TODAY + " → 2027-01-20，判定可复现且与运行日解耦）");
    }

    @Test
    @DisplayName("观察点③='Y' 与判定数据矛盾（偏离 > 90 天）⇒ 拒绝提交（数据一致性）")
    void termObserverInconsistentWithDatesRejected() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        BizException ex = assertThrows(BizException.class,
                () -> svc.assessEffectiveness(
                        req(r.id, "2026-09", "Y", "Y", "Y", LocalDate.of(2027, 1, 20), null), 7L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());
        assertTrue(ex.getMessage().contains("90"), "提示容忍窗口（±3 个月）：" + ex.getMessage());
    }

    @Test
    @DisplayName("FR-ACCT-4 验收1：近相关（linkage=N）须附书面经济关系说明，缺则拒绝")
    void nearCorrelationRequiresWrittenNote() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        AcctService.AssessRequest req = req(r.id, "2026-09", "N", "Y", "Y",
                LocalDate.of(2026, 9, 20), null);
        BizException ex = assertThrows(BizException.class, () -> svc.assessEffectiveness(req, 7L));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("linkageNote") && ex.getMessage().contains("书面经济关系"),
                "缺失项须点名书面说明：" + ex.getMessage());

        // 补齐说明后通过判定（linkage='N' ⇒ 观察点不满足 ⇒ 结论仍 INEFFECTIVE，说明仅承载判定依据）
        req.linkageNote = "铝/铜价格相关性 0.86（近三年月度），经济关系=同属基本金属、共享宏观驱动";
        EffectivenessAssess ea = svc.assessEffectiveness(req, 7L);
        assertEquals(AcctService.CONCLUSION_INEFFECTIVE, ea.conclusion,
                "近相关（linkage='N'）= 观察点①不满足 ⇒ 三项不齐 ⇒ INEFFECTIVE（R-ACCT-2）；"
                        + "书面说明系 Q-7 对近相关的留痕要求，不改变布尔合取");
        assertEquals("铝/铜价格相关性 0.86（近三年月度），经济关系=同属基本金属、共享宏观驱动",
                ea.linkageNote);
        assertEquals("Y", ea.needActionFlag);
    }

    @Test
    @DisplayName("FR-ACCT-4 验收4：观察点数据缺失 ⇒ 评估被拒并说明缺失项（不静默按不满足）")
    void missingObserverDataRejectedWithList() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        AcctService.AssessRequest req = req(r.id, "2026-09", null, null, "Y", null, null);
        BizException ex = assertThrows(BizException.class, () -> svc.assessEffectiveness(req, 7L));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, ex.errorCode());
        assertTrue(ex.getMessage().contains("observerLinkage")
                        && ex.getMessage().contains("observerDirection")
                        && ex.getMessage().contains("exposureExpectedDate"),
                "缺失项须逐项列出：" + ex.getMessage());
        assertEquals(0, assessDao.findBySubAccountAndPeriod(1, "2026-09").size(), "拒绝后不落库");
    }

    @Test
    @DisplayName("FR-ACCT-4 验收5：监测偏离超 ±50% ⇒ monitor_review_flag='Y'（提示复核），结论不因此改变")
    void monitorFlagDoesNotChangeConclusion() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // 三项满足 + 偏离 0.62（超 0.50 阈值）⇒ 结论仍 EFFECTIVE、flag='Y'
        EffectivenessAssess ea = svc.assessEffectiveness(
                req(r.id, "2026-09", "Y", "Y", "Y", LocalDate.of(2026, 9, 20),
                        new BigDecimal("0.62")), 7L);
        assertEquals(AcctService.CONCLUSION_EFFECTIVE, ea.conclusion,
                "量化监测仅提示复核，结论不因此改变（Q-7）");
        assertEquals("Y", ea.monitorReviewFlag, "偏离 0.62 > 0.50（CFG-07b）⇒ 提示复核");
        assertEquals(1, assessDao.findMonitorFlaggedByPeriod("2026-09").size(),
                "监测清单可筛出（Q-7 仅提示形态）");

        // 对照：偏离 0.30（未超阈值）⇒ flag='N'
        AcctService.AssessRequest req2 = req(r.id, "2026-10", "Y", "Y", "Y",
                LocalDate.of(2026, 10, 20), new BigDecimal("0.30"));
        assertEquals("N", svc.assessEffectiveness(req2, 7L).monitorReviewFlag);
    }

    @Test
    @DisplayName("CFG-07b 容错缺省：种子缺失时取 0.50 并照常判定（T-188 CFG-26 先例）")
    void monitorThresholdFallbackWhenSeedMissing() {
        monitorCfg = null;
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        EffectivenessAssess ea = svc.assessEffectiveness(
                req(r.id, "2026-09", "Y", "Y", "Y", LocalDate.of(2026, 9, 20),
                        new BigDecimal("0.51")), 7L);
        assertEquals("Y", ea.monitorReviewFlag, "缺种子 ⇒ 容错取缺省 0.50，0.51 仍超阈");
        assertEquals(AcctService.CONCLUSION_EFFECTIVE, ea.conclusion);
    }

    @Test
    @DisplayName("幂等：同关系同期间重复评估被拒（uk_eff_assess_rel_period 服务侧预检）")
    void duplicateAssessmentRejected() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        svc.assessEffectiveness(req(r.id, "2026-09", "Y", "Y", "Y",
                LocalDate.of(2026, 9, 20), null), 7L);
        BizException ex = assertThrows(BizException.class,
                () -> svc.assessEffectiveness(req(r.id, "2026-09", "Y", "Y", "Y",
                        LocalDate.of(2026, 9, 20), null), 7L));
        assertEquals(ErrorCode.UNIQUE_CONFLICT, ex.errorCode());
    }

    @Test
    @DisplayName("更正链：correctAssessment 新增记录留痕 correctedFromId，原记录 is_corrected='Y'，活跃指向新记录")
    void correctionAppendsWithTrace() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        EffectivenessAssess original = svc.assessEffectiveness(
                req(r.id, "2026-09", "Y", "Y", "Y", LocalDate.of(2026, 9, 20), null), 7L);
        // 评估记录不可编辑（T-076 承载，R-ACCT-2）：updateById 显式拒绝
        assertThrows(UnsupportedOperationException.class, () -> assessDao.updateById(original, 7L));

        EffectivenessAssess corrected = svc.correctAssessment(original.id,
                req(r.id, "2026-09", "Y", "Y", "N", LocalDate.of(2027, 1, 20), null), 8L);
        assertEquals(original.id, corrected.correctedFromId, "更正须引用原记录（留痕）");
        assertEquals("Y", assessDao.findById(original.id).isCorrected, "原记录标记已更正");
        assertEquals(corrected.id, assessDao.findActiveByRelationAndPeriod(r.id, "2026-09").id,
                "活跃记录指向更正后记录");
        assertEquals(AcctService.CONCLUSION_INEFFECTIVE, corrected.conclusion);
        // 已更正记录不得二次更正
        BizException ex = assertThrows(BizException.class,
                () -> svc.correctAssessment(original.id,
                        req(r.id, "2026-09", "Y", "Y", "N", LocalDate.of(2027, 1, 20), null), 8L));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.errorCode());
    }

    @Test
    @DisplayName("TE-T077-001 回归：更正请求校验拒绝 ⇒ 整笔回滚（原记录 is_corrected 必须='N'）")
    void correctionRejectedMustRollbackMarkCorrected() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        EffectivenessAssess original = svc.assessEffectiveness(
                req(r.id, "2026-09", "Y", "Y", "Y", LocalDate.of(2026, 9, 20), null), 7L);
        assertEquals("N", original.isCorrected);

        // 更正请求缺判定基准（exposureExpectedDate=null）⇒ doAssess 在 markCorrected 之后被拒
        BizException ex = assertThrows(BizException.class,
                () -> svc.correctAssessment(original.id,
                        req(r.id, "2026-09", "Y", "Y", "Y", null, null), 8L));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, ex.errorCode());

        // 整笔事务回滚：原记录不得残留 is_corrected='Y'（留痕链不失真）
        assertEquals("N", assessDao.findById(original.id).isCorrected,
                "TE-T077-001：更正被拒后原记录 is_corrected 须回滚为 'N'");
        assertEquals(1, assessDao.findBySubAccountAndPeriod(1, "2026-09").size(),
                "不得落任何更正记录（全笔回滚）");
        assertEquals("EFFECTIVE", relationDao.findById(r.id).status,
                "状态机在拒绝路径不得被触碰（本场景 doAssess 未及状态机，回归防未来形态漂移）");
    }

    // ------------------------------------------------------------------ 隔离与提醒

    @Test
    @DisplayName("读面隔离：有限授权只见可见集；空集恒假（不退化为全可见）；跨账户只读全可见")
    void readPathScopeFiltering() {
        visible = List.of(1, 2);   // 装配期：两个子账户均可写（LIMITED）
        svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);   // 子账户 1
        T077AcctSeed.completeDocRebalance(engine);
        svc.generateHedgeRelation(12L, 22L, "OPTION", "cu2601P", 7L);   // 子账户 2

        PageQuery q = PageQuery.of("1", "20", null, "designated_time");
        visible = List.of(1);
        assertEquals(1, svc.listHedgeRelations(q, null, null, 7L).total(),
                "可见集 [1] 只见子账户 1 的关系");
        visible = List.of();
        assertEquals(0, svc.listHedgeRelations(q, null, null, 7L).total(),
                "空可见集 ⇒ 恒假（fail-closed）");
        visible = List.of(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        assertEquals(2, svc.listHedgeRelations(q, null, null, 7L).total(),
                "跨账户只读 ⇒ 全可见");
        // status 筛选取值域校验
        BizException ex = assertThrows(BizException.class,
                () -> svc.listHedgeRelations(q, null, "NO_SUCH", 7L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());
    }

    @Test
    @DisplayName("写面隔离：跨子账户只读（哨兵 0 唯一授权）不得生成（T-115 先例）")
    void writePathRejectsCrossReadonly() {
        visible = List.of(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        BizException ex = assertThrows(BizException.class,
                () -> svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, ex.errorCode());
    }

    @Test
    @DisplayName("FR-ACCT-2 验收4：某期间未评估时系统提醒（unassessedRelations 列出且排除已评估/已终止）")
    void unassessedReminder() {
        visible = List.of(1, 2);   // 装配期：两个子账户均可写（LIMITED）
        HedgeRelation r1 = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        T077AcctSeed.completeDocRebalance(engine);
        svc.generateHedgeRelation(12L, 22L, "OPTION", "cu2601P", 7L);
        svc.assessEffectiveness(req(r1.id, "2026-09", "Y", "Y", "Y",
                LocalDate.of(2026, 9, 20), null), 7L);
        // 提醒巡检按全可见视角（12 号计划属子账户 2）
        visible = List.of(SubAccountScope.ALL_ACCOUNT_SENTINEL);

        List<HedgeRelation> unassessed = svc.unassessedRelations("2026-09", 7L);
        assertEquals(1, unassessed.size(), "仅 12 号计划的关系未评估");
        assertEquals("DESIGNATED", unassessed.get(0).status);

        // 已终止的关系不提醒
        HedgeRelation r2 = unassessed.get(0);
        r2.status = AcctService.REL_TERMINATED;
        relationDao.updateById(r2, 7L);
        List<HedgeRelation> after = svc.unassessedRelations("2026-09", 7L);
        for (HedgeRelation h : after) {
            assertFalse(AcctService.REL_TERMINATED.equals(h.status), "已终止不提醒");
        }
        assertTrue(after.isEmpty(), "r1 已评估、r2 已终止 ⇒ 本期无提醒对象");
    }

    // ------------------------------------------------------------------ 「80%–125% 不存在」结构性证据

    @Test
    @DisplayName("FR-ACCT-4 验收2（负向判据·结构性证据）：80%/125% 形态监测值翻转不改变结论")
    void extremeMonitorValueNeverAffectsConclusion() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // 三项满足 + 监测值 0.80（=80% 区间下沿数值形态）与 1.25（=125% 上沿）⇒ 结论恒 EFFECTIVE
        EffectivenessAssess first = svc.assessEffectiveness(
                req(r.id, "2026-09", "Y", "Y", "Y", LocalDate.of(2026, 9, 20),
                        new BigDecimal("0.80")), 7L);
        assertEquals(AcctService.CONCLUSION_EFFECTIVE, first.conclusion,
                "监测值 0.80 不得影响结论（80%–125% 不作为判据的结构性证据）");
        // 更正链换入 1.25：结论仍 EFFECTIVE
        EffectivenessAssess second = svc.correctAssessment(first.id,
                req(r.id, "2026-09", "Y", "Y", "Y", LocalDate.of(2026, 9, 20),
                        new BigDecimal("1.25")), 7L);
        assertEquals(AcctService.CONCLUSION_EFFECTIVE, second.conclusion,
                "监测值 1.25 不得影响结论");
        assertEquals("Y", second.monitorReviewFlag);
    }

    // ------------------------------------------------------------------ 夹具

    /** 从测试工作目录向上寻找仓库根（含 {@code docs/DATA_MODEL.md}）；ModuleRoutesConsistencyTest 同法。 */
    private static Path repoRoot() {
        Path path = java.nio.file.Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (java.nio.file.Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test，当前="
                + java.nio.file.Paths.get("").toAbsolutePath());
    }

    private static AcctService.AssessRequest req(long relationId, String period, String linkage,
                                                 String direction, String term, LocalDate expected,
                                                 BigDecimal monitor) {
        AcctService.AssessRequest req = new AcctService.AssessRequest();
        req.hedgeRelationId = relationId;
        req.periodKey = period;
        req.observerLinkage = linkage;
        req.observerDirection = direction;
        req.observerTerm = term;
        req.exposureExpectedDate = expected;
        req.monitorDeviationRatio = monitor;
        return req;
    }
}
