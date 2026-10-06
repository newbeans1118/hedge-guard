package com.hedgeguard.dao;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.entity.AuditLogChecksum;
import com.hedgeguard.dao.entity.AuditReport;
import com.hedgeguard.dao.entity.EvidenceExport;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.dao.entity.OperationLog;
import com.hedgeguard.dao.entity.ViolationScreenItem;
import com.hedgeguard.dao.entity.ViolationScreenRun;
import com.hedgeguard.dao.impl.DmAuditLogChecksumDao;
import com.hedgeguard.dao.impl.DmAuditLogDao;
import com.hedgeguard.dao.impl.DmAuditReportDao;
import com.hedgeguard.dao.impl.DmEvidenceExportDao;
import com.hedgeguard.dao.impl.DmMajorEventDao;
import com.hedgeguard.dao.impl.DmOperationLogDao;
import com.hedgeguard.dao.impl.DmViolationScreenItemDao;
import com.hedgeguard.dao.impl.DmViolationScreenRunDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-062}：AUDIT 8 表「真实 DAO + 内存假 JDBC 引擎」逐列往返（0 差异）用例。
 *
 * <p>本机无 DM8 实例（归 {@code T-052}）⇒ 以等价执行替代：把生产建表脚本
 * {@code docs/DEPLOY/schema/m3_audit.sql}（连同上游 M1/M2 建表脚本）真喂给 {@link InMemoryDmEngine}
 * 建表，再由<b>真实</b> {@code Dm*Dao} 生成 {@code INSERT}/{@code SELECT} 并真正执行。
 * <b>不使用假 DAO</b>（{@code T-136}/{@code T-058} 判例）。
 *
 * <p>判据：① 每表 insert → findById 后，实体<b>全部</b>字段（业务列 + 审计列，含 {@code CLOB}）
 * 逐字段相等 ⇒ DDL 列 / {@code COLUMNS} / bind / read 四处无漂移；② 各表专用查询返回预期行；
 * ③ 具名 CHECK 有牙（越域写入被拒）；④ 只增不改表 {@code updateById} 显式拒绝，可更新表乐观锁生效；
 * ⑤ 引擎报告的 NOT NULL / 外键拒绝为 0。
 */
class M3AuditDaoRoundTripTest {

    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql",
            "docs/DEPLOY/schema/m2_trade.sql",
            "docs/DEPLOY/schema/m2_settle.sql",
            "docs/DEPLOY/schema/m2_fund.sql",
            "docs/DEPLOY/schema/m3_risk.sql",
            "docs/DEPLOY/schema/m3_audit.sql");

    private static final long OPERATOR = 201L;
    private static final int SUB = 11;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime TS = DAY.atTime(10, 0);

    private InMemoryDmEngine engine;
    private DmOperationLogDao operationDao;
    private DmAuditLogDao auditLogDao;
    private DmAuditLogChecksumDao checksumDao;
    private DmViolationScreenRunDao screenRunDao;
    private DmViolationScreenItemDao screenItemDao;
    private DmAuditReportDao reportDao;
    private DmEvidenceExportDao exportDao;
    private DmMajorEventDao eventDao;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        operationDao = new DmOperationLogDao(tx);
        auditLogDao = new DmAuditLogDao(tx);
        checksumDao = new DmAuditLogChecksumDao(tx);
        screenRunDao = new DmViolationScreenRunDao(tx);
        screenItemDao = new DmViolationScreenItemDao(tx);
        reportDao = new DmAuditReportDao(tx);
        exportDao = new DmEvidenceExportDao(tx);
        eventDao = new DmMajorEventDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("八表 insert→findById 逐字段 0 差异（业务列 + 审计列 + CLOB）")
    void roundTripAllColumns() {
        OperationLog op = operation();
        assertEquals(1, operationDao.insert(op, OPERATOR));
        assertNotNull(op.id);
        assertSameFields(op, operationDao.findById(op.id));

        AuditLog al = auditLog();
        assertEquals(1, auditLogDao.insert(al, OPERATOR));
        assertSameFields(al, auditLogDao.findById(al.id));

        AuditLogChecksum ck = checksum();
        assertEquals(1, checksumDao.insert(ck, OPERATOR));
        assertSameFields(ck, checksumDao.findById(ck.id));

        ViolationScreenRun run = screenRun();
        assertEquals(1, screenRunDao.insert(run, OPERATOR));
        assertSameFields(run, screenRunDao.findById(run.id));

        ViolationScreenItem it = screenItem(run.id);
        assertEquals(1, screenItemDao.insert(it, OPERATOR));
        assertSameFields(it, screenItemDao.findById(it.id));

        AuditReport rp = report();
        assertEquals(1, reportDao.insert(rp, OPERATOR));
        assertSameFields(rp, reportDao.findById(rp.id));

        EvidenceExport ex = export(al.id);
        assertEquals(1, exportDao.insert(ex, OPERATOR));
        assertSameFields(ex, exportDao.findById(ex.id));

        MajorEvent ev = majorEvent(rp.id);
        assertEquals(1, eventDao.insert(ev, OPERATOR));
        assertSameFields(ev, eventDao.findById(ev.id));

        assertEquals(0, engine.rejectedByNotNull(), "不得出现 NOT NULL 拒绝");
        assertEquals(0, engine.rejectedByForeignKey(), "不得出现外键拒绝");
    }

    @Test
    @DisplayName("八表专用查询返回预期行")
    void findersReturnExpectedRows() {
        OperationLog op = operation();
        operationDao.insert(op, OPERATOR);
        List<OperationLog> ops = operationDao.findByObject("TRADE_ORDER", 1001L);
        assertEquals(1, ops.size());
        assertEquals(op.id, ops.get(0).id);

        AuditLog al = auditLog();
        auditLogDao.insert(al, OPERATOR);
        AuditLog gotAl = auditLogDao.findBySeqInChain(7L);
        assertNotNull(gotAl);
        assertEquals(al.id, gotAl.id);

        AuditLogChecksum ck = checksum();
        checksumDao.insert(ck, OPERATOR);
        assertNotNull(checksumDao.findByBatchNo(1L));

        ViolationScreenRun run = screenRun();
        screenRunDao.insert(run, OPERATOR);
        assertNotNull(screenRunDao.findByScreenNo("SCR-1"));

        ViolationScreenItem it = screenItem(run.id);
        screenItemDao.insert(it, OPERATOR);
        assertEquals(1, screenItemDao.findByScreenRunAndType(run.id, "OVER_PRODUCT").size());

        AuditReport rp = report();
        reportDao.insert(rp, OPERATOR);
        assertNotNull(reportDao.findByReportNo("RPT-1"));

        EvidenceExport ex = export(al.id);
        exportDao.insert(ex, OPERATOR);
        assertNotNull(exportDao.findByExportNo("EXP-1"));

        MajorEvent ev = majorEvent(rp.id);
        eventDao.insert(ev, OPERATOR);
        assertEquals(1, eventDao.findByTypeAndStatus("SCALE_REDLINE_HIT", "TRIGGERED").size());
    }

    @Test
    @DisplayName("具名 CHECK 有牙：越域写入被引擎按 DDL 语义拒绝")
    void namedChecksHaveTeeth() {
        OperationLog bad = operation();
        bad.actionResult = "MAYBE";
        long before = engine.rejectedByCheck();
        assertThrows(Exception.class, () -> operationDao.insert(bad, OPERATOR));
        assertEquals(before + 1, engine.rejectedByCheck(), "非法 action_result 应触发 ck_operation_log_action_result");

        AuditReport rp = report();
        rp.dualPnlIncluded = "N";
        assertThrows(Exception.class, () -> reportDao.insert(rp, OPERATOR),
                "dual_pnl_included='N' 应触发 ck_audit_report_dual_pnl");

        ViolationScreenItem it = screenItem(1L);
        it.severity = "FATAL";
        assertThrows(Exception.class, () -> screenItemDao.insert(it, OPERATOR),
                "severity='FATAL' 应触发 ck_violation_screen_item_severity");
    }

    @Test
    @DisplayName("只增不改表拒绝 updateById；可更新表乐观锁生效")
    void updatePolicy() {
        OperationLog op = operation();
        operationDao.insert(op, OPERATOR);
        assertThrows(UnsupportedOperationException.class, () -> operationDao.updateById(op, OPERATOR));

        AuditLog al = auditLog();
        auditLogDao.insert(al, OPERATOR);
        assertThrows(UnsupportedOperationException.class, () -> auditLogDao.updateById(al, OPERATOR));

        AuditLogChecksum ck = checksum();
        checksumDao.insert(ck, OPERATOR);
        assertThrows(UnsupportedOperationException.class, () -> checksumDao.updateById(ck, OPERATOR));

        MajorEvent ev = majorEvent(null);
        eventDao.insert(ev, OPERATOR);
        MajorEvent loaded = eventDao.findById(ev.id);
        assertEquals(1, loaded.rowVersion);
        assertNull(loaded.updatedBy);
        loaded.status = "REPORT_GENERATED";
        assertEquals(1, eventDao.updateById(loaded, OPERATOR));
        MajorEvent updated = eventDao.findById(ev.id);
        assertEquals("REPORT_GENERATED", updated.status);
        assertEquals(2, updated.rowVersion, "乐观锁应推进 row_version");
        assertEquals(Long.valueOf(OPERATOR), updated.updatedBy);
    }

    // ------------------------------------------------------------------ 实体构造

    private static OperationLog operation() {
        OperationLog e = new OperationLog();
        e.subAccountId = SUB;
        e.operatorId = OPERATOR;
        e.operationType = "ORDER_CONFIRM";
        e.objectType = "TRADE_ORDER";
        e.objectId = 1001L;
        e.objectNo = "ORD-1001";
        e.actionResult = "SUCCESS";
        e.resultDetail = "人工确认通过";
        e.operationTime = TS;
        e.clientIp = "10.0.0.8";
        e.traceId = "trace-abc";
        return e;
    }

    private static AuditLog auditLog() {
        AuditLog e = new AuditLog();
        e.subAccountId = SUB;
        e.auditUserId = OPERATOR;
        e.actionType = "QUERY";
        e.queryCondition = "sub_account_id=11 AND date=2026-10-06";
        e.resultCount = 3;
        e.exportFileId = null;
        e.chainIntegrity = "COMPLETE";
        e.actionTime = TS;
        e.prevHash = "0".repeat(64);
        e.rowHash = "a".repeat(64);
        e.seqInChain = 7L;
        return e;
    }

    private static AuditLogChecksum checksum() {
        AuditLogChecksum e = new AuditLogChecksum();
        e.batchNo = 1L;
        e.batchStartSeq = 1L;
        e.batchEndSeq = 100L;
        e.batchHeadHash = "b".repeat(64);
        e.batchTailHash = "c".repeat(64);
        e.batchChecksum = "d".repeat(64);
        e.recordCount = 100;
        return e;
    }

    private static ViolationScreenRun screenRun() {
        ViolationScreenRun e = new ViolationScreenRun();
        e.screenNo = "SCR-1";
        e.dateFrom = DAY.minusDays(30);
        e.dateTo = DAY;
        e.subAccountScope = "ALL_AUTHORIZED";
        e.screenDimensions = "OVER_PRODUCT,OVER_SCALE,REASON_MISSING";
        e.ruleVersion = "RISK-v1.5.15";
        e.hitCount = 1;
        e.operatorId = OPERATOR;
        e.screenTime = TS;
        e.conclusion = "发现 1 条超品种命中";
        return e;
    }

    private static ViolationScreenItem screenItem(long screenRunId) {
        ViolationScreenItem e = new ViolationScreenItem();
        e.subAccountId = SUB;
        e.screenRunId = screenRunId;
        e.violationType = "OVER_PRODUCT";
        e.severity = "HIGH";
        e.ruleId = "R-RISK-1";
        e.objectType = "TRADE_ORDER";
        e.objectId = 1001L;
        e.occurTime = TS;
        e.violationDesc = "品种铜超出授权范围";
        e.tradeReasonId = null;
        e.relatedObjectInfo = "hedge_plan_id=5";
        return e;
    }

    private static AuditReport report() {
        AuditReport e = new AuditReport();
        e.subAccountId = SUB;
        e.reportNo = "RPT-1";
        e.reportType = "QUARTERLY";
        e.periodKey = "2026-Q3";
        e.contentJson = "{\"q\":\"Q3\",\"hedgeScale\":1000}";
        e.dualPnlIncluded = "Y";
        e.chainIntegrity = "COMPLETE";
        e.breakList = null;
        e.dataGapDesc = null;
        e.generatedBy = OPERATOR;
        e.generatedTime = TS;
        e.submitted = "N";
        e.submittedTo = null;
        e.submittedTime = null;
        return e;
    }

    private static EvidenceExport export(Long auditLogId) {
        EvidenceExport e = new EvidenceExport();
        e.exportNo = "EXP-1";
        e.startPointType = "EXPOSURE";
        e.startPointIds = "1,2,3";
        e.dateFrom = DAY.minusDays(30);
        e.dateTo = DAY;
        e.subAccountScope = "ALL_AUTHORIZED";
        e.includeSourceDetail = "N";
        e.chainIntegrity = "HAS_BREAK";
        e.breakList = "[\"gap at 2026-09-15\"]";
        e.dualPnlIncluded = "Y";
        e.reasonIncluded = "Y";
        e.fileId = "FILE-1";
        e.filePath = "/tmp/exp-1.csv";
        e.exportedBy = OPERATOR;
        e.exportedTime = TS;
        e.auditLogId = auditLogId;
        return e;
    }

    private static MajorEvent majorEvent(Long auditReportId) {
        MajorEvent e = new MajorEvent();
        e.subAccountId = SUB;
        e.eventNo = "EVT-1";
        e.eventType = "SCALE_REDLINE_HIT";
        e.triggerSource = "annual_scale:11:55:2026";
        e.eventDesc = "年度保值规模触及红线";
        e.impactScope = "子账户A";
        e.handlingMeasures = "暂停新开仓";
        e.rectificationPlan = "下月调降规模";
        e.status = "TRIGGERED";
        e.auditReportId = auditReportId;
        e.triggeredTime = TS;
        return e;
    }

    // ------------------------------------------------------------------ 逐字段比较

    /** 反射比较实体全部实例字段（含继承的审计字段）；BigDecimal 按 compareTo。 */
    private static void assertSameFields(Object expected, Object actual) {
        assertNotNull(actual, "读回实体为 null：" + expected.getClass().getSimpleName());
        for (Class<?> c = expected.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object ve;
                Object va;
                try {
                    ve = f.get(expected);
                    va = f.get(actual);
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException("反射读字段失败：" + f.getName(), ex);
                }
                String label = expected.getClass().getSimpleName() + "." + f.getName();
                assertEquals(ve, va, label + " 不一致");
            }
        }
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；cwd=" + Paths.get("").toAbsolutePath());
    }
}
