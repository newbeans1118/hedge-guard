package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-048} {@link SettleCheckService} 单测（{@code NFR-TEST-2}：不引 Mockito）。
 *
 * <p>覆盖验收 1–4 与 {@code FR-SETTLE-17} 验收 4/5/6。
 */
class SettleCheckServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    private static final int SUB_A = 101;

    private static final long U_RISK = 11L;
    private static final long U_TRADE = 12L;
    private static final long U_FINANCE = 13L;
    private static final long U_MANAGE = 14L;
    private static final long U_AUDITOR = 15L;
    private static final long U_TRADE_ONLY = 16L;
    private static final long U_MULTI = 17L;
    private static final long U_MANAGE_TRADER = 18L;

    private SettleCheckFakes.CheckTasks tasks;
    private SettleCheckFakes.CheckSignatures signatures;
    private SettleCheckFakes.Attempts attempts;
    private SettleCheckService service;
    private final Map<Long, List<String>> roles = new HashMap<>();
    private final Map<Long, List<Integer>> visible = new HashMap<>();
    private final Map<String, String> config = new HashMap<>();
    private String currentSnapshot = "H1";

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        tasks = new SettleCheckFakes.CheckTasks();
        signatures = new SettleCheckFakes.CheckSignatures();
        attempts = new SettleCheckFakes.Attempts();
        roles.put(U_RISK, Arrays.asList("FIN_RISK"));
        roles.put(U_TRADE, Arrays.asList("TRADER"));
        roles.put(U_FINANCE, Arrays.asList("SETTLER"));
        roles.put(U_MANAGE, Arrays.asList("APPROVER"));
        roles.put(U_AUDITOR, Arrays.asList("AUDITOR"));
        roles.put(U_TRADE_ONLY, Arrays.asList("TRADER"));
        roles.put(U_MULTI, Arrays.asList("FIN_RISK", "TRADER"));
        roles.put(U_MANAGE_TRADER, Arrays.asList("APPROVER", "TRADER"));
        visible.put(U_RISK, Arrays.asList(SUB_A));
        visible.put(U_TRADE, Arrays.asList(SUB_A));
        visible.put(U_FINANCE, Arrays.asList(SUB_A));
        visible.put(U_MANAGE, Arrays.asList(0));
        visible.put(U_AUDITOR, Arrays.asList(0));
        config.put(SettleCheckService.KEY_SIGN_ROLES, "RISK,TRADE,FINANCE");
        config.put(SettleCheckService.KEY_DEADLINE_DAYS, "30");
        SubAccountVisibility vis = userId -> visible.getOrDefault(userId, Arrays.asList(SUB_A));
        SettleCheckService.RoleLookup rl = userId -> roles.getOrDefault(userId, Arrays.asList());
        SettleCheckService.ConfigReader cr = config::get;
        SettleCheckService.SnapshotProvider sp = (t, p, s) -> currentSnapshot;
        service = new SettleCheckService(FakeTx.newTemplate(), vis, rl,
                userId -> userId == U_MANAGE_TRADER, cr, sp, tasks, signatures, attempts);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    private SettleCheckTask generateMonthly() {
        SettleCheckService.GenerateCheckInput in = new SettleCheckService.GenerateCheckInput();
        in.checkType = SettleCheckService.CHECK_MONTHLY;
        in.periodKey = "2026-09";
        in.subAccountId = SUB_A;
        return service.generateCheckTask(in, U_RISK);
    }

    private SettleCheckService.SignCheckInput signInput(long taskId, String role) {
        SettleCheckService.SignCheckInput in = new SettleCheckService.SignCheckInput();
        in.taskId = taskId;
        in.signRole = role;
        return in;
    }

    // ================================================================ 验收 1

    @Test
    @DisplayName("验收1/FR-SETTLE-13：月度三方（RISK/TRADE/FINANCE）各自签署齐备 ⇒ 任务已完成")
    void monthlyThreePartyComplete() {
        SettleCheckTask task = generateMonthly();
        assertEquals(SettleCheckService.CHECK_MONTHLY, task.checkType);
        assertEquals("RISK,TRADE,FINANCE", task.requiredSignRoles);
        assertEquals(SettleCheckService.STATUS_PENDING_SIGN, task.status);
        assertEquals(LocalDate.of(2026, 10, 30), task.deadlineDate, "截止 = 期间结束 + 30 天");

        assertEquals(SettleCheckService.STATUS_PARTIAL_SIGNED,
                service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1").status);
        assertEquals(SettleCheckService.STATUS_PARTIAL_SIGNED,
                service.sign(signInput(task.id, "TRADE"), U_TRADE, "10.0.0.2").status);
        SettleCheckService.SignResult r = service.sign(signInput(task.id, "FINANCE"), U_FINANCE, "10.0.0.3");
        assertEquals(SettleCheckService.STATUS_COMPLETED, r.status);
        assertEquals("RISK,TRADE,FINANCE", r.signedRoles);

        List<SettleCheckSignature> sigs = signatures.findByTask(task.id);
        assertEquals(3, sigs.size(), "须落三条签署记录（FR-SETTLE-13 验收 1）");
        assertNotNull(task.completedTime, "完成时间须记录");
        // 每条签署含账号/角色/时间/IP/快照哈希（FR-SETTLE-17 验收 2）
        for (SettleCheckSignature s : sigs) {
            assertNotNull(s.signerId);
            assertNotNull(s.signerRoleCode);
            assertNotNull(s.signTime);
            assertNotNull(s.signIp);
            assertEquals("H1", s.snapshotHash);
            assertEquals(SettleCheckService.SIGN_SIGNED, s.signResult);
        }
    }

    @Test
    @DisplayName("验收1/FR-SETTLE-14：季度 风控编制 + 交易会签 → 管理层签收（签收留痕、提交对象）")
    void quarterlyManageAccept() {
        SettleCheckService.GenerateCheckInput in = new SettleCheckService.GenerateCheckInput();
        in.checkType = SettleCheckService.CHECK_QUARTERLY;
        in.periodKey = "2026-Q3";
        in.subAccountId = SUB_A;
        SettleCheckTask task = service.generateCheckTask(in, U_RISK);
        assertEquals("RISK,TRADE,MANAGE", task.requiredSignRoles);
        assertEquals(LocalDate.of(2026, 10, 30), task.deadlineDate, "Q3 结束 9/30 + 30 天");

        service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1");
        service.sign(signInput(task.id, "TRADE"), U_TRADE, "10.0.0.2");
        SettleCheckService.SignResult r = service.sign(signInput(task.id, "MANAGE"), U_MANAGE, "10.0.0.9");
        assertEquals(SettleCheckService.STATUS_COMPLETED, r.status);
        SettleCheckSignature manageSig = signatures.findByTaskAndRole(task.id, "MANAGE");
        assertEquals("Y", manageSig.manageRoleTradeBlocked, "管理层签署须留互斥核验标记");
        assertEquals("APPROVER", task.submittedTo, "签收对象 = 管理层角色");
    }

    @Test
    @DisplayName("验收1/FR-SETTLE-17：年度（AUDITOR 全覆盖）单签即完成")
    void annualAuditorCoverage() {
        SettleCheckService.GenerateCheckInput in = new SettleCheckService.GenerateCheckInput();
        in.checkType = SettleCheckService.CHECK_ANNUAL;
        in.periodKey = "2026";
        SettleCheckTask task = service.generateCheckTask(in, U_AUDITOR);
        assertEquals("AUDITOR", task.requiredSignRoles);
        assertEquals(LocalDate.of(2027, 1, 30), task.deadlineDate, "年度结束 12/31 + 30 天");
        SettleCheckService.SignResult r = service.sign(signInput(task.id, "AUDITOR"), U_AUDITOR, "10.0.0.5");
        assertEquals(SettleCheckService.STATUS_COMPLETED, r.status);
    }

    // ================================================================ 验收 2：不可代签

    @Test
    @DisplayName("验收2/DC-022②：所选角色不在应签清单 ⇒ 拒绝 + proxy_rejected='Y' + 越权留痕")
    void proxyNonRequiredRoleRejected() {
        SettleCheckTask task = generateMonthly();
        BizException e = assertThrows(BizException.class,
                () -> service.sign(signInput(task.id, "AUDITOR"), U_AUDITOR, "10.0.0.5"));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode());
        assertEquals(1, attempts.rows.size(), "须记 t_unauthorized_attempt");
        SettleCheckSignature rej = signatures.findByTaskAndRole(task.id, "AUDITOR");
        assertNotNull(rej);
        assertEquals("Y", rej.proxyRejected);
        assertEquals(SettleCheckService.SIGN_REJECTED, rej.signResult);
        assertEquals(SettleCheckService.STATUS_PENDING_SIGN, task.status, "拒绝不得改变任务状态");
    }

    @Test
    @DisplayName("验收2/DC-022①：应签角色在清单内但签署人不持有 ⇒ 拒绝（不猜测不默认）")
    void proxyMissingRoleRejected() {
        SettleCheckTask task = generateMonthly();
        BizException e = assertThrows(BizException.class,
                () -> service.sign(signInput(task.id, "RISK"), U_TRADE_ONLY, "10.0.0.6"));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode());
        assertEquals(1, attempts.rows.size());
    }

    @Test
    @DisplayName("验收2：持多角色账号所选角色不在清单 ⇒ 拒绝（不默认取任一角色）")
    void multiRoleNoGuessRejected() {
        SettleCheckTask task = generateMonthly();
        BizException e = assertThrows(BizException.class,
                () -> service.sign(signInput(task.id, "AUDITOR"), U_MULTI, "10.0.0.7"));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode());
        long signed = signatures.findByTask(task.id).stream()
                .filter(s -> SettleCheckService.SIGN_SIGNED.equals(s.signResult)).count();
        assertEquals(0, signed, "拒绝不落有效签署");
    }

    @Test
    @DisplayName("验收2/uk_check_signature_task_role：同任务同角色二次签署 ⇒ 唯一冲突")
    void duplicateSignRejected() {
        SettleCheckTask task = generateMonthly();
        service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1");
        BizException e = assertThrows(BizException.class,
                () -> service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1"));
        assertEquals(ErrorCode.UNIQUE_CONFLICT, e.errorCode());
    }

    @Test
    @DisplayName("验收1/FR-SETTLE-13 验收2：同一账号不得充当多个参与方（岗位分离）")
    void sameAccountTwoRolesRejected() {
        SettleCheckTask task = generateMonthly();
        service.sign(signInput(task.id, "RISK"), U_MULTI, "10.0.0.7");
        BizException e = assertThrows(BizException.class,
                () -> service.sign(signInput(task.id, "TRADE"), U_MULTI, "10.0.0.7"));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
    }

    @Test
    @DisplayName("验收1/FR-SETTLE-14：管理层签收须在风控编制 + 交易会签之后")
    void manageBeforeCountersignRejected() {
        SettleCheckService.GenerateCheckInput in = new SettleCheckService.GenerateCheckInput();
        in.checkType = SettleCheckService.CHECK_QUARTERLY;
        in.periodKey = "2026-Q3";
        in.subAccountId = SUB_A;
        SettleCheckTask task = service.generateCheckTask(in, U_RISK);
        BizException e = assertThrows(BizException.class,
                () -> service.sign(signInput(task.id, "MANAGE"), U_MANAGE, "10.0.0.9"));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode());
    }

    @Test
    @DisplayName("FR-SETTLE-17 验收6：管理层仍具交易岗能力 ⇒ 签收被拒（签署权与交易权互斥）")
    void manageWithTradingCapabilityRejected() {
        SettleCheckService.GenerateCheckInput in = new SettleCheckService.GenerateCheckInput();
        in.checkType = SettleCheckService.CHECK_QUARTERLY;
        in.periodKey = "2026-Q3";
        in.subAccountId = SUB_A;
        SettleCheckTask task = service.generateCheckTask(in, U_RISK);
        service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1");
        service.sign(signInput(task.id, "TRADE"), U_TRADE, "10.0.0.2");
        BizException e = assertThrows(BizException.class,
                () -> service.sign(signInput(task.id, "MANAGE"), U_MANAGE_TRADER, "10.0.0.9"));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
    }

    // ================================================================ FR-SETTLE-17 验收4：快照

    @Test
    @DisplayName("FR-SETTLE-17 验收4：签署后快照变更 ⇒ 任务回到待签署，须以新快照重签")
    void snapshotChangeResetsTask() {
        SettleCheckTask task = generateMonthly();
        service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1");
        currentSnapshot = "H2";
        BizException e = assertThrows(BizException.class,
                () -> service.sign(signInput(task.id, "TRADE"), U_TRADE, "10.0.0.2"));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode());
        assertEquals(SettleCheckService.STATUS_PENDING_SIGN, task.status, "任务回到待签署");
        assertEquals("", task.signedRoles, "旧签署不再计入");
        assertEquals("H2", task.snapshotHash, "以新快照为新基线");

        // 重签：同角色以新快照重签（替换旧快照行），随后三方齐备
        service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1");
        service.sign(signInput(task.id, "TRADE"), U_TRADE, "10.0.0.2");
        SettleCheckService.SignResult r = service.sign(signInput(task.id, "FINANCE"), U_FINANCE, "10.0.0.3");
        assertEquals(SettleCheckService.STATUS_COMPLETED, r.status);
        assertEquals(3, signatures.findByTask(task.id).size(), "重签替换而非新增");
    }

    // ================================================================ 验收 3：截止 / 逾期 / 导出

    @Test
    @DisplayName("验收3/CFG-14：截止天数取自 settle.sign.deadline_days")
    void deadlineFromConfig() {
        config.put(SettleCheckService.KEY_DEADLINE_DAYS, "15");
        SettleCheckTask task = generateMonthly();
        assertEquals(LocalDate.of(2026, 10, 15), task.deadlineDate);
        config.put(SettleCheckService.KEY_DEADLINE_DAYS, "0");
        SettleCheckService.GenerateCheckInput other = new SettleCheckService.GenerateCheckInput();
        other.checkType = SettleCheckService.CHECK_MONTHLY;
        other.periodKey = "2026-06";
        other.subAccountId = SUB_A;
        assertThrows(BizException.class, () -> service.generateCheckTask(other, U_RISK),
                "越界（<1）须拒绝");
    }

    @Test
    @DisplayName("验收3/R-SETTLE-11：逾期未签 ⇒ 置 OVERDUE + 告警留痕（二次巡检不重复）")
    void overdueScanAlerts() {
        SettleCheckTask task = generateMonthly();
        List<SettleCheckTask> newly = service.scanOverdue(LocalDate.of(2026, 11, 1), U_RISK);
        assertEquals(1, newly.size());
        assertEquals(SettleCheckService.STATUS_OVERDUE, task.status);
        assertEquals("Y", task.overdueAlerted, "逾期须告警留痕，不得静默缺失");
        assertEquals(0, service.scanOverdue(LocalDate.of(2026, 11, 2), U_RISK).size(),
                "已告警任务不重复计");
    }

    @Test
    @DisplayName("验收3：核对记录与签署记录可导出（含账号/角色/时间/IP/快照哈希）")
    void exportIncludesSignatures() {
        SettleCheckTask task = generateMonthly();
        service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1");
        SettleCheckService.QueryCheckInput q = new SettleCheckService.QueryCheckInput();
        q.periodKey = "2026-09";
        q.checkType = "MONTHLY";
        SettleCheckService.CheckExport ex = service.export(q, U_AUDITOR);
        assertTrue(ex.columns.contains("signerId"));
        assertTrue(ex.columns.contains("signRole"));
        assertTrue(ex.columns.contains("signIp"));
        assertTrue(ex.columns.contains("signatureSnapshotHash"));
        assertEquals(1, ex.rows.size());
        String csv = ex.toCsv();
        assertTrue(csv.contains("RISK"));
        assertTrue(csv.contains("10.0.0.1"));
        assertEquals(U_AUDITOR, ex.exportedBy);
    }

    // ================================================================ 验收 4：审计只读

    @Test
    @DisplayName("验收4/FR-AUDIT-13：审计席（跨账户只读）可只读查询核对任务与签署记录")
    void auditReadsAcrossSubAccounts() {
        SettleCheckTask task = generateMonthly();
        service.sign(signInput(task.id, "RISK"), U_RISK, "10.0.0.1");
        SettleCheckService.QueryCheckInput q = new SettleCheckService.QueryCheckInput();
        q.periodKey = "2026-09";
        PageResult<SettleCheckService.CheckTaskView> page = service.query(q,
                PageQuery.of("1", "20", null, "generated_time", "desc"), U_AUDITOR);
        assertEquals(1, page.total());
        assertEquals(1, page.items().get(0).signatures.size());
    }

    @Test
    @DisplayName("验收4/NFR-SEC-7：无可见集 ⇒ 查询为空（fail-closed）；仅可见自身子账户者不得越界")
    void auditVisibilityClosed() {
        generateMonthly();
        SettleCheckService.QueryCheckInput q = new SettleCheckService.QueryCheckInput();
        PageQuery pq = PageQuery.of("1", "20", null, "generated_time", "desc");
        visible.put(999L, Arrays.asList(888));
        assertEquals(0, service.query(q, pq, 999L).total(), "可见集不含任务子账户 ⇒ 不可见");
        visible.put(999L, Arrays.asList());
        assertEquals(0, service.query(q, pq, 999L).total(), "空可见集 ⇒ 恒空（fail-closed）");
    }

    // ================================================================ 幂等 / 生成校验

    @Test
    @DisplayName("生成幂等：同一（类型, 期间, 子账户）重复生成返回既有任务")
    void generateIdempotent() {
        SettleCheckTask t1 = generateMonthly();
        SettleCheckTask t2 = generateMonthly();
        assertSame(t1, t2);
        assertEquals(1, tasks.rows.size());
    }

    @Test
    @DisplayName("参数校验：非法类型 / 期间 / 缺失快照 ⇒ 拒绝")
    void generateValidation() {
        SettleCheckService.GenerateCheckInput bad = new SettleCheckService.GenerateCheckInput();
        bad.checkType = "WEEKLY";
        bad.periodKey = "2026-W1";
        assertThrows(BizException.class, () -> service.generateCheckTask(bad, U_RISK));

        SettleCheckService serviceNoSnap = new SettleCheckService(FakeTx.newTemplate(),
                u -> Arrays.asList(SUB_A), u -> roles.getOrDefault(u, Arrays.asList()),
                u -> false, config::get, null, tasks, signatures, attempts);
        SettleCheckService.GenerateCheckInput noSnap = new SettleCheckService.GenerateCheckInput();
        noSnap.checkType = "MONTHLY";
        noSnap.periodKey = "2026-08";
        noSnap.subAccountId = SUB_A;
        assertThrows(BizException.class, () -> serviceNoSnap.generateCheckTask(noSnap, U_RISK));
    }

    @Test
    @DisplayName("签署定位：taskId 为空时按（类型, 期间, 子账户）生成并签署（POST=发起并签署）")
    void signOrGenerateByPeriod() {
        SettleCheckService.SignCheckInput in = new SettleCheckService.SignCheckInput();
        in.checkType = "MONTHLY";
        in.periodKey = "2026-07";
        in.subAccountId = SUB_A;
        in.signRole = "RISK";
        SettleCheckService.SignResult r = service.sign(in, U_RISK, "10.0.0.1");
        assertEquals(SettleCheckService.STATUS_PARTIAL_SIGNED, r.status);
        assertEquals(1, tasks.rows.size());
        assertNull(tasks.findById(r.taskId).completedTime);
        assertFalse(tasks.rows.isEmpty());
    }
}
