package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SettleCheckSignatureDao;
import com.hedgeguard.dao.SettleCheckTaskDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-048} 退回修复回归测试（{@code TE-T048-001/002/003}）。
 *
 * <p>产出方原单测用「回滚为 no-op」的假事务 ⇒ 掩盖真实事务语义。本用例以<b>连接级 commit/rollback
 * 真实生效</b>的内存库模型复现真实 {@link TxTemplate} 行为：写经 DAO 落到内存库，同一事务内抛
 * {@code RuntimeException} 时回滚丢弃本事务全部写。据此断言：
 * <ul>
 *   <li>{@code TE-T048-001}：越权/代签拒绝后 {@code t_unauthorized_attempt} 与 {@code REJECTED} 行须留痕；</li>
 *   <li>{@code TE-T048-002}：快照变更后任务须回 {@code PENDING_SIGN} 且以新快照为新基线；</li>
 *   <li>{@code TE-T048-003}：任务 &gt; 100 时导出须全量、不静默截断（分页感知假 DAO）。</li>
 * </ul>
 */
class SettleCheckT048bFixTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    private static final int SUB_A = 101;
    private static final long U_RISK = 11L;
    private static final long U_TRADE = 12L;
    private static final long U_TRADE_ONLY = 16L;

    // ============================== 内存库（事务感知 + 分页感知） ==============================

    static final class Store {
        final List<SettleCheckTask> tasks = new ArrayList<>();
        final List<SettleCheckSignature> sigs = new ArrayList<>();
        final List<UnauthorizedAttempt> attempts = new ArrayList<>();
        final Map<Connection, Object[][]> snaps = new IdentityHashMap<>();

        void begin(Connection c) {
            snaps.put(c, new Object[][]{tasks.toArray(), sigs.toArray(), attempts.toArray()});
        }

        void commit(Connection c) {
            snaps.remove(c);
        }

        void rollback(Connection c) {
            Object[][] s = snaps.remove(c);
            if (s == null) {
                return;
            }
            tasks.clear();
            sigs.clear();
            attempts.clear();
            for (Object o : s[0]) tasks.add(copyTask((SettleCheckTask) o));
            for (Object o : s[1]) sigs.add(copySig((SettleCheckSignature) o));
            for (Object o : s[2]) attempts.add(copyAttempt((UnauthorizedAttempt) o));
        }

        static SettleCheckTask copyTask(SettleCheckTask t) {
            SettleCheckTask c = new SettleCheckTask();
            c.id = t.id; c.checkType = t.checkType; c.periodKey = t.periodKey;
            c.subAccountId = t.subAccountId; c.requiredSignRoles = t.requiredSignRoles;
            c.signedRoles = t.signedRoles; c.status = t.status; c.snapshotHash = t.snapshotHash;
            c.snapshotJson = t.snapshotJson; c.checkItems = t.checkItems;
            c.checkConclusion = t.checkConclusion; c.contentJson = t.contentJson;
            c.submittedTo = t.submittedTo; c.deadlineDate = t.deadlineDate;
            c.overdueAlerted = t.overdueAlerted; c.generatedTime = t.generatedTime;
            c.completedTime = t.completedTime;
            return c;
        }

        static SettleCheckSignature copySig(SettleCheckSignature s) {
            SettleCheckSignature c = new SettleCheckSignature();
            c.id = s.id; c.subAccountId = s.subAccountId; c.checkTaskId = s.checkTaskId;
            c.signRole = s.signRole; c.signerId = s.signerId; c.signerRoleCode = s.signerRoleCode;
            c.signTime = s.signTime; c.signIp = s.signIp; c.snapshotHash = s.snapshotHash;
            c.signResult = s.signResult; c.signOpinion = s.signOpinion;
            c.proxyRejected = s.proxyRejected; c.manageRoleTradeBlocked = s.manageRoleTradeBlocked;
            return c;
        }

        static UnauthorizedAttempt copyAttempt(UnauthorizedAttempt a) {
            UnauthorizedAttempt c = new UnauthorizedAttempt();
            c.id = a.id; c.operatorId = a.operatorId; c.attemptType = a.attemptType;
            c.attemptDesc = a.attemptDesc; c.targetResource = a.targetResource;
            c.subAccountId = a.subAccountId; c.rejectReason = a.rejectReason;
            c.rejectCode = a.rejectCode; c.attemptTime = a.attemptTime; c.clientIp = a.clientIp;
            return c;
        }
    }

    static final class TaskDao implements SettleCheckTaskDao {
        final Store s; long seq = 1;
        TaskDao(Store s) { this.s = s; }
        public int insert(SettleCheckTask e, long op) { e.id = seq++; s.tasks.add(Store.copyTask(e)); return 1; }
        public int updateById(SettleCheckTask e, long op) {
            for (int i = 0; i < s.tasks.size(); i++) {
                if (s.tasks.get(i).id == e.id) { s.tasks.set(i, Store.copyTask(e)); return 1; }
            }
            return 0;
        }
        public SettleCheckTask findById(Long id) {
            for (SettleCheckTask t : s.tasks) if (t.id == id) return Store.copyTask(t);
            return null;
        }
        public SettleCheckTask findByPeriod(String ct, String pk, int sub) {
            for (SettleCheckTask t : s.tasks)
                if (ct.equals(t.checkType) && pk.equals(t.periodKey)
                        && t.subAccountId != null && t.subAccountId == sub) return Store.copyTask(t);
            return null;
        }
        public List<SettleCheckTask> findOverdue(LocalDate asOf) { return new ArrayList<>(); }
        /** 分页感知（TE-T048-003：旧实现仅取第 1 页 ⇒ 本假 DAO 须按 page/size 切片方可暴露截断）。 */
        public PageResult<SettleCheckTask> findPage(PageQuery q, String w, List<Object> a) {
            List<SettleCheckTask> all = new ArrayList<>();
            for (SettleCheckTask t : s.tasks) all.add(Store.copyTask(t));
            int from = (q.page() - 1) * q.size();
            int to = Math.min(from + q.size(), all.size());
            List<SettleCheckTask> slice = from >= all.size()
                    ? new ArrayList<>() : new ArrayList<>(all.subList(from, to));
            return new PageResult<>(slice, all.size(), q.page(), q.size());
        }
        public long count(String w, List<Object> a) { return s.tasks.size(); }
    }

    static final class SigDao implements SettleCheckSignatureDao {
        final Store s; long seq = 1;
        SigDao(Store s) { this.s = s; }
        public int insert(SettleCheckSignature e, long op) { e.id = seq++; s.sigs.add(Store.copySig(e)); return 1; }
        public int updateById(SettleCheckSignature e, long op) {
            for (int i = 0; i < s.sigs.size(); i++) {
                if (s.sigs.get(i).id == e.id) { s.sigs.set(i, Store.copySig(e)); return 1; }
            }
            return 0;
        }
        public SettleCheckSignature findById(Long id) {
            for (SettleCheckSignature x : s.sigs) if (x.id == id) return Store.copySig(x);
            return null;
        }
        public SettleCheckSignature findByTaskAndRole(long tid, String role) {
            for (SettleCheckSignature x : s.sigs)
                if (x.checkTaskId != null && x.checkTaskId == tid && role.equals(x.signRole))
                    return Store.copySig(x);
            return null;
        }
        public List<SettleCheckSignature> findByTask(long tid) {
            List<SettleCheckSignature> out = new ArrayList<>();
            for (SettleCheckSignature x : s.sigs)
                if (x.checkTaskId != null && x.checkTaskId == tid) out.add(Store.copySig(x));
            return out;
        }
        public PageResult<SettleCheckSignature> findPage(PageQuery q, String w, List<Object> a) {
            throw new UnsupportedOperationException();
        }
        public long count(String w, List<Object> a) { return s.sigs.size(); }
    }

    static final class AttemptDao implements UnauthorizedAttemptDao {
        final Store s; long seq = 1;
        AttemptDao(Store s) { this.s = s; }
        public int insert(UnauthorizedAttempt e, long op) { e.id = seq++; s.attempts.add(Store.copyAttempt(e)); return 1; }
        public int updateById(UnauthorizedAttempt e, long op) { throw new UnsupportedOperationException(); }
        public UnauthorizedAttempt findById(Long id) { throw new UnsupportedOperationException(); }
        public List<UnauthorizedAttempt> findByOperatorSince(long op, LocalDateTime f) { throw new UnsupportedOperationException(); }
        public long countByTypeBetween(String t, LocalDateTime f, LocalDateTime to) { throw new UnsupportedOperationException(); }
        public PageResult<UnauthorizedAttempt> findPage(PageQuery q, String w, List<Object> a) { throw new UnsupportedOperationException(); }
        public long count(String w, List<Object> a) { return s.attempts.size(); }
    }

    // ============================== 事务感知连接 ==============================

    private Store store;
    private TxTemplate tx;
    private TaskDao taskDao;
    private SigDao sigDao;
    private AttemptDao attemptDao;
    private SettleCheckService service;
    private String currentSnapshot = "H1";

    private ConnectionFactory factory() {
        return () -> {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setAutoCommit":
                        if (args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) {
                            store.begin((Connection) proxy);
                        }
                        return null;
                    case "commit": store.commit((Connection) proxy); return null;
                    case "rollback": store.rollback((Connection) proxy); return null;
                    case "close": return null;
                    case "setReadOnly":
                    case "setTransactionIsolation": return null;
                    case "isClosed": return false;
                    case "isValid": return true;
                    case "getAutoCommit": return true;
                    case "isReadOnly": return false;
                    case "getTransactionIsolation": return Connection.TRANSACTION_READ_COMMITTED;
                    case "prepareStatement":
                    case "prepareCall":
                    case "createStatement":
                        throw new SQLException("服务层单测不应直连 JDBC");
                    case "toString": return "RollbackAwareConnection(T048b)";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default:
                        Class<?> rt = method.getReturnType();
                        if (!rt.isPrimitive()) return null;
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == double.class) return 0d;
                        if (rt == float.class) return 0f;
                        if (rt == char.class) return (char) 0;
                        return null;
                }
            };
            return (Connection) Proxy.newProxyInstance(SettleCheckT048bFixTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, h);
        };
    }

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        store = new Store();
        tx = new TxTemplate(new DataSourcePool(factory(), new PoolConfig(0, 4, 500, 2)));
        taskDao = new TaskDao(store);
        sigDao = new SigDao(store);
        attemptDao = new AttemptDao(store);
        Map<Long, List<String>> roles = new LinkedHashMap<>();
        roles.put(U_RISK, Arrays.asList("FIN_RISK"));
        roles.put(U_TRADE, Arrays.asList("TRADER"));
        roles.put(U_TRADE_ONLY, Arrays.asList("TRADER"));
        service = new SettleCheckService(tx, u -> Arrays.asList(SUB_A),
                u -> roles.getOrDefault(u, Arrays.asList()), u -> false,
                k -> null, (t, p, s) -> currentSnapshot, taskDao, sigDao, attemptDao);
    }

    @AfterEach
    void tearDown() { Clocks.reset(); }

    private void seedTask(long id, String status, String signed, String snap) {
        SettleCheckTask t = new SettleCheckTask();
        t.id = id; t.checkType = "MONTHLY"; t.periodKey = "2026-09"; t.subAccountId = SUB_A;
        t.requiredSignRoles = "RISK,TRADE,FINANCE"; t.signedRoles = signed; t.status = status;
        t.snapshotHash = snap; t.deadlineDate = LocalDate.of(2026, 10, 30); t.overdueAlerted = "N";
        t.generatedTime = Clocks.now();
        store.tasks.add(t);
        taskDao.seq = Math.max(taskDao.seq, id + 1);
    }

    private SettleCheckService.SignCheckInput signIn(long id, String role, String snap) {
        SettleCheckService.SignCheckInput in = new SettleCheckService.SignCheckInput();
        in.taskId = id; in.signRole = role; in.snapshotHash = snap;
        return in;
    }

    // ================================================================ 对照

    @Test
    @DisplayName("对照：合法签署 ⇒ 事务提交、签署记录与任务状态持久化（证明探针 commit 通路有效）")
    void successfulSignPersists() {
        seedTask(1, "PENDING_SIGN", "", "H1");
        service.sign(signIn(1, "RISK", "H1"), U_RISK, "10.0.0.1");
        assertEquals(1, store.sigs.size(), "签署记录须持久化");
        assertEquals("PARTIAL_SIGNED", store.tasks.get(0).status);
    }

    // ================================================================ TE-T048-001

    @Test
    @DisplayName("TE-T048-001：代签被拒 ⇒ t_unauthorized_attempt 与 REJECTED 行须独立提交、不随回滚丢失")
    void rejectAttemptSurvivesRejection() {
        seedTask(1, "PENDING_SIGN", "", "H1");
        assertThrows(BizException.class, () -> service.sign(signIn(1, "RISK", "H1"), U_TRADE_ONLY, "9.9.9.9"));
        assertEquals(1, store.attempts.size(), "拒绝后 t_unauthorized_attempt 须留痕（原缺陷：回滚致 0）");
        SettleCheckSignature rej = sigDao.findByTaskAndRole(1, "RISK");
        assertNotNull(rej, "拒绝后须落 proxy_rejected='Y' 的 REJECTED 行");
        assertEquals("Y", rej.proxyRejected);
        assertEquals("REJECTED", rej.signResult);
    }

    // ================================================================ TE-T048-002

    @Test
    @DisplayName("TE-T048-002：快照变更 ⇒ 任务回 PENDING_SIGN + 新基线须独立提交（原缺陷：回滚致永久无法重签）")
    void snapshotResetSurvives() {
        seedTask(1, "PARTIAL_SIGNED", "RISK", "H1");
        SettleCheckSignature s = new SettleCheckSignature();
        s.id = 1; s.subAccountId = SUB_A; s.checkTaskId = 1L; s.signRole = "RISK"; s.signerId = U_RISK;
        s.signerRoleCode = "FIN_RISK"; s.snapshotHash = "H1"; s.signResult = "SIGNED"; s.proxyRejected = "N";
        store.sigs.add(s);
        currentSnapshot = "H2";
        assertThrows(BizException.class, () -> service.sign(signIn(1, "TRADE", "H2"), U_TRADE, "x"));
        SettleCheckTask after = store.tasks.get(0);
        assertEquals("PENDING_SIGN", after.status, "快照变更后任务须回 PENDING_SIGN");
        assertEquals("H2", after.snapshotHash, "须以新快照为新基线");
        assertEquals("", after.signedRoles, "重置须清空已签角色");
    }

    // ================================================================ TE-T048-003

    @Test
    @DisplayName("TE-T048-003：任务 > 100 ⇒ 导出全量、无静默截断")
    void exportNotTruncatedOver100() {
        for (int i = 1; i <= 101; i++) {
            seedTask(i, "PENDING_SIGN", "", "H" + i);
        }
        SettleCheckService.CheckExport out = service.export(null, U_RISK);
        assertEquals(101, out.rows.size(),
                "101 条任务须全量导出（原缺陷：硬编码 size=100 仅第 1 页 ⇒ 截断为 100）");
        assertTrue(out.rows.get(0).size() == out.columns.size(), "行宽须与表头一致");
    }
}
