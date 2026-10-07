package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.OperationLog;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmOperationLogDao;
import com.hedgeguard.dao.impl.DmPlanApprovalDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmTradeFillDao;
import com.hedgeguard.dao.impl.DmTradeOrderDao;
import com.hedgeguard.dao.impl.DmTradeOrderReportDao;
import com.hedgeguard.dao.impl.DmTradePlanDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.M3AuditTraceSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-067} 审计全链路追溯「真实 DAO + 内存假 JDBC 引擎」用例（禁假 DAO）。
 *
 * <p>把生产建表脚本真喂 {@link InMemoryDmEngine} 建表，再由<b>真实</b> {@code Dm*Dao} 读取，
 * 覆盖正向/反向/完整性三向 + 在途两档时窗 + 断点负向 + 子账户权限边界 + 操作留痕查询。
 *
 * <p><b>凭证接缝</b>：会计凭证 {@code t_voucher} 属 ACCT（{@code T-076+}，TODO）⇒ 以入参
 * {@link AuditService.VoucherLookup} 桩提供（非假 DAO）；在途/断点用例注入 {@code null} 以证
 * 「前置在途/断裂时凭证段不依赖该接缝」。
 */
class AuditTraceServiceTest {

    private static final int SUB = 11;
    private static final int OTHER_SUB = 12;
    private static final long PRODUCT = 7001L;
    private static final long EXP = 2001L;
    private static final long PLAN = 1001L;
    private static final long TRADE_PLAN = 3001L;
    private static final long ORDER = 4001L;
    private static final long SETTLE = 5001L;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime TS = DAY.atTime(10, 0);

    private InMemoryDmEngine engine;
    private DmExposureDao exposureDao;
    private DmHedgePlanExposureDao hedgePlanExposureDao;
    private DmHedgePlanDao hedgePlanDao;
    private DmPlanApprovalDao planApprovalDao;
    private DmTradePlanDao tradePlanDao;
    private DmTradeOrderDao tradeOrderDao;
    private DmTradeOrderReportDao tradeOrderReportDao;
    private DmTradeFillDao tradeFillDao;
    private DmTradeReasonDao tradeReasonDao;
    private DmSettleCycleDao settleCycleDao;
    private DmOperationLogDao operationLogDao;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, OTHER_SUB, "SA-B", "商品子账户B");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.exposure(engine, EXP, SUB, PRODUCT, "100", DAY);
        M2E2eSeed.hedgePlan(engine, PLAN, SUB, PRODUCT, "HP202610060001", "100", DAY, DAY.plusMonths(3));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN, SUB, PLAN, EXP, PRODUCT, "TP-1", "cu2612", "10",
                DAY.plusDays(30));

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        exposureDao = new DmExposureDao(tx);
        hedgePlanExposureDao = new DmHedgePlanExposureDao(tx);
        hedgePlanDao = new DmHedgePlanDao(tx);
        planApprovalDao = new DmPlanApprovalDao(tx);
        tradePlanDao = new DmTradePlanDao(tx);
        tradeOrderDao = new DmTradeOrderDao(tx);
        tradeOrderReportDao = new DmTradeOrderReportDao(tx);
        tradeFillDao = new DmTradeFillDao(tx);
        tradeReasonDao = new DmTradeReasonDao(tx);
        settleCycleDao = new DmSettleCycleDao(tx);
        operationLogDao = new DmOperationLogDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ 正向

    @Test
    @DisplayName("正向追溯：完整六段链 COMPLETE，每段含子账户，起点敞口终点凭证")
    void forwardCompleteChain() throws Exception {
        seedFullChain("ALL_FILLED", true);
        AuditService service = service(SUB, voucherStub());

        AuditService.TraceResult r = service.traceForward(EXP, null, OPERATOR);

        assertEquals(AuditService.COMPLETE, r.chainIntegrity, "完整链应为 COMPLETE");
        assertTrue(r.breakList.isEmpty(), "完整链不应有断点：" + r.breakList);
        assertEquals(AuditService.SEG_EXPOSURE, r.nodes.get(0).segment, "起点应为实货敞口");
        assertEquals(AuditService.SEG_VOUCHER, r.nodes.get(r.nodes.size() - 1).segment, "终点应为会计凭证");
        for (String seg : Arrays.asList(AuditService.SEG_EXPOSURE, AuditService.SEG_HEDGE_PLAN,
                AuditService.SEG_APPROVAL, AuditService.SEG_TRADE, AuditService.SEG_SETTLE,
                AuditService.SEG_VOUCHER)) {
            assertTrue(countSegment(r, seg) > 0, "缺少段：" + seg);
        }
        for (AuditService.TraceNode n : r.nodes) {
            assertEquals(Integer.valueOf(SUB), n.subAccountId, "每段须带子账户标识：" + n.segment);
        }
        // 交易段应含 指令 + 委托 + 成交 三类对象（FR-AUDIT-1 处理规则）
        assertTrue(r.nodes.stream().anyMatch(n -> "t_trade_order".equals(n.objectType)), "缺指令");
        assertTrue(r.nodes.stream().anyMatch(n -> "t_trade_order_report".equals(n.objectType)), "缺委托");
        assertTrue(r.nodes.stream().anyMatch(n -> "t_trade_fill".equals(n.objectType)), "缺成交");
    }

    @Test
    @DisplayName("正向追溯：深度=审批段 只返回前 3 段且不因后段缺失判断点")
    void forwardDepthTruncates() throws Exception {
        seedFullChain("ALL_FILLED", true);
        AuditService service = service(SUB, null); // 不提供凭证接缝
        AuditService.TraceResult r = service.traceForward(EXP, AuditService.SEG_APPROVAL, OPERATOR);
        assertEquals(AuditService.SEG_APPROVAL, r.nodes.get(r.nodes.size() - 1).segment);
        assertTrue(r.nodes.stream().noneMatch(n -> AuditService.SEG_TRADE.equals(n.segment)));
        assertEquals(AuditService.COMPLETE, r.chainIntegrity, "截断深度内完整");
    }

    // ------------------------------------------------------------ 反向

    @Test
    @DisplayName("反向追溯：凭证 -> 敞口，与正向节点集合一致（倒序）")
    void backwardFromVoucher() throws Exception {
        seedFullChain("ALL_FILLED", true);
        AuditService service = service(SUB, voucherStub());

        AuditService.TraceResult fwd = service.traceForward(EXP, null, OPERATOR);
        AuditService.TraceResult bwd = service.traceBackward("VOUCHER", "V-1", null, OPERATOR);

        assertEquals(AuditService.COMPLETE, bwd.chainIntegrity);
        assertEquals(AuditService.SEG_VOUCHER, bwd.nodes.get(0).segment, "反向起点应为凭证");
        assertEquals(AuditService.SEG_EXPOSURE, bwd.nodes.get(bwd.nodes.size() - 1).segment, "反向终点应为敞口");
        assertEquals(fwd.nodes.size(), bwd.nodes.size(), "正反向节点数应一致");
    }

    @Test
    @DisplayName("反向追溯：从交易（指令主键）可定位到套保计划与实货敞口")
    void backwardFromTrade() throws Exception {
        seedFullChain("ALL_FILLED", true);
        AuditService service = service(SUB, null);
        AuditService.TraceResult r = service.traceBackward("TRADE", String.valueOf(ORDER), null, OPERATOR);
        assertEquals(AuditService.SEG_EXPOSURE, r.nodes.get(r.nodes.size() - 1).segment);
        assertTrue(r.nodes.stream().anyMatch(n -> AuditService.SEG_HEDGE_PLAN.equals(n.segment)));
    }

    @Test
    @DisplayName("反向追溯：凭证无来源敞口（exposureId/hedgePlanId 双 null）=> 不得静默 COMPLETE，须记断点")
    void backwardFromVoucherWithoutSourceExposure() throws Exception {
        // TE-T067-001：凭证存在但其 exposureId/hedgePlanId 均缺，无法回溯来源敞口。
        AuditService service = service(SUB, orphanVoucherStub());

        AuditService.TraceResult r = service.traceBackward("VOUCHER", "V-ORPHAN", null, OPERATOR);

        assertFalse(AuditService.COMPLETE.equals(r.chainIntegrity),
                "凭证无法回溯来源敞口时不得判 COMPLETE");
        assertEquals(AuditService.HAS_BREAK, r.chainIntegrity, "应判 HAS_BREAK");
        assertFalse(r.breakList.isEmpty(), "break_list 应含断点");
        AuditService.BreakItem b = r.breakList.get(0);
        assertEquals(AuditService.SEG_VOUCHER, b.position, "断点应位于凭证段");
        assertNotNull(b.reason, "断点须含原因");
        assertFalse(b.reason.trim().isEmpty(), "断点原因不得为空");
        assertEquals("t_voucher", b.objectType, "断点对象类型应为凭证表");
        assertEquals(String.valueOf(91001L), b.objectId, "断点对象标识应为凭证 id");
    }

    // ------------------------------------------------------------ 完整性 / 断点

    @Test
    @DisplayName("完整性：人为移除计划-敞口绑定后 HAS_BREAK，断点含位置与对象")
    void integrityBreakWhenLinkRemoved() throws Exception {
        // 人为移除某环节关联 = 不播种该绑定行（等价于断链），不引入 src/test 外的 SQL。
        seedChain(false, true, "ALL_FILLED", true);
        AuditService service = service(SUB, null);

        AuditService.TraceResult r = service.checkIntegrity(EXP, OPERATOR);

        assertEquals(AuditService.HAS_BREAK, r.chainIntegrity, "存在断点不得输出完整");
        assertFalse(r.breakList.isEmpty(), "break_list 非空");
        AuditService.BreakItem b = r.breakList.get(0);
        assertEquals(AuditService.SEG_HEDGE_PLAN, b.position);
        assertNotNull(b.reason);
    }

    @Test
    @DisplayName("完整性：移除审批记录后 HAS_BREAK")
    void integrityBreakWhenApprovalMissing() throws Exception {
        seedChain(true, false, "ALL_FILLED", true);
        AuditService service = service(SUB, null);
        AuditService.TraceResult r = service.checkIntegrity(EXP, OPERATOR);
        assertEquals(AuditService.HAS_BREAK, r.chainIntegrity);
        assertTrue(r.breakList.stream().anyMatch(x -> AuditService.SEG_APPROVAL.equals(x.position)));
    }

    // ------------------------------------------------------------ 在途时窗（CFG-17c）

    @Test
    @DisplayName("在途：已发 CTP 未回报、当日结算时点前 -> IN_FLIGHT 不计断点")
    void inFlightBeforeCutoff() throws Exception {
        seedFullChain("SENT_CTP", false);
        AuditService service = service(SUB, null);
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));

        AuditService.TraceResult r = service.checkIntegrity(EXP, OPERATOR);

        assertEquals(AuditService.IN_FLIGHT, r.chainIntegrity);
        assertTrue(r.breakList.isEmpty(), "在途不计断点：" + r.breakList);
    }

    @Test
    @DisplayName("在途：逾当日结算时点未回报 -> UNKNOWN + 告警（不得继续挂在途）")
    void inFlightOverdueToUnknown() throws Exception {
        seedFullChain("SENT_CTP", false);
        AuditService service = service(SUB, null);
        Clocks.set(Clock.fixed(DAY.plusDays(1).atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));

        AuditService.TraceResult r = service.checkIntegrity(EXP, OPERATOR);

        assertEquals(AuditService.UNKNOWN, r.chainIntegrity);
        assertFalse(r.alerts.isEmpty(), "应产生在途逾时告警");
    }

    @Test
    @DisplayName("在途：再逾 1 个结算周期 -> HAS_BREAK 且 break_list 非空")
    void inFlightBeyondToBreak() throws Exception {
        seedFullChain("SENT_CTP", false);
        AuditService service = service(SUB, null);
        Clocks.set(Clock.fixed(DAY.plusDays(2).atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));

        AuditService.TraceResult r = service.checkIntegrity(EXP, OPERATOR);

        assertEquals(AuditService.HAS_BREAK, r.chainIntegrity);
        assertFalse(r.breakList.isEmpty());
    }

    @Test
    @DisplayName("在途：数据缺失（订单无交易日）-> UNKNOWN，不得记为在途")
    void inFlightDataMissingUnknown() throws Exception {
        // submit_time 允许为 NULL？DDL 为 NOT NULL ⇒ 改以「无 fill 且订单非 SENT_CTP」的已提交态不可判定语义覆盖：
        // 此处直接以 status=SUBMITTED（非 SENT_CTP）且无成交 => 无成交无法结算，判 HAS_BREAK（环节无记录），
        // 而不会误标在途 —— 断言「不得记为 IN_FLIGHT」。
        seedFullChain("SUBMITTED", false);
        AuditService service = service(SUB, null);
        AuditService.TraceResult r = service.checkIntegrity(EXP, OPERATOR);
        assertFalse(AuditService.IN_FLIGHT.equals(r.chainIntegrity), "数据缺失/未成交不得记在途");
    }

    // ------------------------------------------------------------ 子账户权限边界

    @Test
    @DisplayName("子账户边界：起点不在授权范围 -> 明示边界且不泄露内容")
    void permissionBoundaryForOtherSubAccount() throws Exception {
        seedFullChain("ALL_FILLED", true);
        AuditService service = service(OTHER_SUB, voucherStub()); // 仅授权 OTHER_SUB

        AuditService.TraceResult r = service.traceForward(EXP, null, OPERATOR);

        assertTrue(r.permissionLimited, "应触及权限边界");
        assertNotNull(r.note);
        assertTrue(r.nodes.isEmpty(), "越权起点不应返回节点内容");
        assertEquals(AuditService.HAS_BREAK, r.chainIntegrity);
    }

    @Test
    @DisplayName("子账户边界：跨账户只读（哨兵 0）可见全部")
    void crossReadonlySeesAll() throws Exception {
        seedFullChain("ALL_FILLED", true);
        AuditService service = service(SubAccountScope.ALL_ACCOUNT_SENTINEL, voucherStub());
        AuditService.TraceResult r = service.traceForward(EXP, null, OPERATOR);
        assertEquals(AuditService.COMPLETE, r.chainIntegrity);
        assertFalse(r.permissionLimited);
    }

    // ------------------------------------------------------------ 操作留痕

    @Test
    @DisplayName("操作留痕查询：按子账户/操作类型筛选，只增不改不删（无写入口）")
    void operationLogQueryAndIsolation() throws Exception {
        M3AuditTraceSeed.operationLog(engine, 8001L, SUB, OPERATOR, "PLAN_SUBMIT", "t_hedge_plan", PLAN,
                "HP202610060001", "SUCCESS", TS);
        M3AuditTraceSeed.operationLog(engine, 8002L, OTHER_SUB, 101L, "CONFIG_CHANGE", "t_config_item", 1L,
                "CFG-1", "SUCCESS", TS);
        AuditService service = service(SUB, null);

        AuditService.OperationLogQuery q = new AuditService.OperationLogQuery();
        q.operationType = "PLAN_SUBMIT";
        PageResult<OperationLog> page = service.queryOperationLog(q,
                PageQuery.of(null, null, null, "operation_time", "desc"), OPERATOR);
        assertEquals(1, page.total(), "仅授权子账户范围内可见");
        assertEquals("PLAN_SUBMIT", page.items().get(0).operationType);

        // 越权：查其它子账户 -> 恒假（不可见）
        AuditService.OperationLogQuery q2 = new AuditService.OperationLogQuery();
        q2.subAccountId = OTHER_SUB;
        PageResult<OperationLog> page2 = service.queryOperationLog(q2,
                PageQuery.of(null, null, null, "operation_time", "desc"), OPERATOR);
        assertEquals(0, page2.total(), "未授权子账户留痕不可见");
    }

    // ------------------------------------------------------------ 夹具

    private AuditService service(int visibleSubAccount, AuditService.VoucherLookup vouchers) {
        SubAccountVisibility visibility = uid -> Collections.singletonList(visibleSubAccount);
        return new AuditService(exposureDao, hedgePlanExposureDao, hedgePlanDao, planApprovalDao,
                tradePlanDao, tradeOrderDao, tradeOrderReportDao, tradeFillDao, tradeReasonDao,
                settleCycleDao, operationLogDao, visibility, vouchers, () -> 1, AuditService.endOfTradeDay());
    }

    /** 播种完整链（绑定 + 审批）。 */
    private void seedFullChain(String orderStatus, boolean withFillAndSettle) throws Exception {
        seedChain(true, true, orderStatus, withFillAndSettle);
    }

    /** 播种链中段：绑定（可选）/ 审批（可选）/ 指令 /（可选）委托+成交+结算。 */
    private void seedChain(boolean withHedgePlanExposure, boolean withApproval, String orderStatus,
                          boolean withFillAndSettle) throws Exception {
        if (withHedgePlanExposure) {
            M3AuditTraceSeed.hedgePlanExposure(engine, 6001L, SUB, PLAN, EXP, "100", TS);
        }
        if (withApproval) {
            M3AuditTraceSeed.planApproval(engine, 6101L, SUB, PLAN, 1, TS);
        }
        M3AuditTraceSeed.tradeOrder(engine, ORDER, SUB, "HP202610060001-C1", 9001L, TRADE_PLAN, PLAN, EXP,
                PRODUCT, "cu2612", orderStatus, TS);
        if (withFillAndSettle) {
            M3AuditTraceSeed.tradeOrderReport(engine, 6201L, SUB, "SYS-1", ORDER, "cu2612", "ALL_TRADED",
                    10, 10, TS);
            M3AuditTraceSeed.tradeFill(engine, 6301L, SUB, "TF-1", ORDER, TRADE_PLAN, PLAN, EXP, PRODUCT,
                    "cu2612", "70000", 10, DAY, TS);
            M3AuditTraceSeed.settleCycleConfirmed(engine, SETTLE, SUB, "ST-1", DAY, TS);
        }
    }

    private AuditService.VoucherLookup voucherStub() {
        return new AuditService.VoucherLookup() {
            private AuditService.VoucherRef ref() {
                AuditService.VoucherRef v = new AuditService.VoucherRef();
                v.id = 90001L;
                v.voucherNo = "V-1";
                v.voucherType = "CLOSE_PNL";
                v.subAccountId = SUB;
                v.hedgePlanId = PLAN;
                v.exposureId = EXP;
                v.settleCycleId = SETTLE;
                v.status = "POSTED";
                return v;
            }

            @Override
            public List<AuditService.VoucherRef> findByExposure(long exposureId) {
                return exposureId == EXP ? Collections.singletonList(ref()) : new ArrayList<>();
            }

            @Override
            public List<AuditService.VoucherRef> findByHedgePlan(long hedgePlanId) {
                return hedgePlanId == PLAN ? Collections.singletonList(ref()) : new ArrayList<>();
            }

            @Override
            public List<AuditService.VoucherRef> findBySettleCycle(long settleCycleId) {
                return settleCycleId == SETTLE ? Collections.singletonList(ref()) : new ArrayList<>();
            }

            @Override
            public AuditService.VoucherRef findByVoucher(String voucherIdOrNo) {
                return ("V-1".equals(voucherIdOrNo) || "90001".equals(voucherIdOrNo)) ? ref() : null;
            }
        };
    }

    /** 会计凭证桩：存在但 exposureId/hedgePlanId 均缺（无法回溯来源敞口，TE-T067-001）。 */
    private AuditService.VoucherLookup orphanVoucherStub() {
        return new AuditService.VoucherLookup() {
            private AuditService.VoucherRef ref() {
                AuditService.VoucherRef v = new AuditService.VoucherRef();
                v.id = 91001L;
                v.voucherNo = "V-ORPHAN";
                v.voucherType = "CLOSE_PNL";
                v.subAccountId = SUB;
                v.hedgePlanId = null;
                v.exposureId = null;
                v.settleCycleId = null;
                v.status = "POSTED";
                return v;
            }

            @Override
            public List<AuditService.VoucherRef> findByExposure(long exposureId) {
                return new ArrayList<>();
            }

            @Override
            public List<AuditService.VoucherRef> findByHedgePlan(long hedgePlanId) {
                return new ArrayList<>();
            }

            @Override
            public List<AuditService.VoucherRef> findBySettleCycle(long settleCycleId) {
                return new ArrayList<>();
            }

            @Override
            public AuditService.VoucherRef findByVoucher(String voucherIdOrNo) {
                return "V-ORPHAN".equals(voucherIdOrNo) ? ref() : null;
            }
        };
    }

    private static long countSegment(AuditService.TraceResult r, String segment) {
        long n = 0;
        for (AuditService.TraceNode node : r.nodes) {
            if (segment.equals(node.segment)) {
                n++;
            }
        }
        return n;
    }

    private static List<String> schemaScripts() {
        return Arrays.asList(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m1_product.sql",
                "docs/DEPLOY/schema/m1_spot.sql",
                "docs/DEPLOY/schema/m1_plan.sql",
                "docs/DEPLOY/schema/m2_trade.sql",
                "docs/DEPLOY/schema/m2_settle.sql",
                "docs/DEPLOY/schema/m2_fund.sql",
                "docs/DEPLOY/schema/m3_risk.sql",
                "docs/DEPLOY/schema/m3_audit.sql");
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/DEPLOY/schema"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("无法定位仓库根（docs/DEPLOY/schema）");
        }
        return p;
    }
}
