package com.hedgeguard.service;

import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.RebalanceRecord;
import com.hedgeguard.dao.impl.DmEffectivenessAssessDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgeDocDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgeRelationDao;
import com.hedgeguard.dao.impl.DmRebalanceRecordDao;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-078} 再平衡服务单测（真实 DAO + {@code InMemoryDmEngine} 等价执行；
 * {@code AcctServiceTest} 同形态，事务感知包装 {@code TransactionAwareInMemory} 装配）。
 *
 * <p>覆盖：FR-ACCT-3 四类触发场景（① 敞口变化 &gt; 20% ② 比率偏离 &gt; 20% ③ 计划终止
 * ④ 敞口消失）+ 验收 4 触发条件不满足拒绝并给出当前偏离值 + 验收 2 记录要素 + 验收 3
 * 状态流转「已重新指定」与书面文件重新生成（R-ACCT-3 不得只改数字）+ 敞口/工具侧
 * 双侧任一命中即触发（Q-8）+ 三要素门禁（R-ACCT-1 重新指定同样成立）+ 写面隔离 +
 * 整笔事务包裹（TE-T077-001 同族回归：拒绝路径不留半笔写，{@code pendingCount} 归零）
 * + {@code current_doc_version} 同事务面回写（TE-T078-001 回归：成功后与当前文档版本一致、
 * 回滚后不变——PLAN 侧 {@code generateHedgeDoc} 版本推导不变量）。
 */
class AcctRebalanceServiceTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m4_acct.sql";

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TransactionAwareInMemory aware;
    private TxTemplate tx;
    private AcctService svc;
    private List<Integer> visible = List.of(1);
    private String exposureCfg = AcctService.CFG_EXPOSURE_DELTA_DEFAULT;
    private String ratioCfg = AcctService.CFG_RATIO_DEVIATION_DEFAULT;
    private DmHedgeRelationDao relationDao;
    private DmHedgePlanDao planDao;
    private DmHedgeDocDao docDao;
    private DmRebalanceRecordDao rebalanceDao;
    private DmExposureDao exposureDao;

    /** 时间敏感断言的固定运行日（TE-T077-002 判例：与运行日解耦）。 */
    private static final String TODAY = "2026-10-13";

    @BeforeEach
    void setUp() throws Exception {
        com.hedgeguard.common.Clocks.set(
                Clock.fixed(java.time.LocalDate.of(2026, 10, 13)
                                .atStartOfDay(com.hedgeguard.common.Clocks.ZONE).toInstant(),
                        com.hedgeguard.common.Clocks.ZONE));
        engine = new InMemoryDmEngine();
        engine.executeScript(Files.readString(repoRoot().resolve(SCRIPT), StandardCharsets.UTF_8));
        T077AcctSeed.createParentTables(engine);
        T077AcctSeed.seedChains(engine);
        // TE-T077-001：事务感知包装（引擎裸连接 rollback=no-op ⇒ 拒绝路径残留半笔写假绿）
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware,
                new PoolConfig(0, 4, 500, 2));
        tx = new TxTemplate(pool);
        relationDao = new DmHedgeRelationDao(tx);
        docDao = new DmHedgeDocDao(tx);
        rebalanceDao = new DmRebalanceRecordDao(tx);
        exposureDao = new DmExposureDao(tx);
        planDao = new DmHedgePlanDao(tx);
        svc = new AcctService(tx,
                (uid) -> visible,
                (key) -> AcctService.CFG_EXPOSURE_DELTA.equals(key) ? exposureCfg
                        : (AcctService.CFG_RATIO_DEVIATION.equals(key) ? ratioCfg : null),
                new DmHedgePlanDao(tx), docDao, relationDao,
                new DmEffectivenessAssessDao(tx), rebalanceDao, exposureDao);
    }

    @AfterEach
    void tearDown() {
        com.hedgeguard.common.Clocks.reset();
        assertEquals(0, aware.pendingCount(),
                "事务未残留（te-verify-t077d O-1 建议：pendingCount 归零自证）");
        if (pool != null) {
            pool.close();
        }
    }

    // ------------------------------------------------------------------ 验收 1（①②）：阈值型触发

    @Test
    @DisplayName("验收1①：敞口变化 > 20%（敞口侧，CFG-08 exposure_delta_pct）⇒ 生成记录")
    void exposureDeltaTriggerGeneratesRecord() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // 敞口 100 → 130（变化 30% > 20%）；计划基数 planQty=100
        T077AcctSeed.setExposureQty(engine, 21L, "130.0000");

        RebalanceRecord rec = svc.rebalance(req(r.id,
                AcctService.TRIGGER_EXPOSURE_DELTA, "现货敞口上行 30%（合同追加）",
                new BigDecimal("0.8"), new BigDecimal("130.0000"), "Y"), 7L);

        assertEquals("RB0001", rec.rebalanceNo, "RB 前缀全系统 max+1：首条 RB0001");
        assertEquals(AcctService.TRIGGER_EXPOSURE_DELTA, rec.triggerType);
        assertEquals(AcctService.SIDE_EXPOSURE, rec.triggerSide, "敞口变化 = 敞口侧");
        assertEquals(0, new BigDecimal("0.300000").compareTo(rec.triggerExposureDelta),
                "偏离值系统复核 = |130-100|/100 = 0.30（不采信客户端声明）");
        assertEquals(0, new BigDecimal("0.200000").compareTo(rec.triggerThreshold),
                "阈值快照 = CFG-08 敞口侧 0.20");
        assertEquals(0, new BigDecimal("0.800000").compareTo(rec.ratioBefore));
        assertEquals(0, new BigDecimal("0.800000").compareTo(rec.ratioAfter));
        assertEquals(0, new BigDecimal("100.0000").compareTo(rec.qtyBefore));
        assertEquals(0, new BigDecimal("130.0000").compareTo(rec.qtyAfter));
        assertEquals(7L, rec.executorId);
        assertNotNull(rec.rebalanceTime);
        assertEquals("Y", rec.planFeedbackFlag);
        // 状态流转（验收 3）：旧关系 → 已重新指定
        assertEquals(AcctService.REL_REDESIGNATED, relationDao.findById(r.id).status);
        // 新关系行（REDESIGNATED，关联新文档）
        HedgeRelation newRel = relationDao.findById(rec.newHedgeRelationId);
        assertNotNull(newRel, "重新指定 = 新关系行");
        assertEquals(AcctService.REL_REDESIGNATED, newRel.status);
        assertNotEquals(r.id, newRel.id);
        assertTrue(newRel.relationNo.compareTo(r.relationNo) > 0, "新关系取号递增");
        // 书面文件重新生成（验收 3 / R-ACCT-3 不得只改数字）
        HedgeDoc newDoc = docDao.findById(rec.newHedgeDocId);
        assertNotNull(newDoc);
        assertEquals("Y", newDoc.isCurrent);
        assertEquals(2, newDoc.docVersion, "v1 → v2 新版本");
        assertEquals("Y", newDoc.pendingExternalReview, "R-ACCT-10 恒标注");
        HedgeDoc oldDoc = docDao.findById(r.hedgeDocId);
        assertEquals("N", oldDoc.isCurrent, "旧版本置 N（历史保留）");
        assertNotNull(oldDoc.invalidatedTime);
        // 记录不可事后修改（R-ACCT-3）
        assertThrows(UnsupportedOperationException.class, () -> rebalanceDao.updateById(rec, 7L));
    }

    @Test
    @DisplayName("验收1②：套期比率偏离 > 20%（工具侧，CFG-08 ratio_deviation_pct）⇒ 生成记录（side=INSTRUMENT_SIDE）")
    void ratioDeviationTriggerOnInstrumentSide() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // 敞口 100 → 150：实际覆盖比率 = 100/150 = 0.667，计划比率 0.8 ⇒ 偏离 ≈ 0.1667 < 0.2
        // 再压敞口到 170：100/170 = 0.588 ⇒ 偏离 ≈ 0.265 > 0.2（工具侧命中）
        T077AcctSeed.setExposureQty(engine, 21L, "170.0000");

        RebalanceRecord rec = svc.rebalance(req(r.id,
                AcctService.TRIGGER_RATIO_DEVIATION, "比率偏离 26.5%（敞口扩张未同步调仓）",
                new BigDecimal("0.6"), new BigDecimal("102.0000"), "N"), 7L);

        assertEquals(AcctService.TRIGGER_RATIO_DEVIATION, rec.triggerType);
        assertEquals(AcctService.SIDE_INSTRUMENT, rec.triggerSide, "比率偏离 = 工具侧（Q-8）");
        assertTrue(rec.triggerRatioDeviation.compareTo(new BigDecimal("0.200000")) > 0,
                "偏离 " + rec.triggerRatioDeviation.toPlainString() + " > 阈值 0.20（工具侧命中）");
        assertEquals(0, new BigDecimal("0.200000").compareTo(rec.triggerThreshold));
        assertEquals(AcctService.REL_REDESIGNATED, relationDao.findById(r.id).status);
        assertEquals("N", rec.planFeedbackFlag);
        // idx_rebalance_trigger 取数路径（Q-8 核验承载）
        assertEquals(1, rebalanceDao.findByTrigger(AcctService.TRIGGER_RATIO_DEVIATION,
                AcctService.SIDE_INSTRUMENT).size());
    }

    // ------------------------------------------------------------------ 验收 1（③④）：状态型触发

    @Test
    @DisplayName("验收1③：计划数量被修订/终止（plan TERMINATED）⇒ 生成记录（敞口侧）")
    void planRevisedTrigger() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        T077AcctSeed.setPlanStatus(engine, 11L, "TERMINATED");

        RebalanceRecord rec = svc.rebalance(req(r.id,
                AcctService.TRIGGER_PLAN_REVISED, "计划数量被修订（终止重建）",
                new BigDecimal("0.5"), new BigDecimal("50.0000"), "Y"), 7L);

        assertEquals(AcctService.TRIGGER_PLAN_REVISED, rec.triggerType);
        assertEquals(AcctService.SIDE_EXPOSURE, rec.triggerSide);
        assertEquals(AcctService.REL_REDESIGNATED, relationDao.findById(r.id).status);
        assertNotNull(rebalanceDao.findByRebalanceNo(rec.rebalanceNo), "记录落库可查");
        assertEquals(1, rebalanceDao.findByHedgeRelation(r.id).size(),
                "idx_rebalance_relation_time 取数路径");
    }

    @Test
    @DisplayName("验收1④：敞口消失（现值归 0）⇒ 生成记录；qty_after 恒 0（终止重指定）")
    void exposureGoneTriggerForcesZeroQty() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // 敞口行被 t_hedge_relation FK 引用不可物理删除（RESTRICT）；敞口消失的数据面形态
        // = 现值归 0（重算清空，Q-17 快照语义）——行仍在、数量为 0 即「无敞口可套保」。
        T077AcctSeed.setExposureQty(engine, 21L, "0");

        AcctService.RebalanceRequest bad = req(r.id, AcctService.TRIGGER_EXPOSURE_GONE,
                "敞口消失需终止关系", new BigDecimal("0.5"), new BigDecimal("50.0000"), "N");
        BizException paramEx = assertThrows(BizException.class,
                () -> svc.rebalance(bad, 7L));
        assertEquals(ErrorCode.PARAM_INVALID, paramEx.errorCode(),
                "敞口消失场景 qty_after 须为 0（无敞口可套保）");

        AcctService.RebalanceRequest ok = req(r.id, AcctService.TRIGGER_EXPOSURE_GONE,
                "敞口消失需终止关系", new BigDecimal("0.5"), null, "N");
        RebalanceRecord rec = svc.rebalance(ok, 7L);
        assertEquals(AcctService.TRIGGER_EXPOSURE_GONE, rec.triggerType);
        assertEquals(0, BigDecimal.ZERO.compareTo(rec.qtyAfter), "调整后数量 = 0");
        assertEquals(AcctService.REL_REDESIGNATED, relationDao.findById(r.id).status);
    }

    // ------------------------------------------------------------------ 验收 4：不满足 ⇒ 拒绝 + 当前偏离值

    @Test
    @DisplayName("验收4：触发条件不满足 ⇒ 拒绝生成并给出当前偏离值（10012 四项载荷；不留半笔写）")
    void triggerNotMetRejectedWithCurrentDeviation() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // 敞口 100 → 110（变化 10% ≤ 20%）；比率偏离 0 ⇒ 全场景不满足
        T077AcctSeed.setExposureQty(engine, 21L, "110.0000");

        BizException ex = assertThrows(BizException.class,
                () -> svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                        "敞口小幅波动", new BigDecimal("0.8"), new BigDecimal("110.0000"), "N"), 7L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, ex.errorCode());
        assertEquals("R-ACCT-3", ex.ruleId());
        assertTrue(ex.current().contains("exposureDelta=0.1"),
                "当前偏离值（FR-ACCT-3 验收 4）：" + ex.current());
        assertEquals("0.20", ex.threshold(), "阈值 = CFG-08 快照（eval.thresholdValue 原始形态）");
        assertTrue(ex.nextStep().contains("敞口变化 0.1") && ex.nextStep().contains("比率偏离"),
                "拒绝须给出两侧当前偏离值：" + ex.nextStep());
        // 整笔事务：拒绝路径不落任何行（TE-T077-001 同族回归）
        assertEquals(0, rebalanceDao.findByHedgeRelation(r.id).size(), "拒绝后不落记录");
        assertEquals(AcctService.REL_DESIGNATED, relationDao.findById(r.id).status,
                "拒绝后状态不变");
        assertEquals("Y", docDao.findCurrent(11L).isCurrent,
                "拒绝后不生成新版本文档（不留半笔写）");
    }

    // ------------------------------------------------------------------ Q-8：两侧任一命中即触发

    @Test
    @DisplayName("Q-8：敞口侧与工具侧各设一项、任一命中即触发（① 走敞口侧阈值、② 走工具侧阈值，双侧独立命中）")
    void eitherSideHitTriggers() {
        // 侧 1：敞口侧命中（变化 0.5 > 0.2），工具侧未命中场景（比率偏离=0）仍生成
        HedgeRelation r1 = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        T077AcctSeed.setExposureQty(engine, 21L, "150.0000");
        RebalanceRecord byExposure = svc.rebalance(req(r1.id,
                AcctService.TRIGGER_EXPOSURE_DELTA, "敞口侧命中",
                new BigDecimal("0.7"), new BigDecimal("105.0000"), "N"), 7L);
        assertEquals(AcctService.SIDE_EXPOSURE, byExposure.triggerSide);

        // 侧 2：新关系先经评估转「有效」（状态机：已重新指定 ──评估通过──→ 有效），
        // 再敞口漂移后走工具侧触发（敞口 200 ⇒ 实际覆盖比率 = 100/200 = 0.5，
        // 对计划比率 0.8 偏离 0.375 > 0.2）。
        T077AcctSeed.setExposureQty(engine, 21L, "200.0000");
        HedgeRelation r2 = relationDao.findById(byExposure.newHedgeRelationId);
        assertEquals(AcctService.REL_REDESIGNATED, r2.status,
                "新关系行为 REDESIGNATED（再平衡链）");
        // REDESIGNATED 直接再平衡被拒（须先评估回「有效」）
        BizException stateEx = assertThrows(BizException.class,
                () -> svc.rebalance(req(r2.id, AcctService.TRIGGER_RATIO_DEVIATION,
                        "状态机负例", new BigDecimal("0.5"), new BigDecimal("100"), "N"), 7L));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, stateEx.errorCode());

        AcctService.AssessRequest assess = new AcctService.AssessRequest();
        assess.hedgeRelationId = r2.id;
        assess.periodKey = "2026-10";
        assess.observerLinkage = "Y";
        assess.observerDirection = "Y";
        assess.observerTerm = "Y";
        assess.exposureExpectedDate = java.time.LocalDate.of(2026, 10, 20);
        svc.assessEffectiveness(assess, 7L);
        assertEquals(AcctService.REL_EFFECTIVE, relationDao.findById(r2.id).status);

        RebalanceRecord byRatio = svc.rebalance(req(r2.id,
                AcctService.TRIGGER_RATIO_DEVIATION, "工具侧命中",
                new BigDecimal("0.5"), new BigDecimal("100.0000"), "N"), 7L);
        assertEquals(AcctService.SIDE_INSTRUMENT, byRatio.triggerSide);
        assertTrue(byRatio.triggerRatioDeviation.compareTo(new BigDecimal("0.2")) > 0);
        // 两侧均落库 ⇒ findByTrigger 双侧可各查到（Q-8 落实核验）
        assertEquals(1, rebalanceDao.findByTrigger(AcctService.TRIGGER_EXPOSURE_DELTA,
                AcctService.SIDE_EXPOSURE).size());
        assertEquals(1, rebalanceDao.findByTrigger(AcctService.TRIGGER_RATIO_DEVIATION,
                AcctService.SIDE_INSTRUMENT).size());
    }

    // ------------------------------------------------------------------ 门禁与状态机

    @Test
    @DisplayName("R-ACCT-1 重新指定同样成立：当前版本文档缺要素 ⇒ 10012 阻断并列缺失项（整笔回滚）")
    void regenerateDocMissingElementBlocked() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        // 现版文档（T077AcctSeed 文档 21）要素③置空串 = 缺项
        T077AcctSeed.setExposureQty(engine, 21L, "150.0000");
        T077AcctSeed.setDocRebalanceMechanism(engine, 21L, "");

        BizException ex = assertThrows(BizException.class,
                () -> svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                        "敞口上行", new BigDecimal("0.8"), new BigDecimal("120.0000"), "N"), 7L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, ex.errorCode());
        assertEquals("R-ACCT-1", ex.ruleId());
        assertTrue(ex.nextStep().contains("要素③再平衡机制"), "缺失项点名：" + ex.nextStep());
        assertEquals(0, rebalanceDao.findByHedgeRelation(r.id).size(), "整笔回滚：无记录");
        assertEquals(AcctService.REL_DESIGNATED, relationDao.findById(r.id).status,
                "整笔回滚：状态不变");
    }

    @Test
    @DisplayName("状态机：已终止/已重新指定的关系不得再平衡（10009）")
    void terminalStatesRejected() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        r.status = AcctService.REL_TERMINATED;
        relationDao.updateById(r, 7L);
        BizException ex = assertThrows(BizException.class,
                () -> svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                        "已终止", new BigDecimal("0.8"), new BigDecimal("100"), "N"), 7L));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.errorCode());
    }

    // ------------------------------------------------------------------ 隔离与取号

    @Test
    @DisplayName("写面隔离：跨子账户只读（哨兵 0 唯一授权）不得执行再平衡（T-115 先例）")
    void writePathRejectsCrossReadonly() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        visible = List.of(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        BizException ex = assertThrows(BizException.class,
                () -> svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                        "无写权", new BigDecimal("0.8"), new BigDecimal("100"), "N"), 7L));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, ex.errorCode());
        assertEquals(0, rebalanceDao.findByHedgeRelation(r.id).size());
    }

    @Test
    @DisplayName("RB 取号：全系统前缀 max+1（预置 RB0009 ⇒ RB0010；O-4 教训：非当日形态）")
    void rebalanceNoIsSystemWideMaxPlusOne() {
        T077AcctSeed.seedLegacyRebalance(engine);
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        T077AcctSeed.setExposureQty(engine, 21L, "150.0000");
        RebalanceRecord rec = svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                "敞口上行", new BigDecimal("0.8"), new BigDecimal("120"), "N"), 7L);
        assertEquals("RB0010", rec.rebalanceNo, "RB 前缀跨全系统 max+1");
    }

    // ------------------------------------------------------------------ 事务自证（先红后绿载体）

    @Test
    @DisplayName("整笔事务：新文档编号冲突（uk_hedge_doc_no）⇒ 全笔回滚（旧文档版本切换还原/无记录/状态不变）")
    void transactionalRollbackOnDocNoConflict() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        T077AcctSeed.setExposureQty(engine, 21L, "150.0000");
        // 冲突注入：预置 v2 文档行（docNo = HDHP202609010001-V2），is_current='N' 绕过当前版本
        // 条件唯一；rebalance 的 regenerateHedgeDoc 将以同号插入 ⇒ uk_hedge_doc_no 冲突。
        // 该冲突发生在「旧文档版本切换（①段写）之后、记录写入（③段）之前」——
        // 整笔回滚的可观察证据 = ①段写被还原（is_current 复原 'Y'）且无任何后续残留。
        T077AcctSeed.seedDocVersionConflict(engine, 11L, 21L, "HDHP202609010001-V2", 2, 91L);

        // uk 冲突以 SQLException 传播（TxTemplate 包装为 IllegalStateException）
        assertThrows(RuntimeException.class,
                () -> svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                        "敞口上行", new BigDecimal("0.8"), new BigDecimal("120"), "N"), 7L));

        // 事务感知包装的真实回滚（内存引擎裸连接会假绿——T-048b/T-077 判例）
        assertEquals(0, rebalanceDao.findByHedgeRelation(r.id).size(), "无孤儿再平衡记录");
        assertEquals(AcctService.REL_DESIGNATED, relationDao.findById(r.id).status,
                "状态机写（④段）未发生，原状态保持");
        HedgeDoc current = docDao.findCurrent(11L);
        assertEquals("Y", current.isCurrent, "①段写（旧文档置 N）须被回滚复原");
        assertEquals(1, current.docVersion, "文档版本未推进");
    }

    // ------------------------------------------------------------------ TE-T078-001 回归（current_doc_version 同事务面回写）

    @Test
    @DisplayName("TE-T078-001 回归：再平衡成功后 plan.current_doc_version == 新文档版本（PLAN 侧版本推导不变量）")
    void rebalanceWritesBackCurrentDocVersion() {
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        T077AcctSeed.setExposureQty(engine, 21L, "150.0000");

        RebalanceRecord rec = svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                "敞口上行", new BigDecimal("0.8"), new BigDecimal("120.0000"), "N"), 7L);

        // 不变量：plan.currentDocVersion 与当前版本文档 docVersion 同值（v1 → v2）
        HedgeDoc currentDoc = docDao.findCurrent(11L);
        assertNotNull(currentDoc);
        assertEquals(2, currentDoc.docVersion, "新文档 v2（v1 + 1）");
        assertEquals(2, planDao.findById(11L).currentDocVersion,
                "TE-T078-001：漏回写 ⇒ PLAN 侧 generateHedgeDoc 以 currentDocVersion+1 推导"
                        + " v2 撞 uk_hedge_doc_no，PLAN 侧再生成永久失败");
        assertEquals("HDHP202609010001-V2", currentDoc.docNo);
    }

    @Test
    @DisplayName("TE-T078-001 回归：再平衡事务回滚后 plan.current_doc_version 不变（同笔提交同笔回滚）")
    void rebalanceRollbackLeavesCurrentDocVersionUntouched() {
        // 复用 transactionalRollbackOnDocNoConflict 场景：预置 v2 同号行 ⇒ 新文档插入撞
        // uk_hedge_doc_no ⇒ 整笔回滚。回写若不在同一事务（或被摘除为事务外写），该列将
        // 残留 v2 —— 以「回滚后仍为种子值 1」作可观察证据。
        assertEquals(1, planDao.findById(11L).currentDocVersion,
                "种子前提：计划 11 current_doc_version=1");
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        T077AcctSeed.setExposureQty(engine, 21L, "150.0000");
        T077AcctSeed.seedDocVersionConflict(engine, 11L, 21L, "HDHP202609010001-V2", 2, 91L);

        assertThrows(RuntimeException.class,
                () -> svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                        "敞口上行", new BigDecimal("0.8"), new BigDecimal("120"), "N"), 7L));

        assertEquals(1, planDao.findById(11L).currentDocVersion,
                "整笔回滚：current_doc_version 不得残留事务内回写值");
        assertEquals(0, rebalanceDao.findByHedgeRelation(r.id).size(), "无孤儿再平衡记录");
        assertEquals(AcctService.REL_DESIGNATED, relationDao.findById(r.id).status,
                "状态机写未发生，原状态保持");
        HedgeDoc current = docDao.findCurrent(11L);
        assertEquals("Y", current.isCurrent, "①段写（旧文档置 N）须被回滚复原");
        assertEquals(1, current.docVersion, "文档版本未推进");
    }

    // ------------------------------------------------------------------ CFG-08 容错

    @Test
    @DisplayName("CFG-08 容错缺省：种子缺失时两侧均取 0.20（T-188 CFG-26 先例）")
    void cfgFallbackWhenSeedMissing() {
        exposureCfg = null;
        ratioCfg = null;
        HedgeRelation r = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L);
        T077AcctSeed.setExposureQty(engine, 21L, "120.0000");
        // 变化 0.2 == 阈值 0.2（> 才触发）⇒ 拒绝
        BizException ex = assertThrows(BizException.class,
                () -> svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                        "恰好 20%", new BigDecimal("0.8"), new BigDecimal("120"), "N"), 7L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, ex.errorCode());
        // 变化 0.25 > 0.20 ⇒ 触发（容错缺省生效）
        T077AcctSeed.setExposureQty(engine, 21L, "125.0000");
        RebalanceRecord rec = svc.rebalance(req(r.id, AcctService.TRIGGER_EXPOSURE_DELTA,
                "变化 25%", new BigDecimal("0.8"), new BigDecimal("125"), "N"), 7L);
        assertEquals("0.200000", rec.triggerThreshold.toPlainString());
    }

    // ------------------------------------------------------------------ 夹具

    private static Path repoRoot() {
        Path path = java.nio.file.Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (java.nio.file.Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test");
    }

    private static AcctService.RebalanceRequest req(long relationId, String scenario,
                                                    String reason, BigDecimal ratioAfter,
                                                    BigDecimal qtyAfter, String feedback) {
        AcctService.RebalanceRequest req = new AcctService.RebalanceRequest();
        req.hedgeRelationId = relationId;
        req.triggerScenario = scenario;
        req.triggerReason = reason;
        req.ratioAfter = ratioAfter;
        req.qtyAfter = qtyAfter;
        req.planFeedbackRequired = feedback;
        return req;
    }
}
