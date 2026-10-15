package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.entity.AccountingPeriod;
import com.hedgeguard.dao.entity.PeriodCloseLog;
import com.hedgeguard.dao.entity.Voucher;
import com.hedgeguard.dao.entity.VoucherReview;
import com.hedgeguard.dao.impl.DmAccountSubjectDao;
import com.hedgeguard.dao.impl.DmAccountingPeriodDao;
import com.hedgeguard.dao.impl.DmPeriodCloseLogDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmVoucherDao;
import com.hedgeguard.dao.impl.DmVoucherLineDao;
import com.hedgeguard.dao.impl.DmVoucherReviewDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.T077AcctSeed;
import com.hedgeguard.dao.testsupport.T079AcctVoucherSeed;
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
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-079} 凭证草稿/审核/冲销 + 期间关账服务单测（真实 DAO + InMemoryDmEngine 等价执行；
 * {@code AcctServiceTest} 同形态，测试三件套：TransactionAwareInMemory + pendingCount 断言 +
 * Clocks.set 固定时钟）。
 *
 * <p>覆盖四验收：① DRAFT_ONLY 草稿生成不做总账过账（CFG-09 模式校验 + 标注 + 幂等 +
 * 子账户出账红线 + 关账期间拒生成）；② 科目建议按 biz_scene 从字典取（不硬编码——
 * 缺字典拒绝为 10015）；③ 审核岗位分离（R-ACCT-6 同人拒绝 10005）+ 驳回原因必填 +
 * 冲销反向凭证（双人复核 DC-032 三人互异 + 已关账期间落当期调整）；④ 关账三前置
 * （R-ACCT-8 任一不满足拒绝 10012）+ 撤销关账双人复核留痕 + 关账后凭证不可修改。
 */
class AcctVoucherServiceTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m4_acct.sql";
    private static final String SETTLE_SCRIPT = "docs/DEPLOY/schema/m2_settle.sql";
    private static final String M1_SCRIPT = "docs/DEPLOY/schema/m1_config_auth.sql";

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TransactionAwareInMemory aware;
    private TxTemplate tx;
    private AcctService svc;
    private List<Integer> visible = List.of(1);
    private String voucherMode = AcctService.VOUCHER_MODE_DRAFT_ONLY;
    private DmVoucherDao voucherDao;
    private DmVoucherLineDao lineDao;
    private DmVoucherReviewDao reviewDao;
    /** 评估记录读回（TE-T079-002 回归用例断言用）。 */
    private com.hedgeguard.dao.impl.DmEffectivenessAssessDao assessDao;
    /** 审核留痕写入失败注入（TE-T079-001 载体；缺省直通真实 DAO，零行为差）。 */
    private FailInsertReviewDao failInsertReviewDao;
    private DmAccountingPeriodDao periodDao;
    private DmPeriodCloseLogDao closeLogDao;

    /** 固定运行日（测试三件套；日期断言与运行日解耦）。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @BeforeEach
    void setUp() throws Exception {
        com.hedgeguard.common.Clocks.set(
                Clock.fixed(TODAY.atStartOfDay(com.hedgeguard.common.Clocks.ZONE).toInstant(),
                        com.hedgeguard.common.Clocks.ZONE));
        engine = new InMemoryDmEngine();
        engine.executeScript(Files.readString(repoRoot().resolve(M1_SCRIPT), StandardCharsets.UTF_8));
        engine.executeScript(Files.readString(repoRoot().resolve(SETTLE_SCRIPT), StandardCharsets.UTF_8));
        engine.executeScript(Files.readString(repoRoot().resolve(SCRIPT), StandardCharsets.UTF_8));
        T077AcctSeed.createParentTables(engine);
        T077AcctSeed.seedChains(engine);
        // 测试三件套①：事务感知包装（引擎裸连接 rollback=no-op ⇒ 拒绝路径残留半笔写假绿）
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware, new PoolConfig(0, 4, 500, 2));
        tx = new TxTemplate(pool);
        voucherDao = new DmVoucherDao(tx);
        lineDao = new DmVoucherLineDao(tx);
        reviewDao = new DmVoucherReviewDao(tx);
        failInsertReviewDao = new FailInsertReviewDao(reviewDao);
        assessDao = new com.hedgeguard.dao.impl.DmEffectivenessAssessDao(tx);
        periodDao = new DmAccountingPeriodDao(tx);
        closeLogDao = new DmPeriodCloseLogDao(tx);
        svc = new AcctService(tx,
                (uid) -> visible,
                (key) -> AcctService.CFG_VOUCHER_MODE.equals(key) ? voucherMode : null,
                new com.hedgeguard.dao.impl.DmHedgePlanDao(tx),
                new com.hedgeguard.dao.impl.DmHedgeDocDao(tx),
                new com.hedgeguard.dao.impl.DmHedgeRelationDao(tx),
                new com.hedgeguard.dao.impl.DmEffectivenessAssessDao(tx),
                new com.hedgeguard.dao.impl.DmRebalanceRecordDao(tx),
                new com.hedgeguard.dao.impl.DmExposureDao(tx),
                voucherDao, lineDao, failInsertReviewDao, periodDao, closeLogDao,
                new DmSettleCycleDao(tx), new DmAccountSubjectDao(tx));
    }

    @AfterEach
    void tearDown() {
        com.hedgeguard.common.Clocks.reset();
        // 测试三件套②：pendingCount 归零自证（事务无残留）
        assertEquals(0, aware.pendingCount(), "事务未残留（TE-T077-001 判例自证）");
        if (pool != null) {
            pool.close();
        }
    }

    // ---------------------------------------------------------------- 验收 1：草稿生成（CFG-09）

    @Test
    @DisplayName("验收1：DRAFT_ONLY 草稿生成（借贷成对 + 待企业财务复核入账 + 不做过账）")
    void draftOnlyVoucherGenerated() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "其他业务收入", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.accountSubject(engine, 2L, "3101", "衍生工具", "DEBIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "10月平仓盈亏结转",
                "500.00"), 7L);
        assertEquals("DRAFT", v.status, "草稿态（不直接入账）");
        assertEquals("AV20261000001", v.voucherNo, "AV<期间><seq(5)>（DATA_MODEL §3.5）");
        assertEquals("Y", v.pendingExternalReview, "R-ACCT-10 待外部复核恒 Y");
        // 分录成对 + 平衡
        assertEquals(2, lineDao.findByVoucher(v.id).size(), "借贷成对两行");
        assertEquals(v.totalAmount, lineDao.findByVoucher(v.id).get(0).amount);
    }

    @Test
    @DisplayName("验收1：非 DRAFT_ONLY 模式拒绝（CFG-09 取值域外不静默降级）")
    void nonDraftOnlyModeRejected() {
        voucherMode = "AUTO_POST";
        BizException e = assertThrows(BizException.class, () -> svc.createVoucherDraft(
                req(1, "2026-10", "CLOSE_PNL", "x", "1"), 7L));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
    }

    @Test
    @DisplayName("验收1：子账户标识缺失拒绝（R-ACCT-5 凭证按子账户出账红线）")
    void missingSubAccountRejected() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        AcctService.VoucherDraftRequest r = req(null, "2026-10", "CLOSE_PNL", "x", "1");
        BizException e = assertThrows(BizException.class,
                () -> svc.createVoucherDraft(r, 7L));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(e.getMessage().contains("子账户"));
    }

    @Test
    @DisplayName("验收1：科目字典缺失拒绝（R-ACCT-11 不硬编码/不臆造科目）")
    void missingSubjectDictionaryRejected() {
        AcctService.VoucherDraftRequest r = req(1, "2026-10", "CLOSE_PNL", "x", "1");
        BizException e = assertThrows(BizException.class, () -> svc.createVoucherDraft(r, 7L));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, e.errorCode(),
                "FR-ACCT-6 失败列「科目字典缺失」= 10015");
        assertTrue(e.getMessage().contains("CLOSE_PNL"));
    }

    @Test
    @DisplayName("验收1：同期间同类型重复生成被拒（FR-ACCT-6 验收 4 幂等）")
    void duplicateDraftRejected() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "首张", "100.00"), 7L);
        BizException e = assertThrows(BizException.class,
                () -> svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "重复", "100.00"), 7L));
        assertEquals(ErrorCode.UNIQUE_CONFLICT, e.errorCode());
    }

    @Test
    @DisplayName("验收1：已关账期间生成凭证被拒（FR-ACCT-6 验收 3 = 10016 走下期调整）")
    void closedPeriodVoucherRejected() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        T079AcctVoucherSeed.closePeriod(engine, 1L);
        BizException e = assertThrows(BizException.class,
                () -> svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "1"), 7L));
        assertEquals(ErrorCode.PERIOD_CLOSED, e.errorCode());
        assertTrue(e.getMessage().contains("下期调整"));
    }

    @Test
    @DisplayName("取号：跨业务日全系统前缀 max+1（O-4；AV20261000001→AV20261000002）")
    void voucherNoGlobalMaxPlusOne() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        // 旧编号凭证 = 上一期间留下的 00001（前缀同 AV202610 的旧行 + 跨业务日）
        T079AcctVoucherSeed.legacyVoucher(engine, 90L, 1, "AV20261000001", "2026-10",
                "POSTED", 7L);
        // 不同类型可再生成（幂等按类型隔离）
        T079AcctVoucherSeed.accountSubject(engine, 2L, "6602", "y", "CREDIT",
                "COMMISSION", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "COMMISSION", "手续费", "10.00"), 7L);
        assertEquals("AV20261000002", v.voucherNo, "全前缀 max+1（非当日重置）");
    }

    // ---------------------------------------------------------------- 验收 3：审核（R-ACCT-6）

    @Test
    @DisplayName("验收3：审核通过（DRAFT→PENDING→POSTED）+ 留痕链 node_seq 递增")
    void approveFlow() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        assertEquals("PENDING_REVIEW", voucherDao.findById(v.id).status);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_APPROVE, "同意", null), 8L);
        assertEquals("POSTED", voucherDao.findById(v.id).status);
        List<VoucherReview> chain = reviewDao.findByVoucher(v.id);
        assertEquals(2, chain.size(), "提交 + 通过两节点留痕");
        assertEquals(1, chain.get(0).nodeSeq);
        assertEquals(2, chain.get(1).nodeSeq);
        assertEquals("APPROVED", chain.get(1).reviewResult);
        assertEquals(8L, chain.get(1).reviewerId, "审核留痕含审核人");
    }

    @Test
    @DisplayName("验收3：生成人审核自己生成的凭证被拒（R-ACCT-6 岗位分离 = 10005）")
    void selfApprovalRejected() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        BizException e = assertThrows(BizException.class, () -> svc.reviewVoucher(v.id,
                reviewReq(AcctService.REVIEW_ACTION_APPROVE, null, null), 7L));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
    }

    @Test
    @DisplayName("验收3：驳回未填原因被拒；填原因后回到草稿可再提交")
    void rejectRequiresReason() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        BizException e = assertThrows(BizException.class, () -> svc.reviewVoucher(v.id,
                reviewReq(AcctService.REVIEW_ACTION_REJECT, null, null), 8L));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        // 填原因 → 驳回成功 → DRAFT
        svc.reviewVoucher(v.id,
                reviewReq(AcctService.REVIEW_ACTION_REJECT, "金额不对", "摘要与结算不符"), 8L);
        assertEquals("DRAFT", voucherDao.findById(v.id).status);
        // 可再次提交（状态机回流）
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        assertEquals("PENDING_REVIEW", voucherDao.findById(v.id).status);
    }

    // ---------------------------------------------------------------- 验收 3：冲销（R-ACCT-5 / DC-032）

    @Test
    @DisplayName("冲销：正式凭证生成反向凭证（REVERSAL + 反向分录 + 原凭证 REVERSED 留痕）")
    void reverseGeneratesReversalVoucher() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_APPROVE, null, null), 8L);

        Voucher reversal = svc.reverseVoucher(v.id, reverseReq("金额重复入账", 9L), 10L);
        assertEquals("REVERSAL", reversal.voucherType);
        assertEquals(v.id, reversal.reversalOfId, "反向凭证关联原凭证");
        assertEquals("POSTED", reversal.status);
        assertEquals("2026-10", reversal.periodKey, "原期间开放 → 落原期间");
        // 原凭证被标记已冲销
        Voucher after = voucherDao.findById(v.id);
        assertEquals("REVERSED", after.status);
        assertEquals(reversal.id, after.reversedById);
        assertEquals("金额重复入账", after.reversalReason, "冲销原因留痕");
        // 反向分录借贷对调
        assertEquals(2, lineDao.findByVoucher(reversal.id).size());
        // DC-032：co_reviewer_id 生效（原凭证复核节点）
        VoucherReview coNode = reviewDao.findByVoucher(v.id).get(2);
        assertEquals(9L, coNode.coReviewerId, "第二复核人生效（DC-032）");
        assertEquals(10L, coNode.reviewerId);
        assertEquals(7L, coNode.generatorId);
    }

    @Test
    @DisplayName("冲销：复核人与发起人同人被拒（FR-ACCT-13 验收 3 = 10005）")
    void reverseCoReviewerSameAsInitiatorRejected() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_APPROVE, null, null), 8L);
        BizException e = assertThrows(BizException.class,
                () -> svc.reverseVoucher(v.id, reverseReq("r", 10L), 10L));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
    }

    @Test
    @DisplayName("冲销：发起人=生成人被拒（R-ACCT-6）；第二复核人=生成人被拒（DC-032）")
    void reverseThreeWaySeparation() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_APPROVE, null, null), 8L);
        // 发起人 = 生成人
        BizException e1 = assertThrows(BizException.class,
                () -> svc.reverseVoucher(v.id, reverseReq("r", 9L), 7L));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e1.errorCode());
        // 第二复核人 = 生成人
        BizException e2 = assertThrows(BizException.class,
                () -> svc.reverseVoucher(v.id, reverseReq("r", 7L), 10L));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e2.errorCode());
    }

    @Test
    @DisplayName("冲销：已关账期间冲销落当期调整（FR-ACCT-13 验收 4 不回改已关账期间）")
    void reverseClosedPeriodGoesToCurrentPeriod() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-09", "MONTH",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        // 凭证落 2026-09（随后关账）；固定时钟当期 = 2026-10（开放）
        Voucher v = svc.createVoucherDraft(req(1, "2026-09", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_APPROVE, null, null), 8L);
        T079AcctVoucherSeed.closePeriod(engine, 1L);
        Voucher reversal = svc.reverseVoucher(v.id, reverseReq("r", 9L), 10L);
        assertEquals("2026-10", reversal.periodKey, "已关账原期间 → 当期调整落点");
        assertEquals(v.id, reversal.reversalOfId);
        assertEquals("REVERSED", voucherDao.findById(v.id).status);
    }

    @Test
    @DisplayName("草稿删除：DRAFT 可处置留痕；POSTED 拒绝（FR-ACCT-13）")
    void draftDeletionFlow() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        Voucher after = svc.deleteDraftVoucher(v.id, 7L);
        assertEquals("REVERSED", after.status, "草稿删除留痕承载");
        assertNotNull(after.reversalReason);
        // 正式凭证不可删除
        T079AcctVoucherSeed.setVoucherStatus(engine, v.id, "POSTED");
        BizException e = assertThrows(BizException.class, () -> svc.deleteDraftVoucher(v.id, 7L));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode());
    }

    // ---------------------------------------------------------------- 验收 4：期间关账（R-ACCT-8）

    @Test
    @DisplayName("验收4：三前置满足关账成功 + 留痕（CLOSE 日志 + closed_by/reviewer）")
    void closePeriodWithPreconditions() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        T079AcctVoucherSeed.settleCycle(engine, 501L, 1, "ST20261001",
                LocalDate.of(2026, 10, 10), "CONFIRMED");
        // ③ 评估：该期间补一条评估记录（经 svc.assessEffectiveness 正常管线）
        seedAssessment("2026-10");
        PeriodCloseLog log = svc.closePeriod(1L, AcctService.CLOSE_ACTION_CLOSE,
                new AcctService.PeriodCloseRequest(), 10L, 11L);
        assertEquals(AcctService.CLOSE_ACTION_CLOSE, log.action);
        assertEquals(10L, log.operatorId);
        assertEquals(11L, log.reviewerId, "复核人留痕");
        assertEquals("CLOSED", periodDao.findById(1L).status);
        assertEquals(1, closeLogDao.findByPeriod("2026-10").size(), "留痕行");
    }

    @Test
    @DisplayName("验收4：存在未审核凭证关账被拒（R-ACCT-8 前置① = 10012 列出凭证）")
    void closeRejectedWithUnreviewedVouchers() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        seedAssessment("2026-10");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        BizException e = assertThrows(BizException.class, () -> svc.closePeriod(1L,
                AcctService.CLOSE_ACTION_CLOSE, new AcctService.PeriodCloseRequest(), 10L, 11L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertTrue(e.current().contains(v.voucherNo),
                "验收 2：列出未审核凭证（前置①载荷）：" + e.current());
        assertEquals("OPEN", periodDao.findById(1L).status, "关账未生效");
    }

    @Test
    @DisplayName("验收4：结算未完成关账被拒（前置②）")
    void closeRejectedWithUnsettled() {
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        T079AcctVoucherSeed.settleCycle(engine, 501L, 1, "ST20261001",
                LocalDate.of(2026, 10, 10), "PENDING");
        seedAssessment("2026-10");
        BizException e = assertThrows(BizException.class, () -> svc.closePeriod(1L,
                AcctService.CLOSE_ACTION_CLOSE, new AcctService.PeriodCloseRequest(), 10L, 11L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertTrue(e.current().contains("结算"), "前置②载荷：" + e.current());
    }

    @Test
    @DisplayName("验收4：该期间无评估记录关账被拒（前置③，R-ACCT-2 每期至少一次）")
    void closeRejectedWithoutAssessment() {
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        BizException e = assertThrows(BizException.class, () -> svc.closePeriod(1L,
                AcctService.CLOSE_ACTION_CLOSE, new AcctService.PeriodCloseRequest(), 10L, 11L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertTrue(e.current().contains("评估"), "前置③载荷：" + e.current());
    }

    @Test
    @DisplayName("TE-T079-002 回归：已更正评估不阻塞关账（前置③按未更正记录判定，复用 findActiveByRelationAndPeriod）")
    void correctedAssessmentDoesNotBlockClose() {
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        T079AcctVoucherSeed.settleCycle(engine, 501L, 1, "ST20261001",
                LocalDate.of(2026, 10, 10), "CONFIRMED");
        // 该期间唯一的评估记录随后被更正（is_corrected='Y' ⇒ 判定已作废）——
        // 前置③须以「未更正」口径判定：更正后的新记录（is_corrected='N'）继续承载「已评估」。
        seedAssessment("2026-10");
        long relationId = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L).id;
        var original = assessDao.findBySubAccountAndPeriod(1, "2026-10").get(0);
        AcctService.AssessRequest cr = new AcctService.AssessRequest();
        cr.hedgeRelationId = relationId;
        cr.periodKey = "2026-10";
        cr.observerLinkage = "Y";
        cr.observerDirection = "Y";
        cr.observerTerm = "N";
        cr.exposureExpectedDate = LocalDate.of(2026, 12, 1);
        svc.correctAssessment(original.id, cr, 8L);
        assertEquals("Y", assessDao.findById(original.id).isCorrected, "原记录已更正");
        // 前置③：无未更正记录 ⇒ 拒（对照负例）；有未更正记录 ⇒ 通过（本用例正例）。
        // 负证：若前置③仍按 findByPeriodAndConclusion（不滤 is_corrected）口径，
        // 即使新记录不存在也误判通过——此处更正链已留新记录，正向关账即含「未更正」判别。
        PeriodCloseLog log = svc.closePeriod(1L, AcctService.CLOSE_ACTION_CLOSE,
                new AcctService.PeriodCloseRequest(), 10L, 11L);
        assertEquals("CLOSED", periodDao.findById(1L).status, "更正链活跃记录承载前置③ ⇒ 关账通过");
        assertEquals(AcctService.CLOSE_ACTION_CLOSE, log.action);
    }

    @Test
    @DisplayName("TE-T079-002 负例：期间仅剩已更正评估（无活跃记录）⇒ 前置③不得误判通过（10012）")
    void onlyCorrectedAssessmentStillBlocksClose() {
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        seedAssessment("2026-10");
        // 直接经 DAO 窄口把唯一评估记录置 is_corrected='Y'（模拟更正链原记录侧；
        // 无新记录 ⇒ 该期间无任何未更正评估）——前置③若按 findByPeriodAndConclusion
        // 旧口径（不滤 is_corrected）将误判通过，本负例必红（判别力载体）。
        var only = assessDao.findBySubAccountAndPeriod(1, "2026-10").get(0);
        assertEquals(1, assessDao.markCorrected(only.id, 8L), "标记已更正");
        assertEquals("Y", assessDao.findById(only.id).isCorrected);
        BizException e = assertThrows(BizException.class, () -> svc.closePeriod(1L,
                AcctService.CLOSE_ACTION_CLOSE, new AcctService.PeriodCloseRequest(), 10L, 11L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode(),
                "仅剩已更正记录 = 未评估（判定已作废）⇒ 前置③阻断：" + e.current());
        assertTrue(e.current().contains("评估"), "前置③载荷：" + e.current());
    }

    @Test
    @DisplayName("验收4：复核人=操作人被拒（R-ACCT-8 双人复核 = 10005）")
    void closeReviewerSameAsOperatorRejected() {
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        BizException e = assertThrows(BizException.class, () -> svc.closePeriod(1L,
                AcctService.CLOSE_ACTION_CLOSE, new AcctService.PeriodCloseRequest(), 10L, 10L));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
    }

    @Test
    @DisplayName("验收4：撤销关账双人复核留痕（REOPEN 日志 + reason）")
    void reopenPeriodWithDualReview() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        seedAssessment("2026-10");
        svc.closePeriod(1L, AcctService.CLOSE_ACTION_CLOSE,
                new AcctService.PeriodCloseRequest(), 10L, 11L);
        AcctService.PeriodCloseRequest req = new AcctService.PeriodCloseRequest();
        req.reason = "漏计凭证需补录";
        PeriodCloseLog log = svc.closePeriod(1L, AcctService.CLOSE_ACTION_REOPEN, req, 12L, 13L);
        assertEquals(AcctService.CLOSE_ACTION_REOPEN, log.action);
        assertEquals("漏计凭证需补录", log.reason);
        assertEquals("OPEN", periodDao.findById(1L).status);
        assertEquals(12L, periodDao.findById(1L).reopenedBy);
        // REOPEN 原因缺失拒绝
        BizException e = assertThrows(BizException.class, () -> svc.closePeriod(1L,
                AcctService.CLOSE_ACTION_REOPEN, new AcctService.PeriodCloseRequest(), 12L, 13L));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
    }

    @Test
    @DisplayName("验收4联动：关账后凭证审核被拒（关账后凭证不可修改 = 10016）")
    void closedPeriodVoucherMutationRejected() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.openPeriod(engine, 1L, "2026-10", "MONTH",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        seedAssessment("2026-10");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        // 先审核通过（三前置①要求全部凭证已审核/已冲销）
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_APPROVE, null, null), 8L);
        // 三前置全绿（凭证已审核 + 评估已补；无结算单 = 无账可结视为完成）后经服务关账
        svc.closePeriod(1L, AcctService.CLOSE_ACTION_CLOSE,
                new AcctService.PeriodCloseRequest(), 10L, 11L);
        assertEquals("CLOSED", periodDao.findById(1L).status);
        // 关账后：已 POSTED 凭证的冲销（状态变更）被拒
        BizException e = assertThrows(BizException.class, () -> svc.reverseVoucher(v.id,
                reverseReq("r", 9L), 10L));
        assertEquals(ErrorCode.PERIOD_CLOSED, e.errorCode());
        // 关账后：草稿删除被拒（凭证不可修改族）
        T079AcctVoucherSeed.setVoucherStatus(engine, v.id, "DRAFT");
        BizException e2 = assertThrows(BizException.class, () -> svc.deleteDraftVoucher(v.id, 7L));
        assertEquals(ErrorCode.PERIOD_CLOSED, e2.errorCode());
    }

    // ---------------------------------------------------------------- 读取面与隔离

    @Test
    @DisplayName("列表：子账户可见集过滤 + 默认凭证编号倒序（FR-ACCT-8）")
    void listFilteredByVisibility() {
        visible = List.of(1, 2);   // 生成两子账户凭证须先有双侧写授权
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "A", "100.00"), 7L);
        T079AcctVoucherSeed.accountSubject(engine, 2L, "6602", "y", "CREDIT",
                "COMMISSION", "Y", "ENABLED");
        svc.createVoucherDraft(req(2, "2026-10", "COMMISSION", "B", "10.00"), 7L);
        visible = List.of(1);
        assertEquals(1, svc.listVouchers(PageQuery.of(null, "50", null, "voucher_no", "desc"),
                null, null, null, 7L).total(), "可见集 [1] 仅见子账户 1");
        visible = List.of(0);   // 跨账户只读
        assertEquals(2, svc.listVouchers(PageQuery.of(null, "50", null, "voucher_no", "desc"),
                null, null, null, 7L).total(), "跨账户只读见全部");
    }

    @Test
    @DisplayName("VoucherLookup 装配：按敞口/计划/结算反查（TE-T079B-001：无 lookup 层假过滤，消费面承载可见性）")
    void voucherLookupWiring() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        T079AcctVoucherSeed.settleCycle(engine, 501L, 1, "ST20261001",
                LocalDate.of(2026, 10, 1), "CONFIRMED");
        AcctService.VoucherDraftRequest r = req(1, "2026-10", "CLOSE_PNL", "A", "100.00");
        r.exposureId = 21L;
        r.settleCycleId = 501L;
        svc.createVoucherDraft(r, 7L);
        var lookup = svc.voucherLookup();
        assertNotNull(lookup);
        // TE-T079B-001：原 toRefs 以 visibleSubAccountIds(-1) 过滤——生产装配（AuthService）下
        // user_id=-1 无权限行 ⇒ 过滤集恒空 ⇒ 正向三路恒空 ⇒ 凭证段恒 UNKNOWN（装配语义失效）。
        // 裁定撤假过滤：lookup 返回全集，可见性由 AuditService 消费面（Chain.allowed）承载；
        // 此处断言「无关 operatorId 上下文恒返回真数据」+ 三路字段正确。
        assertEquals(1, lookup.findByExposure(21L).size());
        assertEquals(0, lookup.findByExposure(22L).size(), "敞口 22 无凭证行（非过滤语义）");
        assertEquals("AV20261000001", lookup.findByExposure(21L).get(0).voucherNo);
        assertEquals(1, lookup.findBySettleCycle(501L).size());
        assertNull(lookup.findByVoucher("NO-SUCH"), "不存在返回 null");
        assertNotNull(lookup.findByVoucher("AV20261000001"));
        // operatorId 上下文不再影响 lookup 面（visible 字段变更零效果 = 假过滤撤除的负证）
        visible = List.of();
        assertEquals(1, lookup.findByExposure(21L).size(),
                "撤假过滤后 lookup 不依赖任何 uid 的可见集（可见性在消费面）");
    }

    @Test
    @DisplayName("整笔事务回归：审核留痕写入失败 ⇒ 凭证状态回滚（TE-T077-001 同族；TE-T079-001 重设计）")
    void reviewRollbackOnConcurrentConflict() {
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        Voucher v = svc.createVoucherDraft(req(1, "2026-10", "CLOSE_PNL", "x", "100.00"), 7L);
        svc.reviewVoucher(v.id, reviewReq(AcctService.REVIEW_ACTION_SUBMIT, null, null), 7L);
        // 冲突注入：留痕 insert 置 0（委托式假 DAO，注入点在凭证 updateById 之后——
        // 服务于事务内 findById 重读后才 updateById，行外预拨 row_version 被读新覆盖，
        // 乐观锁冲突结构性不可触发；改注入「两段写的第二段失败」观察整笔回滚）
        failInsertReviewDao.failNextInsert = true;
        BizException e = assertThrows(BizException.class, () -> svc.reviewVoucher(v.id,
                reviewReq(AcctService.REVIEW_ACTION_APPROVE, null, null), 8L));
        assertEquals(ErrorCode.INTERNAL_ERROR, e.errorCode(), "留痕写入失败 = 10001");
        assertEquals("PENDING_REVIEW", voucherDao.findById(v.id).status,
                "留痕失败 ⇒ 状态回滚（半笔写不得落库）");
        assertEquals(1, reviewDao.findByVoucher(v.id).size(), "仅提交节点，无半笔审核留痕");
        assertEquals(0, aware.pendingCount(), "整笔回滚后无未决事务残留");
    }

    // ---------------------------------------------------------------- 夹具

    private void seedAssessment(String periodKey) {
        AcctService.AssessRequest ar = new AcctService.AssessRequest();
        ar.hedgeRelationId = svc.generateHedgeRelation(11L, 21L, "FUTURES", "cu2612", 7L).id;
        ar.periodKey = periodKey;
        ar.observerLinkage = "Y";
        ar.observerDirection = "Y";
        ar.observerTerm = "Y";
        ar.exposureExpectedDate = LocalDate.of(2026, 12, 1);
        svc.assessEffectiveness(ar, 7L);
    }

    private static AcctService.VoucherDraftRequest req(Integer subAccountId, String periodKey,
                                                       String type, String summary, String amount) {
        AcctService.VoucherDraftRequest r = new AcctService.VoucherDraftRequest();
        r.subAccountId = subAccountId;
        r.periodKey = periodKey;
        r.voucherType = type;
        r.summary = summary;
        r.amount = new BigDecimal(amount);
        return r;
    }

    private static AcctService.VoucherReviewRequest reviewReq(String action, String comment,
                                                              String rejectReason) {
        AcctService.VoucherReviewRequest r = new AcctService.VoucherReviewRequest();
        r.action = action;
        r.reviewComment = comment;
        r.rejectReason = rejectReason;
        return r;
    }

    private static AcctService.VoucherReverseRequest reverseReq(String reason, long coReviewerId) {
        AcctService.VoucherReverseRequest r = new AcctService.VoucherReverseRequest();
        r.reason = reason;
        r.coReviewerId = coReviewerId;
        return r;
    }

    /**
     * 审核留痕「写入失败」注入（TE-T079-001 载体；{@code UnsupportedBaseDao}/{@code FakeDao}
     * 委托形态）：缺省<b>直通真实 DAO</b>（零行为差，其余 24 个用例不受影响）；
     * 置 {@code failNextInsert} 后 {@code insert} 返回 {@code 0}（模拟写入影响 0 行）——
     * 按返回值契约注入，不裸抛异常（{@code FakeDao.unsupported} 显式失败纪律不受影响）。
     */
    private static final class FailInsertReviewDao
            extends com.hedgeguard.testsupport.UnsupportedBaseDao<VoucherReview, Long>
            implements com.hedgeguard.dao.VoucherReviewDao {

        private final com.hedgeguard.dao.VoucherReviewDao delegate;
        /** 置 {@code true} 后下一次 {@code insert} 返回 0（写入失败）；一次性标志。 */
        boolean failNextInsert;

        FailInsertReviewDao(com.hedgeguard.dao.VoucherReviewDao delegate) {
            this.delegate = delegate;
        }

        @Override
        public int insert(VoucherReview entity, long operatorId) {
            if (failNextInsert) {
                failNextInsert = false;
                return 0;
            }
            return delegate.insert(entity, operatorId);
        }

        @Override
        public List<VoucherReview> findByVoucher(long voucherId) {
            return delegate.findByVoucher(voucherId);
        }

        @Override
        public List<VoucherReview> findByReviewer(long reviewerId) {
            return delegate.findByReviewer(reviewerId);
        }

        @Override
        public int findMaxNodeSeq(long voucherId) {
            return delegate.findMaxNodeSeq(voucherId);
        }

        @Override
        public int updateById(VoucherReview entity, long operatorId) {
            return delegate.updateById(entity, operatorId);
        }

        @Override
        public VoucherReview findById(Long id) {
            return delegate.findById(id);
        }

        @Override
        public com.hedgeguard.dao.PageResult<VoucherReview> findPage(
                com.hedgeguard.dao.PageQuery query, String where, List<Object> whereArgs) {
            return delegate.findPage(query, where, whereArgs);
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return delegate.count(where, whereArgs);
        }
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test，当前="
                + Paths.get("").toAbsolutePath());
    }
}
