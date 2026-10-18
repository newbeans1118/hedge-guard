package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.Cas24Selfcheck;
import com.hedgeguard.dao.entity.Cas24SelfcheckItem;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.impl.DmCas24SelfcheckDao;
import com.hedgeguard.dao.impl.DmCas24SelfcheckItemDao;
import com.hedgeguard.dao.impl.DmAccountSubjectDao;
import com.hedgeguard.dao.impl.DmAccountingPeriodDao;
import com.hedgeguard.dao.impl.DmDualPnlReportDao;
import com.hedgeguard.dao.impl.DmEffectivenessAssessDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgeDocDao;
import com.hedgeguard.dao.impl.DmHedgeEffectDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmHedgeRelationDao;
import com.hedgeguard.dao.impl.DmPeriodCloseLogDao;
import com.hedgeguard.dao.impl.DmRebalanceRecordDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmSettlePnlDao;
import com.hedgeguard.dao.impl.DmSpotPriceDao;
import com.hedgeguard.dao.impl.DmVoucherDao;
import com.hedgeguard.dao.impl.DmVoucherLineDao;
import com.hedgeguard.dao.impl.DmVoucherReviewDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.T077AcctSeed;
import com.hedgeguard.dao.testsupport.T080AcctEffectSeed;
import com.hedgeguard.dao.testsupport.T085M4DocPackageSeed;
import com.hedgeguard.dao.testsupport.TransactionAwareInMemory;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.handler.acct.AcctRoutes;
import com.hedgeguard.web.handler.acct.SelfcheckExportHandler;
import com.hedgeguard.web.handler.acct.SelfcheckHandler;
import com.hedgeguard.web.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-197} CAS 24 自检端点化（{@code FR-ACCT-14} 验收 1~4 + 「无法自检」+ 越权负例 +
 * 幂等；装配级 + 端点级）。
 *
 * <p><b>层级与边界（诚实声明）</b>：真实 {@link AcctService}（廿一参全参构造）+ 真实
 * {@link Cas24SelfcheckService} + 真实 {@code Dm*Dao}，跑在 {@link InMemoryDmEngine} +
 * {@link TransactionAwareInMemory}（TE-T077-001 判例）；{@code Clocks.set} 固定时钟
 * （TE-T077-002 判例）。夹具复用生产形态种子（{@code t_settle_pnl} 计划号 NULL，TE-T080-001
 * 口径）。断言锚<b>冻结域字面量</b>（"ALL_PASS"/"NEED_REBALANCE"/"Y" 等），不引生产常量
 * （变异判据纪律）；{@code M4DocPackageCas24SelfcheckTest} 为验收对照物，本类<b>不照抄</b>其
 * 断言——生产判据以 {@code FR-ACCT-14}/R-ACCT-1/R-ACCT-10 字面为准（⑤=两栏成对非空，
 * 与基线「衍生品非静默 0」负证形态刻意不同源）。
 *
 * <p>完全离线（NFR-TEST-2）。
 */
@DisplayName("T-197：CAS 24 自检端点化（FR-ACCT-14 验收 1~4）")
class Cas24SelfcheckServiceTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m4_acct.sql";
    private static final String CONFIG_SCRIPT = "docs/DEPLOY/schema/m1_config_auth.sql";

    /** 固定运行日（TE-T077-002 判例）。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private static final long OP = 7L;

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TransactionAwareInMemory aware;
    private TxTemplate tx;
    private AcctService svc;
    private Cas24SelfcheckService scSvc;
    private DmHedgeRelationDao relationDao;
    private DmCas24SelfcheckDao scDao;
    private DmCas24SelfcheckItemDao itemDao;

    /** 可变可见集（越权负例切换授权用）。 */
    private List<Integer> visible = List.of(1);

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(TODAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        engine.executeScript(Files.readString(repoRoot().resolve(CONFIG_SCRIPT), StandardCharsets.UTF_8));
        engine.executeScript(Files.readString(repoRoot().resolve(SCRIPT), StandardCharsets.UTF_8));
        T077AcctSeed.createParentTables(engine);
        T077AcctSeed.seedChains(engine);
        T080AcctEffectSeed.createEffectTables(engine);
        T080AcctEffectSeed.seedEffectChains(engine);
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware, new PoolConfig(0, 4, 500, 2));
        tx = new TxTemplate(pool);
        relationDao = new DmHedgeRelationDao(tx);
        scDao = new DmCas24SelfcheckDao(tx);
        itemDao = new DmCas24SelfcheckItemDao(tx);
        svc = new AcctService(tx,
                (uid) -> visible,
                (key) -> null,
                new DmHedgePlanDao(tx), new DmHedgeDocDao(tx),
                relationDao, new DmEffectivenessAssessDao(tx),
                new DmRebalanceRecordDao(tx), new DmExposureDao(tx),
                new DmVoucherDao(tx), new DmVoucherLineDao(tx), new DmVoucherReviewDao(tx),
                new DmAccountingPeriodDao(tx), new DmPeriodCloseLogDao(tx),
                new DmSettleCycleDao(tx), new DmAccountSubjectDao(tx),
                new DmHedgeEffectDao(tx), new DmDualPnlReportDao(tx),
                new DmHedgePlanExposureDao(tx), new DmSettlePnlDao(tx),
                new DmSpotPriceDao(tx));
        scSvc = new Cas24SelfcheckService(tx, (uid) -> visible, svc,
                relationDao, new DmHedgeDocDao(tx),
                new DmEffectivenessAssessDao(tx), new DmRebalanceRecordDao(tx),
                scDao, itemDao);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
        assertEquals(0, aware.pendingCount(), "事务未残留（te-verify-t077d O-1 自证）");
        if (pool != null) {
            pool.close();
        }
    }

    // ================================================================ 链路夹具

    /** 链路产物（期货腿五条全过；期权腿 ④ 触发未生成 ⇒ 不通过）。 */
    private static final class Chain {
        HedgeRelation futuresRelation;
        HedgeRelation optionRelation;
    }

    /**
     * 驱动链路：期货腿（计划 11/敞口 21）⇒ 关系生成 → 期间评估（有效）→ 敞口 100→130
     * （变化 30% &gt; 20%）⇒ 再平衡（记录 + 新文档）；期权腿（计划 13/敞口 23）⇒ 关系生成
     * → 评估观察点①近相关 ⇒ 结论无效 → 关系状态机置 NEED_REBALANCE，<b>不执行再平衡</b>
     * （FR-ACCT-14 验收 2 负例：已触发但记录缺失）；效果评估落汇总行（⑤取数源）。
     */
    private Chain buildChain() {
        T085M4DocPackageSeed.seedPlan13Doc(engine);
        Chain c = new Chain();
        c.futuresRelation = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", OP);
        AcctService.AssessRequest ar = new AcctService.AssessRequest();
        ar.hedgeRelationId = c.futuresRelation.id;
        ar.periodKey = "2026-10";
        ar.observerLinkage = "Y";
        ar.observerDirection = "Y";
        ar.observerTerm = "Y";
        ar.exposureExpectedDate = LocalDate.of(2026, 11, 30);
        svc.assessEffectiveness(ar, OP);
        T077AcctSeed.setExposureQty(engine, 21L, "130.0000");
        AcctService.RebalanceRequest rr = new AcctService.RebalanceRequest();
        rr.hedgeRelationId = c.futuresRelation.id;
        rr.triggerScenario = "EXPOSURE_DELTA";
        rr.triggerReason = "现货敞口上行 30%（T-197 自检端点化场景）";
        rr.ratioAfter = new BigDecimal("0.8");
        rr.qtyAfter = new BigDecimal("130.0000");
        rr.planFeedbackRequired = "Y";
        svc.rebalance(rr, OP);
        c.optionRelation = svc.generateHedgeRelation(13L, 23L, "OPTION", "al2602P", OP);
        AcctService.AssessRequest or = new AcctService.AssessRequest();
        or.hedgeRelationId = c.optionRelation.id;
        or.periodKey = "2026-10";
        or.observerLinkage = "N";
        or.linkageNote = "期权以 al 期货为标的，与实货铝存在经济关系（书面说明）";
        or.observerDirection = "Y";
        or.observerTerm = "Y";
        or.exposureExpectedDate = LocalDate.of(2026, 11, 30);
        svc.assessEffectiveness(or, OP);
        svc.evaluateEffect(1, "2026-10", OP);
        return c;
    }

    private Cas24SelfcheckService.SelfcheckRequest relationReq(long relationId, String period) {
        Cas24SelfcheckService.SelfcheckRequest req =
                new Cas24SelfcheckService.SelfcheckRequest();
        req.scopeType = "HEDGE_RELATION";
        req.hedgeRelationId = relationId;
        req.periodKey = period;
        return req;
    }

    private Cas24SelfcheckService.SelfcheckRequest periodReq(String period) {
        Cas24SelfcheckService.SelfcheckRequest req =
                new Cas24SelfcheckService.SelfcheckRequest();
        req.scopeType = "PERIOD";
        req.periodKey = period;
        return req;
    }

    // ================================================================ 验收 1

    @Test
    @DisplayName("验收 1：对已成立关系自检——五条逐条结论 + 证据位置；批次 ALL_PASS；标注与说明随行")
    void acceptance1_relationScopeFiveItemsAllPassWithEvidence() {
        Chain c = buildChain();
        Cas24Selfcheck batch = scSvc.runSelfcheck(
                relationReq(c.futuresRelation.id, "2026-10"), OP);

        assertEquals("ALL_PASS", batch.conclusion, "期货腿五条全过");
        assertEquals(Integer.valueOf(5), batch.passCount);
        assertEquals(Integer.valueOf(0), batch.failCount);
        assertEquals("Y", batch.pendingExternalReview, "「待外部复核」标注（R-ACCT-10）");
        assertNotNull(batch.notSubstituteNote);
        assertTrue(batch.notSubstituteNote.contains("自检不替代外部复核"),
                "「自检不替代外部复核」显式说明（R-ACCT-10）");
        assertEquals(OP, batch.executorId, "执行人=会话身份");
        assertNotNull(batch.selfcheckTime, "自检时间");
        assertNotNull(batch.reportFileId);
        assertTrue(batch.reportFileId.startsWith("cas24-selfcheck-"), "报告文件标识");

        List<Cas24SelfcheckItem> items = itemDao.findBySelfcheck(batch.id);
        assertEquals(5, items.size(), "五条逐条（item_seq 1~5）");
        String[] expectedCodes = {"FORMAL_DESIGNATION", "WRITTEN_DOC", "EFFECTIVENESS_RECORDS",
                "REBALANCE_RECORDS", "DUAL_PNL_PRESENTED"};
        for (int i = 0; i < 5; i++) {
            Cas24SelfcheckItem it = items.get(i);
            assertEquals(i + 1, it.itemSeq, "序号");
            assertEquals(expectedCodes[i], it.itemCode, "条款代码逐字");
            assertEquals("PASS", it.result, "期货腿全过：" + it.itemCode);
            assertNotNull(it.evidenceType, "证据位置（类型）：" + it.itemCode);
            assertNotNull(it.evidenceId, "证据位置（对象）：" + it.itemCode);
            assertNotNull(it.evidenceDesc, "证据位置（说明，FR-ACCT-14 验收 1）：" + it.itemCode);
            assertNull(it.failReason, "通过条目无失败原因：" + it.itemCode);
        }
        // 证据位置可定位（④：再平衡记录 + 新文档链接；FR-ACCT-14「证据位置」字面）
        Cas24SelfcheckItem i4 = items.get(3);
        assertEquals("t_rebalance_record", i4.evidenceType);
        assertTrue(i4.evidenceDesc.contains("再平衡记录"), "④证据描述点名再平衡记录："
                + i4.evidenceDesc);

        // 生产 DAO 落库直证（不经 service 断言）
        assertEquals(1, engine.table("t_cas24_selfcheck").rows.size(), "批次恰 1 行");
        assertEquals(5, engine.table("t_cas24_selfcheck_item").rows.size(), "条目恰 5 行");
        assertEquals("Y", engine.table("t_cas24_selfcheck").rows.get(0)
                .get("pending_external_review"), "落库行标注 'Y'（R-ACCT-10）");
    }

    // ================================================================ 验收 2

    @Test
    @DisplayName("验收 2：已触发再平衡但记录缺失 ⇒ 该项「不通过」+ 失败原因；批次 HAS_FAIL")
    void acceptance2_triggeredWithoutRebalanceRecordFails() {
        Chain c = buildChain();
        // 状态机现读：期权腿评估结论无效 ⇒ NEED_REBALANCE（R-ACCT-2）
        assertEquals("NEED_REBALANCE", relationDao.findById(c.optionRelation.id).status,
                "前置：期权腿已触发再平衡（状态机）");
        Cas24Selfcheck batch = scSvc.runSelfcheck(
                relationReq(c.optionRelation.id, "2026-10"), OP);

        assertEquals("HAS_FAIL", batch.conclusion, "④不通过 ⇒ 批次 HAS_FAIL");
        assertEquals(Integer.valueOf(4), batch.passCount);
        assertEquals(Integer.valueOf(1), batch.failCount);
        List<Cas24SelfcheckItem> items = itemDao.findBySelfcheck(batch.id);
        Cas24SelfcheckItem i4 = items.get(3);
        assertEquals("REBALANCE_RECORDS", i4.itemCode);
        assertEquals("FAIL", i4.result, "FR-ACCT-14 验收 2：缺再平衡记录 ⇒ 不通过");
        assertNotNull(i4.failReason);
        assertTrue(i4.failReason.contains("已触发") && i4.failReason.contains("无该关系任何记录"),
                "失败原因说清触发与缺失：" + i4.failReason);
        // 其余四条不受牵连（近相关评估仍在案）
        assertEquals("PASS", items.get(2).result, "③评估记录齐备（期权腿已评估）");
    }

    // ================================================================ 验收 3 + 4（导出）

    @Test
    @DisplayName("验收 3+4：报告导出 CSV——含自检时间与执行人 + 待外部复核标注 + 不替代说明；文件名白名单")
    void acceptance3And4_exportContainsTimeExecutorAndMarking() {
        Chain c = buildChain();
        Cas24Selfcheck batch = scSvc.runSelfcheck(
                relationReq(c.futuresRelation.id, "2026-10"), OP);
        Cas24SelfcheckService.SelfcheckExportResult r =
                scSvc.exportReport(batch.selfcheckNo, OP);

        assertEquals("cas24-selfcheck-" + batch.selfcheckNo + ".csv", r.fileName,
                "文件名由已落库批次号拼装（非客户端原文直进 header）");
        String content = r.content;
        assertTrue(content.contains("BATCH_INFO"), "section 分段（T-193 形态）");
        assertTrue(content.contains("SELFCHECK_ITEM"), "条目段在");
        // 验收 4：自检时间 + 执行人
        assertTrue(content.contains(batch.selfcheckTime.toString()),
                "报告含自检时间：" + batch.selfcheckTime);
        assertTrue(content.contains(String.valueOf(OP)), "报告含执行人：" + OP);
        // 验收 3：待外部复核标注 + 不替代说明
        assertTrue(content.contains("自检不替代外部复核"), "「自检不替代外部复核」显式说明");
        assertTrue(content.contains(",Y,"), "pending_external_review='Y' 值在 CSV 值位");
        // 验收 1 承接：逐条结论与证据位置随行
        assertTrue(content.contains("REBALANCE_RECORDS"), "条款代码在");
        assertTrue(content.contains("t_rebalance_record"), "证据类型在");
        assertEquals(6, content.split("\r\n").length - 1, "1 表头 + 1 批次行 + 5 条目行");

        // 注入面负例：非法批次号（引号/换行/空格/CRLF 载体）一律参数错误，不进 Content-Disposition
        String[] injected = {"SC202610130001\r\nX-Evil: 1", "SC20261013; rm -rf",
                "SC202610130001\",on", "abcd", "SC20261013"};
        for (String bad : injected) {
            BizException ex = assertThrows(BizException.class,
                    () -> scSvc.exportReport(bad, OP),
                    "非法批次号须拒绝：" + bad);
            assertTrue(String.valueOf(ex.getMessage()).contains("SC"),
                    "拒绝信息点名合法形态：" + ex.getMessage());
        }
    }

    // ================================================================ 无法自检 + 期间聚合

    @Test
    @DisplayName("无法自检：期间无任何评估记录（关键数据缺失）⇒ CANNOT_SELFCHECK + 原因，不落条目")
    void cannotSelfcheckWhenPeriodHasNoData() {
        buildChain();
        Cas24Selfcheck batch = scSvc.runSelfcheck(periodReq("2030-01"), OP);
        assertEquals("CANNOT_SELFCHECK", batch.conclusion, "FR-ACCT-14 输出列：无法自检");
        assertNotNull(batch.cannotSelfcheckReason, "必须给原因");
        assertTrue(batch.cannotSelfcheckReason.contains("无法"), "原因语义：" + batch.cannotSelfcheckReason);
        assertEquals(Integer.valueOf(0), batch.passCount);
        assertEquals(Integer.valueOf(0), batch.failCount);
        assertEquals(0, engine.table("t_cas24_selfcheck_item").rows.size(),
                "无法自检批次不落条目");
        assertEquals("Y", batch.pendingExternalReview, "标注仍恒 'Y'（R-ACCT-10）");
    }

    @Test
    @DisplayName("期间聚合：PERIOD 范围圈定可见关系聚合为一批次；④任一触发未生成 ⇒ 聚合不通过")
    void periodScopeAggregatesVisibleRelations() {
        Chain c = buildChain();
        Cas24Selfcheck batch = scSvc.runSelfcheck(periodReq("2026-10"), OP);

        assertEquals("PERIOD", batch.scopeType);
        assertEquals("2026-10", batch.scopeKey, "期间键=scopeKey（idx_cas24_selfcheck_scope）");
        assertNull(batch.subAccountId, "全账户自检批次 sub_account_id=NULL（§4.6.1 #51 ②）");
        // 期货腿④过（有记录）、期权腿④不通过（触发未生成）⇒ 聚合不通过
        List<Cas24SelfcheckItem> items = itemDao.findBySelfcheck(batch.id);
        assertEquals(5, items.size(), "聚合仍五条逐条");
        assertEquals("FAIL", items.get(3).result, "④聚合=任一不通过即不通过");
        assertNotNull(items.get(3).failReason);
        assertEquals("PASS", items.get(0).result, "①聚合（两关系均正式指定）");
        assertEquals("PASS", items.get(2).result, "③聚合（两关系均当期评估）");
        assertEquals("HAS_FAIL", batch.conclusion);
    }

    // ================================================================ 越权负例

    @Test
    @DisplayName("越权负例：不可见子账户的关系自检/详情/导出均拒绝（fail-closed）")
    void unauthorizedAccessRejected() {
        Chain c = buildChain();
        Cas24Selfcheck batch = scSvc.runSelfcheck(
                relationReq(c.futuresRelation.id, "2026-10"), OP);

        visible = List.of(2); // 切换为另一授权（子账户 2）
        BizException post = assertThrows(BizException.class,
                () -> scSvc.runSelfcheck(relationReq(c.optionRelation.id, "2026-10"), OP),
                "子账户 1 的关系对授权 2 不可见");
        assertTrue(String.valueOf(post.getMessage()).contains("无权访问该子账户数据"),
                "可见性拒绝语义：" + post.getMessage());
        assertThrows(BizException.class, () -> scSvc.findByNo(batch.selfcheckNo, OP),
                "详情读取同拒绝");
        assertThrows(BizException.class, () -> scSvc.exportReport(batch.selfcheckNo, OP),
                "导出同拒绝");
        // 分页 fail-closed：不可见 ⇒ 不出他人数据（可见集空 ⇒ 恒假；授权 2 ⇒ 仅子账户 2）
        com.hedgeguard.dao.PageQuery q = com.hedgeguard.dao.PageQuery.of("1", "20", null,
                "selfcheck_time", "desc");
        assertEquals(0, scSvc.listBatches(q, null, null, null, OP).total(),
                "授权 2 不得见子账户 1 的批次（NFR-SEC-7）");
        visible = List.of(1);
        assertEquals(1, scSvc.listBatches(q, null, null, null, OP).total(),
                "授权 1 可见本批次");
    }

    // ================================================================ 幂等 + 取号

    @Test
    @DisplayName("幂等：同 scope 同业务日重复发起返回既有批次不重复落批；异 scope 顺延取号")
    void idempotentRerunDoesNotDuplicateBatch() {
        Chain c = buildChain();
        Cas24Selfcheck first = scSvc.runSelfcheck(
                relationReq(c.futuresRelation.id, "2026-10"), OP);
        Cas24Selfcheck again = scSvc.runSelfcheck(
                relationReq(c.futuresRelation.id, "2026-10"), OP);
        assertEquals(first.id, again.id, "同批次（幂等命中）");
        assertEquals(first.selfcheckNo, again.selfcheckNo);
        assertEquals(1, engine.table("t_cas24_selfcheck").rows.size(), "不重复落批");
        assertEquals(5, engine.table("t_cas24_selfcheck_item").rows.size(), "条目不重复");

        assertTrue(first.selfcheckNo.startsWith("SC20261013"), "批次号=SC+业务日："
                + first.selfcheckNo);
        // 异 scope ⇒ 新批次，序号顺延（当日前缀最大 + 1）
        Cas24Selfcheck second = scSvc.runSelfcheck(
                relationReq(c.optionRelation.id, "2026-10"), OP);
        assertEquals("SC202610130002", second.selfcheckNo, "序号顺延（当日最大+1）");
        assertEquals(2, engine.table("t_cas24_selfcheck").rows.size());

        // 期间聚合批次幂等同法
        Cas24Selfcheck p1 = scSvc.runSelfcheck(periodReq("2026-10"), OP);
        Cas24Selfcheck p2 = scSvc.runSelfcheck(periodReq("2026-10"), OP);
        assertEquals(p1.id, p2.id, "期间批次幂等");
        assertEquals(3, engine.table("t_cas24_selfcheck").rows.size());

        // T-197 复核 O-2 收口：幂等键含期间——同日同关系换期间 ⇒ 各自成批（不错误命中他期间快照）
        Cas24Selfcheck otherPeriod = scSvc.runSelfcheck(
                relationReq(c.futuresRelation.id, "2026-11"), OP);
        assertEquals("SC202610130004", otherPeriod.selfcheckNo, "换期间 ⇒ 新批次顺延");
        assertEquals(c.futuresRelation.relationNo + "|2026-11", otherPeriod.scopeKey,
                "scope_key=关系编号|期间键（O-2 收口形态；32+1+7=40 ≤ 64）");
        Cas24Selfcheck hit = scSvc.runSelfcheck(
                relationReq(c.futuresRelation.id, "2026-11"), OP);
        assertEquals(otherPeriod.id, hit.id, "同 (关系,期间) 仍幂等命中");
        assertEquals(4, engine.table("t_cas24_selfcheck").rows.size());
    }

    // ================================================================ fail-closed 空可见集（TE-T197-001 条件闭环）

    @Test
    @DisplayName("TE-T197-001：零授权 operator ⇒ 圈定 0 关系走「无法自检」+ listBatches 走 IS NOT NULL 恒假形态，不抛")
    void emptyVisibleSetFailsClosedWithoutThrowing() {
        // 事先以正常授权落 1 批（子账户 1），再切零授权
        visible = List.of(1);
        Chain c0 = buildChain();
        Cas24Selfcheck batch = scSvc.runSelfcheck(
                relationReq(c0.futuresRelation.id, "2026-10"), OP);
        assertEquals(1, engine.table("t_cas24_selfcheck").rows.size());
        // 零授权：可见集空 ⇒ ① listBatches 命中引擎 IS NOT NULL + IN (-1) 恒假形态（M-NOTNULL 判别面）
        visible = List.of();
        com.hedgeguard.dao.PageQuery q = com.hedgeguard.dao.PageQuery.of("1", "20", null,
                "selfcheck_time", "desc");
        assertEquals(0, scSvc.listBatches(q, null, null, null, OP).total(),
                "空可见集 ⇒ 分页恒空（fail-closed，不抛 UnsupportedOperationException）");
        // ② 期间自检：圈定 0 关系 ⇒ CANNOT_SELFCHECK 落批（FR-ACCT-14「无法自检」输出列）
        Cas24Selfcheck cannot = scSvc.runSelfcheck(periodReq("2026-10"), OP);
        assertEquals("CANNOT_SELFCHECK", cannot.conclusion, "零授权圈定 0 关系 ⇒ 无法自检");
        assertNotNull(cannot.cannotSelfcheckReason);
        // ③ 单关系自检：读面断言对不可见关系拒绝（语义不变，回归确认）
        assertThrows(BizException.class,
                () -> scSvc.runSelfcheck(relationReq(batch.id, "2026-10"), OP),
                "零授权单关系自检 ⇒ 可见性拒绝");
    }

    // ================================================================ 端点级（POST/GET selfcheck + GET export）

    @Test
    @DisplayName("端点级：POST /acct/selfcheck、GET 清单/详情、GET /acct/export CSV 直返 + 头注入拒绝")
    void endpointLevelPostGetAndExport() {
        Chain c = buildChain();
        Router router = new Router();
        AcctRoutes.register(router, svc, scSvc);
        SelfcheckHandler postHandler = (SelfcheckHandler)
                router.match("POST", "/api/v1/acct/selfcheck").handler();
        SelfcheckHandler getHandler = (SelfcheckHandler)
                router.match("GET", "/api/v1/acct/selfcheck").handler();
        SelfcheckExportHandler exportHandler = (SelfcheckExportHandler)
                router.match("GET", "/api/v1/acct/export").handler();

        // —— 验收 1（端点级）：POST 发起 ⇒ 200 信封 + 逐条 items + 证据位置
        HttpResult post = postHandler.handle(postCtx("{\"scopeType\":\"HEDGE_RELATION\","
                + "\"hedgeRelationId\":" + c.futuresRelation.id + ",\"periodKey\":\"2026-10\"}"));
        assertEquals(200, post.status);
        Map<String, Object> envelope = JsonCodec.fromJson(
                new String(post.body, StandardCharsets.UTF_8), Map.class);
        assertEquals(0, ((Number) envelope.get("code")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertEquals("ALL_PASS", data.get("conclusion"));
        assertEquals("Y", data.get("pendingExternalReview"));
        String selfcheckNo = String.valueOf(data.get("selfcheckNo"));
        assertEquals(5, ((List<?>) data.get("items")).size(), "端点级逐条 items");
        assertTrue(String.valueOf(data.get("notSubstituteNote")).contains("自检不替代外部复核"));

        // —— 查询清单（PageData 分页信封）
        HttpResult list = getHandler.handle(getCtx("/api/v1/acct/selfcheck?page=1&size=10"));
        assertEquals(200, list.status);
        Map<String, Object> listEnvelope = JsonCodec.fromJson(
                new String(list.body, StandardCharsets.UTF_8), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) listEnvelope.get("data");
        assertEquals(1L, ((Number) page.get("total")).longValue(), "PageData.total");
        assertEquals(1, ((List<?>) page.get("items")).size());

        // —— 单批次详情（selfcheckNo 参数）
        HttpResult detail = getHandler.handle(
                getCtx("/api/v1/acct/selfcheck?selfcheckNo=" + selfcheckNo));
        assertEquals(200, detail.status);
        Map<String, Object> detailEnvelope = JsonCodec.fromJson(
                new String(detail.body, StandardCharsets.UTF_8), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> detailData = (Map<String, Object>) detailEnvelope.get("data");
        assertEquals(selfcheckNo, detailData.get("selfcheckNo"));
        assertEquals(5, ((List<?>) detailData.get("items")).size(), "详情含逐条结论+证据位置");

        // —— 验收 3/4（端点级）：GET /acct/export CSV 直返 + Content-Disposition
        HttpResult export = exportHandler.handle(
                getCtx("/api/v1/acct/export?selfcheckNo=" + selfcheckNo));
        assertEquals(200, export.status);
        assertEquals("text/csv; charset=UTF-8", export.contentType);
        String disposition = export.headers().get("Content-Disposition");
        assertNotNull(disposition, "Content-Disposition 必带");
        assertTrue(disposition.contains("cas24-selfcheck-" + selfcheckNo + ".csv"),
                "文件名=已校验批次号拼装：" + disposition);
        String csv = new String(export.body, StandardCharsets.UTF_8);
        assertTrue(csv.contains("自检不替代外部复核"), "验收 3：不替代说明在报告");
        assertTrue(csv.contains("BATCH_INFO"), "批次段在");

        // —— 验收 2（端点级）：期权腿 POST ⇒ items 第 4 条 FAIL
        HttpResult post2 = postHandler.handle(postCtx("{\"scopeType\":\"HEDGE_RELATION\","
                + "\"hedgeRelationId\":" + c.optionRelation.id + ",\"periodKey\":\"2026-10\"}"));
        Map<String, Object> envelope2 = JsonCodec.fromJson(
                new String(post2.body, StandardCharsets.UTF_8), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data2 = (Map<String, Object>) envelope2.get("data");
        assertEquals("HAS_FAIL", data2.get("conclusion"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) data2.get("items");
        assertEquals("FAIL", items2.get(3).get("result"), "验收 2 端点级：④不通过");

        // —— 参数缺失/注入负例（端点级 400/异常）
        HttpResult badBody = postHandler.handle(postCtx("{\"periodKey\":\"2026-10\"}"));
        assertEquals(400, badBody.status, "缺 scopeType ⇒ 400");
        HttpResult badBody2 = postHandler.handle(postCtx(
                "{\"scopeType\":\"HEDGE_RELATION\",\"periodKey\":\"2026-13\"}"));
        assertEquals(400, badBody2.status, "非法期间 ⇒ 400");
        HttpResult badExport = exportHandler.handle(getCtx("/api/v1/acct/export"));
        assertEquals(400, badExport.status, "缺 selfcheckNo ⇒ 400");
        // CRLF 注入载体经查询串进入 ⇒ 服务层白名单拒绝（不进 Content-Disposition）
        assertThrows(BizException.class, () -> exportHandler.handle(
                getCtx("/api/v1/acct/export?selfcheckNo=SC202610130001%0d%0aX-Evil:%201")),
                "头注入载体须拒绝");
        // 未注册方法不得命中（PATCH 等）
        assertNull(router.match("DELETE", "/api/v1/acct/selfcheck"));
        assertNull(router.match("POST", "/api/v1/acct/export"));
    }

    // ================================================================ 工具

    private RequestContext postCtx(String body) {
        RequestContext ctx = new RequestContext("POST", "/api/v1/acct/selfcheck", null,
                Map.of("x-hg-session", "S-1"), body, "HGTEST-T197", "127.0.0.1");
        ctx.bindSession(new Session("S-1", OP, "op", 1L, Clocks.now().plusMinutes(30)));
        return ctx;
    }

    private RequestContext getCtx(String pathWithQuery) {
        int q = pathWithQuery.indexOf('?');
        String path = q < 0 ? pathWithQuery : pathWithQuery.substring(0, q);
        String query = q < 0 ? null : pathWithQuery.substring(q + 1);
        RequestContext ctx = new RequestContext("GET", path, query,
                Map.of("x-hg-session", "S-1"), "", "HGTEST-T197", "127.0.0.1");
        ctx.bindSession(new Session("S-1", OP, "op", 1L, Clocks.now().plusMinutes(30)));
        return ctx;
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("docs/DEPLOY/schema"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（含 docs/DEPLOY/schema）");
    }
}
