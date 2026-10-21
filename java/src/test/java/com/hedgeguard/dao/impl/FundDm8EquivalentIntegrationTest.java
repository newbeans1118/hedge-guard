package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.entity.CapitalReceipt;
import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.dao.entity.FundFlow;
import com.hedgeguard.dao.entity.FundLedger;
import com.hedgeguard.dao.entity.FundQuota;
import com.hedgeguard.dao.entity.FundQuotaAlert;
import com.hedgeguard.dao.entity.FundReconcile;
import com.hedgeguard.dao.entity.FundReviewRecord;
import com.hedgeguard.dao.entity.TransferApply;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-045} 等价执行（建表 → 真实 DAO 读写 → 约束强制），覆盖 FUND 9 张表与 9 个 Dm*Dao。
 *
 * <p>本环境无可达 DM8 实例，故把 {@code docs/DEPLOY/schema/m2_fund.sql} 的 {@code CREATE TABLE}
 * 逐条喂给 {@link InMemoryDmEngine}（按 DDL 语义登记列/NOT NULL/主键/唯一/外键/CHECK），
 * 再由真实 DAO 生成 INSERT/SELECT 真正执行、按语义求值。仅喂 FUND 脚本 ⇒ 跨模块目标表
 * （t_sub_account/t_settle_cycle/t_trade_fill/t_aggregation_check）缺失，引擎对缺失引用表
 * 跳过外键校验（模块内 FK 仍强制）；跨模块外键的存在性由 {@code FundSchemaTest} 静态核对。
 *
 * <p>结论口径：通过 ⇒ 「建表 → 读写 → 约束强制」在等价引擎上跑通；DM8 实机待部署方复跑。
 */
class FundDm8EquivalentIntegrationTest {

    private static final String DDL_REL = "docs/DEPLOY/schema/m2_fund.sql";

    private InMemoryDmEngine engine;
    private TxTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        Path file = repoRoot().resolve(DDL_REL);
        assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
        engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
    }

    @Test
    @DisplayName("建表：m2_fund.sql 登记 9 张表；台账/流水子账户 NOT NULL；幂等与 CHECK 已登记")
    void ddlCreatesNineTables() {
        assertEquals(9, engine.tableCount(), "应登记 9 张 FUND 表，实际 " + engine.tableCount());
        InMemoryDmEngine.Table ledger = engine.table("t_fund_ledger");
        assertNotNull(ledger, "t_fund_ledger 应已建表");
        assertTrue(ledger.columns.get("sub_account_id").notNull, "t_fund_ledger.sub_account_id 应 NOT NULL");
        assertTrue(ledger.columns.get("id").identity, "id 应 IDENTITY 自增");
        InMemoryDmEngine.Table flow = engine.table("t_fund_flow");
        assertTrue(flow.columns.get("voucher_no").notNull, "t_fund_flow.voucher_no 应 NOT NULL");
        assertTrue(hasUnique(flow, "uk_fund_flow_voucher_no"), "t_fund_flow 应登记 uk_fund_flow_voucher_no");
        InMemoryDmEngine.Table dw = engine.table("t_deposit_withdraw_apply");
        assertTrue(hasUnique(dw, "uk_dw_apply_idem"), "t_deposit_withdraw_apply 应登记 uk_dw_apply_idem");
        InMemoryDmEngine.Table alert = engine.table("t_fund_quota_alert");
        assertTrue(hasCheck(alert, "ck_fund_quota_alert_blocked"), "t_fund_quota_alert 应登记 ck_fund_quota_alert_blocked");
        InMemoryDmEngine.Table reconcile = engine.table("t_fund_reconcile");
        assertTrue(reconcile.columns.containsKey("difference_list"), "t_fund_reconcile 应含 CLOB 列 difference_list");
        assertTrue(!engine.table("t_fund_quota").columns.get("sub_account_id").notNull,
                "t_fund_quota.sub_account_id 应为可空（账户级）");
    }

    @Test
    @DisplayName("读写：9 个真实 Dm*Dao 生成 INSERT/SELECT 经引擎执行——主键回填、按索引读回、CLOB 往返")
    void readWriteThroughRealDao() {
        DmFundLedgerDao ledgerDao = new DmFundLedgerDao(tx);
        FundLedger ledger = ledger();
        assertEquals(1, ledgerDao.insert(ledger, 7L));
        assertTrue(ledger.id > 0, "自增主键应回填");
        assertNotNull(ledgerDao.findBySubAccountAndDate(1, D_1004), "按子账户+记账日应读回台账");

        DmFundFlowDao flowDao = new DmFundFlowDao(tx);
        FundFlow flow = flow();
        assertEquals(1, flowDao.insert(flow, 7L));
        assertEquals("FV20261004001", flowDao.findByVoucherNo("FV20261004001").voucherNo, "按凭证编号应读回流水");
        assertEquals(1, flowDao.findBySubAccountAndDate(1, D_1004).size(), "按子账户+日期应读回 1 行");

        DmDepositWithdrawApplyDao dwDao = new DmDepositWithdrawApplyDao(tx);
        DepositWithdrawApply dw = dw();
        assertEquals(1, dwDao.insert(dw, 7L));
        assertNotNull(dwDao.findByIdempotencyKey("IDEM-1"), "按幂等键应读回申请");
        assertEquals(1, dwDao.findBySubAccountAndStatus(1, "PENDING_REVIEW").size());

        DmTransferApplyDao transferDao = new DmTransferApplyDao(tx);
        TransferApply transfer = transfer();
        assertEquals(1, transferDao.insert(transfer, 7L));
        assertNotNull(transferDao.findByApplyNo("TR20261004001"), "按划拨单号应读回");
        assertEquals(1, transferDao.findByFromSubAccountAndStatus(1, "PENDING_APPROVAL").size());
        assertNotNull(transferDao.findByIdempotencyKey("IDEM-TR-1"), "按幂等键应读回划拨申请（v1.3.9/T-202）");

        DmCapitalReceiptDao receiptDao = new DmCapitalReceiptDao(tx);
        CapitalReceipt receipt = receipt(dw.id);
        assertEquals(1, receiptDao.insert(receipt, 7L));
        assertNotNull(receiptDao.findByIdem("RCPT-1", "SUCCESS", 1759536600000L), "按回执幂等键应读回");
        assertEquals(1, receiptDao.findByApplyId(dw.id).size(), "按申请应读回 1 条回执");

        DmFundReviewRecordDao reviewDao = new DmFundReviewRecordDao(tx);
        FundReviewRecord review = review(dw.id);
        assertEquals(1, reviewDao.insert(review, 7L));
        assertEquals(1, reviewDao.findByBiz("DEPOSIT_WITHDRAW", dw.id).size(), "按业务对象应读回复核记录");
        assertEquals(1, reviewDao.findBySelfReviewRejected("N").size());

        DmFundQuotaDao quotaDao = new DmFundQuotaDao(tx);
        FundQuota quota = quota();
        assertEquals(1, quotaDao.insert(quota, 7L));
        assertEquals(1, quotaDao.findBySubAccount(1).size(), "按子账户应读回额度");
        assertEquals(1, quotaDao.findByScope("SUB_ACCOUNT").size());

        DmFundQuotaAlertDao alertDao = new DmFundQuotaAlertDao(tx);
        FundQuotaAlert alert = alert();
        assertEquals(1, alertDao.insert(alert, 7L));
        assertEquals(1, alertDao.findBySubAccount(1).size());
        assertEquals(1, alertDao.findByLevel("WARNING").size());

        DmFundReconcileDao reconcileDao = new DmFundReconcileDao(tx);
        FundReconcile reconcile = reconcile(1);
        assertEquals(1, reconcileDao.insert(reconcile, 7L));
        FundReconcile read = reconcileDao.findByKey(D_1004, "SUB_ACCOUNT", 1);
        assertNotNull(read, "按对账键应读回子账户级对账");
        assertEquals("diff-a,diff-b", read.differenceList, "CLOB 应往返一致");
        assertEquals("CONSISTENT", read.result);

        FundReconcile accountReconcile = reconcile(null);
        accountReconcile.reconcileLevel = "ACCOUNT";
        accountReconcile.subAccountId = null;
        accountReconcile.aggCompareValue = new BigDecimal("105000.0000");
        accountReconcile.realAccountValue = new BigDecimal("105000.0000");
        accountReconcile.aggConsistent = "Y";
        assertEquals(1, reconcileDao.insert(accountReconcile, 7L));
        assertNotNull(reconcileDao.findByKey(D_1004, "ACCOUNT", null),
                "账户级对账（sub_account_id IS NULL）应按键读回");
        assertEquals(2, reconcileDao.findByDateAndLevel(D_1004, "SUB_ACCOUNT").size()
                + reconcileDao.findByDateAndLevel(D_1004, "ACCOUNT").size());
    }

    @Test
    @DisplayName("约束：凭证编号/幂等键唯一、子账户 NOT NULL、模块内外键、CHECK（告警不阻断/方向）被强制")
    void constraintsEnforced() {
        DmFundFlowDao flowDao = new DmFundFlowDao(tx);
        flowDao.insert(flow(), 7L);
        IllegalStateException dupVoucher = assertThrows(IllegalStateException.class,
                () -> flowDao.insert(flow(), 7L), "重复 voucher_no 应被唯一约束拒绝");
        assertTrue(dupVoucher.getMessage() != null, "唯一冲突应带原因");
        assertEquals(1L, engine.rejectedByUnique(), "引擎应记录 1 次唯一冲突（凭证编号）");

        DmDepositWithdrawApplyDao dwDao = new DmDepositWithdrawApplyDao(tx);
        dwDao.insert(dw(), 7L);
        assertThrows(IllegalStateException.class, () -> dwDao.insert(dw(), 7L),
                "重复 idempotency_key 应被 uk_dw_apply_idem 拒绝（验收 4）");
        assertEquals(2L, engine.rejectedByUnique(), "引擎应累计 2 次唯一冲突（凭证编号 + 幂等键）");

        DmFundLedgerDao ledgerDao = new DmFundLedgerDao(tx);
        FundLedger badLedger = ledger();
        badLedger.subAccountId = null;
        assertThrows(IllegalStateException.class, () -> ledgerDao.insert(badLedger, 7L),
                "子账户 NOT NULL 应被拒绝（验收 2）");
        assertEquals(1L, engine.rejectedByNotNull(), "引擎应记录 1 次 NOT NULL 冲突");

        DmCapitalReceiptDao receiptDao = new DmCapitalReceiptDao(tx);
        assertThrows(IllegalStateException.class, () -> receiptDao.insert(receipt(999L), 7L),
                "孤儿 apply_id 应被模块内外键拒绝");
        assertEquals(1L, engine.rejectedByForeignKey(), "引擎应记录 1 次外键冲突");

        DmFundQuotaAlertDao alertDao = new DmFundQuotaAlertDao(tx);
        FundQuotaAlert badAlert = alert();
        badAlert.blockedOperation = "WITHDRAW";
        assertThrows(IllegalStateException.class, () -> alertDao.insert(badAlert, 7L),
                "blocked_operation 非 NONE 应被 CHECK 拒绝（告警不阻断）");
        assertEquals(1L, engine.rejectedByCheck(), "引擎应记录 1 次 CHECK 冲突（告警不阻断）");

        DmFundFlowDao flowDao2 = new DmFundFlowDao(tx);
        FundFlow badFlow = flow();
        badFlow.voucherNo = "FV20261004002";
        badFlow.direction = "SIDEWAYS";
        assertThrows(IllegalStateException.class, () -> flowDao2.insert(badFlow, 7L),
                "非法 direction 应被 ck_fund_flow_direction 拒绝");
        assertEquals(2L, engine.rejectedByCheck(), "引擎应累计 2 次 CHECK 冲突");
    }

    @Test
    @DisplayName("约束（v1.3.9/T-202）：划拨幂等键唯一（uk_transfer_apply_idem）被引擎强制——先查后插的 uk 兜底")
    void transferIdempotencyKeyUniqueEnforced() {
        DmTransferApplyDao transferDao = new DmTransferApplyDao(tx);
        transferDao.insert(transfer(), 7L);
        TransferApply bypass = transfer();
        bypass.applyNo = "TR20261004002"; // 单号不同 ⇒ 仅 idempotency_key 撞 uk_transfer_apply_idem
        assertThrows(IllegalStateException.class, () -> transferDao.insert(bypass, 7L),
                "同幂等键绕过服务层先查直插应被 uk_transfer_apply_idem 拒绝（FR-FUND-10）");
        assertEquals(1L, engine.rejectedByUnique(), "引擎应记录 1 次唯一冲突（划拨幂等键）");
        assertEquals(1, transferDao.findByFromSubAccountAndStatus(1, "PENDING_APPROVAL").size(),
                "拒绝后首单仍唯一在库（不产生第二笔划拨）");
    }

    @Test
    @DisplayName("只增不改：DmFundFlowDao.updateById 显式抛（R-FUND-2 流水只增不改不删）")
    void appendOnlyDaoRejectsUpdate() {
        assertThrows(UnsupportedOperationException.class,
                () -> new DmFundFlowDao(tx).updateById(flow(), 7L));
    }

    @Test
    @DisplayName("可加总（验收 2）：两个子账户台账期末余额之和 = 账户合计（Σ 子账户 = 真实账户）")
    void ledgerIsAdditive() {
        DmFundLedgerDao dao = new DmFundLedgerDao(tx);
        FundLedger a = ledger();
        dao.insert(a, 7L);
        FundLedger b = ledger();
        b.subAccountId = 2;
        b.beginBalance = new BigDecimal("200000.0000");
        b.amountIn = new BigDecimal("3000.0000");
        b.endBalance = new BigDecimal("203000.0000");
        dao.insert(b, 7L);

        List<FundLedger> rows = dao.findByDate(D_1004);
        assertEquals(2, rows.size(), "按记账日应取回 2 个子账户台账");
        BigDecimal sum = BigDecimal.ZERO;
        for (FundLedger r : rows) {
            sum = sum.add(r.endBalance);
        }
        assertEquals(new BigDecimal("308000.0000"), sum,
                "Σ 各子账户期末余额应可直接求和（加总对账核心，R-FUND-1）");
    }

    @Test
    @DisplayName("读侧闭合（TE-T045-001）：balance_after 逐笔读回 == 期初 + 有向发生额（IN 加 / OUT 减）")
    void balanceAfterReadbackClosesEquation() {
        DmFundLedgerDao ledgerDao = new DmFundLedgerDao(tx);
        ledgerDao.insert(ledger(), 7L);
        FundLedger begin = ledgerDao.findBySubAccountAndDate(1, D_1004);
        assertNotNull(begin, "应读回台账作为期初余额");
        BigDecimal open = begin.beginBalance;

        DmFundFlowDao flowDao = new DmFundFlowDao(tx);

        FundFlow in = flow();
        in.voucherNo = "FV20261004010";
        in.direction = "IN";
        in.amount = new BigDecimal("5000.0000");
        in.balanceAfter = open.add(in.amount);
        assertEquals(1, flowDao.insert(in, 7L));
        FundFlow inRead = flowDao.findByVoucherNo("FV20261004010");
        assertNotNull(inRead, "应按凭证编号读回 IN 方向流水");
        assertEquals(0, inRead.balanceAfter.compareTo(open.add(inRead.amount)),
                "IN 方向：balance_after 应 == 期初 + 发生额（读侧不得漂移到 amount）");
        assertEquals(0, inRead.balanceAfter.compareTo(new BigDecimal("105000.0000")),
                "IN 方向：期初 100000 + 发生额 5000 = 期末 105000");

        FundFlow out = flow();
        out.voucherNo = "FV20261004011";
        out.flowType = "WITHDRAW";
        out.direction = "OUT";
        out.amount = new BigDecimal("2000.0000");
        out.balanceAfter = open.subtract(out.amount);
        assertEquals(1, flowDao.insert(out, 7L));
        FundFlow outRead = flowDao.findByVoucherNo("FV20261004011");
        assertNotNull(outRead, "应按凭证编号读回 OUT 方向流水");
        assertEquals(0, outRead.balanceAfter.compareTo(open.subtract(outRead.amount)),
                "OUT 方向：balance_after 应 == 期初 - 发生额（有向负向亦闭合）");
        assertEquals(0, outRead.balanceAfter.compareTo(new BigDecimal("98000.0000")),
                "OUT 方向：期初 100000 - 发生额 2000 = 期末 98000");
    }

    // ------------------------------------------------------------ 构造器

    private static final LocalDate D_1004 = LocalDate.of(2026, 10, 4);

    private static FundLedger ledger() {
        FundLedger e = new FundLedger();
        e.subAccountId = 1;
        e.ledgerDate = D_1004;
        e.beginBalance = new BigDecimal("100000.0000");
        e.amountIn = new BigDecimal("5000.0000");
        e.amountOut = BigDecimal.ZERO;
        e.transferIn = BigDecimal.ZERO;
        e.transferOut = BigDecimal.ZERO;
        e.pnlCarryover = BigDecimal.ZERO;
        e.commission = BigDecimal.ZERO;
        e.marginChange = BigDecimal.ZERO;
        e.differenceAdjust = BigDecimal.ZERO;
        e.endBalance = new BigDecimal("105000.0000");
        e.availableFund = new BigDecimal("90000.0000");
        e.marginOccupied = new BigDecimal("15000.0000");
        e.equity = new BigDecimal("105000.0000");
        e.riskRatio = new BigDecimal("0.142857");
        e.isClosed = "N";
        return e;
    }

    private static FundFlow flow() {
        FundFlow e = new FundFlow();
        e.subAccountId = 1;
        e.voucherNo = "FV20261004001";
        e.ledgerDate = D_1004;
        e.flowType = "DEPOSIT";
        e.amount = new BigDecimal("5000.0000");
        e.direction = "IN";
        e.balanceAfter = new BigDecimal("105000.0000");
        e.operatorId = 7L;
        e.flowTime = LocalDateTime.of(2026, 10, 4, 9, 30);
        e.remark = "入金";
        e.doubleReviewRequired = "N";
        return e;
    }

    private static DepositWithdrawApply dw() {
        DepositWithdrawApply e = new DepositWithdrawApply();
        e.subAccountId = 1;
        e.applyNo = "DW20261004001";
        e.idempotencyKey = "IDEM-1";
        e.direction = "IN";
        e.amount = new BigDecimal("5000.0000");
        e.fundSourceDesc = "自有资金";
        e.status = "PENDING_REVIEW";
        e.applicantId = 7L;
        e.originalConfirmed = "N";
        return e;
    }

    private static TransferApply transfer() {
        TransferApply e = new TransferApply();
        e.subAccountId = 1;
        e.fromSubAccountId = 1;
        e.toSubAccountId = 2;
        e.applyNo = "TR20261004001";
        e.idempotencyKey = "IDEM-TR-1"; // v1.3.9/T-202：NOT NULL 增列（引擎 DDL DEFAULT 不生效 ⇒ 显式赋值）
        e.amount = new BigDecimal("1000.0000");
        e.reason = "资金调剂";
        e.expectedEffectiveDate = LocalDate.of(2026, 10, 5);
        e.status = "PENDING_APPROVAL";
        e.applicantId = 7L;
        return e;
    }

    private static CapitalReceipt receipt(long applyId) {
        CapitalReceipt e = new CapitalReceipt();
        e.subAccountId = 1;
        e.idempotencyKey = "RCPT-1";
        e.applyId = applyId;
        e.result = "SUCCESS";
        e.amount = new BigDecimal("5000.0000");
        e.receiptTsMs = 1759536600000L;
        e.receiptTime = LocalDateTime.of(2026, 10, 4, 9, 31);
        e.booked = "N";
        e.queryConfirmed = "N";
        return e;
    }

    private static FundReviewRecord review(long bizId) {
        FundReviewRecord e = new FundReviewRecord();
        e.subAccountId = 1;
        e.bizType = "DEPOSIT_WITHDRAW";
        e.bizId = bizId;
        e.applicantId = 7L;
        e.reviewerId = 8L;
        e.reviewResult = "APPROVED";
        e.reviewComment = "同意";
        e.selfReviewRejected = "N";
        e.reviewTime = LocalDateTime.of(2026, 10, 4, 9, 32);
        return e;
    }

    private static FundQuota quota() {
        FundQuota e = new FundQuota();
        e.subAccountId = 1;
        e.quotaScope = "SUB_ACCOUNT";
        e.quotaValue = new BigDecimal("500000.0000");
        e.effectiveFrom = LocalDate.of(2026, 10, 1);
        e.operatorId = 7L;
        return e;
    }

    private static FundQuotaAlert alert() {
        FundQuotaAlert e = new FundQuotaAlert();
        e.subAccountId = 1;
        e.quotaScope = "SUB_ACCOUNT";
        e.quotaValue = new BigDecimal("500000.0000");
        e.occupiedValue = new BigDecimal("510000.0000");
        e.alertLevel = "WARNING";
        e.alertTime = LocalDateTime.of(2026, 10, 4, 9, 33);
        e.blockedOperation = "NONE";
        e.disposed = "N";
        return e;
    }

    private static FundReconcile reconcile(Integer subAccountId) {
        FundReconcile e = new FundReconcile();
        e.subAccountId = subAccountId;
        e.tradeDate = D_1004;
        e.reconcileLevel = "SUB_ACCOUNT";
        e.beginBalance = new BigDecimal("100000.0000");
        e.occurrenceTotal = new BigDecimal("5000.0000");
        e.endBalance = new BigDecimal("105000.0000");
        e.balanceEquationOk = "Y";
        e.result = "CONSISTENT";
        e.differenceList = "diff-a,diff-b";
        e.reconcileTime = LocalDateTime.of(2026, 10, 4, 15, 0);
        e.operatorId = 7L;
        return e;
    }

    // ------------------------------------------------------------ 工具

    private static boolean hasUnique(InMemoryDmEngine.Table table, String name) {
        return table.uniques.stream().anyMatch(u -> name.equals(u.name));
    }

    private static boolean hasCheck(InMemoryDmEngine.Table table, String name) {
        return table.checks.stream().anyMatch(c -> name.equals(c.name));
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根（应含 docs/DATA_MODEL.md），当前目录="
                + Paths.get("").toAbsolutePath());
    }
}
