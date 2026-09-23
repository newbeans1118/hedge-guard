package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.LoginLogDao;
import com.hedgeguard.dao.LoginSessionDao;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.PermissionChangeLogDao;
import com.hedgeguard.dao.PositionConflictDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.LoginLog;
import com.hedgeguard.dao.entity.LoginSession;
import com.hedgeguard.dao.entity.PasswordHistory;
import com.hedgeguard.dao.entity.PermissionChangeLog;
import com.hedgeguard.dao.entity.PositionConflict;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthService} 单元测试（{@code T-010}：登录 / 会话 / 岗位分离 / 负责人禁操盘 / 子账户软隔离）。
 *
 * <p>时钟经 {@link Clocks#set} 注入（{@code NFR-TEST-4}），故"空闲 30 分钟超时"与
 * "失败 5 次锁 5 分钟"两条时间相关规则可确定性复现。
 */
class AuthServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");   // 北京时间 10:00

    private FakeUserAccountDao userDao;
    private FakeRoleDao roleDao;
    private FakeUserRoleDao userRoleDao;
    private FakePositionConflictDao conflictDao;
    private FakeLoginSessionDao sessionDao;
    private FakeLoginLogDao loginLogDao;
    private FakePasswordHistoryDao historyDao;
    private FakeUserSubAccountPermDao permDao;
    private FakeUnauthorizedAttemptDao attemptDao;
    private FakePermissionChangeLogDao changeLogDao;
    private SessionStore store;
    private AuthService service;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        userDao = new FakeUserAccountDao();
        roleDao = new FakeRoleDao();
        userRoleDao = new FakeUserRoleDao();
        conflictDao = new FakePositionConflictDao();
        sessionDao = new FakeLoginSessionDao();
        loginLogDao = new FakeLoginLogDao();
        historyDao = new FakePasswordHistoryDao();
        permDao = new FakeUserSubAccountPermDao();
        attemptDao = new FakeUnauthorizedAttemptDao();
        changeLogDao = new FakePermissionChangeLogDao();
        store = new SessionStore.InMemory();
        service = new AuthService(FakeTx.newTemplate(), userDao, roleDao, userRoleDao, conflictDao,
                sessionDao, loginLogDao, historyDao, permDao, attemptDao, store,
                null, changeLogDao, null, null);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ 登录

    @Test
    @DisplayName("登录成功：建库内会话行 + 内存会话 + 登录日志；口令不落日志")
    void loginSucceeds() {
        addUser(1L, "trader01", "ENABLED");
        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));
        userRoleDao.put(userRole(100L, 1L, 10L));

        Session session = service.login("trader01", "Hg#2026Trading01", "127.0.0.1", "JUnit");

        assertNotNull(session, "应返回会话");
        assertEquals(1L, session.userId);
        assertEquals("trader01", session.username);
        assertEquals(Clocks.now().plusMinutes(AuthService.SESSION_IDLE_MINUTES), session.expireTime,
                "过期时间 = 登录时刻 + 空闲超时（CFG-23b）");
        assertNotNull(store.find(session.sessionId), "内存会话应可查");
        assertEquals(1, sessionDao.rows.size(), "应写入 t_login_session");
        assertEquals("ACTIVE", sessionDao.rows.get(0).status);
        assertEquals(AuthService.SESSION_IDLE_MINUTES, sessionDao.rows.get(0).idleTimeoutMinutes);
        assertEquals(1, loginLogDao.rows.size(), "登录成功写 1 条登录日志");
        assertTrue(loginLogDao.rows.stream().anyMatch(l -> "LOGIN_SUCCESS".equals(l.eventType)));
        assertEquals(0, userDao.users.get(1L).failCount, "成功登录应清零失败计数");
        assertNotNull(userDao.users.get(1L).lastLoginTime, "应更新最后登录时间");
    }

    @Test
    @DisplayName("防账号枚举：用户不存在与口令错误返回同一码同一文案")
    void loginFailuresAreIndistinguishable() {
        addUser(1L, "trader01", "ENABLED");

        BizException unknown = assertThrows(BizException.class,
                () -> service.login("nobody", "Hg#2026Trading01", "127.0.0.1", "JUnit"));
        BizException wrong = assertThrows(BizException.class,
                () -> service.login("trader01", "Hg#WrongPassword01", "127.0.0.1", "JUnit"));

        assertEquals(ErrorCode.AUTH_FAILED, unknown.errorCode());
        assertEquals(ErrorCode.AUTH_FAILED, wrong.errorCode());
        assertEquals(unknown.getMessage(), wrong.getMessage(), "文案必须一致（防枚举）");
        assertEquals(1, userDao.users.get(1L).failCount, "失败计数应 +1");
        assertTrue(loginLogDao.rows.stream().anyMatch(l -> "LOGIN_FAIL".equals(l.eventType)
                && "10007".equals(l.failReasonCode)), "失败须写登录日志（不含口令）");
    }

    @Test
    @DisplayName("连续 5 次失败锁定 5 分钟（CFG-23）：第 5 次返回 10008，锁定期内再登录仍 10008")
    void fiveFailuresLockTheAccount() {
        addUser(1L, "trader01", "ENABLED");
        for (int i = 0; i < AuthService.LOGIN_FAIL_LIMIT - 1; i++) {
            assertThrows(BizException.class,
                    () -> service.login("trader01", "Hg#WrongPassword01", "127.0.0.1", "JUnit"));
        }
        BizException locked = assertThrows(BizException.class,
                () -> service.login("trader01", "Hg#WrongPassword01", "127.0.0.1", "JUnit"));
        assertEquals(ErrorCode.ACCOUNT_LOCKED, locked.errorCode(), "第 5 次失败应锁定");
        assertEquals(Clocks.now().plusMinutes(AuthService.LOGIN_LOCK_MINUTES),
                userDao.users.get(1L).lockedUntil, "锁定时长 = CFG-23 lock_minutes");

        // 即使口令正确，锁定期内也拒绝
        BizException stillLocked = assertThrows(BizException.class,
                () -> service.login("trader01", "Hg#2026Trading01", "127.0.0.1", "JUnit"));
        assertEquals(ErrorCode.ACCOUNT_LOCKED, stillLocked.errorCode());

        // 锁定到期后可用正确口令登录
        userDao.users.get(1L).lockedUntil = Clocks.now().minusMinutes(1);
        assertNotNull(service.login("trader01", "Hg#2026Trading01", "127.0.0.1", "JUnit"));
        assertEquals(0, userDao.users.get(1L).failCount, "登录成功清零计数");
        assertNull(userDao.users.get(1L).lockedUntil, "解锁");
    }

    @Test
    @DisplayName("停用账号：口令正确也拒绝，且与口令错误同码（不泄露账号状态）")
    void disabledAccountIsRejected() {
        addUser(1L, "trader01", "DISABLED");
        BizException e = assertThrows(BizException.class,
                () -> service.login("trader01", "Hg#2026Trading01", "127.0.0.1", "JUnit"));
        assertEquals(ErrorCode.AUTH_FAILED, e.errorCode());
        assertNull(store.find("anyid"));
    }

    // ------------------------------------------------------------ 会话

    @Test
    @DisplayName("会话：续期顺延过期时间；空闲超时后作废并 10006")
    void sessionIdleTimeout() {
        addUser(1L, "trader01", "ENABLED");
        Session session = service.login("trader01", "Hg#2026Trading01", "127.0.0.1", "JUnit");

        Clocks.set(Clock.fixed(T0.plusSeconds(600), Clocks.ZONE));   // +10 分钟
        Session renewed = service.touchSession(session.sessionId);
        assertNotNull(renewed);
        assertEquals(Clocks.now().plusMinutes(AuthService.SESSION_IDLE_MINUTES), renewed.expireTime,
                "滑动过期：续期应顺延");
        assertTrue(sessionDao.rows.get(0).lastActiveTime.isAfter(LocalDateTime.ofInstant(T0, Clocks.ZONE)),
                "库内会话应记录活跃时间");

        // 空闲超时：真实 SessionStore 会直接丢弃过期会话（HTTP 层据此返 10006）
        Clocks.set(Clock.fixed(T0.plusSeconds(600 + AuthService.SESSION_IDLE_MINUTES * 60 + 1),
                Clocks.ZONE));
        assertNull(store.find(session.sessionId), "过期会话不应可查");
        assertNull(service.touchSession(session.sessionId), "会话不存在时返回 null");

        // 非自清理的存储实现（如未来 DB 版）：由本服务判定并抛 10006
        SessionStore noPurge = new NonPurgingStore();
        Session stale = new Session("stale-session", 1L, "trader01", 1L,
                Clocks.now().minusSeconds(1));
        noPurge.save(stale);
        AuthService dbBacked = new AuthService(FakeTx.newTemplate(), userDao, roleDao, userRoleDao,
                conflictDao, sessionDao, loginLogDao, historyDao, permDao, attemptDao, noPurge,
                null, changeLogDao, null, null);
        BizException e = assertThrows(BizException.class, () -> dbBacked.touchSession("stale-session"));
        assertEquals(ErrorCode.NOT_AUTHENTICATED, e.errorCode(), "超时应 10006");
        assertNull(noPurge.find("stale-session"), "超时会话应被作废");
    }

    @Test
    @DisplayName("登出：作废内存与库内会话；登出未知会话为幂等空操作")
    void logoutInvalidatesBoth() {
        addUser(1L, "trader01", "ENABLED");
        Session session = service.login("trader01", "Hg#2026Trading01", "127.0.0.1", "JUnit");
        service.logout(session.sessionId);
        assertNull(store.find(session.sessionId));
        assertEquals(1, sessionDao.invalidateCalls, "库内会话应被作废");
        service.logout("not-exist");   // 幂等
    }

    // ------------------------------------------------------------ 岗位分离

    @Test
    @DisplayName("岗位互斥：持 TRADER 岗位组时授予 FIN_RISK 角色被拒（10005）并记越权尝试")
    void grantRoleRejectsPositionConflict() {
        addUser(1L, "trader01", "ENABLED");
        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));          // 岗位组 1
        roleDao.put(role(20L, "FIN_RISK", 2, "N"));                        // 岗位组 2
        userRoleDao.put(userRole(100L, 1L, 10L));
        conflictDao.rows.add(conflict(1, 1, 2));

        BizException e = assertThrows(BizException.class,
                () -> service.grantRole(1L, 20L, 9L, 8L));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
        assertEquals(1, attemptDao.rows.size(), "须记 t_unauthorized_attempt");
        assertEquals(AuthService.ATTEMPT_POSITION_CONFLICT, attemptDao.rows.get(0).attemptType);

        // 非互斥岗位组可授予
        roleDao.put(role(30L, "AUDITOR", 3, "N"));
        service.grantRole(1L, 30L, 9L, 8L);
        assertEquals(2, userRoleDao.rows.size());
        assertTrue(sessionDao.invalidateCalls >= 1, "权限变更须使会话失效（R-AUTH-9）");
    }

    @Test
    @DisplayName("角色授予须双人复核：复核人缺失或等于操作人 → 10005")
    void grantRoleRequiresDoubleReview() {
        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class, () -> service.grantRole(1L, 10L, 9L, null)).errorCode());
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class, () -> service.grantRole(1L, 10L, 9L, 9L)).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.grantRole(1L, 999L, 9L, 8L)).errorCode());
    }

    // ------------------------------------------------------------ 负责人禁操盘

    @Test
    @DisplayName("负责人禁操盘（CH-010）：负责人且持管理层角色（两条件同满足）亦拒；普通交易岗放行")
    void leaderWithManagementRoleCannotTrade() {
        addUser(1L, "boss", "ENABLED");
        userDao.users.get(1L).isEnterpriseLeader = "Y";
        roleDao.put(role(20L, AuthService.ROLE_APPROVER, 2, "N"));
        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));
        userRoleDao.put(userRole(100L, 1L, 20L));

        assertTrue(service.isManagementRole(1L), "APPROVER 角色即管理层");
        assertEquals("Y", userDao.users.get(1L).isEnterpriseLeader, "本用例两条件同满足");
        BizException e = assertThrows(BizException.class, () -> service.assertCanTrade(1L, 3));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
        assertEquals(1, attemptDao.rows.size(), "须留痕越权尝试");
        assertEquals(AuthService.ATTEMPT_MANAGEMENT_TRADE_BLOCKED,
                attemptDao.rows.get(0).attemptType);

        // 普通交易岗可操盘
        addUser(3L, "trader01", "ENABLED");
        userRoleDao.put(userRole(102L, 3L, 10L));
        service.assertCanTrade(3L, 3);
    }

    @Test
    @DisplayName("负责人禁操盘（CH-010 析取 · ②b）：持管理层（审批人）角色单条件（非负责人）独立成立即拒")
    void managementRoleAloneCannotTrade() {
        addUser(2L, "approver", "ENABLED");   // is_enterprise_leader 缺省为 "N"
        // 授管理层角色（APPROVER），刻意不设负责人标记：仅「管理层角色」单条件成立
        roleDao.put(role(20L, AuthService.ROLE_APPROVER, 2, "N"));
        userRoleDao.put(userRole(101L, 2L, 20L));

        assertTrue(service.isManagementRole(2L), "APPROVER 角色即管理层");
        assertFalse("Y".equals(userDao.users.get(2L).isEnterpriseLeader),
                "本用例刻意不设负责人标记（②b 单条件）");
        BizException e = assertThrows(BizException.class, () -> service.assertCanTrade(2L, 3));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode(),
                "管理层单条件（无负责人标记）独立成立即拒（CH-010 析取；合取实现下此断言必红）");
        assertEquals(1, attemptDao.rows.size(), "②b 单条件亦须留痕");
        assertEquals(AuthService.ATTEMPT_MANAGEMENT_TRADE_BLOCKED,
                attemptDao.rows.get(0).attemptType);
    }

    @Test
    @DisplayName("负责人禁操盘（CH-010 析取 · ②a）：is_enterprise_leader='Y' 单条件（无管理层角色）独立成立即拒")
    void enterpriseLeaderAloneCannotTrade() {
        addUser(4L, "leader02", "ENABLED");
        userDao.users.get(4L).isEnterpriseLeader = "Y";
        // 授交易岗角色：负责人「单条件」即须拒，且不得要求其同时持管理层角色
        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));
        userRoleDao.put(userRole(103L, 4L, 10L));

        assertFalse(service.isManagementRole(4L), "本用例刻意不挂管理层角色（②a 单条件）");
        BizException e = assertThrows(BizException.class, () -> service.assertCanTrade(4L, 3));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode(),
                "负责人单条件（无管理层角色）独立成立即拒（合取实现下此断言必红）");
        assertEquals(1, attemptDao.rows.size(), "负责人单条件亦须留痕");
        assertEquals(AuthService.ATTEMPT_MANAGEMENT_TRADE_BLOCKED,
                attemptDao.rows.get(0).attemptType);
    }

    // ------------------------------------------------------------ 子账户软隔离

    @Test
    @DisplayName("子账户软隔离：无授权 → 10004 并留痕；LIMITED 放行本账户、跨账户仍拒；CROSS_READONLY 放行")
    void subAccountSoftIsolation() {
        assertThrows(BizException.class, () -> service.assertSubAccountVisible(1L, 7));

        UserSubAccountPerm limited = perm(1L, 7, AuthService.PERM_LIMITED);
        permDao.rows.add(limited);
        service.assertSubAccountVisible(1L, 7);
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class, () -> service.assertSubAccountVisible(1L, 8))
                        .errorCode());
        assertEquals(AuthService.ATTEMPT_SUB_ACCOUNT_DENIED,
                attemptDao.rows.get(attemptDao.rows.size() - 1).attemptType);

        // 已过期授权不生效
        limited.effectiveTo = Clocks.today().minusDays(1);
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class, () -> service.assertSubAccountVisible(1L, 7))
                        .errorCode());

        // 跨账户只读（审计/管理层授权）
        UserSubAccountPerm cross = perm(2L, 9, AuthService.PERM_CROSS_READONLY);
        permDao.rows.add(cross);
        service.assertSubAccountVisible(2L, 8);
        List<Integer> visible = service.visibleSubAccountIds(2L);
        assertTrue(visible.contains(com.hedgeguard.dao.SubAccountScope.ALL_ACCOUNT_SENTINEL),
                "跨账户只读须带哨兵值：" + visible);
    }

    @Test
    @DisplayName("T-115：CROSS_READONLY 读面放行（可看全部）、写面 10004 并留痕；LIMITED 仅可写被授权子账户")
    void crossReadonlyIsReadOnlyOnWriteFace() {
        // 读面（导出 / 列表 / 详情）：跨子账户只读 ⇒ 放行（FR-AUTH-13 验收 1）
        permDao.rows.add(perm(2L, 9, AuthService.PERM_CROSS_READONLY));
        service.assertSubAccountVisible(2L, 8);
        service.assertSubAccountVisible(2L, 9);

        // 写面：CROSS_READONLY 对任意子账户 ⇒ 拒绝 10004 + 留痕（FR-AUTH-13 验收 3 / R-AUTH-5）
        int before = attemptDao.rows.size();
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> service.assertSubAccountWritable(2L, 8)).errorCode(),
                "写面须拒跨账户只读（未授权子账户）");
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> service.assertSubAccountWritable(2L, 9)).errorCode(),
                "即使 CROSS_READONLY 行 sub_account_id 命中目标，也不得授予写");
        assertEquals(before + 2, attemptDao.rows.size(), "两次写面越权须各留痕（R-AUTH-8）");
        assertEquals(AuthService.ATTEMPT_SUB_ACCOUNT_DENIED,
                attemptDao.rows.get(attemptDao.rows.size() - 1).attemptType);

        // 对照：LIMITED ⇒ 仅可写被授权子账户（读权限 ≠ 写权限）
        permDao.rows.add(perm(1L, 7, AuthService.PERM_LIMITED));
        service.assertSubAccountWritable(1L, 7);
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> service.assertSubAccountWritable(1L, 8)).errorCode(),
                "LIMITED 不得写未授权子账户");

        // 对照：无任何授权 ⇒ 写面 fail-closed
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> service.assertSubAccountWritable(3L, 7)).errorCode());
    }

    // ------------------------------------------------------------ 口令变更

    @Test
    @DisplayName("口令变更：强度不足拒、与当前或近 5 次重复拒、成功则写历史并更新哈希")
    void changePasswordEnforcesStrengthAndHistory() {
        addUser(1L, "trader01", "ENABLED");
        String current = userDao.users.get(1L).passwordHash;

        assertThrows(IllegalArgumentException.class,
                () -> service.changePassword(1L, "Hg#2026Trading01", "weak"), "强度不足须拒");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.changePassword(1L, "Hg#2026Trading01", "Hg#2026Trading01"))
                        .errorCode(), "与当前口令相同须拒");

        // 历史口令（近 5 次）不得重用
        PasswordHistory reused = new PasswordHistory();
        reused.userId = 1L;
        reused.passwordHash = PasswordHasher.hash("Hg#2025Trading01");
        historyDao.rows.add(reused);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.changePassword(1L, "Hg#2026Trading01", "Hg#2025Trading01"))
                        .errorCode(), "历史口令须拒");

        assertEquals(ErrorCode.AUTH_FAILED,
                assertThrows(BizException.class,
                        () -> service.changePassword(1L, "Hg#WrongOld01", "Hg#2027Trading01"))
                        .errorCode(), "原口令错误须拒");

        service.changePassword(1L, "Hg#2026Trading01", "Hg#2027Trading01");
        assertFalse(current.equals(userDao.users.get(1L).passwordHash), "哈希应更新");
        assertTrue(PasswordHasher.verify("Hg#2027Trading01", userDao.users.get(1L).passwordHash));
        assertTrue(PasswordHasher.verify("Hg#2026Trading01",
                        historyDao.rows.get(historyDao.rows.size() - 1).passwordHash),
                "旧口令须入历史（供禁重用比对）");
    }

    @Test
    @DisplayName("T-136 管理员重置：不返回明文新口令（FR-AUTH-16）；哈希改写 + 旧口令失效 + 强制首次登录修改")
    void resetPasswordDoesNotReturnPlaintext() {
        addUser(1L, "trader01", "ENABLED");
        String beforeHash = userDao.users.get(1L).passwordHash;
        // 改前（锁定违规形状）：resetPassword 返回一次性明文（String），用例据其 verify + login 断言「可校验/可登录」。
        // 改后（合规）：返回 void —— 明文不落盘、不入日志、不回传；改以「哈希已改写 + 旧口令失效 + 强制改口令」证明重置生效。
        service.resetPassword(1L, 9L);
        String afterHash = userDao.users.get(1L).passwordHash;
        assertNotEquals(beforeHash, afterHash, "重置须改写加盐哈希（改前=" + beforeHash + "）");
        assertFalse(PasswordHasher.verify("Hg#2026Trading01", afterHash), "重置后旧口令须失效");
        assertEquals("Y", userDao.users.get(1L).mustChangePassword, "须要求首次登录修改");
    }

    // ------------------------------------------------------------ 桩与数据

    private static final class NonPurgingStore implements SessionStore {

        private final java.util.Map<String, Session> map = new java.util.HashMap<>();

        @Override
        public Session find(String sessionId) {
            return map.get(sessionId);
        }

        @Override
        public void save(Session session) {
            map.put(session.sessionId, session);
        }

        @Override
        public void invalidate(String sessionId) {
            map.remove(sessionId);
        }

        @Override
        public int invalidateByUser(long userId) {
            int n = 0;
            for (java.util.Map.Entry<String, Session> e : new ArrayList<>(map.entrySet())) {
                if (e.getValue().userId == userId) {
                    map.remove(e.getKey());
                    n++;
                }
            }
            return n;
        }
    }

    private void addUser(long id, String username, String status) {
        UserAccount user = new UserAccount();
        user.id = id;
        user.username = username;
        user.realName = username;
        user.status = status;
        user.isEnterpriseLeader = "N";
        user.passwordHash = PasswordHasher.hash("Hg#2026Trading01");
        user.failCount = 0;
        userDao.users.put(id, user);
    }

    private static Role role(long id, String code, int groupId, String trading) {
        Role role = new Role();
        role.id = id;
        role.roleCode = code;
        role.roleName = code;
        role.positionGroupId = groupId;
        role.hasTradingOperation = trading;
        role.status = "ENABLED";
        role.isBusinessRole = "Y";
        return role;
    }

    private static UserRole userRole(long id, long userId, long roleId) {
        UserRole userRole = new UserRole();
        userRole.id = id;
        userRole.userId = userId;
        userRole.roleId = roleId;
        userRole.grantedBy = 1L;
        userRole.grantedTime = Clocks.now();
        return userRole;
    }

    private static PositionConflict conflict(long id, int a, int b) {
        PositionConflict conflict = new PositionConflict();
        conflict.id = id;
        conflict.groupAId = a;
        conflict.groupBId = b;
        conflict.isMandatory = "Y";
        conflict.conflictReason = "岗位互斥（DATA_MODEL.md §2.2）";
        return conflict;
    }

    private static UserSubAccountPerm perm(long userId, int subAccountId, String permType) {
        UserSubAccountPerm perm = new UserSubAccountPerm();
        perm.userId = userId;
        perm.subAccountId = subAccountId;
        perm.permType = permType;
        perm.grantedBy = 1L;
        perm.effectiveFrom = LocalDate.of(2026, 1, 1);
        return perm;
    }

    /** 内存假用户 DAO。 */
    private static final class FakeUserAccountDao extends UnsupportedBaseDao<UserAccount, Long>
            implements UserAccountDao {

        private final java.util.Map<Long, UserAccount> users = new java.util.LinkedHashMap<>();

        @Override
        public UserAccount findById(Long id) {
            return users.get(id);
        }

        @Override
        public int updateById(UserAccount entity, long operatorId) {
            return users.containsKey(entity.id) ? 1 : 0;
        }

        @Override
        public UserAccount findByUsername(String username) {
            for (UserAccount user : users.values()) {
                if (user.username.equals(username)) {
                    return user;
                }
            }
            return null;
        }

        @Override
        public UserAccount findByNaturalPersonKey(String naturalPersonKey) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByNaturalPersonKey");
        }

        @Override
        public int incrementFailCount(long userId) {
            UserAccount user = users.get(userId);
            user.failCount = user.failCount + 1;
            return 1;
        }

        @Override
        public int lockUntil(long userId, LocalDateTime lockedUntil) {
            users.get(userId).lockedUntil = lockedUntil;
            return 1;
        }

        @Override
        public int unlock(long userId) {
            users.get(userId).lockedUntil = null;
            users.get(userId).failCount = 0;
            return 1;
        }

        @Override
        public int updatePasswordHash(long userId, String passwordHash, LocalDateTime updatedTime) {
            UserAccount user = users.get(userId);
            user.passwordHash = passwordHash;
            user.passwordUpdatedTime = updatedTime;
            return 1;
        }

        @Override
        public int touchLastLogin(long userId, LocalDateTime lastLoginTime) {
            users.get(userId).lastLoginTime = lastLoginTime;
            return 1;
        }
    }

    /** 内存假角色 DAO。 */
    private static final class FakeRoleDao extends UnsupportedBaseDao<Role, Long> implements RoleDao {

        private final java.util.Map<Long, Role> roles = new java.util.LinkedHashMap<>();

        void put(Role role) {
            roles.put(role.id, role);
        }

        @Override
        public Role findById(Long id) {
            return roles.get(id);
        }

        @Override
        public Role findByCode(String roleCode) {
            for (Role role : roles.values()) {
                if (role.roleCode.equals(roleCode)) {
                    return role;
                }
            }
            return null;
        }

        @Override
        public List<Role> findByPositionGroup(int positionGroupId) {
            List<Role> out = new ArrayList<>();
            for (Role role : roles.values()) {
                if (role.positionGroupId == positionGroupId) {
                    out.add(role);
                }
            }
            return out;
        }

        @Override
        public List<Role> findTradingRoles() {
            List<Role> out = new ArrayList<>();
            for (Role role : roles.values()) {
                if ("Y".equals(role.hasTradingOperation)) {
                    out.add(role);
                }
            }
            return out;
        }
    }

    /** 内存假用户角色 DAO。 */
    private static final class FakeUserRoleDao extends UnsupportedBaseDao<UserRole, Long>
            implements UserRoleDao {

        private final List<UserRole> rows = new ArrayList<>();

        void put(UserRole userRole) {
            rows.add(userRole);
        }

        @Override
        public int insert(UserRole entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public List<UserRole> findByUser(long userId) {
            List<UserRole> out = new ArrayList<>();
            for (UserRole row : rows) {
                if (row.userId == userId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<UserRole> findByRole(long roleId) {
            List<UserRole> out = new ArrayList<>();
            for (UserRole row : rows) {
                if (row.roleId == roleId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假岗位互斥 DAO。 */
    private static final class FakePositionConflictDao extends UnsupportedBaseDao<PositionConflict, Long>
            implements PositionConflictDao {

        private final List<PositionConflict> rows = new ArrayList<>();

        @Override
        public List<PositionConflict> findConflictsOf(int groupId) {
            List<PositionConflict> out = new ArrayList<>();
            for (PositionConflict row : rows) {
                if (row.groupAId == groupId || row.groupBId == groupId) {
                    out.add(row);
                }
            }
            return out;
        }
    }

    /** 内存假登录会话 DAO。 */
    private static final class FakeLoginSessionDao extends UnsupportedBaseDao<LoginSession, Long>
            implements LoginSessionDao {

        private final List<LoginSession> rows = new ArrayList<>();
        private int invalidateCalls;

        @Override
        public int insert(LoginSession entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public LoginSession findBySessionId(String sessionId) {
            for (LoginSession row : rows) {
                if (row.sessionId.equals(sessionId)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int invalidateByUser(long userId, String status, Long kickedBy, long permissionVersion) {
            invalidateCalls++;
            for (LoginSession row : rows) {
                if (row.userId == userId) {
                    row.status = status;
                    row.kickedBy = kickedBy;
                }
            }
            return 1;
        }

        @Override
        public int touch(String sessionId, LocalDateTime lastActiveTime) {
            for (LoginSession row : rows) {
                if (row.sessionId.equals(sessionId)) {
                    row.lastActiveTime = lastActiveTime;
                }
            }
            return 1;
        }

        @Override
        public int deleteExpired(LocalDateTime before) {
            return 0;
        }

        @Override
        public int deleteById(Long id) {
            rows.removeIf(row -> row.id == id);
            return 1;
        }
    }

    /** 内存假登录日志 DAO。 */
    private static final class FakeLoginLogDao extends UnsupportedBaseDao<LoginLog, Long>
            implements LoginLogDao {

        private final List<LoginLog> rows = new ArrayList<>();

        @Override
        public int insert(LoginLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public List<LoginLog> findByUserBetween(Long userId, LocalDateTime from, LocalDateTime to) {
            return new ArrayList<>(rows);
        }

        @Override
        public long countFailByIpSince(String clientIp, LocalDateTime from) {
            long n = 0;
            for (LoginLog row : rows) {
                if ("LOGIN_FAIL".equals(row.eventType) && clientIp.equals(row.clientIp)) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 内存假口令历史 DAO（近 N 条按 seq_no 升序）。 */
    private static final class FakePasswordHistoryDao extends UnsupportedBaseDao<PasswordHistory, Long>
            implements PasswordHistoryDao {

        private final List<PasswordHistory> rows = new ArrayList<>();

        @Override
        public int insert(PasswordHistory entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public List<PasswordHistory> findRecent(long userId, int limit) {
            List<PasswordHistory> out = new ArrayList<>();
            for (PasswordHistory row : rows) {
                if (row.userId == userId && out.size() < limit) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public int shiftSeqNo(long userId) {
            return 1;
        }

        @Override
        public int deleteBeyond(long userId, int keep) {
            return 1;
        }
    }

    /** 内存假子账户权限 DAO。 */
    private static final class FakeUserSubAccountPermDao
            extends UnsupportedBaseDao<UserSubAccountPerm, Long> implements UserSubAccountPermDao {

        private final List<UserSubAccountPerm> rows = new ArrayList<>();

        @Override
        public List<UserSubAccountPerm> findByUser(long userId) {
            List<UserSubAccountPerm> out = new ArrayList<>();
            for (UserSubAccountPerm row : rows) {
                if (row.userId == userId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<Long> findUserIdsBySubAccount(int subAccountId) {
            List<Long> out = new ArrayList<>();
            for (UserSubAccountPerm row : rows) {
                if (row.subAccountId == subAccountId && !out.contains(row.userId)) {
                    out.add(row.userId);
                }
            }
            return out;
        }
    }

    /** 内存假越权尝试 DAO。 */
    private static final class FakeUnauthorizedAttemptDao
            extends UnsupportedBaseDao<UnauthorizedAttempt, Long> implements UnauthorizedAttemptDao {
        private final List<UnauthorizedAttempt> rows = new ArrayList<>();

        @Override
        public int insert(UnauthorizedAttempt entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public List<UnauthorizedAttempt> findByOperatorSince(long operatorId, LocalDateTime from) {
            return new ArrayList<>(rows);
        }

        @Override
        public long countByTypeBetween(String attemptType, LocalDateTime from, LocalDateTime to) {
            long n = 0;
            for (UnauthorizedAttempt row : rows) {
                if (attemptType.equals(row.attemptType)) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 内存假权限变更留痕 DAO（只增；供 grantRole 等留痕调用）。 */
    private static final class FakePermissionChangeLogDao
            extends UnsupportedBaseDao<PermissionChangeLog, Long> implements PermissionChangeLogDao {

        private final List<PermissionChangeLog> rows = new ArrayList<>();

        @Override
        public int insert(PermissionChangeLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public List<PermissionChangeLog> findRejectedSince(LocalDateTime from) {
            return new ArrayList<>(rows);
        }

        @Override
        public List<PermissionChangeLog> findByTargetUserBetween(long targetUserId,
                                                                 LocalDateTime from,
                                                                 LocalDateTime to) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByTargetUserBetween");
        }
    }
}
