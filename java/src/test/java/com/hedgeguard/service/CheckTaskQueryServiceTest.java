package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.dao.impl.DmSettleCheckSignatureDao;
import com.hedgeguard.dao.impl.DmSettleCheckTaskDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.CheckTaskQueryService.AnnualCoverage;
import com.hedgeguard.service.CheckTaskQueryService.CheckTaskFilter;
import com.hedgeguard.service.CheckTaskQueryService.CheckTaskResult;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-069} 核对任务与签署记录查询「真实 DAO + 内存假 JDBC 引擎」用例（禁假 DAO；只读）。
 *
 * <p>覆盖 {@code FR-AUDIT-13}：任务 + 三方签署明细（账号/角色/时间/IP/快照哈希）验收 1、
 * 逾期未签筛出（验收 2）、年度完成率与逾期清单（验收 3）、跨子账户可见（验收 5）。
 */
class CheckTaskQueryServiceTest {

    private static final int SUB = 11;
    private static final int OTHER_SUB = 12;
    private static final long OPERATOR = 100L;
    private static final long SIGNER = 201L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime TS = AS_OF.atTime(9, 0);

    private InMemoryDmEngine engine;
    private DmSettleCheckTaskDao taskDao;
    private DmSettleCheckSignatureDao signatureDao;
    private TxTemplate tx;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(AS_OF.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, OTHER_SUB, "SA-B", "商品子账户B");
        M1E2eSeed.user(engine, OPERATOR, "auditor", "审计岗", "NP-100");
        M1E2eSeed.user(engine, SIGNER, "risk01", "风控岗", "NP-201");
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        taskDao = new DmSettleCheckTaskDao(tx);
        signatureDao = new DmSettleCheckSignatureDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("查询月度核对任务：可见三方签署明细（账号/角色/时间/IP/快照哈希）")
    void queryMonthlyWithSignatures() {
        SettleCheckTask task = task("MONTHLY", "2026-09", SUB, "PENDING_SIGN", AS_OF.plusDays(5));
        seed(task);
        seedSignature(task.id, "RISK");
        seedSignature(task.id, "TRADE");
        seedSignature(task.id, "FINANCE");

        CheckTaskQueryService service = service(crossReadonly());
        CheckTaskResult r = service.query(null, "1", "20", OPERATOR);

        assertEquals(1, r.tasks.size());
        assertEquals(3, r.tasks.get(0).signatures.size());
        SettleCheckSignature sig = r.tasks.get(0).signatures.get(0);
        assertNotNull(sig.signerId);
        assertNotNull(sig.signRole);
        assertNotNull(sig.signTime);
        assertEquals("10.0.0.1", sig.signIp);
        assertEquals("SNAP-1", sig.snapshotHash);
    }

    @Test
    @DisplayName("逾期未签可单独筛出（FR-AUDIT-13 验收 2）")
    void overdueFilter() {
        seed(task("MONTHLY", "2026-08", SUB, "PENDING_SIGN", AS_OF.minusDays(3)));
        seed(task("MONTHLY", "2026-09", SUB, "COMPLETED", AS_OF.plusDays(3)));

        CheckTaskQueryService service = service(crossReadonly());
        CheckTaskFilter f = new CheckTaskFilter();
        f.overdueOnly = Boolean.TRUE;
        f.asOf = AS_OF;
        CheckTaskResult r = service.query(f, "1", "20", OPERATOR);

        assertEquals(1, r.tasks.size(), "仅逾期任务");
        assertEquals("2026-08", r.tasks.get(0).task.periodKey);
        assertEquals(1, r.overdue.size());
        assertEquals(1, service.overdue(AS_OF, OPERATOR).size());
    }

    @Test
    @DisplayName("年度全覆盖：各类型完成率 + 逾期清单（FR-AUDIT-13 验收 3）")
    void annualCoverage() {
        seed(task("MONTHLY", "2026-01", SUB, "COMPLETED", AS_OF.minusDays(200)));
        seed(task("MONTHLY", "2026-02", SUB, "PENDING_SIGN", AS_OF.minusDays(100)));
        seed(task("QUARTERLY", "2026-Q1", SUB, "COMPLETED", AS_OF.minusDays(90)));
        seed(task("ANNUAL", "2026", SUB, "PENDING_SIGN", AS_OF.plusDays(30)));

        CheckTaskQueryService service = service(crossReadonly());
        AnnualCoverage c = service.annualCoverage("2026", AS_OF, OPERATOR);

        assertEquals(2, c.byType.get("MONTHLY").total);
        assertEquals(1, c.byType.get("MONTHLY").completed);
        assertEquals(1, c.byType.get("MONTHLY").overdue);
        assertEquals(1, c.byType.get("QUARTERLY").completed);
        assertEquals(1, c.byType.get("ANNUAL").total);
        assertEquals(0, c.byType.get("ANNUAL").overdue, "未逾期不计入逾期清单");
        assertEquals(1, c.overdue.size());
        assertEquals("2026-02", c.overdue.get(0).periodKey);
    }

    @Test
    @DisplayName("软隔离：跨账户可见全部，有限授权仅见本子账户（FR-AUDIT-13 验收 5）")
    void subAccountScope() {
        seed(task("MONTHLY", "2026-09", SUB, "COMPLETED", AS_OF));
        seed(task("MONTHLY", "2026-09", OTHER_SUB, "COMPLETED", AS_OF));

        CheckTaskResult cross = service(crossReadonly()).query(null, "1", "20", OPERATOR);
        assertEquals(2, cross.tasks.size());
        assertTrue(cross.crossSubAccount);

        CheckTaskResult limited = service(uid -> Collections.singletonList(SUB))
                .query(null, "1", "20", OPERATOR);
        assertEquals(1, limited.tasks.size());
        assertFalse(limited.crossSubAccount);
    }

    // ------------------------------------------------------------ 夹具

    private CheckTaskQueryService service(SubAccountVisibility visibility) {
        return new CheckTaskQueryService(taskDao, signatureDao, visibility);
    }

    private SettleCheckTask task(String type, String period, int sub, String status, LocalDate deadline) {
        SettleCheckTask t = new SettleCheckTask();
        t.checkType = type;
        t.periodKey = period;
        t.subAccountId = sub;
        t.requiredSignRoles = "RISK,TRADE,FINANCE";
        t.signedRoles = "COMPLETED".equals(status) ? "RISK,TRADE,FINANCE" : "";
        t.status = status;
        t.snapshotHash = "SNAP-1";
        t.checkItems = "持仓/资金/盈亏";
        t.deadlineDate = deadline;
        t.overdueAlerted = "N";
        t.generatedTime = TS;
        if ("COMPLETED".equals(status)) {
            t.completedTime = TS;
        }
        return t;
    }

    private void seed(SettleCheckTask task) {
        tx.inTransaction(connection -> taskDao.insert(task, OPERATOR));
    }

    private void seedSignature(long taskId, String role) {
        SettleCheckSignature sig = new SettleCheckSignature();
        sig.subAccountId = SUB;
        sig.checkTaskId = taskId;
        sig.signRole = role;
        sig.signerId = SIGNER;
        sig.signerRoleCode = role;
        sig.signTime = TS;
        sig.signIp = "10.0.0.1";
        sig.snapshotHash = "SNAP-1";
        sig.signResult = "SIGNED";
        sig.proxyRejected = "N";
        sig.manageRoleTradeBlocked = "N";
        tx.inTransaction(connection -> signatureDao.insert(sig, OPERATOR));
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
