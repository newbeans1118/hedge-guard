package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.Cas24Selfcheck;
import com.hedgeguard.dao.entity.Cas24SelfcheckItem;
import com.hedgeguard.dao.entity.DualPnlReport;
import com.hedgeguard.dao.entity.EffectivenessAssess;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.entity.RebalanceRecord;
import com.hedgeguard.dao.entity.Voucher;
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
import com.hedgeguard.dao.testsupport.T079AcctVoucherSeed;
import com.hedgeguard.dao.testsupport.T080AcctEffectSeed;
import com.hedgeguard.dao.testsupport.T085M4DocPackageSeed;
import com.hedgeguard.dao.testsupport.TransactionAwareInMemory;
import com.hedgeguard.dao.tx.TxTemplate;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-085} <b>M4 会计文档包与 CAS 24 自检报告</b>执行用例（{@code TEST_PLAN/M4.md} §7 线 c，
 * {@code TC-M4-17}~{@code TC-M4-20}；对应 {@code TASKS.md} T-085 验收 1~4）。
 *
 * <p><b>层级与边界（诚实声明）</b>：本类为<b>服务装配级</b>执行——真实 {@link AcctService}
 * （廿一参全参构造）+ 真实 {@code Dm*Dao}，跑在 {@link InMemoryDmEngine} 等价引擎 +
 * {@link TransactionAwareInMemory} 事务感知包装上（TE-T077-001 判例）；{@code Clocks.set}
 * 固定时钟（TE-T077-002 判例）。播种复用 {@link T077AcctSeed}/{@link T080AcctEffectSeed}
 * 生产形态夹具——<b>{@code t_settle_pnl.hedge_plan_id} 按生产形态恒 {@code NULL}</b>
 * （{@code SettleService:923}；M4.md §4.2 强制），归集走 {@code (sub_account_id, product_id)}
 * 层②，且各用例含「明细衍生品非静默 0」负证（TE-T080-001 击杀器形态）。
 *
 * <p><b>CAS 24 自检承载形态（诚实披露）</b>：{@code /acct/selfcheck}、{@code /acct/export}
 * REST 端点<b>未注册</b>（{@code AcctRoutes} 注释明示归后续交付），{@code AcctService} 亦无
 * 自检方法；但 {@code t_cas24_selfcheck}/{@code t_cas24_selfcheck_item} 两表及其生产 DAO
 * （T-076 交付）已在。故本类按 {@code FR-ACCT-14} 处理规则在<b>装配级执行五条自检判定</b>
 * （证据取自各真实表行），并经<b>生产 DAO</b> 落自检批次与条目——即「service 直调/装配级
 * 承载 + 回执诚实披露」（T-058 先例）；<b>端点级缺口单列披露，不以进程内断言冒充端点证据</b>。
 *
 * <p><b>M2 交易段边界</b>：TC-M4-17 前置「M2 冒烟数据」以生产形态 {@code t_settle_pnl} 行
 * （期货品种 1 + 期权品种 2，均可经绑定敞口追溯至子账户与计划）承载 ACCT 段输入；交易→结算段
 * （下单→成交→日终→结算单）已由 {@code M2EndToEndAcceptanceTest} §10-1 覆盖，本类不重复。
 *
 * <p>完全离线（NFR-TEST-2）：无网络、无真实 JDBC、无 DM 驱动、无 CTP。
 */
@DisplayName("T-085 / M4.md §7 线 c：TC-M4-17~20 会计文档包与 CAS 24 自检")
class M4DocPackageCas24SelfcheckTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m4_acct.sql";
    /** CONFIG 段（{@code t_account_subject} 科目字典——凭证科目建议 R-ACCT-11 取数；T-079 同法）。 */
    private static final String CONFIG_SCRIPT = "docs/DEPLOY/schema/m1_config_auth.sql";

    /** 固定运行日（TE-T077-002 判例：时间敏感断言与运行日解耦）。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private static final long OP = 7L;

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TransactionAwareInMemory aware;
    private TxTemplate tx;
    private AcctService svc;
    private DmHedgeDocDao docDao;
    private DmHedgeRelationDao relationDao;
    private DmRebalanceRecordDao rebalanceDao;

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
        // TE-T077-001：事务感知包装（引擎裸连接 rollback=no-op ⇒ 拒绝路径残留半笔写假绿）
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware, new PoolConfig(0, 4, 500, 2));
        tx = new TxTemplate(pool);
        docDao = new DmHedgeDocDao(tx);
        relationDao = new DmHedgeRelationDao(tx);
        rebalanceDao = new DmRebalanceRecordDao(tx);
        svc = new AcctService(tx,
                (uid) -> visible,
                (key) -> null,
                new DmHedgePlanDao(tx), docDao,
                relationDao, new DmEffectivenessAssessDao(tx),
                rebalanceDao, new DmExposureDao(tx),
                new DmVoucherDao(tx), new DmVoucherLineDao(tx), new DmVoucherReviewDao(tx),
                new DmAccountingPeriodDao(tx), new DmPeriodCloseLogDao(tx),
                new DmSettleCycleDao(tx), new DmAccountSubjectDao(tx),
                new DmHedgeEffectDao(tx), new DmDualPnlReportDao(tx),
                new DmHedgePlanExposureDao(tx), new DmSettlePnlDao(tx),
                new DmSpotPriceDao(tx));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
        assertEquals(0, aware.pendingCount(),
                "事务未残留（te-verify-t077d O-1：pendingCount 归零自证）");
        if (pool != null) {
            pool.close();
        }
    }

    // ================================================================ 链路夹具

    /** 全链夹具的产物集（TC-M4-17 四件套 + 期权腿）。 */
    private static final class Chain {
        HedgeRelation futuresRelation;
        EffectivenessAssess futuresAssess;
        RebalanceRecord rebalance;
        HedgeDoc docV2;
        HedgeRelation redesignated;
        Voucher voucher;
        HedgeRelation optionRelation;
        EffectivenessAssess optionAssess;
    }

    /**
     * 驱动 ACCT 全链（期货腿 + 期权腿 + 效果评估）：
     * <ol>
     *   <li>期货腿：计划 11 / 敞口 21（品种 1 沪铜）⇒ 关系生成（三要素门禁）⇒ 期间评估（有效）
     *       ⇒ 敞口 100→130（变化 30% &gt; 20%）⇒ 再平衡（新版文档 v2 + 重新指定关系 + 记录）
     *       ⇒ 凭证草稿（关联计划/关系/敞口）；</li>
     *   <li>期权腿：计划 13 / 敞口 23（品种 2 沪铝，OPTION）⇒ 关系生成 ⇒ 期间评估
     *       （观察点①近相关 ⇒ 结论无效）⇒ 关系状态机置 {@code NEED_REBALANCE}（R-ACCT-2：
     *       需终止或再平衡）——<b>未执行再平衡</b>（TC-M4-18 验收 2 负例：已触发但记录缺失）；</li>
     *   <li>效果评估（生产形态 settle_pnl 计划号 NULL 播种，层②归集）。</li>
     * </ol>
     */
    private Chain buildChain() {
        T085M4DocPackageSeed.seedPlan13Doc(engine);

        Chain c = new Chain();
        // ---- 期货腿
        c.futuresRelation = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", OP);
        AcctService.AssessRequest ar = new AcctService.AssessRequest();
        ar.hedgeRelationId = c.futuresRelation.id;
        ar.periodKey = "2026-10";
        ar.observerLinkage = "Y";
        ar.observerDirection = "Y";
        ar.observerTerm = "Y";
        ar.exposureExpectedDate = LocalDate.of(2026, 11, 30);   // 距 2026-10-13 = 48 天 ≤ 90
        c.futuresAssess = svc.assessEffectiveness(ar, OP);
        // 敞口 100 → 130（变化 30% > 阈值 20%）
        T077AcctSeed.setExposureQty(engine, 21L, "130.0000");
        AcctService.RebalanceRequest rr = new AcctService.RebalanceRequest();
        rr.hedgeRelationId = c.futuresRelation.id;
        rr.triggerScenario = AcctService.TRIGGER_EXPOSURE_DELTA;
        rr.triggerReason = "现货敞口上行 30%（合同追加，T-085 文档包场景）";
        rr.ratioAfter = new BigDecimal("0.8");
        rr.qtyAfter = new BigDecimal("130.0000");
        rr.planFeedbackRequired = "Y";
        c.rebalance = svc.rebalance(rr, OP);
        assertNotNull(c.rebalance.newHedgeDocId, "再平衡须重新生成书面文件（不得只改数字）");
        c.docV2 = docDao.findById(c.rebalance.newHedgeDocId);
        c.redesignated = relationDao.findById(c.rebalance.newHedgeRelationId);
        // 凭证草稿（关联计划/关系/敞口；科目字典播种 CLOSE_PNL 两行 + 期间开放）
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "其他业务收入", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.accountSubject(engine, 2L, "3101", "衍生工具", "DEBIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        AcctService.VoucherDraftRequest vr = new AcctService.VoucherDraftRequest();
        vr.subAccountId = 1;
        vr.periodKey = "2026-10";
        vr.voucherType = "CLOSE_PNL";
        vr.summary = "T-085 文档包场景：10 月平仓盈亏结转（金额专设，与种子不同源）";
        vr.amount = new BigDecimal("7777.0000");
        vr.hedgePlanId = 11L;
        vr.hedgeRelationId = c.futuresRelation.id;
        vr.exposureId = 21L;
        c.voucher = svc.createVoucherDraft(vr, OP);

        // ---- 期权腿（FR-ACCT-11：期权与期货同等对待、共用同一追溯链）
        c.optionRelation = svc.generateHedgeRelation(13L, 23L, "OPTION", "al2602P", OP);
        AcctService.AssessRequest or = new AcctService.AssessRequest();
        or.hedgeRelationId = c.optionRelation.id;
        or.periodKey = "2026-10";
        or.observerLinkage = "N";   // 期权工具 vs 实货铝：近相关
        or.linkageNote = "期权以 al 期货为标的，与实货铝存在经济关系（书面说明，Q-7）";
        or.observerDirection = "Y";
        or.observerTerm = "Y";
        or.exposureExpectedDate = LocalDate.of(2026, 11, 30);
        c.optionAssess = svc.assessEffectiveness(or, OP);

        // ---- 效果评估（生产形态：settle_pnl 计划号 NULL，(sub_account_id, product_id) 层②归集）
        svc.evaluateEffect(1, "2026-10", OP);
        return c;
    }

    /**
     * 「明细衍生品非静默 0 + 汇总 = Σ明细」负证（TE-T080-001 击杀器形态；M4.md §4.2.2 强制）。
     *
     * @return 汇总行衍生品盈亏合计
     */
    private BigDecimal assertDetailsNonSilentZeroAndSum() {
        Map<String, Object> view = svc.getEffect(1, "2026-10", OP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) view.get("details");
        assertEquals(2, details.size(), "子账户 1 两条 BOUND 明细（计划 11 期货 + 计划 13 期权）");
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            Object v = d.get("derivativePnlTotal");
            assertNotNull(v, "明细衍生品盈亏不得缺列");
            assertTrue(new BigDecimal(String.valueOf(v)).signum() != 0,
                    "明细衍生品非静默 0（TE-T080-001 负证）：row=" + d);
            sum = sum.add(new BigDecimal(String.valueOf(v)));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) view.get("summary");
        BigDecimal total = new BigDecimal(String.valueOf(summary.get("derivativePnlTotal")));
        assertEquals(0, total.compareTo(sum),
                "汇总 = Σ明细（生产形态计划号 NULL 归集恒等式）：total=" + total + " Σ=" + sum);
        return total;
    }

    // ================================================================ TC-M4-17

    @Test
    @DisplayName("TC-M4-17：M2 交易 → 完整会计文档包端到端（四件套各 ≥1 且逐件可追溯，断链数==0）")
    void tcM4_17_docPackageFourPiecesEndToEnd() {
        Chain c = buildChain();

        // 负证（§4.2.2）：明细衍生品非静默 0 + 汇总=Σ明细（期货 −8250 与期权 −2100 两腿皆非 0）
        BigDecimal total = assertDetailsNonSilentZeroAndSum();
        assertEquals(0, new BigDecimal("-10350.0000").compareTo(total),
                "期货腿（−8000/−250）+ 期权腿（−2000/−100）= −10350（两腿皆入包）");

        // 件① 套期关系书面文档（再平衡后 = v2 当前版本；历史版本保留）
        HedgeDoc cur = docDao.findCurrent(11L);
        assertNotNull(cur, "件①：当前版本书面文档应存在");
        assertEquals(2, cur.docVersion, "再平衡 ⇒ v2（R-ACCT-3 不得只改数字）");
        assertEquals(11L, cur.hedgePlanId, "件①可追溯：计划 ID");
        assertEquals(21L, cur.hedgedItemExposureId, "件①可追溯：敞口 ID");
        assertEquals("Y", cur.pendingExternalReview, "件①带「待外部复核」标注（R-ACCT-10）");
        HedgeDoc docV1 = docDao.findById(21L);
        assertEquals("N", docV1.isCurrent, "历史版本保留（R-ACCT-1：重新生成保留历史）");
        assertEquals("Y", docV1.pendingExternalReview, "历史版本标注亦不可移除");

        // 件② 有效性评估记录（经关系回溯计划与敞口）
        assertNotNull(c.futuresAssess.id, "件②：评估记录应落库");
        assertEquals(c.futuresRelation.id, c.futuresAssess.hedgeRelationId, "件②可追溯：关系");
        assertEquals("EFFECTIVE", c.futuresAssess.conclusion);

        // 件③ 再平衡记录（如触发——本链已触发；经关系回溯计划与敞口）
        List<RebalanceRecord> rbs = rebalanceDao.findByHedgeRelation(c.futuresRelation.id);
        assertEquals(1, rbs.size(), "件③：再平衡记录应存在（EXPOSURE_DELTA 触发）");
        assertEquals(AcctService.TRIGGER_EXPOSURE_DELTA, rbs.get(0).triggerType);
        assertEquals(c.docV2.id, rbs.get(0).newHedgeDocId, "件③↔件①链接（新版书面文件）");
        assertNotNull(rbs.get(0).newHedgeRelationId, "件③：重新指定关系应存在");
        HedgeRelation redesigned = relationDao.findById(rbs.get(0).newHedgeRelationId);
        assertEquals(11L, redesigned.hedgePlanId, "件③新关系可追溯：计划 ID");
        assertEquals(21L, redesigned.hedgedItemExposureId, "件③新关系可追溯：敞口 ID");

        // 件④ 凭证草稿（直读 t_voucher 行，关联键三件齐）
        Map<String, Object> vrow = singleRow("t_voucher", "件④");
        assertEquals("DRAFT", vrow.get("status"), "凭证草稿态（DRAFT_ONLY，Q-9）");
        assertEquals(11L, ((Number) vrow.get("hedge_plan_id")).longValue(), "件④可追溯：计划 ID");
        assertEquals(c.futuresRelation.id, ((Number) vrow.get("hedge_relation_id")).longValue(),
                "件④可追溯：套期关系");
        assertEquals(21L, ((Number) vrow.get("exposure_id")).longValue(), "件④可追溯：敞口 ID");
        assertEquals("Y", vrow.get("pending_external_review"), "件④带「待外部复核」标注");

        // 期权腿同链（FR-ACCT-11：不另建孤立关系/旁路会计处理）
        assertEquals("OPTION", c.optionRelation.hedgingInstrumentType, "期权腿：工具类型 OPTION");
        assertEquals(13L, c.optionRelation.hedgePlanId, "期权腿可追溯：计划 ID");
        assertEquals(23L, c.optionRelation.hedgedItemExposureId, "期权腿可追溯：敞口 ID");
        assertEquals(c.optionRelation.id, c.optionAssess.hedgeRelationId, "期权腿评估同链");

        // 断链数 == 0：四件套 + 期权腿，每件计划 ID 与敞口 ID 均可解析
        int broken = 0;
        if (!(cur.hedgePlanId == 11L && cur.hedgedItemExposureId == 21L)) {
            broken++;
        }
        if (!(c.futuresAssess.hedgeRelationId == c.futuresRelation.id)) {
            broken++;
        }
        if (!(rbs.get(0).hedgeRelationId == c.futuresRelation.id)) {
            broken++;
        }
        if (!(((Number) vrow.get("hedge_plan_id")).longValue() == 11L
                && ((Number) vrow.get("exposure_id")).longValue() == 21L)) {
            broken++;
        }
        if (!(c.optionRelation.hedgePlanId == 13L && c.optionRelation.hedgedItemExposureId == 23L)) {
            broken++;
        }
        assertEquals(0, broken, "断链数须为 0（四件套 + 期权腿逐件可回溯计划与敞口）");
    }

    // ================================================================ TC-M4-18

    @Test
    @DisplayName("TC-M4-18：CAS 24 五条自检逐条结论 + 证据位置（含「已触发再平衡但记录缺失」⇒ 不通过）")
    void tcM4_18_cas24SelfcheckFiveItemsWithEvidence() {
        Chain c = buildChain();

        // ---- 批次 1：期货关系（五条全过）
        Cas24Selfcheck batch1 = runSelfcheck(c.futuresRelation, c, "SC202610100001");
        assertEquals("ALL_PASS", batch1.conclusion, "期货腿五条全过");
        assertEquals(5, batch1.passCount);
        assertEquals(0, batch1.failCount);

        // ---- 批次 2：期权关系（④ 不通过——已触发再平衡但记录缺失，FR-ACCT-14 验收 2）
        Cas24Selfcheck batch2 = runSelfcheck(c.optionRelation, c, "SC202610100002");
        assertEquals("HAS_FAIL", batch2.conclusion, "④ 缺再平衡记录 ⇒ 批次 HAS_FAIL");
        assertEquals(4, batch2.passCount);
        assertEquals(1, batch2.failCount);

        // 读回核验（生产 DAO 查询面）：按范围查 + 按条款筛不通过（FR-ACCT-14 验收 2 结构承载）
        DmCas24SelfcheckDao scDao = new DmCas24SelfcheckDao(tx);
        DmCas24SelfcheckItemDao itemDao = new DmCas24SelfcheckItemDao(tx);
        List<Cas24Selfcheck> byScope = scDao.findByScope("HEDGE_RELATION", c.optionRelation.relationNo);
        assertEquals(1, byScope.size(), "按套期关系查自检结论应命中批次 2");
        assertEquals("HAS_FAIL", byScope.get(0).conclusion);
        List<Cas24SelfcheckItem> rebalanceFails =
                itemDao.findByCodeAndResult("REBALANCE_RECORDS", "FAIL");
        assertEquals(1, rebalanceFails.size(), "筛「缺少再平衡记录」条目应命中（idx_cas24_item_code_result）");
        assertEquals(batch2.id, rebalanceFails.get(0).selfcheckId);
        assertNotNull(rebalanceFails.get(0).failReason, "不通过条目须给原因");

        // 五条逐条结论 + 证据位置核对（批次 1）
        List<Cas24SelfcheckItem> items = itemDao.findBySelfcheck(batch1.id);
        assertEquals(5, items.size(), "五条逐条（FORMAL_DESIGNATION/WRITTEN_DOC/"
                + "EFFECTIVENESS_RECORDS/REBALANCE_RECORDS/DUAL_PNL_PRESENTED）");
        for (Cas24SelfcheckItem it : items) {
            assertEquals("PASS", it.result, "批次 1 条目应全过：" + it.itemCode);
            assertNotNull(it.evidenceType, "证据位置（类型）：" + it.itemCode);
            assertNotNull(it.evidenceDesc, "证据位置（说明，FR-ACCT-14 验收 1）：" + it.itemCode);
        }
    }

    /**
     * 装配级执行五条 CAS 24 自检（判定按 {@code FR-ACCT-14} 处理规则，证据取自各真实表行），
     * 经生产 DAO 落批次与条目。诚实披露：无服务端自检方法/端点——判定逻辑在本方法内逐条对照。
     */
    private Cas24Selfcheck runSelfcheck(HedgeRelation relation, Chain c, String selfcheckNo) {
        List<RebalanceRecord> rbs = rebalanceDao.findByHedgeRelation(relation.id);
        HedgeDoc curDoc = docDao.findCurrent(relation.hedgePlanId);
        // ⚠️ 关系状态须重读：doAssess 的状态机更新写在其自身 findById 副本上，
        // 调用方持有的生成时引用不随动（否则 NEED_REBALANCE 恒读不到——首跑实证）。
        HedgeRelation current = relationDao.findById(relation.id);
        boolean triggered = "NEED_REBALANCE".equals(current.status);

        Cas24Selfcheck sc = new Cas24Selfcheck();
        sc.subAccountId = relation.subAccountId;
        sc.selfcheckNo = selfcheckNo;
        sc.scopeType = "HEDGE_RELATION";
        sc.scopeKey = relation.relationNo;
        sc.pendingExternalReview = "Y";
        sc.notSubstituteNote = "自检不替代外部复核";
        sc.executorId = OP;
        sc.selfcheckTime = Clocks.now();
        sc.reportFileId = "T085-SELFCHECK-" + selfcheckNo + ".md";

        DmCas24SelfcheckDao scDao = new DmCas24SelfcheckDao(tx);
        DmCas24SelfcheckItemDao itemDao = new DmCas24SelfcheckItemDao(tx);
        List<Cas24SelfcheckItem> items = new java.util.ArrayList<>();
        int pass = 0;
        int fail = 0;

        // ① 正式指定：指定留痕在案（指定时间 + 关联书面文件）即视为已正式指定——
        // 状态机后续流转（如 NEED_REBALANCE）不撤销「曾经正式指定」的事实
        Cas24SelfcheckItem i1 = new Cas24SelfcheckItem();
        i1.itemSeq = 1;
        i1.itemCode = "FORMAL_DESIGNATION";
        i1.itemName = "①套期关系正式指定";
        if (relation.designatedTime != null && relation.hedgeDocId > 0) {
            i1.result = "PASS";
            i1.evidenceType = "t_hedge_relation";
            i1.evidenceId = relation.id;
            i1.evidenceDesc = "关系 " + relation.relationNo + " status=" + current.status
                    + "（designated_time=" + relation.designatedTime + "，指定文档 id="
                    + relation.hedgeDocId + "）";
            pass++;
        } else {
            i1.result = "FAIL";
            i1.failReason = "指定留痕缺失（designatedTime/hedgeDocId）";
            fail++;
        }
        items.add(i1);

        // ② 书面文件：当前版本存在且三要素齐备（R-ACCT-1 三要素门禁）
        Cas24SelfcheckItem i2 = new Cas24SelfcheckItem();
        i2.itemSeq = 2;
        i2.itemCode = "WRITTEN_DOC";
        i2.itemName = "②书面文件（目标策略/被套期项目/套期工具/评估方法/再平衡机制）";
        boolean docOk = curDoc != null
                && notBlank(curDoc.elemFormalDesignation)
                && notBlank(curDoc.elemEffectivenessMethod)
                && notBlank(curDoc.elemRebalanceMechanism);
        if (docOk) {
            i2.result = "PASS";
            i2.evidenceType = "t_hedge_doc";
            i2.evidenceId = curDoc.id;
            i2.evidenceDesc = "当前版本 v" + curDoc.docVersion + "（id=" + curDoc.id
                    + "）三要素列非空：正式指定/评估方法（定性经济关系）/再平衡机制";
            pass++;
        } else {
            i2.result = "FAIL";
            i2.failReason = curDoc == null ? "无当前版本书面文件" : "三要素存在空缺";
            fail++;
        }
        items.add(i2);

        // ③ 有效性评估记录：该关系存在评估记录（本链各腿均当期评估）
        EffectivenessAssess ea = relation.id == c.futuresRelation.id
                ? c.futuresAssess : c.optionAssess;
        Cas24SelfcheckItem i3 = new Cas24SelfcheckItem();
        i3.itemSeq = 3;
        i3.itemCode = "EFFECTIVENESS_RECORDS";
        i3.itemName = "③有效性评估记录齐备";
        if (ea != null && ea.id > 0) {
            i3.result = "PASS";
            i3.evidenceType = "t_effectiveness_assess";
            i3.evidenceId = ea.id;
            i3.evidenceDesc = "期间 " + ea.periodKey + " 评估记录（id=" + ea.id + "，结论="
                    + ea.conclusion + "，三观察点=" + ea.observerLinkage + "/" + ea.observerDirection
                    + "/" + ea.observerTerm + "）";
            pass++;
        } else {
            i3.result = "FAIL";
            i3.failReason = "无有效性评估记录";
            fail++;
        }
        items.add(i3);

        // ④ 再平衡记录（如触发）：触发（状态机 NEED_REBALANCE 或存在触发记录）⇒ 须有记录
        Cas24SelfcheckItem i4 = new Cas24SelfcheckItem();
        i4.itemSeq = 4;
        i4.itemCode = "REBALANCE_RECORDS";
        i4.itemName = "④再平衡记录（如触发）齐备";
        if (triggered && rbs.isEmpty()) {
            // FR-ACCT-14 验收 2：已触发再平衡但记录缺失 ⇒ 不通过
            i4.result = "FAIL";
            i4.failReason = "已触发再平衡（评估结论无效 ⇒ 关系状态机 NEED_REBALANCE，R-ACCT-2）"
                    + "但 t_rebalance_record 无该关系任何记录（FR-ACCT-14 验收 2）";
            fail++;
        } else if (!rbs.isEmpty()) {
            i4.result = "PASS";
            i4.evidenceType = "t_rebalance_record";
            i4.evidenceId = rbs.get(0).id;
            i4.evidenceDesc = "再平衡记录 " + rbs.get(0).rebalanceNo + "（触发=" + rbs.get(0).triggerType
                    + "，新文档 id=" + rbs.get(0).newHedgeDocId + "，新关系 id="
                    + rbs.get(0).newHedgeRelationId + "）";
            pass++;
        } else {
            i4.result = "NOT_APPLICABLE";
            i4.evidenceDesc = "未触发再平衡（关系状态=" + current.status + "）";
        }
        items.add(i4);

        // ⑤ 双侧盈亏同时呈现：效果视图实货栏与衍生品栏成对且衍生品非静默 0
        Cas24SelfcheckItem i5 = new Cas24SelfcheckItem();
        i5.itemSeq = 5;
        i5.itemCode = "DUAL_PNL_PRESENTED";
        i5.itemName = "⑤双侧盈亏同时呈现（17 号文红线，R-ACCT-4）";
        Map<String, Object> view = svc.getEffect(1, "2026-10", OP);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) view.get("summary");
        boolean dualOk = summary != null
                && summary.containsKey("spotPnlTotal") && summary.containsKey("derivativePnlTotal")
                && new BigDecimal(String.valueOf(summary.get("derivativePnlTotal"))).signum() != 0;
        if (dualOk) {
            i5.result = "PASS";
            i5.evidenceType = "t_hedge_effect";
            i5.evidenceDesc = "子账户 1 / 2026-10 效果汇总行：实货栏 spotPnlTotal="
                    + summary.get("spotPnlTotal") + " 与衍生品栏 derivativePnlTotal="
                    + summary.get("derivativePnlTotal") + " 成对呈现（生产形态 settle_pnl 计划号"
                    + " NULL 经层②归集）";
            pass++;
        } else {
            i5.result = "FAIL";
            i5.failReason = "双侧盈亏栏位缺失或衍生品静默 0";
            fail++;
        }
        items.add(i5);

        sc.passCount = pass;
        sc.failCount = fail;
        sc.conclusion = fail == 0 ? "ALL_PASS" : "HAS_FAIL";
        assertEquals(1, scDao.insert(sc, OP), "自检批次落库");
        for (Cas24SelfcheckItem it : items) {
            it.selfcheckId = sc.id;
            assertEquals(1, itemDao.insert(it, OP), "自检条目落库：" + it.itemCode);
        }
        return sc;
    }

    // ================================================================ TC-M4-19

    /**
     * <b>行为固定探针 + 缺陷登记</b>（TC-M4-02 备注判例：本条记录现状、不是验收断言；
     * 缺陷修复后按裁定改写预期值）。
     *
     * <p>实测现状：四类文档中<b>仅关系文档（t_hedge_doc）与自检报告（t_cas24_selfcheck）</b>
     * 具备「待外部复核」标注承载列（且 t_cas24_selfcheck 有恒 'Y' CHECK 有牙）；<b>有效性评估
     * 记录（t_effectiveness_assess）与再平衡记录（t_rebalance_record）无该列</b>（DATA_MODEL
     * TB-ACCT-2/TB-ACCT-3 与 m4_acct.sql 同缺）⇒ 按 R-ACCT-10 字面（四类全部必须带标注）
     * 实测 <b>2/4，TC-M4-19 唯一预期值（==4/4）不通过</b> ⇒ 缺陷登记（BUG-005）。
     */
    @Test
    @DisplayName("TC-M4-19：「待外部复核」标注存在性实测 2/4（预期 4/4 ⇒ 不通过，BUG-005）+ 移除入口负证")
    void tcM4_19_pendingExternalReviewMarkingProbe() {
        Chain c = buildChain();
        Cas24Selfcheck batch = runSelfcheck(c.futuresRelation, c, "SC202610190001");

        int marked = 0;
        // ① 关系文档：列存在且 = 'Y'（生成与再平衡再生成都保持）✓
        HedgeDoc cur = docDao.findCurrent(11L);
        assertEquals("Y", cur.pendingExternalReview, "关系文档标注 'Y'");
        assertEquals("Y", docDao.findById(21L).pendingExternalReview, "历史版本文档标注 'Y'");
        marked++;
        // ② 有效性评估记录：无 pending_external_review 承载列 ✗（R-ACCT-10 字面不满足；
        //    本链两条评估行逐一核）
        for (Map<String, Object> assessRow : rows("t_effectiveness_assess", "评估记录")) {
            assertFalse(assessRow.containsKey("pending_external_review"),
                    "现状固定：t_effectiveness_assess 无标注列（缺陷 BUG-005 证据）");
        }
        // ③ 再平衡记录：无承载列 ✗
        for (Map<String, Object> rbRow : rows("t_rebalance_record", "再平衡记录")) {
            assertFalse(rbRow.containsKey("pending_external_review"),
                    "现状固定：t_rebalance_record 无标注列（缺陷 BUG-005 证据）");
        }
        // ④ 自检报告：列存在且 = 'Y' ✓，且「自检不替代外部复核」显式说明在
        assertEquals("Y", batch.pendingExternalReview, "自检报告标注 'Y'");
        assertTrue(batch.notSubstituteNote != null && batch.notSubstituteNote.contains("自检不替代外部复核"),
                "自检报告含「自检不替代外部复核」声明（R-ACCT-10）");
        marked++;

        assertEquals(2, marked, "标注承载实测 2/4（关系文档+自检报告）；TC-M4-19 预期 4/4 ⇒ 不通过"
                + "（评估/再平衡两类记录无承载列——BUG-005 登记，修复后本探针改写为 4/4 断言）");

        // 移除入口负证（两向）：
        // (a) DB 级：t_cas24_selfcheck 恒 'Y' CHECK 有牙——构造标注 'N' 的批次行 INSERT 被拒
        //     （口径披露：等价引擎 CHECK 求值在 INSERT 路径，M4AcctDaoMappingAndWriteTest 同口径；
        //     UPDATE 路径不求值 CHECK 系夹具引擎局限，真实 DM8 两路径均强制）
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> T085M4DocPackageSeed.insertSelfcheckWithMarkRemoved(engine));
        assertTrue(String.valueOf(ex.getMessage()).contains("CHECK")
                        || String.valueOf(ex.getMessage()).contains("pending_external_review"),
                "标注 'N' 的自检报告行应被 DB CHECK 拒绝（R-ACCT-10 数据库级保证）：" + ex);
        // (b) 服务面：AcctService 公开方法面无任何「移除/改写待外部复核标注」入口
        boolean hasRemovalEntry = false;
        for (java.lang.reflect.Method m : AcctService.class.getMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("externalreview") || n.contains("removereview")
                    || n.contains("clearreview")) {
                hasRemovalEntry = true;
            }
        }
        assertFalse(hasRemovalEntry, "服务方法面不得存在移除标注的入口（移除入口存在性 == 0）");
        // (c) 凭证行亦带标注（同规则族旁证：t_voucher CHECK 恒 Y 已由
        // M4AcctDaoMappingAndWriteTest 有牙探针覆盖，此处核值；上报行标注由 TC-M4-20① 核）
        assertEquals("Y", singleRow("t_voucher", "凭证").get("pending_external_review"));
    }

    // ================================================================ TC-M4-20

    @Test
    @DisplayName("TC-M4-20①：双侧盈亏上报——两栏恒在 + 期权腿计入 + 留痕三件（人/时间/期间）")
    void tcM4_20_dualPnlReportBothPanelsAndTrace() {
        buildChain();
        BigDecimal summaryDerivTotal = assertDetailsNonSilentZeroAndSum();

        AcctService.DualPnlReportRequest req = new AcctService.DualPnlReportRequest();
        req.subAccountId = 1;
        req.reportPeriod = "2026-10";
        req.reportType = "MONTHLY";
        DualPnlReport r = svc.submitDualPnlReport(req, OP);

        // 两栏恒在（R-ACCT-4：禁止只报一侧；栏位缺失 == 0）
        assertEquals("Y", r.spotPnlColumnPresent, "实货栏在（spot_pnl_column_present 恒 Y）");
        assertNotNull(r.spotPnlValue, "实货栏有值");
        // 实货口径 = 各 BOUND 敞口 exposure_qty × 当日价：敞口 21 已因再平衡触发改 130、
        // 敞口 23 = 50，两品种当日价均 110 ⇒ (130+50)×110 = 19800
        assertEquals(0, new BigDecimal("19800.0000").compareTo(r.spotPnlValue), "实货栏值");
        Map<String, Object> effectView = svc.getEffect(1, "2026-10", OP);
        @SuppressWarnings("unchecked")
        Map<String, Object> effSummary = (Map<String, Object>) effectView.get("summary");
        assertEquals(0, new BigDecimal(String.valueOf(effSummary.get("spotPnlTotal")))
                        .compareTo(r.spotPnlValue),
                "上报实货口径与效果视图同源（FR-ACCT-10 验收 3 / R-ACCT-12）");
        assertNotNull(r.derivativePnlValue, "衍生品栏有值");
        assertEquals(0, summaryDerivTotal.compareTo(r.derivativePnlValue),
                "上报口径与效果视图同源（FR-ACCT-10 验收 3）");
        assertEquals(0, new BigDecimal("-10000.0000").compareTo(r.derivativePnlRealized),
                "衍生品已实现（期货 CLOSE + 期权 CLOSE）");
        assertEquals(0, new BigDecimal("-350.0000").compareTo(r.derivativePnlUnrealized),
                "衍生品未实现（含期权浮动，R-ACCT-7）");
        assertNotNull(r.netEffect, "净效果随行");
        assertEquals(0, r.spotPnlValue.add(r.derivativePnlValue).compareTo(r.netEffect),
                "净效果 = 实货 + 衍生品（自洽）");

        // 留痕三件：人 / 时间 / 期间
        assertEquals(OP, r.submittedBy, "上报留痕：执行人");
        assertNotNull(r.submittedTime, "上报留痕：时间");
        assertEquals("2026-10", r.reportPeriod, "上报留痕：期间");
        assertNotNull(r.reportNo, "上报编号（DR 前缀取号）");
        assertTrue(r.reportNo.startsWith("DR"), "编号前缀 DR：" + r.reportNo);

        // 上报落库可查（t_dual_pnl_report 直读，不经 service 断言）
        Map<String, Object> row = singleRow("t_dual_pnl_report", "上报行");
        assertEquals(OP, ((Number) row.get("submitted_by")).longValue(), "留痕人落库");
        assertNotNull(row.get("submitted_time"), "留痕时间落库");
        assertEquals("2026-10", row.get("report_period"), "留痕期间落库");
    }

    @Test
    @DisplayName("TC-M4-20②：缺现货价（连历史价也无）⇒ 实货栏保留 + 值 NULL + 缺失原因；衍生品栏照常")
    void tcM4_20_dualPnlReportMissingPriceKeepsSpotColumn() {
        buildChain();
        // ⚠️ 上报读取的是 t_hedge_effect 已存汇总行（findSummaryEffect）——移除价格后必须
        // 重跑效果评估（幂等不增行，T-080 判例），否则上报反映的是移除前的旧快照（首跑实证）。
        T080AcctEffectSeed.noPriceAtAll(engine);
        svc.evaluateEffect(1, "2026-10", OP);

        AcctService.DualPnlReportRequest req = new AcctService.DualPnlReportRequest();
        req.subAccountId = 1;
        req.reportPeriod = "2026-10";
        req.reportType = "MONTHLY";
        DualPnlReport r = svc.submitDualPnlReport(req, OP);

        // 栏位仍不省略（R-ACCT-4 / 17 号文红线）
        assertEquals("Y", r.spotPnlColumnPresent, "实货栏保留（不得省略）");
        assertNull(r.spotPnlValue, "无法计算 ⇒ 值 NULL（不得以 0 冒充）");
        assertNotNull(r.spotPnlMissingReason, "须标注缺失原因");
        assertTrue(r.spotPnlMissingReason.contains("无法计算") || r.spotPnlMissingReason.contains("缺失"),
                "缺失原因语义：" + r.spotPnlMissingReason);
        assertNotNull(r.derivativePnlValue, "衍生品栏照常（两栏同报）");
        assertEquals(0, new BigDecimal("-10350.0000").compareTo(r.derivativePnlValue),
                "衍生品栏不受缺价影响");
        // 留痕三件仍齐
        assertEquals(OP, r.submittedBy);
        assertNotNull(r.submittedTime);
        assertEquals("2026-10", r.reportPeriod);
    }

    /**
     * <b>三态②「仅历史价」的上报报表 face 探针</b>（te-verify-t085 条件 TE-T085-V-001 闭环）：
     * 既有覆盖三态②仅在<b>效果视图 face</b>（{@code AcctEffectServiceTest} FR-ACCT-5③
     * {@code priceStateCarryForwardLabeled}），本用例补齐<b>上报报表 face</b>——与
     * {@code TC-M4-20①/②} 同一取报路径（{@code submitDualPnlReport}，读
     * {@code t_hedge_effect} 汇总行快照），断言 {@code data_completeness_note} 含
     * {@code price_as_of} 时点与非当日价标记（生产承载 {@code AcctService}
     * 「实货盈亏使用非当日价（price_as_of=…，Q-12 标注）」分支）。
     *
     * <p>断言纪律：锚定<b>标注字面量语义与字段存在性</b>，不以生产常量做同源比对；
     * 实货金额与 {@code price_as_of} 时点均由测试夹具（{@code T080AcctEffectSeed}
     * 播种量价）独立推导，与生产零同源。
     */
    @Test
    @DisplayName("TC-M4-20③（三态②）：仅历史价 ⇒ 报表 face 实货栏有值 + price_as_of + 非当日价标注")
    void tcM4_20_dualPnlReportHistoricalPriceOnlyMarksPriceAsOf() {
        buildChain();
        // ⚠️ 上报读取的是 t_hedge_effect 已存汇总行（findSummaryEffect）——改价后必须
        // 重跑效果评估（幂等不增行，T-080 判例），否则上报反映改价前的旧快照
        //（TC-M4-20② 同判例）。
        T080AcctEffectSeed.onlyHistoricalPrice(engine);
        svc.evaluateEffect(1, "2026-10", OP);

        AcctService.DualPnlReportRequest req = new AcctService.DualPnlReportRequest();
        req.subAccountId = 1;
        req.reportPeriod = "2026-10";
        req.reportType = "MONTHLY";
        DualPnlReport r = svc.submitDualPnlReport(req, OP);

        // 三态②（Q-12/R-ACCT-4）：有历史价 ⇒ 非缺失，实货栏不省略且有值（按最近一期计算）
        assertEquals("Y", r.spotPnlColumnPresent, "实货栏保留（不得省略）");
        assertNotNull(r.spotPnlValue, "有历史价 ⇒ 实货可计算（非 NULL）");
        assertNull(r.spotPnlMissingReason, "有历史价 ⇒ 非缺失，无缺失原因");
        // 实货值 = 敞口量 × 历史价 105（夹具专设：敞口 21 再平衡后 130 + 敞口 23 = 50
        // ⇒ (130+50)×105 = 18900；与生产常量零同源）
        assertEquals(0, new BigDecimal("18900.0000").compareTo(r.spotPnlValue),
                "实货栏值（按最近一期历史价计算）");
        // 衍生品栏照常（缺价状态不影响衍生品一侧；R-ACCT-4 两栏同报）
        assertNotNull(r.derivativePnlValue, "衍生品栏照常");
        assertEquals(0, new BigDecimal("-10350.0000").compareTo(r.derivativePnlValue),
                "衍生品栏不受历史价场景影响");

        // 报表 face 非当日价标注：data_completeness_note 含 price_as_of 时点 + 非当日价语义
        // （生产承载 AcctService「实货盈亏使用非当日价（price_as_of=…，Q-12 标注）」；效果
        //  视图 face 同语义既有覆盖 = AcctEffectServiceTest 三态②，两 face 各自断言）
        String note = r.dataCompletenessNote;
        assertNotNull(note, "数据完整性标注（data_completeness_note）须存在");
        assertTrue(note.contains("price_as_of"), "报表 face 须带 price_as_of 标注：" + note);
        assertTrue(note.contains("非当日价"), "报表 face 须带非当日价标记：" + note);
        // price_as_of 时点 = 夹具历史价日（T080AcctEffectSeed.onlyHistoricalPrice 设 2026-10-20）
        assertTrue(note.contains("2026-10-20"), "price_as_of 时点 = 夹具历史价日：" + note);
        // 上报落库可查：data_completeness_note 随行持久化（t_dual_pnl_report 直读）
        assertEquals(note, singleRow("t_dual_pnl_report", "上报行").get("data_completeness_note"),
                "非当日价标注随上报行落库");
    }

    // ================================================================ 工具

    /** 直读引擎表行集（SELECT 无 executeQuery——探针口径照 t080b）。 */
    private List<Map<String, Object>> rows(String table, String what) {
        InMemoryDmEngine.Table t = engine.table(table);
        if (t == null) {
            throw new IllegalStateException("表未登记：" + table
                    + "；已登记=" + engine.tables().keySet());
        }
        assertFalse(t.rows.isEmpty(), what + " 应有行：" + table);
        return t.rows;
    }

    /** 直读引擎表行，要求恰一行。 */
    private Map<String, Object> singleRow(String table, String what) {
        List<Map<String, Object>> all = rows(table, what);
        assertEquals(1, all.size(), what + " 应恰一行：" + table);
        return all.get(0);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
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
