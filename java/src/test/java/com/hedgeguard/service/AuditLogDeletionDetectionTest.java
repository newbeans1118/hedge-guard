package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuditLogChecksumDao;
import com.hedgeguard.dao.AuditLogDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.AuditLog;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-224}：{@code DELETE} 型篡改检出（{@code T-069} 终审 O-1）。
 *
 * <p><b>缺陷背景</b>：{@link AuditLogService#verify} 原仅做逐行/衔接/批次校验，{@code [from,to]} 由
 * {@code maxSeq()} 决定：
 * <ul>
 *   <li><b>全表清空</b> ⇒ {@code max=0}、{@code fetchRange(1,0)} 为空 ⇒ 早退 {@code passed=true}；</li>
 *   <li><b>截断链尾</b> ⇒ {@code to=maxSeq()} 把最后批次排除在「完整覆盖」之外 ⇒ {@code passed=true}。</li>
 * </ul>
 * 两者都让 {@link AuditLogService#assertChainIntact} 放行，导出不被 {@code 10018} 阻断。
 *
 * <p>本类锁定修法后的行为（真实 DAO + 内存假 JDBC 引擎）：
 * <ol>
 *   <li>全表清空 ⇒ 未通过（{@code checkedCount=0}）+ 告警 + 落 {@code FAILED} 审计记录 + {@code 10018}；</li>
 *   <li>截断链尾（删 {@code seq>=3}）⇒ 未通过（「链尾截断」）+ {@code 10018}；</li>
 *   <li>真空白（无行、无批次）⇒ 通过（不误报）；</li>
 *   <li>批次后新增行（{@code max > batchEnd}）⇒ 通过（不误报）。</li>
 * </ol>
 */
class AuditLogDeletionDetectionTest {

    private static final int SUB = 11;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private InMemoryDmEngine engine;
    private DmAuditLogDao logDao;
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

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        logDao = new DmAuditLogDao(tx);
        AuditLogChecksumDao checksumDao = new DmAuditLogChecksumDao(tx);
        service = new AuditLogService(logDao, checksumDao, crossReadonly(),
                (message, mismatches) -> alarms.add(message), tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ 用例 1：全表清空

    @Test
    @DisplayName("全表清空 ⇒ 未通过（空链）+ 告警 + FAILED 留痕 + 10018 阻断导出")
    void deleteAllDetected() throws Exception {
        appendThree();
        assertNotNull(service.generateChecksumBatch(100, OPERATOR), "前置：批次校验值已落库");

        int deleted = T069AuditTamperSeed.deleteAllAuditLogs(engine);
        assertEquals(3, deleted, "前置：全表 3 行被删");

        AuditLogService.VerifyResult r = service.verify(1L, 0L, OPERATOR);
        assertFalse(r.passed, "全表清空后不得默认通过：" + r.mismatches);
        assertEquals(0, r.checkedCount, "空区间 checkedCount=0");
        assertTrue(r.mismatches.stream().anyMatch(m -> m.contains("空链")),
                "差异清单须含「空链」：" + r.mismatches);
        assertEquals(1, alarms.size(), "未通过须告警（含 checked=0 场景）");

        AuditLog failed = logDao.findBySeqInChain(1L);
        assertNotNull(failed, "未通过须落审计记录（即使 checked=0）");
        assertEquals(AuditLogService.ACTION_VERIFY, failed.actionType);
        assertTrue(failed.queryCondition.contains("FAILED"), "记录须标明 FAILED：" + failed.queryCondition);
        assertTrue(failed.queryCondition.contains("checked=0"), "记录须含 checked=0：" + failed.queryCondition);

        BizException ex = assertThrows(BizException.class, () -> service.assertChainIntact(OPERATOR));
        assertEquals(ErrorCode.AUDIT_CHAIN_TAMPERED, ex.errorCode(), "须以 10018 阻断导出");
    }

    // ------------------------------------------------------------ 用例 2：链尾截断

    @Test
    @DisplayName("截断链尾（删 seq>=3）⇒ 未通过（链尾截断）+ 10018 阻断导出")
    void tailTruncationDetected() throws Exception {
        appendThree();
        assertNotNull(service.generateChecksumBatch(100, OPERATOR), "前置：批次 1 覆盖 seq 1..3");

        int deleted = T069AuditTamperSeed.deleteAuditLogsFromSeq(engine, 3L);
        assertEquals(1, deleted, "前置：仅 seq=3 被删");

        AuditLogService.VerifyResult r = service.verify(1L, 0L, OPERATOR);
        assertFalse(r.passed, "截断链尾后不得通过：" + r.mismatches);
        assertTrue(r.mismatches.stream().anyMatch(m -> m.contains("链尾截断")),
                "差异清单须含「链尾截断」：" + r.mismatches);
        assertTrue(alarms.stream().anyMatch(m -> m.contains("未通过")), "未通过须告警");

        BizException ex = assertThrows(BizException.class, () -> service.assertChainIntact(OPERATOR));
        assertEquals(ErrorCode.AUDIT_CHAIN_TAMPERED, ex.errorCode(), "须以 10018 阻断导出");
    }

    // ------------------------------------------------------------ 用例 3/4：不误报

    @Test
    @DisplayName("真空白（无日志行且无批次）⇒ 通过且 checked=0，不误报")
    void emptyChainWithoutBatchesPasses() {
        AuditLogService.VerifyResult r = service.verify(1L, 0L, OPERATOR);
        assertTrue(r.passed, "空库无批次属正常空态：" + r.mismatches);
        assertEquals(0, r.checkedCount);
        assertEquals(0, r.verifiedBatches);
        assertTrue(alarms.isEmpty(), "通过时不应告警");
        service.assertChainIntact(OPERATOR);
    }

    @Test
    @DisplayName("批次后新增行（max > batchEnd）⇒ 通过，不误报截断")
    void rowsAppendedAfterBatchPass() {
        appendThree();
        assertNotNull(service.generateChecksumBatch(100, OPERATOR));

        append(AuditLogService.ACTION_EXPORT, 4);   // 批次之后新增第 4 行

        AuditLogService.VerifyResult r = service.verify(1L, 0L, OPERATOR);
        assertTrue(r.passed, "max(4) > batchEnd(3) 属正常增量：" + r.mismatches);
        assertEquals(4, r.checkedCount);
        assertEquals(1, r.verifiedBatches, "批次 1 仍被完整覆盖并比对");
        assertTrue(alarms.isEmpty(), "通过时不应告警");
        service.assertChainIntact(OPERATOR);
    }

    // ------------------------------------------------------------ 夹具

    private void appendThree() {
        append(AuditLogService.ACTION_QUERY, 1);
        append(AuditLogService.ACTION_EXPORT, 2);
        append(AuditLogService.ACTION_EXPORT, 3);
    }

    private AuditLog append(String action, int resultCount) {
        AuditLog log = new AuditLog();
        log.subAccountId = SUB;
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
