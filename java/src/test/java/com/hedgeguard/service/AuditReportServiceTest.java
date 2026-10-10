package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.MajorEventDao;
import com.hedgeguard.dao.OperationLogDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PostRiskReportDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.entity.AuditReport;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.dao.entity.PostRiskReport;
import com.hedgeguard.dao.impl.DmAuditLogChecksumDao;
import com.hedgeguard.dao.impl.DmAuditLogDao;
import com.hedgeguard.dao.impl.DmAuditReportDao;
import com.hedgeguard.dao.impl.DmMajorEventDao;
import com.hedgeguard.dao.impl.DmOperationLogDao;
import com.hedgeguard.dao.impl.DmPostRiskReportDao;
import com.hedgeguard.dao.impl.DmSettleCheckSignatureDao;
import com.hedgeguard.dao.impl.DmSettleCheckTaskDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuditReportService.MajorEventRequest;
import com.hedgeguard.service.AuditReportService.ReportDataProvider;
import com.hedgeguard.service.AuditReportService.ReportExport;
import com.hedgeguard.service.AuditReportService.ReportRow;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-069} 审计报表服务用例（真实 DAO + 内存假 JDBC 引擎；聚合数据源以 provider 桩注入唯一预期值）。
 *
 * <p>覆盖：季度报表（品种/保值规模/盈亏/持仓 + 双侧盈亏 + 汇总=明细之和，{@code FR-AUDIT-5}）、
 * 年度专项报告（五部分 + 季度清单 + 核对覆盖 + 缺口标注，{@code FR-AUDIT-6}）、重大事项专项报告
 * （类型必填 + 生成/提交留痕 + 触发提醒，{@code FR-AUDIT-7}）、报表导出（元数据 + 日志留痕 +
 * 无数据不生成，{@code FR-AUDIT-12}）、事后汇总进 {@code AUDIT}（{@code FR-RISK-18} 验收 4）。
 */
class AuditReportServiceTest {

    private static final int SUB = 11;
    private static final long PRODUCT_A = 7001L;
    private static final long PRODUCT_B = 7002L;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private InMemoryDmEngine engine;
    private DmAuditReportDao reportDao;
    private DmMajorEventDao majorEventDao;
    private DmOperationLogDao operationLogDao;
    private DmAuditLogDao auditLogDao;
    private DmPostRiskReportDao postRiskReportDao;
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
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        reportDao = new DmAuditReportDao(tx);
        majorEventDao = new DmMajorEventDao(tx);
        operationLogDao = new DmOperationLogDao(tx);
        auditLogDao = new DmAuditLogDao(tx);
        postRiskReportDao = new DmPostRiskReportDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("季度报表：品种/规模/双侧盈亏/持仓 + 汇总=明细之和 + 留痕（FR-AUDIT-5）")
    void quarterlyReport() {
        seedPostRiskReport("{\"violations\":3}");
        AuditReportService service = service(twoRows());

        AuditReport r = service.generateQuarterly("2026-Q4", null, OPERATOR);

        assertEquals(AuditReportService.TYPE_QUARTERLY, r.reportType);
        assertEquals("2026-Q4", r.periodKey);
        assertEquals("Y", r.dualPnlIncluded);
        assertEquals(AuditReportService.COMPLETE, r.chainIntegrity);
        assertEquals(OPERATOR, r.generatedBy);
        assertTrue(r.contentJson.contains("\"hedgeScale\":300"), "汇总保值规模=明细之和：" + r.contentJson);
        assertTrue(r.contentJson.contains("\"derivativePnl\":500"), "汇总衍生品盈亏=明细之和");
        assertTrue(r.contentJson.contains("\"positionQty\":30"), "汇总持仓=明细之和");
        assertTrue(r.contentJson.contains("铜") && r.contentJson.contains("铝"), "含品种");
        assertTrue(r.contentJson.contains("实货盈亏数据源"), "实货盈亏缺口须标注");
        assertTrue(r.contentJson.contains("violations"), "FR-RISK-18 验收 4：事后汇总进 AUDIT");
        assertNotNull(reportDao.findByReportNo(r.reportNo));
        assertEquals(1, countOperation(AuditReportService.OP_GENERATE));
    }

    @Test
    @DisplayName("期间无数据 ⇒ 拒绝生成（FR-AUDIT-5）")
    void quarterlyNoData() {
        AuditReportService service = service(emptyProvider());
        BizException ex = assertThrows(BizException.class,
                () -> service.generateQuarterly("2026-Q4", null, OPERATOR));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, ex.errorCode());
    }

    @Test
    @DisplayName("年度专项报告：缺口显式标注（UNKNOWN + break_list），补齐后 COMPLETE")
    void annualReportGap() {
        AuditReportService service = service(twoRows());
        AuditReport annual = service.generateAnnual("2026", null, OPERATOR);
        assertEquals(AuditReportService.UNKNOWN, annual.chainIntegrity, "季报缺失须标 UNKNOWN");
        assertNotNull(annual.breakList);
        assertTrue(annual.dataGapDesc.contains("缺少季度数据"));
        assertTrue(annual.contentJson.contains("missingQuarters"));

        for (int q = 1; q <= 4; q++) {
            service.generateQuarterly("2026-Q" + q, null, OPERATOR);
        }
        AuditReport complete = service.generateAnnual("2026", null, OPERATOR);
        assertEquals(AuditReportService.COMPLETE, complete.chainIntegrity);
        assertNull(complete.breakList);
    }

    @Test
    @DisplayName("重大事项专项报告：类型必填；生成/提交留痕；触发提醒（FR-AUDIT-7）")
    void majorEventReport() {
        AuditReportService service = service(twoRows());
        MajorEventRequest noType = new MajorEventRequest();
        BizException ex = assertThrows(BizException.class,
                () -> service.generateMajorEvent(noType, OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());

        MajorEventRequest req = new MajorEventRequest();
        req.eventType = "AGG_INCONSISTENT_TIMEOUT";
        req.eventNo = "ME-1";
        req.eventDesc = "加总对账不一致";
        req.causeAnalysis = "子账户差异";
        req.impactScope = "子账户 A";
        req.handlingMeasures = "冻结新开仓";
        req.rectificationPlan = "复核差异";
        AuditReport r = service.generateMajorEvent(req, OPERATOR);
        assertEquals(AuditReportService.TYPE_MAJOR_EVENT, r.reportType);
        assertEquals(1, countOperation(AuditReportService.OP_GENERATE), "生成留痕一条");

        AuditReport submitted = service.submit(r.reportNo, "管理层", OPERATOR);
        assertEquals("Y", submitted.submitted);
        assertEquals(1, countOperation(AuditReportService.OP_SUBMIT));

        MajorEvent triggered = new MajorEvent();
        triggered.subAccountId = SUB;
        triggered.eventNo = "ME-2";
        triggered.eventType = "LOSS_THRESHOLD";
        triggered.triggerSource = "T-1";
        triggered.eventDesc = "亏损超阈值";
        triggered.status = "TRIGGERED";
        triggered.triggeredTime = Clocks.now();
        tx.inTransaction(connection -> majorEventDao.insert(triggered, OPERATOR));
        assertEquals(1, service.pendingMajorEvents(OPERATOR).size(), "命中触发条件须提醒");
    }

    @Test
    @DisplayName("报表导出：含类型/时间/生成时间/生成人/子账户范围 + 审计日志留痕（FR-AUDIT-12）")
    void exportReport() {
        AuditReportService service = service(twoRows());
        AuditReport r = service.generateQuarterly("2026-Q4", null, OPERATOR);

        ReportExport ex = service.export(r.reportNo, OPERATOR);
        assertTrue(ex.content.contains("报表类型: QUARTERLY"));
        assertTrue(ex.content.contains("生成人: " + OPERATOR));
        assertTrue(ex.content.contains("完整性: COMPLETE"));
        assertEquals("REPORT-" + r.reportNo, ex.fileId);
        AuditLog log = findAuditLog(AuditLogService.ACTION_EXPORT);
        assertNotNull(log, "导出动作须写入审计日志");
        assertEquals(ex.fileId, log.exportFileId);
    }

    @Test
    @DisplayName("无报表数据导出 ⇒ 不生成空文件（FR-AUDIT-12 验收 4）")
    void exportNoData() {
        AuditReportService service = service(twoRows());
        BizException ex = assertThrows(BizException.class,
                () -> service.export("ARQ-NOPE", OPERATOR));
        assertEquals(ErrorCode.DATA_INSUFFICIENT, ex.errorCode());
    }

    // ------------------------------------------------------------ 夹具

    private AuditReportService service(ReportDataProvider provider) {
        AuditLogService logService = new AuditLogService(auditLogDao,
                new DmAuditLogChecksumDao(tx), crossReadonly(), AuditLogService.TamperAlarm.NOOP, tx);
        CheckTaskQueryService checkService = new CheckTaskQueryService(
                new DmSettleCheckTaskDao(tx), new DmSettleCheckSignatureDao(tx), crossReadonly());
        return new AuditReportService(reportDao, majorEventDao, operationLogDao, logService,
                checkService, postRiskReportDao, provider, crossReadonly(), tx);
    }

    private static ReportDataProvider twoRows() {
        return (from, to, visible, cross) -> {
            List<ReportRow> rows = new ArrayList<>();
            rows.add(row(PRODUCT_A, "铜", "100", "200", "10"));
            rows.add(row(PRODUCT_B, "铝", "200", "300", "20"));
            return rows;
        };
    }

    private static ReportDataProvider emptyProvider() {
        return (from, to, visible, cross) -> new ArrayList<>();
    }

    private static ReportRow row(long productId, String name, String hedge, String deriv, String pos) {
        ReportRow r = new ReportRow();
        r.subAccountId = SUB;
        r.productId = productId;
        r.productName = name;
        r.hedgeScale = new BigDecimal(hedge);
        r.derivativePnl = new BigDecimal(deriv);
        r.positionQty = new BigDecimal(pos);
        r.physicalPnl = null;
        return r;
    }

    private void seedPostRiskReport(String contentJson) {
        PostRiskReport r = new PostRiskReport();
        r.subAccountId = null;
        r.reportDate = DAY;
        r.reportType = PostRiskReportService.REPORT_TYPE_VIOLATION_SUMMARY;
        r.contentJson = contentJson;
        r.generatedBy = OPERATOR;
        r.generatedTime = Clocks.now();
        tx.inTransaction(connection -> postRiskReportDao.insert(r, OPERATOR));
    }

    private AuditLog findAuditLog(String actionType) {
        com.hedgeguard.dao.PageResult<AuditLog> pr = auditLogDao.findPage(
                PageQuery.of("1", "100", null, "id", "asc"), "action_type = ?",
                new ArrayList<Object>(Collections.singletonList(actionType)));
        return pr.items().isEmpty() ? null : pr.items().get(0);
    }

    private int countOperation(String operationType) {
        PageResult<com.hedgeguard.dao.entity.OperationLog> pr = operationLogDao.findPage(
                PageQuery.of("1", "100", null, "id", "asc"), "operation_type = ?",
                new ArrayList<Object>(Collections.singletonList(operationType)));
        return pr.items().size();
    }

    private static SubAccountVisibility crossReadonly() {
        return uid -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
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
