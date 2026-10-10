package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuditLogDao;
import com.hedgeguard.dao.EvidenceExportDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradeReasonDao;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.entity.EvidenceExport;
import com.hedgeguard.dao.entity.TradeReason;
import com.hedgeguard.dao.impl.DmAuditLogChecksumDao;
import com.hedgeguard.dao.impl.DmAuditLogDao;
import com.hedgeguard.dao.impl.DmEvidenceExportDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.impl.*;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.M3AuditTraceSeed;
import com.hedgeguard.dao.testsupport.T069AuditTamperSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.EvidenceExportService.DualPnl;
import com.hedgeguard.service.EvidenceExportService.EvidenceExportRequest;
import com.hedgeguard.service.EvidenceExportService.EvidenceExportResult;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-069} 证据链导出「真实 DAO + 内存假 JDBC 引擎」用例（禁假 DAO）。
 *
 * <p>覆盖：六段全链 + 子账户 + 双侧盈亏 + 交易理由（{@code FR-AUDIT-8} 验收 1）、断点显式标注
 * （验收 2）、审计日志新增导出记录（验收 3）、导出不改变业务数据（验收 4）、防篡改未过阻断导出
 * （{@code FR-AUDIT-10} 验收 2）、审计日志写失败中断导出（{@code R-AUDIT-4}）。
 */
class EvidenceExportServiceTest {

    private static final int SUB = 11;
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
    private DmAuditLogDao logDao;
    private DmEvidenceExportDao exportDao;
    private DmTradeReasonDao reasonDao;
    private DmAuditLogChecksumDao checksumDao;
    private DmTradeOrderDao tradeOrderDao;
    private DmTradeFillDao tradeFillDao;
    private TxTemplate tx;

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
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.exposure(engine, EXP, SUB, PRODUCT, "100", DAY);
        M2E2eSeed.hedgePlan(engine, PLAN, SUB, PRODUCT, "HP202610060001", "100", DAY, DAY.plusMonths(3));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN, SUB, PLAN, EXP, PRODUCT, "TP-1", "cu2612", "10",
                DAY.plusDays(30));
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        logDao = new DmAuditLogDao(tx);
        exportDao = new DmEvidenceExportDao(tx);
        reasonDao = new DmTradeReasonDao(tx);
        checksumDao = new DmAuditLogChecksumDao(tx);
        tradeOrderDao = new DmTradeOrderDao(tx);
        tradeFillDao = new DmTradeFillDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("导出完整链：六段 + 子账户 + 双侧盈亏 + 交易理由，且落审计日志与导出记录")
    void exportComplete() throws Exception {
        seedChain(true, true);
        seedReason();
        EvidenceExportService service = service(new DmAuditLogDao(tx));

        EvidenceExportResult r = service.export(req("EXPOSURE", String.valueOf(EXP)), OPERATOR);

        assertEquals(AuditService.COMPLETE, r.chainIntegrity, "完整链应为 COMPLETE：" + r.breakList);
        for (String seg : Arrays.asList("[EXPOSURE]", "[HEDGE_PLAN]", "[APPROVAL]", "[TRADE]",
                "[SETTLE]", "[VOUCHER]")) {
            assertTrue(r.content.contains(seg), "导出内容缺段：" + seg);
        }
        assertTrue(r.content.contains("子账户=" + SUB), "缺子账户维度");
        assertTrue(r.content.contains("双侧盈亏") && r.content.contains("实货盈亏=800")
                && r.content.contains("衍生品盈亏=1200"), "缺双侧盈亏：" + r.content);
        assertTrue(r.content.contains("交易理由") && r.content.contains("PLAN_EXECUTION"),
                "缺交易理由");
        assertTrue(r.content.contains(EvidenceExportService.MARK_COMPLETE));

        EvidenceExport ex = exportDao.findByExportNo(r.exportNo);
        assertNotNull(ex, "导出记录须落库");
        assertEquals("Y", ex.dualPnlIncluded);
        assertEquals("Y", ex.reasonIncluded);
        assertNotNull(ex.auditLogId);
        AuditLog log = logDao.findById(ex.auditLogId);
        assertEquals(AuditLogService.ACTION_EXPORT, log.actionType);
        assertEquals(r.fileId, log.exportFileId, "审计日志须含导出文件标识");
        assertEquals(r.nodeCount, log.resultCount, "审计日志须含结果条数");
    }

    @Test
    @DisplayName("存在断点：导出带「链条不完整」标注与断点清单（FR-AUDIT-8 验收 2）")
    void exportWithBreak() throws Exception {
        seedChain(false, true);
        EvidenceExportService service = service(new DmAuditLogDao(tx));

        EvidenceExportResult r = service.export(req("EXPOSURE", String.valueOf(EXP)), OPERATOR);

        assertEquals(AuditService.HAS_BREAK, r.chainIntegrity);
        assertTrue(r.content.contains(EvidenceExportService.MARK_INCOMPLETE), "应显式标注链条不完整");
        assertTrue(!r.breakList.isEmpty(), "断点清单非空");
        assertTrue(r.content.contains("断点清单"));
        assertEquals("HAS_BREAK", exportDao.findByExportNo(r.exportNo).chainIntegrity);
    }

    @Test
    @DisplayName("导出不改变业务数据（FR-AUDIT-8 验收 4）")
    void exportDoesNotChangeBusinessData() throws Exception {
        seedChain(true, true);
        long orderRows = tradeOrderDao.count(null, new ArrayList<>());
        long fillRows = tradeFillDao.count(null, new ArrayList<>());
        EvidenceExportService service = service(new DmAuditLogDao(tx));
        service.export(req("EXPOSURE", String.valueOf(EXP)), OPERATOR);
        assertEquals(orderRows, tradeOrderDao.count(null, new ArrayList<>()));
        assertEquals(fillRows, tradeFillDao.count(null, new ArrayList<>()));
        assertNotNull(tradeOrderDao.findById(ORDER), "业务行仍存在");
    }

    @Test
    @DisplayName("审计日志被篡改 ⇒ 校验未过，阻断导出（10018）")
    void tamperedChainBlocksExport() throws Exception {
        seedChain(true, true);
        EvidenceExportService service = service(new DmAuditLogDao(tx));
        service.export(req("EXPOSURE", String.valueOf(EXP)), OPERATOR); // 先产生若干日志
        T069AuditTamperSeed.tamperAuditLogResultCount(engine, 1L, 999);

        BizException ex = assertThrows(BizException.class,
                () -> service.export(req("EXPOSURE", String.valueOf(EXP)), OPERATOR));
        assertEquals(ErrorCode.AUDIT_CHAIN_TAMPERED, ex.errorCode());
    }

    @Test
    @DisplayName("审计日志写失败 ⇒ 中断本次导出（R-AUDIT-4 / 10017）")
    void logWriteFailureInterruptsExport() throws Exception {
        seedChain(true, true);
        AuditLogService failing = new AuditLogService(new ExportFailingLogDao(), checksumDao,
                crossReadonly(), AuditLogService.TamperAlarm.NOOP, tx);
        EvidenceExportService service = new EvidenceExportService(auditService(), failing,
                exportDao, reasonDao, dualPnlStub(), crossReadonly(), tx);

        BizException ex = assertThrows(BizException.class,
                () -> service.export(req("EXPOSURE", String.valueOf(EXP)), OPERATOR));
        assertEquals(ErrorCode.AUDIT_LOG_WRITE_FAILED, ex.errorCode());
        assertTrue(exportDao.count(null, new ArrayList<>()) == 0, "中断的导出不得落导出记录");
    }

    // ------------------------------------------------------------ 导出记录列表可见性（O-1 修复）

    @Test
    @DisplayName("O-1 ①：无权限者（可见集 null/空）查列表 ⇒ 恒假空集，不自行放行（fail-closed）")
    void listWithoutVisibilityYieldsEmpty() throws Exception {
        seedExport("EV-0001", "[" + SUB + "]", 200L);
        seedExport("EV-0002", "ALL_VISIBLE", 300L);
        SubAccountVisibility noAccess = uid -> new ArrayList<>();
        EvidenceExportService service = service(noAccess);

        // 可见集为空集合 ⇒ 空集
        PageResult<EvidenceExport> page = service.queryExports(pageQuery(), OPERATOR, null);
        assertEquals(0, page.total(), "空可见集 ⇒ 空集");
        assertTrue(page.items().isEmpty(), "空可见集 ⇒ 无行");
        // 可见集不可判定（null）⇒ 同样空集
        SubAccountVisibility nullAuth = uid -> null;
        EvidenceExportService nullService = service(nullAuth);
        PageResult<EvidenceExport> page2 = nullService.queryExports(pageQuery(), OPERATOR, null);
        assertEquals(0, page2.total(), "可见集 null ⇒ 空集（不自行放行）");
    }

    @Test
    @DisplayName("O-1 ②：全权限者（哨兵 0）查列表 ⇒ 全量可见；带子账户筛选走调用方传入值")
    void listWithCrossVisibilitySeesAll() throws Exception {
        seedExport("EV-0011", "[" + SUB + "]", 200L);
        seedExport("EV-0012", "[12]", 300L);
        seedExport("EV-0013", "ALL_VISIBLE", 300L);
        EvidenceExportService service = service(crossReadonly());

        PageResult<EvidenceExport> page = service.queryExports(pageQuery(), OPERATOR, null);
        assertEquals(3, page.total(), "全权限 ⇒ 全量可见");
        // 带筛选：声明包含该子账户的批次保留（含 ALL_VISIBLE 的批次不因范围声明收窄，全权限者本就全见）
        PageResult<EvidenceExport> filtered =
                service.queryExports(pageQuery(), OPERATOR, SUB);
        assertEquals(2, filtered.total(), "全权限筛选 ⇒ 声明包含 11 的批次 + ALL_VISIBLE 批次");
    }

    @Test
    @DisplayName("O-1 ③：非全权限者——越权 subAccountId 筛选 ⇒ 空集；声明含越权子账户的批次整批不可见；仅本人 ALL_VISIBLE 可见")
    void listScopedVisibilityEnforcesBoundary() throws Exception {
        long own = 500L;
        seedExport("EV-0021", "[" + SUB + "]", 200L);          // 声明 ⊆ 可见集 ⇒ 可见
        seedExport("EV-0022", "[12]", 200L);                    // 声明含越权子账户 ⇒ 整批不可见
        seedExport("EV-0023", "[" + SUB + ",12]", 200L);        // 部分越权 ⇒ 整批不可见
        seedExport("EV-0024", "ALL_VISIBLE", own);              // 本人 ALL_VISIBLE ⇒ 可见
        seedExport("EV-0025", "ALL_VISIBLE", 300L);             // 他人 ALL_VISIBLE ⇒ 不可见
        SubAccountVisibility scoped = uid -> new ArrayList<>(Collections.singletonList(SUB));
        EvidenceExportService service = service(scoped);

        // 不带筛选：仅「声明 ⊆ 可见集」的批次 + 本人 ALL_VISIBLE 批次
        PageResult<EvidenceExport> page = service.queryExports(pageQuery(), own, null);
        assertEquals(2, page.total(), "仅可见 EV-0021 与本人 EV-0024");
        for (EvidenceExport ex : page.items()) {
            assertTrue(ex.exportNo.equals("EV-0021") || ex.exportNo.equals("EV-0024"),
                    "越权批次不得出现在结果中：" + ex.exportNo);
        }

        // 越权筛选：请求不可见子账户 99 ⇒ 空集（不放行、不报错）
        PageResult<EvidenceExport> overflow =
                service.queryExports(pageQuery(), own, 99);
        assertEquals(0, overflow.total(), "越权 subAccountId 筛选 ⇒ 空集");
        assertTrue(overflow.items().isEmpty(), "越权 subAccountId 筛选 ⇒ 无行");

        // 合法筛选：请求可见子账户 11 ⇒ 声明包含 11 且整批可见的批次（EV-0021）
        // + 本人 ALL_VISIBLE 批次（本人可见范围覆盖 11 ⇒ EV-0024 亦命中）
        PageResult<EvidenceExport> legit = service.queryExports(pageQuery(), own, SUB);
        assertEquals(2, legit.total(), "合法筛选 ⇒ EV-0021 与本人 ALL_VISIBLE 批次 EV-0024");
        for (EvidenceExport ex : legit.items()) {
            assertTrue(ex.exportNo.equals("EV-0021") || ex.exportNo.equals("EV-0024"),
                    "合法筛选结果须为 EV-0021/EV-0024：" + ex.exportNo);
        }
    }

    // ------------------------------------------------------------ 夹具

    private EvidenceExportService service(AuditLogDao dao) {
        AuditLogService logService = new AuditLogService(dao, checksumDao, crossReadonly(),
                AuditLogService.TamperAlarm.NOOP, tx);
        return new EvidenceExportService(auditService(), logService, exportDao, reasonDao,
                dualPnlStub(), crossReadonly(), tx);
    }

    private AuditService auditService() {
        return new AuditService(new DmExposureDao(tx), new DmHedgePlanExposureDao(tx),
                new DmHedgePlanDao(tx), new DmPlanApprovalDao(tx), new DmTradePlanDao(tx),
                tradeOrderDao, new DmTradeOrderReportDao(tx), tradeFillDao, reasonDao,
                new DmSettleCycleDao(tx), new DmOperationLogDao(tx), crossReadonly(),
                voucherStub(), () -> 1, AuditService.endOfTradeDay());
    }

    private static EvidenceExportService.DualPnlProvider dualPnlStub() {
        return (subAccountId, from, to) -> {
            DualPnl dp = new DualPnl();
            dp.physicalPnl = new BigDecimal("800");
            dp.derivativePnl = new BigDecimal("1200");
            return dp;
        };
    }

    private void seedChain(boolean withApproval, boolean withFill) throws Exception {
        M3AuditTraceSeed.hedgePlanExposure(engine, 6001L, SUB, PLAN, EXP, "100", TS);
        if (withApproval) {
            M3AuditTraceSeed.planApproval(engine, 6101L, SUB, PLAN, 1, TS);
        }
        M3AuditTraceSeed.tradeOrder(engine, ORDER, SUB, "HP202610060001-C1", 9001L, TRADE_PLAN, PLAN,
                EXP, PRODUCT, "cu2612", "ALL_FILLED", TS);
        if (withFill) {
            M3AuditTraceSeed.tradeOrderReport(engine, 6201L, SUB, "SYS-1", ORDER, "cu2612",
                    "ALL_TRADED", 10, 10, TS);
            M3AuditTraceSeed.tradeFill(engine, 6301L, SUB, "TF-1", ORDER, TRADE_PLAN, PLAN, EXP,
                    PRODUCT, "cu2612", "70000", 10, DAY, TS);
            M3AuditTraceSeed.settleCycleConfirmed(engine, SETTLE, SUB, "ST-1", DAY, TS);
        }
    }

    private void seedReason() {
        TradeReason reason = new TradeReason();
        reason.subAccountId = SUB;
        reason.tradeOrderId = ORDER;
        reason.relatedType = "PLAN_EXECUTION";
        reason.relatedPlanId = PLAN;
        reason.relatedExposureId = EXP;
        reason.timeDesc = "PRICE_TRIGGERED";
        reason.reasonText = "套保执行";
        reason.forcedByFreqAlert = "N";
        reason.relatedObjectMissingFlag = "N";
        reason.reasonMissingFlag = "N";
        reason.isModified = "N";
        reason.operatorId = OPERATOR;
        reason.fillTime = TS;
        tx.inTransaction(connection -> reasonDao.insert(reason, OPERATOR));
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

    private static EvidenceExportRequest req(String type, String id) {
        EvidenceExportRequest req = new EvidenceExportRequest();
        req.startPointType = type;
        req.startPointIds = new ArrayList<>(Collections.singletonList(id));
        req.dateFrom = DAY;
        req.dateTo = DAY;
        return req;
    }

    private static SubAccountVisibility crossReadonly() {
        return uid -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
    }

    /** 直接落一条导出记录（绕过导出流程，专供列表可见性用例控制声明范围与导出人）。 */
    private void seedExport(String exportNo, String scope, long exportedBy) {
        EvidenceExport ex = new EvidenceExport();
        ex.exportNo = exportNo;
        ex.startPointType = "EXPOSURE";
        ex.startPointIds = String.valueOf(EXP);
        ex.subAccountScope = scope;
        ex.includeSourceDetail = "N";
        ex.chainIntegrity = AuditService.COMPLETE;
        ex.dualPnlIncluded = "Y";
        ex.reasonIncluded = "Y";
        ex.fileId = "FILE-" + exportNo;
        ex.exportedBy = exportedBy;
        ex.exportedTime = TS;
        int rows = tx.inTransaction(connection -> exportDao.insert(ex, exportedBy));
        assertEquals(1, rows, "导出记录须落库：" + exportNo);
    }

    /** 列表查询分页参数（与生产 handler 同缺省：exported_time desc）。 */
    private static PageQuery pageQuery() {
        return PageQuery.of("1", "20", null, "exported_time", "desc");
    }

    /** 以指定可见性取数构造被测服务（审计日志服务同源真实 DAO）。 */
    private EvidenceExportService service(SubAccountVisibility auth) {
        AuditLogService logService = new AuditLogService(new DmAuditLogDao(tx), checksumDao,
                auth, AuditLogService.TamperAlarm.NOOP, tx);
        return new EvidenceExportService(auditService(), logService, exportDao, reasonDao,
                dualPnlStub(), auth, tx);
    }

    /** 故障注入桩：仅 {@code EXPORT} 动作写入失败（其余返回成功、不持久化），用于验证导出中断。 */
    private static final class ExportFailingLogDao implements AuditLogDao {
        @Override
        public int insert(AuditLog entity, long operatorId) {
            if (AuditLogService.ACTION_EXPORT.equals(entity.actionType)) {
                throw new IllegalStateException("模拟审计日志写入失败（R-AUDIT-4）");
            }
            return 1;
        }

        @Override
        public int updateById(AuditLog entity, long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuditLog findById(Long id) {
            return null;
        }

        @Override
        public PageResult<AuditLog> findPage(PageQuery query, String where, List<Object> whereArgs) {
            return new PageResult<>(new ArrayList<>(), 0, 1, 20);
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return 0;
        }

        @Override
        public AuditLog findBySeqInChain(long seqInChain) {
            return null;
        }
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
