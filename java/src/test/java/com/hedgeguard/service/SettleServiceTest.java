package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.SettleAggregationCheck;
import com.hedgeguard.dao.entity.SettleCtpBatch;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.SettlePosition;
import com.hedgeguard.dao.entity.SettleReconcileDifference;
import com.hedgeguard.dao.entity.SettleReconcileRecord;
import com.hedgeguard.dao.entity.SettleReport;
import com.hedgeguard.dao.entity.SettleStressTest;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-047} {@link SettleService} 服务层单测（{@code NFR-TEST-2}：不引 Mockito，以记录型内存假 DAO 替代）。
 *
 * <p>覆盖验收标准 1–4：
 * <ul>
 *   <li>验收 1：CTP 日终入库 → 子账户拆分 → 独立结算 → 三类核对；</li>
 *   <li>验收 2：{@code Σ 子账户 = 真实账户} 校验 + 人为构造不一致告警；</li>
 *   <li>验收 3：仅差异所属子账户被阻断（新开仓/划拨/关账），不波及其他子账户、不阻断平仓；</li>
 *   <li>验收 4：关账门禁（差异未闭环不得关账）。</li>
 * </ul>
 */
class SettleServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 4);
    private static final int SUB_A = 101;
    private static final int SUB_B = 102;
    private static final long OP = 7L;

    private SettleFakes.Cycles cycles;
    private SettleFakes.Positions positions;
    private SettleFakes.Funds funds;
    private SettleFakes.CtpBatches batches;
    private SettleFakes.CtpPositions ctpPositions;
    private SettleFakes.CtpFunds ctpFunds;
    private SettleFakes.CtpTrades ctpTrades;
    private SettleFakes.ReconcileRecords records;
    private SettleFakes.Differences differences;
    private SettleFakes.AggChecks aggChecks;
    private SettleFakes.Candidates candidates;
    private SettleFakes.Reports reports;
    private SettleFakes.StressTests stress;
    private SettleService service;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        cycles = new SettleFakes.Cycles();
        positions = new SettleFakes.Positions();
        funds = new SettleFakes.Funds();
        batches = new SettleFakes.CtpBatches();
        ctpPositions = new SettleFakes.CtpPositions();
        ctpFunds = new SettleFakes.CtpFunds();
        ctpTrades = new SettleFakes.CtpTrades();
        records = new SettleFakes.ReconcileRecords();
        differences = new SettleFakes.Differences();
        aggChecks = new SettleFakes.AggChecks();
        candidates = new SettleFakes.Candidates();
        reports = new SettleFakes.Reports();
        stress = new SettleFakes.StressTests();
        SubAccountVisibility visibility = userId -> Arrays.asList(SUB_A, SUB_B);
        service = new SettleService(FakeTx.newTemplate(), visibility, batches, ctpPositions, ctpTrades, ctpFunds,
                cycles, positions, funds, new SettleFakes.Pnls(), records, differences, aggChecks, candidates,
                reports, stress);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ================================================================ FR-SETTLE-1 / 验收1

    @Test
    @DisplayName("验收1/FR-SETTLE-1：CTP 数据到达 ⇒ 入库且置已确认；重复接收幂等")
    void ingestArrivedAndIdempotent() {
        SettleService.CtpIngestResult r1 = service.ingestCtpData(ctpRequest("15", "1000"), OP);
        assertFalse(r1.pendingRetry);
        assertEquals(1, r1.positionCount);
        assertEquals(1, r1.fundCount);
        SettleCtpBatch b = batches.rows.get(0);
        assertEquals("Y", b.isConfirmed);
        assertEquals("PARSED", b.parseStatus);
        assertNotNull(b.receivedTime, "接收记录须含接收时间（FR-SETTLE-1 验收4）");
        assertEquals("CTP-20261004", b.sourceIdentifier);
        int posBefore = ctpPositions.rows.size();

        SettleService.CtpIngestResult r2 = service.ingestCtpData(ctpRequest("15", "1000"), OP);
        assertEquals(r1.batchId, r2.batchId, "重复接收同一批次应幂等（同批次号）");
        assertEquals(posBefore, ctpPositions.rows.size(), "重复接收不得插入重复明细");
        assertEquals(1, batches.rows.size());
    }

    @Test
    @DisplayName("验收1/FR-SETTLE-1 验收2：CTP 未到达 ⇒ 置待重试、不产生结算结果；结算被拒")
    void ingestNotArrivedPendingAndSettlementRefused() {
        SettleService.CtpIngestResult r = service.ingestCtpData(emptyCtpRequest(), OP);
        assertTrue(r.pendingRetry);
        SettleCtpBatch b = batches.rows.get(0);
        assertEquals("PENDING_DATA", b.parseStatus);
        assertEquals("N", b.isConfirmed);
        assertEquals(1, b.retryCount);
        assertTrue(ctpPositions.rows.isEmpty());

        SettleService.SettlementInput in = settlement("15");
        BizException e = assertThrows(BizException.class, () -> service.runSettlement(in, OP));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, e.errorCode(), "数据未到达不得用推算数据替代（R-SETTLE-3）");
        assertTrue(cycles.rows.isEmpty(), "不得产生任何结算结果");
    }

    @Test
    @DisplayName("验收1：拆分 → 独立结算（六项齐备）→ 三类核对记录")
    void settlementSplitsAndReconciles() {
        service.ingestCtpData(ctpRequest("15", "1000"), OP);
        SettleService.SettlementInput in = settlement("15");
        in.positions.add(pos(SUB_A, "cu2612", "LONG", "TODAY", "10"));
        in.positions.add(pos(SUB_B, "cu2612", "LONG", "TODAY", "5"));
        in.funds.add(fund(SUB_A, "600", "600", "200"));
        in.funds.add(fund(SUB_B, "400", "400", "0"));

        List<SettleCycle> out = service.runSettlement(in, OP);
        assertEquals(2, out.size(), "每个子账户各一份独立结算单（R-SETTLE-1）");
        BigDecimal sum = BigDecimal.ZERO;
        for (SettlePosition p : positions.rows) {
            sum = sum.add(p.positionQty);
            assertTrue(p.subAccountId != null && p.subAccountId > 0, "拆分结果须显式携带子账户");
        }
        assertEquals(0, sum.compareTo(new BigDecimal("15")), "各子账户持仓之和 = CTP 合并持仓（FR-SETTLE-2 验收1）");
        for (SettleCycle c : out) {
            assertNotNull(c.closePnl);
            assertNotNull(c.floatPnl);
            assertNotNull(c.commission);
            assertNotNull(c.marginOccupied);
            assertNotNull(c.availableFund);
            assertNotNull(c.equity);
        }
        long tradeRec = records.rows.stream().filter(r -> "TRADE".equals(r.reconcileType)).count();
        long posRec = records.rows.stream().filter(r -> "POSITION".equals(r.reconcileType)).count();
        long fundRec = records.rows.stream().filter(r -> "FUND".equals(r.reconcileType)).count();
        assertEquals(2, tradeRec, "每子账户各一条成交核对记录");
        assertEquals(2, posRec, "每子账户各一条仓位核对记录");
        assertEquals(2, fundRec, "每子账户各一条资金核对记录（三类核对必做，R-SETTLE-4）");
    }

    @Test
    @DisplayName("验收1/FR-SETTLE-2 验收2：拆分不出（Σ子 < CTP）⇒ 列差额告警、不默认归入任一子账户")
    void unattributedPositionAlertsAndIsNotAssigned() {
        service.ingestCtpData(ctpRequest("15", "1000"), OP);
        SettleService.SettlementInput in = settlement("15");
        in.positions.add(pos(SUB_A, "cu2612", "LONG", "TODAY", "10")); // 仅 A，缺 5 手
        in.funds.add(fund(SUB_A, "600", "600", "200"));

        service.runSettlement(in, OP);
        SettleReconcileDifference posDiff = null;
        for (SettleReconcileDifference d : differences.rows) {
            if ("POSITION".equals(d.differenceType)) {
                posDiff = d;
            }
        }
        assertNotNull(posDiff, "拆分对不上须产生仓位差异（告警）");
        assertNull(posDiff.subAccountId, "无法归属的差额不得默认归入任一子账户");
        assertEquals("CTP_ONLY", posDiff.differenceKind);
        assertEquals(0, posDiff.diffQty.compareTo(new BigDecimal("5")));
        // A 的持仓仍为 10，未被抬到 15
        BigDecimal aQty = BigDecimal.ZERO;
        for (SettlePosition p : positions.rows) {
            if (p.subAccountId == SUB_A) {
                aQty = aQty.add(p.positionQty);
            }
        }
        assertEquals(0, aQty.compareTo(new BigDecimal("10")));
    }

    @Test
    @DisplayName("验收1/FR-SETTLE-4 验收3：子账户资金为负 ⇒ negative_fund_flag='Y'")
    void negativeFundFlagged() {
        service.ingestCtpData(ctpRequest("0", "1000"), OP);
        SettleService.SettlementInput in = settlement("0");
        in.funds.add(fund(SUB_A, "-50", "-50", "0"));
        List<SettleCycle> out = service.runSettlement(in, OP);
        assertEquals("Y", out.get(0).negativeFundFlag);
    }

    // ================================================================ 验收2 + 3：加总对账与阻断范围

    @Test
    @DisplayName("验收2：Σ子账户 = 真实账户 ⇒ CONSISTENT、无阻断（block_effective_time 为空）")
    void aggregateConsistent() {
        seedConsistentScenario("600", "400", "1000");
        SettleService.AggregateResult res = service.aggregate(firstCycleId(), OP);
        assertTrue(res.consistent, "Σ子 = 真实 ⇒ 一致");
        for (SettleAggregationCheck c : res.checks) {
            assertEquals("CONSISTENT", c.result);
            assertNull(c.blockEffectiveTime, "一致时不得有阻断时间");
        }
        assertTrue(res.blockedSubAccountIds.isEmpty());
    }

    @Test
    @DisplayName("验收2+3：人为构造不一致 ⇒ 全账户告警 + 仅差异所属子账户(SUB_A)被阻断")
    void aggregateInconsistentBlocksOnlyCulprit() {
        seedConsistentScenario("650", "400", "1000"); // A 结算权益 650 与台账 600 不符
        SettleService.AggregateResult res = service.aggregate(firstCycleId(), OP);
        assertFalse(res.consistent, "Σ子(1050) ≠ 真实(1000) ⇒ 不一致");
        assertTrue(res.blockedSubAccountIds.contains(SUB_A), "差异所属子账户 A 应被阻断");
        assertFalse(res.blockedSubAccountIds.contains(SUB_B), "其他子账户不得被阻断");

        boolean anyBlockTime = false;
        for (SettleAggregationCheck c : res.checks) {
            if ("INCONSISTENT_BLOCKED".equals(c.result)) {
                assertNotNull(c.blockEffectiveTime, "阻断须立即生效（NFR-REL-6）");
                assertNotNull(c.alertTime, "告警时间非空");
                anyBlockTime = true;
            }
        }
        assertTrue(anyBlockTime, "须存在不一致-已阻断的对账记录");

        // 验收3：仅 A 的新开仓/划拨/关账被拒；A 平仓放行；B 新开仓正常
        assertTrue(service.evaluateBlock(DAY, SUB_A, SettleService.ACTION_OPEN).blocked, "A 新开仓被拒");
        assertFalse(service.evaluateBlock(DAY, SUB_A, SettleService.ACTION_CLOSE).blocked, "A 平仓放行");
        assertFalse(service.evaluateBlock(DAY, SUB_B, SettleService.ACTION_OPEN).blocked, "B 新开仓正常");
        assertTrue(service.evaluateBlock(DAY, SUB_A, SettleService.ACTION_TRANSFER).blocked, "A 资金划拨被拒");
        assertTrue(service.evaluateBlock(DAY, SUB_A, SettleService.ACTION_DAY_CLOSE).blocked, "A 日终关账被拒");

        BizException e = assertThrows(BizException.class,
                () -> service.assertActionAllowed(DAY, SUB_A, SettleService.ACTION_OPEN));
        assertEquals(ErrorCode.AGGREGATION_MISMATCH, e.errorCode());
        assertNotNull(e.ruleId());
        assertNotNull(e.current());
        assertNotNull(e.threshold());
        assertNotNull(e.nextStep());
    }

    @Test
    @DisplayName("验收3：机判取数须为 t_agg_diff_candidate，不解析 possible_sub_accounts 文本")
    void machineJudgmentUsesCandidates() {
        seedConsistentScenario("650", "400", "1000");
        service.aggregate(firstCycleId(), OP);
        // 候选表中应有 A，且可能的展示文本仅为缓存
        boolean hasA = candidates.rows.stream().anyMatch(c -> c.subAccountId == SUB_A);
        assertTrue(hasA, "机判候选须落 t_agg_diff_candidate");
        for (SettleAggregationCheck c : aggChecks.rows) {
            if (c.possibleSubAccounts != null) {
                assertFalse(c.possibleSubAccounts.contains("NOT_A_SUB"), "展示文本不得混入判定来源");
            }
        }
    }

    // ================================================================ 验收4：关账门禁

    @Test
    @DisplayName("验收4：一致场景无未闭环差异 ⇒ 关账成功")
    void closeSucceedsWhenClean() {
        seedConsistentScenario("600", "400", "1000");
        service.aggregate(firstCycleId(), OP);
        SettleService.CloseResult r = service.closeCycle(firstCycleId(), OP);
        assertTrue(r.confirmed);
        assertEquals("CONFIRMED", cycles.findById(firstCycleId()).status);
    }

    @Test
    @DisplayName("验收4：差异未闭环 ⇒ 关账被拒；差异闭环后关账成功（并解除阻断）")
    void closeGateBlocksUntilDifferencesClosed() {
        seedConsistentScenario("650", "400", "1000");
        service.aggregate(firstCycleId(), OP);
        BizException e = assertThrows(BizException.class, () -> service.closeCycle(firstCycleId(), OP));
        assertTrue(e.errorCode() == ErrorCode.AGGREGATION_MISMATCH
                        || e.errorCode() == ErrorCode.STATE_NOT_ALLOWED,
                "差异未闭环不得关账（R-SETTLE-5），实际=" + e.errorCode());

        // 逐条闭环差异
        for (SettleReconcileDifference d : new ArrayList<>(differences.rows)) {
            SettleService.DifferenceInput in = new SettleService.DifferenceInput();
            in.handleStatus = "CLOSED";
            in.handleMethod = "SUPPLEMENT";
            in.handleDesc = "已核对并补录";
            service.handleDifference(d.id, in, OP);
        }
        SettleService.CloseResult r = service.closeCycle(firstCycleId(), OP);
        assertTrue(r.confirmed, "差异闭环后关账应成功");
    }

    @Test
    @DisplayName("验收4：加总一致但存在未闭环差异 ⇒ 关账仍被拒（关账门禁 ③ 独立承重）")
    void closeBlockedByOpenDifferenceEvenWhenAggregationConsistent() {
        // CTP 有一笔成交 t1；系统侧多出一笔 T-EXTRA（CTP 无）⇒ 成交差异，且加总各口径一致
        service.ingestCtpData(ctpRequestWithTrade("15", "1000", "t1"), OP);
        SettleService.SettlementInput in = settlement("15");
        in.positions.add(pos(SUB_A, "cu2612", "LONG", "TODAY", "10"));
        in.positions.add(pos(SUB_B, "cu2612", "LONG", "TODAY", "5"));
        in.funds.add(fund(SUB_A, "600", "600", "200"));
        in.funds.add(fund(SUB_B, "400", "400", "0"));
        in.trades.add(trade(SUB_A, "T-EXTRA", "cu2612", "70500"));
        service.runSettlement(in, OP);
        SettleService.AggregateResult agg = service.aggregate(firstCycleId(), OP);
        assertTrue(agg.consistent, "本场景加总各口径一致（差异仅在成交核对）");
        BizException e = assertThrows(BizException.class, () -> service.closeCycle(firstCycleId(), OP));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode(), "未闭环差异不得关账（R-SETTLE-5 条件③）");
    }

    @Test
    @DisplayName("FR-SETTLE-9：调整类差异处理须双人复核（复核人 ≠ 处理人）")
    void adjustRequiresDualReview() {
        seedConsistentScenario("650", "400", "1000");
        service.aggregate(firstCycleId(), OP);
        SettleReconcileDifference d = differences.rows.get(0);
        SettleService.DifferenceInput in = new SettleService.DifferenceInput();
        in.handleStatus = "HANDLING";
        in.handleMethod = "ADJUST";
        in.reviewerId = null;
        BizException e = assertThrows(BizException.class, () -> service.handleDifference(d.id, in, OP));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
    }

    // ================================================================ FR-SETTLE-10/11/15/16

    @Test
    @DisplayName("FR-SETTLE-10：不一致时报告显式标注 INCONSISTENT_BLOCKED，不得输出正常结论")
    void reportMarksInconsistent() {
        seedConsistentScenario("650", "400", "1000");
        service.aggregate(firstCycleId(), OP);
        SettleReport r = service.generateReport(DAY, "DAILY_SETTLE", OP);
        assertEquals("INCONSISTENT_BLOCKED", r.aggResult);
        assertNotNull(r.contentJson);
        assertNotNull(r.generatedTime);
    }

    @Test
    @DisplayName("FR-SETTLE-11：保证金视图汇总 = 明细之和；压力测试输出保证金缺口")
    void marginAndStress() {
        seedConsistentScenario("600", "400", "1000");
        SettleService.MarginView view = service.marginView(DAY, null, "0.1", OP);
        assertEquals(0, view.sumEquity.compareTo(new BigDecimal("1000")));
        assertEquals(0, view.sumMarginOccupied.compareTo(new BigDecimal("200")));
        SettleService.StressInput in = new SettleService.StressInput();
        in.testDate = DAY;
        in.priceShockPct = "0.1";
        SettleStressTest st = service.runStressTest(in, OP);
        assertEquals(0, st.marginGap.compareTo(new BigDecimal("20")), "缺口 = 200 × 10%");
        assertEquals(1, stress.rows.size());
    }

    @Test
    @DisplayName("FR-SETTLE-15：历史结算记录查询仅返回非汇总行且受子账户范围约束")
    void listCyclesScoped() {
        seedConsistentScenario("600", "400", "1000");
        PageResult<SettleCycle> page = service.listCycles(
                PageQuery.of("1", "20", "trade_date,desc", "trade_date", "desc"),
                null, null, null, null, OP);
        assertEquals(2, page.items().size());
        for (SettleCycle c : page.items()) {
            assertEquals("N", c.isAggregate);
        }
    }

    // ================================================================ helpers

    private long firstCycleId() {
        long min = Long.MAX_VALUE;
        for (SettleCycle c : cycles.rows) {
            if (c.subAccountId == SUB_A) {
                min = Math.min(min, c.id);
            }
        }
        return min;
    }

    /** 一致或不一致场景：CTP 持仓 15、权益 realEquity；A/B 系统持仓 10/5；A 台账 600、B 400。 */
    private void seedConsistentScenario(String aEquity, String bEquity, String realEquity) {
        service.ingestCtpData(ctpRequest("15", realEquity), OP);
        SettleService.SettlementInput in = settlement("15");
        in.positions.add(pos(SUB_A, "cu2612", "LONG", "TODAY", "10"));
        in.positions.add(pos(SUB_B, "cu2612", "LONG", "TODAY", "5"));
        in.funds.add(fundWithLedger(SUB_A, aEquity, "600", "200"));
        in.funds.add(fundWithLedger(SUB_B, bEquity, "400", "0"));
        service.runSettlement(in, OP);
    }

    private SettleService.CtpDataRequest ctpRequest(String posQty, String equity) {
        SettleService.CtpDataRequest r = new SettleService.CtpDataRequest();
        r.tradeDate = DAY.toString();
        r.sourceIdentifier = "CTP-20261004";
        r.positions = new ArrayList<>();
        SettleService.CtpPositionRow p = new SettleService.CtpPositionRow();
        p.instrumentId = "cu2612";
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = posQty;
        r.positions.add(p);
        r.funds = new ArrayList<>();
        SettleService.CtpFundRow f = new SettleService.CtpFundRow();
        f.equity = equity;
        f.availableFund = "800";
        f.marginOccupied = "200";
        r.funds.add(f);
        r.trades = new ArrayList<>();
        return r;
    }

    private SettleService.CtpDataRequest ctpRequestWithTrade(String posQty, String equity, String tradeId) {
        SettleService.CtpDataRequest r = ctpRequest(posQty, equity);
        SettleService.CtpTradeRow tr = new SettleService.CtpTradeRow();
        tr.tradeId = tradeId;
        tr.instrumentId = "cu2612";
        tr.direction = "BUY";
        tr.offsetFlag = "OPEN";
        tr.fillPrice = "70000";
        tr.fillVolume = 1;
        r.trades.add(tr);
        return r;
    }

    private static SettleService.SystemTradeRow trade(int sub, String tradeId, String instrument, String price) {
        SettleService.SystemTradeRow t = new SettleService.SystemTradeRow();
        t.subAccountId = sub;
        t.tradeId = tradeId;
        t.instrumentId = instrument;
        t.fillPrice = price;
        t.fillVolume = 1;
        return t;
    }

    private SettleService.CtpDataRequest emptyCtpRequest() {
        SettleService.CtpDataRequest r = new SettleService.CtpDataRequest();
        r.tradeDate = DAY.toString();
        r.sourceIdentifier = "CTP-EMPTY";
        r.positions = new ArrayList<>();
        r.funds = new ArrayList<>();
        r.trades = new ArrayList<>();
        return r;
    }

    private SettleService.SettlementInput settlement(String aPosQty) {
        SettleService.SettlementInput in = new SettleService.SettlementInput();
        in.tradeDate = DAY.toString();
        in.positions = new ArrayList<>();
        in.funds = new ArrayList<>();
        in.trades = new ArrayList<>();
        return in;
    }

    private static SettleService.SystemPositionRow pos(int sub, String instrument, String dir, String type, String qty) {
        SettleService.SystemPositionRow p = new SettleService.SystemPositionRow();
        p.subAccountId = sub;
        p.instrumentId = instrument;
        p.direction = dir;
        p.positionType = type;
        p.positionQty = qty;
        p.instrumentType = "FUTURES";
        p.productId = 1L;
        return p;
    }

    private static SettleService.SystemFundRow fund(int sub, String equity, String ledger, String margin) {
        SettleService.SystemFundRow f = new SettleService.SystemFundRow();
        f.subAccountId = sub;
        f.beginBalance = "0";
        f.equity = equity;
        f.ledgerEquity = ledger;
        f.availableFund = "800";
        f.marginOccupied = margin;
        f.closePnl = "0";
        f.floatPnl = "0";
        f.commission = "0";
        return f;
    }

    private static SettleService.SystemFundRow fundWithLedger(int sub, String equity, String ledger, String margin) {
        return fund(sub, equity, ledger, margin);
    }
}
