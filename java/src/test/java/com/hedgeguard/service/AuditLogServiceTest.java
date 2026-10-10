package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuditLogChecksumDao;
import com.hedgeguard.dao.AuditLogDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.entity.AuditLogChecksum;
import com.hedgeguard.dao.impl.DmAuditLogChecksumDao;
import com.hedgeguard.dao.impl.DmAuditLogDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.T069AuditTamperSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-069} 审计日志服务「真实 DAO + 内存假 JDBC 引擎」用例（禁假 DAO）。
 *
 * <p>覆盖：链式写入（seq/prev_hash/row_hash）、查询自身留痕（{@code FR-AUDIT-9} 验收 2）、
 * 防篡改校验通过 / 篡改检出 + 告警（{@code FR-AUDIT-10} 验收 1/2）、导出前阻断（{@code 10018}）、
 * 批次校验值生成与比对。
 */
class AuditLogServiceTest {

    private static final int SUB = 11;
    private static final int OTHER_SUB = 12;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private InMemoryDmEngine engine;
    private DmAuditLogDao logDao;
    private DmAuditLogChecksumDao checksumDao;
    private AuditLogService service;
    private final List<String> alarms = new ArrayList<>();

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        alarms.clear();
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, OTHER_SUB, "SA-B", "商品子账户B");

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        logDao = new DmAuditLogDao(tx);
        checksumDao = new DmAuditLogChecksumDao(tx);
        service = new AuditLogService(logDao, checksumDao, crossReadonly(),
                (message, mismatches) -> alarms.add(message), tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ 链式写入

    @Test
    @DisplayName("链式写入：seq 单调、prev_hash 衔接、row_hash 可重算")
    void appendBuildsChain() {
        AuditLog a = append(AuditLogService.ACTION_QUERY, SUB, 1);
        AuditLog b = append(AuditLogService.ACTION_EXPORT, SUB, 2);
        AuditLog c = append(AuditLogService.ACTION_VERIFY, SUB, 3);

        assertEquals(1L, a.seqInChain);
        assertEquals(AuditLogService.GENESIS_HASH, a.prevHash, "首行 prev_hash 须为创世哈希");
        assertEquals(2L, b.seqInChain);
        assertEquals(a.rowHash, b.prevHash, "第二行 prev_hash 须等于首行 row_hash");
        assertEquals(c.prevHash, b.rowHash);
        assertEquals(AuditLogService.computeRowHash(a), a.rowHash, "row_hash 须可由关键字段重算");
        assertEquals(a.rowHash, logDao.findBySeqInChain(1L).rowHash, "落库 row_hash 一致");
    }

    @Test
    @DisplayName("查询审计日志本身写入 QUERY 日志（FR-AUDIT-9 验收 2）")
    void queryWritesSelfLog() {
        append(AuditLogService.ACTION_EXPORT, SUB, 1);
        append(AuditLogService.ACTION_EXPORT, SUB, 2);

        AuditLogService.AuditLogQuery q = new AuditLogService.AuditLogQuery();
        q.actionType = AuditLogService.ACTION_EXPORT;
        var page = service.query(q, PageQuery.of("1", "20", null, "action_time", "desc"), OPERATOR);

        assertEquals(2, page.items().size(), "应查回 2 条 EXPORT");
        AuditLog self = logDao.findBySeqInChain(3L);
        assertNotNull(self, "查询本身应新增一条日志");
        assertEquals(AuditLogService.ACTION_QUERY, self.actionType);
        assertEquals(2, self.resultCount);
    }

    // ------------------------------------------------------------ 防篡改校验

    @Test
    @DisplayName("正常数据校验通过，且结论写入 VERIFY 日志（FR-AUDIT-10 验收 1/3）")
    void verifyPasses() {
        append(AuditLogService.ACTION_QUERY, SUB, 1);
        append(AuditLogService.ACTION_EXPORT, SUB, 2);
        append(AuditLogService.ACTION_EXPORT, SUB, 3);
        AuditLogChecksum batch = service.generateChecksumBatch(100, OPERATOR);
        assertNotNull(batch);
        assertEquals(1L, batch.batchNo);
        assertEquals(1L, batch.batchStartSeq);
        assertEquals(3L, batch.batchEndSeq);
        assertEquals(3, batch.recordCount);
        assertEquals(logDao.findBySeqInChain(1L).rowHash, batch.batchHeadHash);
        assertEquals(logDao.findBySeqInChain(3L).rowHash, batch.batchTailHash);

        AuditLogService.VerifyResult r = service.verify(1L, 0L, OPERATOR);
        assertTrue(r.passed, "正常数据应通过：" + r.mismatches);
        assertEquals(3, r.checkedCount);
        assertEquals(1, r.verifiedBatches);
        assertTrue(alarms.isEmpty(), "通过时不应告警");
        AuditLog verifyLog = logDao.findBySeqInChain(4L);
        assertEquals(AuditLogService.ACTION_VERIFY, verifyLog.actionType, "校验结论须写入审计日志");
        assertTrue(verifyLog.queryCondition.contains("PASSED"));
        assertTrue(verifyLog.queryCondition.contains("range=[1,3]"));
    }

    @Test
    @DisplayName("直接改底层日志 ⇒ 校验未通过 + 告警 + 阻断导出（FR-AUDIT-10 验收 2）")
    void verifyDetectsTamperAndBlocksExport() throws Exception {
        append(AuditLogService.ACTION_QUERY, SUB, 1);
        append(AuditLogService.ACTION_EXPORT, SUB, 2);
        append(AuditLogService.ACTION_EXPORT, SUB, 3);

        T069AuditTamperSeed.tamperAuditLogResultCount(engine, 2L, 999);

        AuditLogService.VerifyResult r = service.verify(1L, 0L, OPERATOR);
        assertFalse(r.passed, "篡改后须校验未通过");
        assertFalse(r.mismatches.isEmpty(), "须给出差异清单");
        assertEquals(1, alarms.size(), "校验未通过须告警");

        BizException ex = assertThrows(BizException.class, () -> service.assertChainIntact(OPERATOR));
        assertEquals(ErrorCode.AUDIT_CHAIN_TAMPERED, ex.errorCode(), "须以 10018 阻断导出");
    }

    @Test
    @DisplayName("批次校验值有牙：篡改后批次校验和不符")
    void checksumBatchDetectsTamper() throws Exception {
        append(AuditLogService.ACTION_QUERY, SUB, 1);
        append(AuditLogService.ACTION_EXPORT, SUB, 2);
        service.generateChecksumBatch(100, OPERATOR);
        T069AuditTamperSeed.tamperAuditLogRowHash(engine, 1L,
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

        AuditLogService.VerifyResult r = service.verify(1L, 0L, OPERATOR);
        assertFalse(r.passed);
        assertTrue(r.mismatches.stream().anyMatch(m -> m.contains("批次校验和不符")),
                "须报批次校验和不符：" + r.mismatches);
    }

    @Test
    @DisplayName("无新增行时不落空批次")
    void emptyBatchNotPersisted() {
        assertTrue(service.generateChecksumBatch(100, OPERATOR) == null);
        append(AuditLogService.ACTION_QUERY, SUB, 1);
        assertNotNull(service.generateChecksumBatch(100, OPERATOR));
        assertTrue(service.generateChecksumBatch(100, OPERATOR) == null, "无新增行不得落空批次");
    }

    // ------------------------------------------------------------ 子账户隔离

    @Test
    @DisplayName("非跨账户查询仅返回可见子账户日志")
    void queryScopedToVisible() {
        append(AuditLogService.ACTION_QUERY, SUB, 1);
        append(AuditLogService.ACTION_QUERY, OTHER_SUB, 2);
        AuditLogService scoped = new AuditLogService(logDao, checksumDao,
                uid -> Collections.singletonList(SUB), AuditLogService.TamperAlarm.NOOP,
                new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2))));
        AuditLogService.AuditLogQuery q = new AuditLogService.AuditLogQuery();
        q.actionType = AuditLogService.ACTION_QUERY;
        assertEquals(1, scoped.query(q, PageQuery.of("1", "20", null, "action_time", "desc"), 7L)
                .items().size());
    }

    // ------------------------------------------------------------ 夹具

    private AuditLog append(String action, int sub, int resultCount) {
        AuditLog log = new AuditLog();
        log.subAccountId = sub;
        log.auditUserId = OPERATOR;
        log.actionType = action;
        log.queryCondition = "c" + resultCount;
        log.resultCount = resultCount;
        log.exportFileId = AuditLogService.ACTION_EXPORT.equals(action) ? "F" + resultCount : null;
        log.actionTime = Clocks.now();
        return service.append(log, OPERATOR);
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
