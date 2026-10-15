package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.impl.DmAccountSubjectDao;
import com.hedgeguard.dao.impl.DmAccountingPeriodDao;
import com.hedgeguard.dao.impl.DmEffectivenessAssessDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgeDocDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgeRelationDao;
import com.hedgeguard.dao.impl.DmPeriodCloseLogDao;
import com.hedgeguard.dao.impl.DmRebalanceRecordDao;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-079} VoucherLookup 接缝装配回归（T-076 三遗留①承接）：AuditService 三消费点
 * （反向起点 / 正向终点 / collectVouchers 三路并集）在<b>生产装配形态</b>（注入
 * {@code AcctService.voucherLookup()} 适配器）下的凭证段行为——装配前该段判 UNKNOWN，
 * 装配后转真数据（回归判据：unknownSegments 不含 VOUCHER、节点含 t_voucher 行）。
 */
class AcctVoucherLookupWiringTest {

    private static final String M4 = "docs/DEPLOY/schema/m4_acct.sql";
    private static final String M1 = "docs/DEPLOY/schema/m1_config_auth.sql";
    private static final String M1P = "docs/DEPLOY/schema/m1_plan.sql";
    private static final String M2S = "docs/DEPLOY/schema/m2_settle.sql";
    private static final String M2T = "docs/DEPLOY/schema/m2_trade.sql";
    private static final String M3R = "docs/DEPLOY/schema/m3_risk.sql";
    private static final String M3A = "docs/DEPLOY/schema/m3_audit.sql";

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TransactionAwareInMemory aware;
    private AcctService acctService;
    private List<Integer> visible = List.of(1);

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(LocalDate.of(2026, 10, 13)
                .atStartOfDay(com.hedgeguard.common.Clocks.ZONE).toInstant(),
                com.hedgeguard.common.Clocks.ZONE));
        engine = new InMemoryDmEngine();
        engine.executeScript(Files.readString(repoRoot().resolve(M1), StandardCharsets.UTF_8));
        engine.executeScript(Files.readString(repoRoot().resolve(M1P), StandardCharsets.UTF_8));
        engine.executeScript(Files.readString(repoRoot().resolve(M2S), StandardCharsets.UTF_8));
        // 正向链遍历需交易/审批/风险/审计段表（auditScenarioVoucherSegmentHasData 用）
        engine.executeScript(Files.readString(repoRoot().resolve(M2T), StandardCharsets.UTF_8));
        engine.executeScript(Files.readString(repoRoot().resolve(M3R), StandardCharsets.UTF_8));
        engine.executeScript(Files.readString(repoRoot().resolve(M3A), StandardCharsets.UTF_8));
        engine.executeScript(Files.readString(repoRoot().resolve(M4), StandardCharsets.UTF_8));
        T077AcctSeed.createParentTables(engine);
        T077AcctSeed.seedChains(engine);
        T079AcctVoucherSeed.accountSubject(engine, 1L, "6001", "x", "CREDIT",
                "CLOSE_PNL", "Y", "ENABLED");
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware, new PoolConfig(0, 4, 500, 2));
        TxTemplate tx = new TxTemplate(pool);
        acctService = new AcctService(tx, (uid) -> visible, (key) -> null,
                new DmHedgePlanDao(tx), new DmHedgeDocDao(tx), new DmHedgeRelationDao(tx),
                new DmEffectivenessAssessDao(tx), new DmRebalanceRecordDao(tx),
                new DmExposureDao(tx),
                new DmVoucherDao(tx), new DmVoucherLineDao(tx), new DmVoucherReviewDao(tx),
                new DmAccountingPeriodDao(tx), new DmPeriodCloseLogDao(tx),
                new DmSettleCycleDao(tx), new DmAccountSubjectDao(tx));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
        assertEquals(0, aware.pendingCount(), "事务未残留");
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("装配回归：正向链终点凭证段由真数据承载（collectVouchers 三路并集）")
    void voucherSegmentServedByLookup() {
        AcctService.VoucherDraftRequest r = new AcctService.VoucherDraftRequest();
        r.subAccountId = 1;
        r.periodKey = "2026-10";
        r.voucherType = "CLOSE_PNL";
        r.summary = "平仓盈亏";
        r.amount = new BigDecimal("100.00");
        r.exposureId = 21L;
        acctService.createVoucherDraft(r, 7L);

        AuditService.VoucherLookup lookup = acctService.voucherLookup();
        assertNotNull(lookup);
        // collectVouchers 三路并集之「按敞口」路：装配后返回真数据（装配前恒空 ⇒ UNKNOWN）
        assertEquals(1, lookup.findByExposure(21L).size());
        assertEquals("AV20261000001", lookup.findByExposure(21L).get(0).voucherNo);
        assertEquals("CLOSE_PNL", lookup.findByExposure(21L).get(0).voucherType);
        // 按凭证号定位（traceBackward 反向起点同路）
        assertNotNull(lookup.findByVoucher("AV20261000001"));
    }

    @Test
    @DisplayName("装配回归：反向起点 VOUCHER 定位为真数据节点（非 UNKNOWN/断点）")
    void backwardStartFromVoucher() {
        AcctService.VoucherDraftRequest r = new AcctService.VoucherDraftRequest();
        r.subAccountId = 1;
        r.periodKey = "2026-10";
        r.voucherType = "CLOSE_PNL";
        r.summary = "平仓盈亏";
        r.amount = new BigDecimal("100.00");
        r.exposureId = 21L;
        acctService.createVoucherDraft(r, 7L);

        AuditService audit = new AuditService(new DmExposureDao(txOf()),
                new com.hedgeguard.dao.impl.DmHedgePlanExposureDao(txOf()),
                new DmHedgePlanDao(txOf()), null, null, null, null, null, null,
                null, null, (uid) -> visible, acctService.voucherLookup(),
                () -> 1, AuditService.endOfTradeDay());
        AuditService.TraceResult bwd = audit.traceBackward("VOUCHER", "AV20261000001", null, 7L);
        assertTrue(bwd.nodes.stream().anyMatch(n ->
                        AuditService.SEG_VOUCHER.equals(n.segment)
                                && "AV20261000001".equals(n.objectNo)),
                "反向起点应为凭证节点（装配前 UNKNOWN 无节点）：" + bwd.breakList);
    }

    @Test
    @DisplayName("TE-T079B-001 回归：审计查证场景下凭证段有数据（uid 区分形态——真实可见集按 uid 解析，非 uid 盲 lambda 假象）")
    void auditScenarioVoucherSegmentHasData() throws Exception {
        T079AcctVoucherSeed.settleCycle(engine, 501L, 1, "ST20261001",
                LocalDate.of(2026, 10, 1), "CONFIRMED");
        AcctService.VoucherDraftRequest r = new AcctService.VoucherDraftRequest();
        r.subAccountId = 1;
        r.periodKey = "2026-10";
        r.voucherType = "CLOSE_PNL";
        r.summary = "平仓盈亏";
        r.amount = new BigDecimal("100.00");
        r.exposureId = 21L;
        r.settleCycleId = 501L;
        acctService.createVoucherDraft(r, 7L);

        // 敞口 21 ↔ 计划 11 绑定 + 审批节点 + 指令（正向链六段遍历所需；M3AuditTraceSeed 同法，
        // 无绑定/审批/指令则正向链在中段断裂，凭证段不可达）
        com.hedgeguard.dao.testsupport.M3AuditTraceSeed.hedgePlanExposure(engine, 6201L, 1, 11L, 21L,
                "100", java.time.LocalDateTime.of(2026, 10, 1, 9, 0));
        com.hedgeguard.dao.testsupport.M3AuditTraceSeed.planApproval(engine, 6202L, 1, 11L, 1,
                java.time.LocalDateTime.of(2026, 10, 1, 9, 30));
        // 交易计划行（t_trade_order.trade_plan_id 有 FK）
        com.hedgeguard.dao.testsupport.M2E2eSeed.tradePlan(engine, 1L, 1, 11L, 21L, 1L,
                "TP202610010001", "cu2612", "10", LocalDate.of(2026, 10, 31));
        com.hedgeguard.dao.testsupport.M3AuditTraceSeed.tradeOrder(engine, 6203L, 1,
                "HP202609010001-C1", 9001L, 1L, 11L, 21L, 1L, "cu2612", "ALL_FILLED",
                java.time.LocalDateTime.of(2026, 10, 1, 10, 0));
        // 成交行（正向链凭证段前置：结算段须有已确认结算单才可达凭证判定——
        // 结算单复用上方 501L（CONFIRMED，同 uk_settle_cycle_date_sub 集合））
        com.hedgeguard.dao.testsupport.M3AuditTraceSeed.tradeFill(engine, 6204L, 1, "TF-1", 6203L,
                1L, 11L, 21L, 1L, "cu2612", "70000", 10,
                LocalDate.of(2026, 10, 1), java.time.LocalDateTime.of(2026, 10, 1, 10, 5));

        // 可见集按 uid 区分：operatorId=7 仅见子账户 1、operatorId=8 恒空（无任何可见数据）
        // ——若实现仍按哨兵 uid（-1）取可见集（uid 盲），本断言不区分此形态；
        // 故再断言「空集 uid 下起点节点为断点、非真数据节点」（TE-T079-001 越权探测负例）。
        AuditService audit = new AuditService(new DmExposureDao(txOf()),
                new com.hedgeguard.dao.impl.DmHedgePlanExposureDao(txOf()),
                new DmHedgePlanDao(txOf()),
                new com.hedgeguard.dao.impl.DmPlanApprovalDao(txOf()),
                new com.hedgeguard.dao.impl.DmTradePlanDao(txOf()),
                new com.hedgeguard.dao.impl.DmTradeOrderDao(txOf()),
                new com.hedgeguard.dao.impl.DmTradeOrderReportDao(txOf()),
                new com.hedgeguard.dao.impl.DmTradeFillDao(txOf()),
                new com.hedgeguard.dao.impl.DmTradeReasonDao(txOf()),
                new DmSettleCycleDao(txOf()),
                new com.hedgeguard.dao.impl.DmOperationLogDao(txOf()),
                (uid) -> uid == 7L ? List.of(1) : List.of(),
                acctService.voucherLookup(), () -> 1, AuditService.endOfTradeDay());

        // 正例：有授权 uid 的审计查证——正向终点与反向起点凭证段均转真数据（非 UNKNOWN/空）
        AuditService.TraceResult fwd = audit.traceForward(21L, null, 7L);
        assertTrue(fwd.nodes.stream().anyMatch(n ->
                        AuditService.SEG_VOUCHER.equals(n.segment)
                                && "AV20261000001".equals(n.objectNo)),
                "审计查证（有授权 uid）正向链凭证段须有真数据节点（装配语义恢复）："
                        + fwd.breakList);
        AuditService.TraceResult bwd = audit.traceBackward("VOUCHER", "AV20261000001", null, 7L);
        assertTrue(bwd.nodes.stream().anyMatch(n ->
                        AuditService.SEG_VOUCHER.equals(n.segment)
                                && "AV20261000001".equals(n.objectNo)),
                "反向起点为真数据节点：" + bwd.breakList);
        assertFalse(bwd.breakList.stream().anyMatch(b ->
                        AuditService.SEG_VOUCHER.equals(b.position)
                                && "UNKNOWN".equals(b.kind)),
                "凭证段不得判 UNKNOWN：" + bwd.breakList);

        // 负例（TE-T079-001 承接）：无授权 uid 按凭证号探测 ⇒ 视同不存在（断点），不泄露 VoucherRef
        AuditService.TraceResult denied = audit.traceBackward("VOUCHER", "AV20261000001", null, 8L);
        assertFalse(denied.nodes.stream().anyMatch(n ->
                        AuditService.SEG_VOUCHER.equals(n.segment)
                                && "AV20261000001".equals(n.objectNo)),
                "越权 uid 不得获得凭证节点（存在性/归属不泄露）");
        assertTrue(denied.breakList.stream().anyMatch(b ->
                        "凭证不存在或不可见".equals(b.reason)),
                "越权起点记断点（不可见 = 视同不存在）：" + denied.breakList);
    }

    private TxTemplate txCache;

    private TxTemplate txOf() {
        if (txCache == null) {
            txCache = new TxTemplate(pool);
        }
        return txCache;
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根");
    }
}
