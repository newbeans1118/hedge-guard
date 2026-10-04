package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.dao.entity.FundFlow;
import com.hedgeguard.dao.entity.FundLedger;
import com.hedgeguard.dao.entity.FundQuotaAlert;
import com.hedgeguard.dao.entity.TransferApply;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-049} 资金管理服务单测（{@code FR-FUND-1}~{@code 17}；验收 1~5）。
 *
 * <p>内存假 DAO（{@link FundFakes}）+ 假事务（{@link FakeTx}）+ lambda 注入的可见性 / 岗位 / 配置接缝，
 * 不引 Mockito、不碰 JDBC。
 */
class FundServiceTest {

    private final AtomicReference<List<Integer>> visible = new AtomicReference<>(List.of(1, 2, 3));
    private final Set<Long> tradingUsers = new HashSet<>();
    private String cfg = FundService.DEFAULT_QUOTA_ALERT_LEVELS;

    private FundFakes.Ledgers ledgers;
    private FundFakes.Flows flows;
    private FundFakes.DwApplies dws;
    private FundFakes.Transfers transfers;
    private FundFakes.Receipts receipts;
    private FundFakes.Reviews reviews;
    private FundFakes.Quotas quotas;
    private FundFakes.Alerts alerts;
    private FundFakes.Reconciles reconciles;
    private FundService svc;

    @BeforeEach
    void setUp() {
        ledgers = new FundFakes.Ledgers();
        flows = new FundFakes.Flows();
        dws = new FundFakes.DwApplies();
        transfers = new FundFakes.Transfers();
        receipts = new FundFakes.Receipts();
        reviews = new FundFakes.Reviews();
        quotas = new FundFakes.Quotas();
        alerts = new FundFakes.Alerts();
        reconciles = new FundFakes.Reconciles();
        visible.set(List.of(1, 2, 3));
        tradingUsers.clear();
        cfg = FundService.DEFAULT_QUOTA_ALERT_LEVELS;
        svc = new FundService(FakeTx.newTemplate(), uid -> visible.get(),
                uid -> tradingUsers.contains(uid), key -> cfg,
                ledgers, flows, dws, transfers, receipts, reviews, quotas, alerts, reconciles);
    }

    // ------------------------------------------------------------------ 验收 1

    @Test
    @DisplayName("验收1：台账完整（期初+发生额=期末），每笔有凭证编号，可导出对账")
    void ledgerEquationVoucherAndExport() {
        long ledgerId = initLedger(1, "1000");
        long applyId = deposit(1, "500", "K1");
        review(applyId, 2L, true, null);
        assertSame(FundService.DW_SUBMITTED, dws.findById(applyId).status);

        FundService.ReceiptResult rr = receipt(applyId, FundService.RECEIPT_SUCCESS, "R1", 1L);
        assertTrue(rr.processed);

        FundLedger l = ledgers.findById(ledgerId);
        assertDec("1000", l.beginBalance);
        assertDec("500", l.amountIn);
        assertDec("1500", l.endBalance);
        assertDec("1500", l.beginBalance.add(l.amountIn)); // 期初 + 发生额 = 期末

        assertEquals(1, flows.rows.size());
        assertNotNull(flows.rows.get(0).voucherNo);
        assertTrue(flows.rows.get(0).voucherNo.startsWith("FV"), "凭证编号须为 FV<yyyyMMdd><seq>");
        assertNotNull(flows.rows.get(0).operatorId);

        FundService.ExportResult ex = svc.exportReconcile(Clocks.today(), Clocks.today(), null, 1L);
        assertEquals(1, ex.rows.size());
        assertEquals(flows.rows.get(0).voucherNo, ex.rows.get(0).get("voucherNo"));
        assertDec("500", ex.grandTotal);
    }

    @Test
    @DisplayName("验收1/3：台账建立同账号复核被拒且写越权留痕")
    void ledgerInitSelfReviewRejected() {
        FundService.LedgerInitRequest req = new FundService.LedgerInitRequest();
        req.subAccountId = 1;
        req.ledgerDate = Clocks.today().toString();
        req.beginBalance = "1000";
        req.reviewerId = 1L;
        BizException ex = assertThrows(BizException.class, () -> svc.initLedger(req, 1L));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, ex.errorCode());
        assertEquals(1, reviews.findBySelfReviewRejected("Y").size(), "越权尝试须留痕");
        assertEquals(0, ledgers.rows.size(), "拒绝后不得建立台账");
    }

    // ------------------------------------------------------------------ 验收 2

    @Test
    @DisplayName("验收2：幂等去重 + 不回执不记账 + 超时置「超时待查」（不假设未到账）")
    void asyncIdempotentTimeout() {
        initLedger(1, "0");
        long a1 = deposit(1, "500", "K1");
        long a2 = deposit(1, "500", "K1");
        assertEquals(a1, a2, "同一幂等标识重复提交不得产生第二笔");
        assertEquals(1, dws.rows.size());

        review(a1, 2L, true, null);
        DepositWithdrawApply a = dws.findById(a1);
        assertEquals(FundService.DW_SUBMITTED, a.status);
        assertNotNull(a.submitTime, "提交后应记录 CTP 提交时间，且不阻塞等待");

        FundService.ReceiptResult unknown = receipt(a1, FundService.RECEIPT_UNKNOWN, "R1", 1L);
        assertTrue(unknown.processed);
        assertEquals(FundService.DW_TIMEOUT_UNKNOWN, dws.findById(a1).status,
                "未收到最终回执须置「超时待查」，不得假设未到账");
        assertEquals(0, flows.rows.size(), "超时未定论不得记账");

        FundService.ReceiptResult dup = receipt(a1, FundService.RECEIPT_UNKNOWN, "R1", 1L);
        assertFalse(dup.processed, "重复回执须幂等 no-op");

        receipt(a1, FundService.RECEIPT_SUCCESS, "R2", 2L);
        assertEquals(FundService.DW_SUCCESS, dws.findById(a1).status);
        assertEquals(1, flows.rows.size(), "成功回执仅记一次账");
        assertEquals("Y", receipts.rows.get(receipts.rows.size() - 1).booked);
    }

    @Test
    @DisplayName("验收2：未确认原请求结果不得重试；确认后可重试且不重复划转")
    void retryRequiresConfirmation() {
        initLedger(1, "0");
        long a1 = deposit(1, "500", "K1");
        review(a1, 2L, true, null);
        receipt(a1, FundService.RECEIPT_UNKNOWN, "R1", 1L);
        assertEquals(FundService.DW_TIMEOUT_UNKNOWN, dws.findById(a1).status);

        BizException ex = assertThrows(BizException.class,
                () -> svc.retryDepositWithdraw(a1, "K2", 1L));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, ex.errorCode());

        svc.confirmTimeoutResult(a1, receiptReq(FundService.RECEIPT_FAIL, "R2", 2L, true), 1L);
        DepositWithdrawApply orig = dws.findById(a1);
        assertEquals(FundService.DW_FAILED, orig.status);
        assertEquals("Y", orig.originalConfirmed);

        long a2 = svc.retryDepositWithdraw(a1, "K2", 1L).id;
        assertEquals(a1, dws.findById(a2).retryOfId);
        assertEquals(0, flows.rows.size(), "失败回执不产生记账");
    }

    // ------------------------------------------------------------------ 验收 3

    @Test
    @DisplayName("验收3：双人复核 —— 同账号复核被拒且留痕；驳回须填原因")
    void doubleReviewDeposit() {
        long a = deposit(1, "100", "K1");
        BizException self = assertThrows(BizException.class,
                () -> svc.reviewDepositWithdraw(a, approve(), 1L));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, self.errorCode());
        assertTrue(reviews.findBySelfReviewRejected("Y").size() >= 1);

        FundService.ReviewRequest reject = new FundService.ReviewRequest();
        reject.approved = false;
        BizException noReason = assertThrows(BizException.class,
                () -> svc.reviewDepositWithdraw(a, reject, 2L));
        assertEquals(ErrorCode.PARAM_INVALID, noReason.errorCode());
    }

    // ------------------------------------------------------------------ 验收 4

    @Test
    @DisplayName("验收4：划拨 A 减 B 增、加总不变、成对流水、审批留痕、可回滚")
    void transferKeepsSumAndRollback() {
        initLedger(1, "1000");
        initLedger(2, "500");
        long tid = initiateTransfer(1, 2, "300", "调配");

        FundService.TransferApproveRequest ap = new FundService.TransferApproveRequest();
        ap.approved = true;
        TransferApply posted = svc.approveTransfer(tid, ap, 2L, new BigDecimal("1500"));
        assertEquals(FundService.TR_POSTED, posted.status);

        FundLedger l1 = ledgers.findBySubAccountAndDate(1, Clocks.today());
        FundLedger l2 = ledgers.findBySubAccountAndDate(2, Clocks.today());
        assertDec("700", l1.availableFund);
        assertDec("800", l2.availableFund);
        assertDec("1500", l1.availableFund.add(l2.availableFund)); // 加总不变

        List<FundFlow> pair = svc.listTransferFlows(tid, 2L);
        assertEquals(2, pair.size());
        assertDec(pair.get(0).amount.toPlainString(), pair.get(1).amount);

        svc.rollbackTransfer(tid, 2L);
        assertDec("1000", l1.availableFund);
        assertDec("500", l2.availableFund);
        assertEquals(FundService.TR_ROLLED_BACK, transfers.findById(tid).status);
        assertEquals(4, flows.rows.size(), "记账 2 条 + 回滚 2 条");
    }

    @Test
    @DisplayName("验收4：加总校验不通过 ⇒ 回滚本次记账并恢复余额")
    void transferAggMismatchRollsBack() {
        initLedger(1, "1000");
        initLedger(2, "500");
        long tid = initiateTransfer(1, 2, "300", "调配");
        FundService.TransferApproveRequest ap = new FundService.TransferApproveRequest();
        ap.approved = true;
        TransferApply r = svc.approveTransfer(tid, ap, 2L, new BigDecimal("9999"));
        assertEquals(FundService.TR_ROLLED_BACK, r.status);
        assertEquals("N", r.aggCheckPassed);
        assertDec("1000", ledgers.findBySubAccountAndDate(1, Clocks.today()).availableFund);
        assertDec("500", ledgers.findBySubAccountAndDate(2, Clocks.today()).availableFund);
    }

    @Test
    @DisplayName("验收4：发起人自审被拒；交易岗审批被拒（岗位分离）")
    void transferApprovalPositionSeparation() {
        initLedger(1, "1000");
        initLedger(2, "500");
        long t1 = initiateTransfer(1, 2, "100", "调配");
        FundService.TransferApproveRequest ap = new FundService.TransferApproveRequest();
        ap.approved = true;
        BizException self = assertThrows(BizException.class,
                () -> svc.approveTransfer(t1, ap, 1L, null));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, self.errorCode());

        long t2 = initiateTransfer(1, 2, "100", "调配");
        tradingUsers.add(3L);
        BizException trading = assertThrows(BizException.class,
                () -> svc.approveTransfer(t2, ap, 3L, null));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, trading.errorCode());
    }

    // ------------------------------------------------------------------ 验收 5

    @Test
    @DisplayName("验收5：额度超限告警（默认阈值 0.80/0.95/1.00），告警不阻断")
    void quotaAlertLevels() {
        svc.setQuota(quota(1, FundService.SCOPE_SUB, "1000", null), null, 1L);
        assertEquals(FundService.ALERT_INFO,
                svc.checkQuotaAlert(1, FundService.SCOPE_SUB, new BigDecimal("850"), 1L).get(0).alertLevel);
        assertEquals(FundService.ALERT_WARNING,
                svc.checkQuotaAlert(1, FundService.SCOPE_SUB, new BigDecimal("960"), 1L).get(0).alertLevel);
        FundQuotaAlert critical =
                svc.checkQuotaAlert(1, FundService.SCOPE_SUB, new BigDecimal("1000"), 1L).get(0);
        assertEquals(FundService.ALERT_CRITICAL, critical.alertLevel);
        assertEquals("NONE", critical.blockedOperation, "告警不阻断（R-FUND-7）");
        assertTrue(svc.checkQuotaAlert(1, FundService.SCOPE_SUB, new BigDecimal("500"), 1L).isEmpty());
        assertEquals(3, alerts.rows.size());
    }

    @Test
    @DisplayName("验收5：CFG-25 阈值可配置；账户级额度不得超过真实账户上限")
    void quotaConfigurableAndAccountCap() {
        cfg = "0.50,0.75,0.90";
        svc.setQuota(quota(1, FundService.SCOPE_SUB, "1000", null), null, 1L);
        assertEquals(FundService.ALERT_INFO,
                svc.checkQuotaAlert(1, FundService.SCOPE_SUB, new BigDecimal("600"), 1L).get(0).alertLevel);
        assertEquals(FundService.ALERT_WARNING,
                svc.checkQuotaAlert(1, FundService.SCOPE_SUB, new BigDecimal("800"), 1L).get(0).alertLevel);
        assertEquals(FundService.ALERT_CRITICAL,
                svc.checkQuotaAlert(1, FundService.SCOPE_SUB, new BigDecimal("950"), 1L).get(0).alertLevel);

        BizException ex = assertThrows(BizException.class, () -> svc.setQuota(
                quota(null, FundService.SCOPE_ACCOUNT, "999999", null), new BigDecimal("100"), 1L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());
    }

    // ------------------------------------------------------------------ FR-FUND-17 / 隔离

    @Test
    @DisplayName("FR-FUND-17：正常跑完一日结论一致；篡改期末/真实账户 ⇒ 不一致或未闭环差异")
    void dailyReconcileConsistentAndTampered() {
        initLedger(1, "1000");
        long a = deposit(1, "500", "K1");
        review(a, 2L, true, null);
        receipt(a, FundService.RECEIPT_SUCCESS, "R1", 1L);

        FundService.ReconcileOutcome ok =
                svc.dailyReconcile(Clocks.today(), new BigDecimal("1500"), 1L);
        assertEquals(FundService.RECON_CONSISTENT, ok.result);
        assertTrue(ok.differences.isEmpty());

        ledgers.rows.get(0).endBalance = ledgers.rows.get(0).endBalance.add(new BigDecimal("1"));
        FundService.ReconcileOutcome bad =
                svc.dailyReconcile(Clocks.today(), new BigDecimal("1500"), 1L);
        assertEquals(FundService.RECON_OPEN_DIFF, bad.result);
        assertFalse(bad.differences.isEmpty());

        FundService.ReconcileOutcome mismatch =
                svc.dailyReconcile(Clocks.today(), new BigDecimal("1"), 1L);
        assertFalse(FundService.RECON_CONSISTENT.equals(mismatch.result));
    }

    @Test
    @DisplayName("NFR-SEC-7：读取面按可见子账户收窄；空可见 ⇒ 恒假（fail-closed）")
    void scopeFailClosed() {
        PageQuery q = PageQuery.of("1", "20", null, "ledger_date", "asc");
        visible.set(List.of(2));
        svc.listFlow(q, null, null, null, null, 1L);
        assertTrue(flows.lastWhere.contains("sub_account_id IN"), "受限可见须下推 IN 条件");

        BizException ex = assertThrows(BizException.class,
                () -> svc.listFlow(q, null, null, 1, null, 1L));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, ex.errorCode());

        visible.set(List.of());
        svc.listFlow(q, null, null, null, null, 1L);
        assertTrue(flows.lastWhere.contains("IN (-1)"), "空可见须恒假，绝不退化为不过滤");
    }

    @Test
    @DisplayName("划拨前置：划出=划入被拒；划出可用不足被拒")
    void transferPreconditions() {
        initLedger(1, "1000");
        initLedger(2, "500");
        FundService.TransferRequest same = new FundService.TransferRequest();
        same.fromSubAccountId = 1;
        same.toSubAccountId = 1;
        same.amount = "100";
        same.reason = "x";
        BizException ex = assertThrows(BizException.class, () -> svc.initiateTransfer(same, 1L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());

        BizException poor = assertThrows(BizException.class, () -> initiateTransfer(1, 2, "99999", "x"));
        assertEquals(ErrorCode.PARAM_INVALID, poor.errorCode());
    }

    // ------------------------------------------------------------------ 工具

    private long initLedger(int sub, String begin) {
        FundService.LedgerInitRequest req = new FundService.LedgerInitRequest();
        req.subAccountId = sub;
        req.ledgerDate = Clocks.today().toString();
        req.beginBalance = begin;
        req.reviewerId = 2L;
        req.reviewComment = "复核通过";
        return svc.initLedger(req, 1L).id;
    }

    private long deposit(int sub, String amount, String idem) {
        FundService.DepositWithdrawRequest req = new FundService.DepositWithdrawRequest();
        req.subAccountId = sub;
        req.direction = FundService.DIR_IN;
        req.amount = amount;
        req.idempotencyKey = idem;
        req.fundSourceDesc = "测试入金";
        return svc.initiateDepositWithdraw(req, 1L).id;
    }

    private FundService.ReviewRequest approve() {
        FundService.ReviewRequest r = new FundService.ReviewRequest();
        r.approved = true;
        r.comment = "同意";
        return r;
    }

    private void review(long applyId, long reviewer, boolean approved, String reason) {
        FundService.ReviewRequest r = new FundService.ReviewRequest();
        r.approved = approved;
        r.rejectReason = reason;
        svc.reviewDepositWithdraw(applyId, r, reviewer);
    }

    private FundService.ReceiptRequest receiptReq(String result, String idem, long ts, boolean queryConfirmed) {
        FundService.ReceiptRequest req = new FundService.ReceiptRequest();
        req.idempotencyKey = idem;
        req.result = result;
        req.receiptTsMs = ts;
        req.queryConfirmed = queryConfirmed;
        return req;
    }

    private FundService.ReceiptResult receipt(long applyId, String result, String idem, long ts) {
        FundService.ReceiptRequest req = receiptReq(result, idem, ts, false);
        req.applyId = applyId;
        return svc.onFundReceipt(req, 1L);
    }

    private long initiateTransfer(int from, int to, String amount, String reason) {
        FundService.TransferRequest req = new FundService.TransferRequest();
        req.fromSubAccountId = from;
        req.toSubAccountId = to;
        req.amount = amount;
        req.reason = reason;
        return svc.initiateTransfer(req, 1L).id;
    }

    private FundService.QuotaRequest quota(Integer sub, String scope, String value, String to) {
        FundService.QuotaRequest q = new FundService.QuotaRequest();
        q.subAccountId = sub;
        q.quotaScope = scope;
        q.quotaValue = value;
        q.effectiveFrom = Clocks.today().toString();
        q.effectiveTo = to;
        q.changeReason = "测试";
        return q;
    }

    private static void assertDec(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + actual.toPlainString());
    }
}
