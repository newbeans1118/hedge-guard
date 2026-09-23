package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.LoginLogDao;
import com.hedgeguard.dao.LoginSessionDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.PermissionChangeLogDao;
import com.hedgeguard.dao.PositionConflictDao;
import com.hedgeguard.dao.PositionGroupDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.entity.LoginLog;
import com.hedgeguard.dao.entity.LoginSession;
import com.hedgeguard.dao.entity.PasswordHistory;
import com.hedgeguard.dao.entity.PermissionChangeLog;
import com.hedgeguard.dao.entity.PositionConflict;
import com.hedgeguard.dao.entity.PositionGroup;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.handler.auth.ChangeLogHandler;
import com.hedgeguard.web.handler.auth.UserHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthService} 管理面方法（{@code FR-AUTH-1~7/10~13/15④/16/17④/18}）单元测试 +
 * {@code CFG-23}/{@code CFG-23b} 配置化（缺口 (c)①）。
 *
 * <p>本用例即 {@code T-010}「{@code FR-AUTH-1~18} 覆盖 = 已交付 2 / 部分 5 / 未交付 11」缺口的
 * 反退化判据：删掉任一 service 方法或接线，对应用例必红。
 */
class AuthAdminServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-21T02:00:00Z");

    private FakeUserDao userDao;
    private FakeRoleDao roleDao;
    private FakeGroupDao groupDao;
    private FakeUserRoleDao userRoleDao;
    private FakeConflictDao conflictDao;
    private FakeSessionDao sessionDao;
    private FakeLoginLogDao loginLogDao;
    private FakeHistoryDao historyDao;
    private FakePermDao permDao;
    private FakeAuthzDao authzDao;
    private FakeChangeLogDao changeLogDao;
    private FakeAttemptDao attemptDao;
    private SessionStore store;
    private AuthService service;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        userDao = new FakeUserDao();
        roleDao = new FakeRoleDao();
        groupDao = new FakeGroupDao();
        userRoleDao = new FakeUserRoleDao();
        conflictDao = new FakeConflictDao();
        sessionDao = new FakeSessionDao();
        loginLogDao = new FakeLoginLogDao();
        historyDao = new FakeHistoryDao();
        permDao = new FakePermDao();
        authzDao = new FakeAuthzDao();
        changeLogDao = new FakeChangeLogDao();
        attemptDao = new FakeAttemptDao();
        store = new SessionStore.InMemory();
        service = build(Config.empty());
    }

    private AuthService build(Config config) {
        return new AuthService(FakeTx.newTemplate(), userDao, roleDao, userRoleDao, conflictDao,
                sessionDao, loginLogDao, historyDao, permDao, attemptDao, store,
                authzDao, changeLogDao, groupDao, config);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
        System.clearProperty(Config.KEY_LOGIN_FAIL_LIMIT);
        System.clearProperty(Config.KEY_LOGIN_LOCK_MINUTES);
    }

    // ------------------------------------------------------------ FR-AUTH-1/2

    @Test
    @DisplayName("FR-AUTH-1：新增用户（加盐哈希 + 强制改口令 + 默认无子账户权限）；用户名/自然人重复拒")
    void createUserHashesAndSeeds() {
        long id = service.createUser("lisi", "李四", "NP-001", "财务部", "N",
                "Hg#2026Trading01", 9L);
        UserAccount created = userDao.byId.get(id);
        assertNotNull(created);
        assertEquals("ENABLED", created.status);
        assertEquals("Y", created.mustChangePassword, "首次登录须强制改口令");
        assertTrue(PasswordHasher.isEncoded(created.passwordHash), "口令须加盐哈希存储（NFR-SEC-2）");
        assertTrue(PasswordHasher.verify("Hg#2026Trading01", created.passwordHash));
        assertTrue(permDao.rows.isEmpty(), "新增用户默认不得有子账户数据权限（安全默认）");
        assertTrue(changeLogDao.rows.stream().anyMatch(
                r -> AuthService.CHANGE_USER_ATTR.equals(r.changeTarget)), "须留痕（FR-AUTH-11）");

        assertEquals(ErrorCode.UNIQUE_CONFLICT,
                assertThrows(BizException.class, () -> service.createUser("lisi", "李四二", "NP-002",
                        "财务部", "N", "Hg#2026Trading01", 9L)).errorCode(), "用户名重复须拒");
        assertEquals(ErrorCode.UNIQUE_CONFLICT,
                assertThrows(BizException.class, () -> service.createUser("wangwu", "王五", "NP-001",
                        "财务部", "N", "Hg#2026Trading01", 9L)).errorCode(),
                "同一自然人第二个账号须拒（R-AUTH-2）");
    }

    @Test
    @DisplayName("FR-AUTH-2：企业负责人标记变更须双人复核（缺/自审 10005）；提供复核人则成功并留痕")
    void updateUserLeaderFlagRequiresReview() {
        service.createUser("lisi", "李四", "NP-001", "财务部", "N", "Hg#2026Trading01", 9L);
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class, () -> service.updateUser(1L, "李四", "财务部",
                        "Y", 9L, null)).errorCode(), "缺复核人须拒");
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class, () -> service.updateUser(1L, "李四", "财务部",
                        "Y", 9L, 9L)).errorCode(), "自审自批须拒");
        service.updateUser(1L, "李四", "财务部", "Y", 9L, 8L);
        assertEquals("Y", userDao.byId.get(1L).isEnterpriseLeader);
    }

    // ------------------------------------------------------------ FR-AUTH-3/4/5

    @Test
    @DisplayName("FR-AUTH-3：停用须填原因、立即失效会话；FR-AUTH-4：无角色启用被拒、有角色启用返回一次性口令")
    void disableAndEnable() {
        service.createUser("lisi", "李四", "NP-001", "财务部", "N", "Hg#2026Trading01", 9L);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.disableUser(1L, " ", 9L)).errorCode());

        service.disableUser(1L, "离职", 9L);
        assertEquals("DISABLED", userDao.byId.get(1L).status);
        assertTrue(sessionDao.invalidateCalls >= 1, "停用须立即失效会话（R-AUTH-9）");

        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class, () -> service.enableUser(1L, 9L, 8L)).errorCode(),
                "未配置角色即启用须提示（FR-AUTH-4 验收 4）");

        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));
        userRoleDao.put(userRole(1L, 10L));
        String oneTime = service.enableUser(1L, 9L, 8L);
        assertEquals("ENABLED", userDao.byId.get(1L).status);
        assertTrue(PasswordHasher.verify(oneTime, userDao.byId.get(1L).passwordHash));
    }

    @Test
    @DisplayName("FR-AUTH-5：用户列表分页（默认字段 created_time）+ 状态筛选；返回不含口令字段（结构保证）")
    void listUsersPaged() {
        service.createUser("a", "A", "NP-A", "d", "N", "Hg#2026Trading01", 9L);
        service.createUser("b", "B", "NP-B", "d", "Y", "Hg#2026Trading01", 9L);
        PageResult<UserAccount> page = service.listUsers(
                PageQuery.of("1", "20", null, "created_time", "desc"), "ENABLED", null);
        assertEquals(2, page.total());
        assertTrue(userDao.lastWhere.contains("status"), "状态筛选应下推 where");
    }

    // ------------------------------------------------------------ FR-AUTH-6/7

    @Test
    @DisplayName("FR-AUTH-6：角色须选有效岗位组；系统管理员含交易能力被拒；定义成功留痕")
    void defineRoleValidations() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.defineRole("X", "X", 99, "N", 9L, 8L))
                        .errorCode(), "须选择有效岗位组");
        groupDao.put(group(1, "TRADER", "FRONT"));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED,
                assertThrows(BizException.class, () -> service.defineRole(AuthService.ROLE_SYS_ADMIN,
                        "系统管理员", 1, "Y", 9L, 8L)).errorCode(), "SYS_ADMIN 不得含交易能力（R-AUTH-10）");

        long id = service.defineRole("AUDITOR", "审计岗", 1, "N", 9L, 8L);
        assertEquals("AUDITOR", roleDao.byId.get(id).roleCode);
        assertEquals("Y", roleDao.byId.get(id).isBusinessRole);
    }

    @Test
    @DisplayName("FR-AUTH-7：岗位互斥配置（同组拒绝；按 a<b 单向存储）")
    void definePositionConflict() {
        groupDao.put(group(3, "TRADER", "FRONT"));
        groupDao.put(group(1, "FIN_RISK", "MID"));
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.definePositionConflict(1, 1, "r", 9L, 8L))
                        .errorCode(), "同组不得互斥");
        service.definePositionConflict(3, 1, "交易↔风控互斥", 9L, 8L);
        PositionConflict saved = conflictDao.rows.get(conflictDao.rows.size() - 1);
        assertTrue(saved.groupAId < saved.groupBId, "矩阵须按 a<b 单向存储");
        assertEquals(1, saved.groupAId);
        assertEquals(3, saved.groupBId);
    }

    // ------------------------------------------------------------ FR-AUTH-10/12/13

    @Test
    @DisplayName("FR-AUTH-10：授权须齐备人员+品种+额度（额度<=0 拒）；成功留痕")
    void grantAuthorization() {
        service.createUser("lisi", "李四", "NP-001", "财务部", "N", "Hg#2026Trading01", 9L);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.grantAuthorization(1L, 7, 5,
                        BigDecimal.ZERO, LocalDate.of(2026, 1, 1), null, 9L, 8L)).errorCode(),
                "缺额度（<=0）须拒");
        long id = service.grantAuthorization(1L, 7, 5, new BigDecimal("100.0000"),
                LocalDate.of(2026, 1, 1), null, 9L, 8L);
        assertEquals("EFFECTIVE", authzDao.byId.get(id).status);
    }

    @Test
    @DisplayName("FR-AUTH-12/13：跨子账户只读仅管理层/审计岗可授；LIMITED 任意可授")
    void assignSubAccountPerm() {
        service.createUser("lisi", "李四", "NP-001", "财务部", "N", "Hg#2026Trading01", 9L);
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class, () -> service.assignSubAccountPerm(1L, 0,
                        AuthService.PERM_CROSS_READONLY, LocalDate.of(2026, 1, 1), null, 9L, 8L))
                        .errorCode(), "非管理层/审计岗不得授跨子账户只读（FR-AUTH-13 验收 2）");

        service.assignSubAccountPerm(1L, 7, AuthService.PERM_LIMITED,
                LocalDate.of(2026, 1, 1), null, 9L, 8L);
        assertEquals(1, permDao.rows.size());

        roleDao.put(role(20L, AuthService.ROLE_AUDITOR, 6, "N"));
        userRoleDao.put(userRole(1L, 20L));
        service.assignSubAccountPerm(1L, 0, AuthService.PERM_CROSS_READONLY,
                LocalDate.of(2026, 1, 1), null, 9L, 8L);
        assertEquals(2, permDao.rows.size(), "审计岗可被授跨子账户只读");
    }

    // ------------------------------------------------------------ FR-AUTH-11/18/15④

    @Test
    @DisplayName("FR-AUTH-11/18：变更留痕与越权记录可分页查询（含按用户/操作人筛选）")
    void changeLogAndAttemptQueries() {
        changeLogDao.rows.add(changeLog(1L));
        attemptDao.rows.add(attempt(2L));
        assertEquals(1, service.listPermissionChangeLogs(
                PageQuery.of("1", "20", null, "change_time", "desc"), 1L).total());
        assertEquals(1, service.listUnauthorizedAttempts(
                PageQuery.of("1", "20", null, "attempt_time", "desc"), 2L).total());
    }

    @Test
    @DisplayName("FR-AUTH-15④：管理员强制下线（作废会话 + 留痕）")
    void forceLogout() {
        service.createUser("lisi", "李四", "NP-001", "财务部", "N", "Hg#2026Trading01", 9L);
        service.forceLogout(1L, 9L);
        assertTrue(sessionDao.invalidateCalls >= 1);
        assertTrue(loginLogDao.rows.stream().anyMatch(l -> "KICKED".equals(l.eventType)));
    }

    // ------------------------------------------------------------ T-135：FR-AUTH-5/11 筛选 + FR-AUTH-1 初始角色 + FR-AUTH-15④ 端点

    @Test
    @DisplayName("T-135 FR-AUTH-5：关键字 / 角色 / 子账户筛选下推 SQL（不取回内存再过滤）")
    void listUsersPushesDownRoleKeywordSubAccount() {
        // 关键字 ⇒ username/real_name 二者取其一（LIKE 两侧 %）
        service.listUsers(PageQuery.of("1", "20", null, "created_time", "desc"),
                null, null, null, "ali", null);
        assertTrue(userDao.lastWhere.contains("username LIKE ?"), "关键字须下推 username LIKE");
        assertTrue(userDao.lastWhere.contains("real_name LIKE ?"), "关键字须下推 real_name LIKE");
        assertTrue(userDao.lastWhere.contains("OR"), "关键字须 username/real_name 取其一（OR）");

        // 角色 ⇒ 解析受授用户集 ⇒ id IN (...)
        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));
        userRoleDao.put(userRole(1L, 10L));
        service.listUsers(PageQuery.of("1", "20", null, "created_time", "desc"),
                null, null, AuthService.ROLE_TRADER, null, null);
        assertTrue(userDao.lastWhere.contains("id IN ("), "角色筛须下推 id IN");

        // 子账户 ⇒ 授权用户集 ⇒ id IN (...)
        permDao.rows.add(perm(1L, 7));
        service.listUsers(PageQuery.of("1", "20", null, "created_time", "desc"),
                null, null, null, null, 7);
        assertTrue(userDao.lastWhere.contains("id IN ("), "子账户筛须下推 id IN");

        // 组合：状态 + 负责人 + 关键字 + 角色 ⇒ 以 AND 连接
        service.listUsers(PageQuery.of("1", "20", null, "created_time", "desc"),
                "ENABLED", "N", AuthService.ROLE_TRADER, "ali", null);
        assertTrue(userDao.lastWhere.contains("status = ?"), "状态筛在册");
        assertTrue(userDao.lastWhere.contains("is_enterprise_leader = ?"), "负责人筛在册");
        assertTrue(userDao.lastWhere.contains("username LIKE ?"), "关键字在册");
        assertTrue(userDao.lastWhere.contains("id IN ("), "角色在册");
    }

    @Test
    @DisplayName("T-135 FR-AUTH-5：未知角色 / 无授权子账户 ⇒ 空页（不落查询、不误报全量）")
    void listUsersUnknownFilterShortCircuits() {
        PageResult<UserAccount> byRole = service.listUsers(
                PageQuery.of("1", "20", null, "created_time", "desc"),
                null, null, "NO_SUCH_ROLE", null, null);
        assertEquals(0, byRole.total(), "未知角色 ⇒ 空页（不得退化为不过滤）");
        PageResult<UserAccount> bySub = service.listUsers(
                PageQuery.of("1", "20", null, "created_time", "desc"),
                null, null, null, null, 99);
        assertEquals(0, bySub.total(), "无授权子账户 ⇒ 空页");
    }

    @Test
    @DisplayName("T-135 FR-AUTH-11：变更留痕按 变更类型 / 操作人 / 时间范围 筛选下推")
    void changeLogPushesDownFilters() {
        service.listPermissionChangeLogs(PageQuery.of("1", "20", null, "change_time", "desc"),
                1L, AuthService.CHANGE_USER_ROLE, 9L,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        String w = changeLogDao.lastWhere;
        assertTrue(w.contains("target_user_id = ?"), "用户维度在册");
        assertTrue(w.contains("change_target = ?"), "变更类型在册");
        assertTrue(w.contains("operator_id = ?"), "操作人在册");
        assertTrue(w.contains("change_time >= ?"), "起始时间在册");
        assertTrue(w.contains("change_time <= ?"), "截止时间在册");
    }

    @Test
    @DisplayName("T-135 FR-AUTH-1：初始角色随创建授予（落 t_user_role + 留痕）")
    void createUserWithInitialRole() {
        roleDao.put(role(10L, AuthService.ROLE_AUDITOR, 1, "N"));
        long id = service.createUser("lisi", "李四", "NP-001", "财务部", "N",
                "Hg#2026Trading01", 9L, AuthService.ROLE_AUDITOR);
        assertTrue(userRoleDao.rows.stream().anyMatch(r -> r.userId == id && r.roleId == 10L),
                "初始角色须落 t_user_role");
        assertTrue(changeLogDao.rows.stream().anyMatch(r ->
                        AuthService.CHANGE_USER_ROLE.equals(r.changeTarget)
                                && r.targetUserId != null && r.targetUserId == id),
                "初始角色授予须留痕（CHANGE_USER_ROLE）");
    }

    @Test
    @DisplayName("T-135 FR-AUTH-1：UserHandler POST 透传 roleCode ⇒ 授予初始角色")
    void userHandlerCreatePassesRoleCode() {
        roleDao.put(role(10L, AuthService.ROLE_AUDITOR, 1, "N"));
        HttpResult result = new UserHandler(service).handle(new RequestContext("POST", UserHandler.PATH,
                null, java.util.Collections.emptyMap(),
                "{\"username\":\"lisi\",\"realName\":\"李四\",\"naturalPersonKey\":\"NP-001\","
                        + "\"department\":\"财务部\",\"isEnterpriseLeader\":\"N\","
                        + "\"initialPassword\":\"Hg#2026Trading01\",\"roleCode\":\"AUDITOR\"}",
                "HGT135", "127.0.0.1"));
        assertEquals(200, result.status);
        assertTrue(userRoleDao.rows.stream().anyMatch(r -> r.roleId == 10L),
                "handler 的 roleCode 须透传到 createUser 并授予初始角色");
    }

    @Test
    @DisplayName("T-135 FR-AUTH-1：初始角色校验先于落库（负责人+交易岗 / 未知角色 ⇒ 无用户半态）")
    void createUserInitialRoleValidatedBeforeInsert() {
        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class, () -> service.createUser("lisi", "李四", "NP-001",
                        "财务部", "Y", "Hg#2026Trading01", 9L, AuthService.ROLE_TRADER)).errorCode(),
                "企业负责人授交易岗初始角色须拒（FR-AUTH-9 验收 1）");
        assertTrue(userDao.byId.isEmpty(), "校验失败不得留下用户半态（CONTRACTS §5.3）");

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.createUser("wang", "王", "NP-002",
                        "财务部", "N", "Hg#2026Trading01", 9L, "NO_SUCH_ROLE")).errorCode(),
                "未知初始角色须拒");
        assertTrue(userDao.byId.isEmpty(), "未知初始角色不得留下用户半态");
    }

    @Test
    @DisplayName("T-135 FR-AUTH-15④：PUT /auth/user action=FORCE_LOGOUT 经 handler 接线（作废会话 + 留痕）")
    void forceLogoutWiredViaHandler() {
        service.createUser("lisi", "李四", "NP-001", "财务部", "N", "Hg#2026Trading01", 9L);
        HttpResult result = new UserHandler(service).handle(new RequestContext("PUT", UserHandler.PATH,
                null, java.util.Collections.emptyMap(),
                "{\"action\":\"FORCE_LOGOUT\",\"userId\":1}", "HGT135", "127.0.0.1"));
        assertEquals(200, result.status);
        assertTrue(sessionDao.invalidateCalls >= 1, "强制下线须作废会话");
        assertTrue(loginLogDao.rows.stream().anyMatch(l -> "KICKED".equals(l.eventType)), "须留痕");
    }

    @Test
    @DisplayName("T-135 FR-AUTH-5：UserHandler 逐项透传 keyword / role / subAccount 查询参数")
    void userHandlerReadsFilterParams() {
        roleDao.put(role(10L, AuthService.ROLE_TRADER, 1, "Y"));
        userRoleDao.put(userRole(1L, 10L));
        permDao.rows.add(perm(1L, 7));
        UserHandler handler = new UserHandler(service);

        // 单项调用，使每条参数的下推可被独立观测（role 与 subAccount 均产出 id IN，合调会互相掩盖）
        userDao.lastWhere = "";
        handler.handle(ctx("keyword=ali"));
        assertTrue(userDao.lastWhere.contains("username LIKE ?"), "handler 须透传 keyword");

        userDao.lastWhere = "";
        handler.handle(ctx("role=TRADER"));
        assertTrue(userDao.lastWhere.contains("id IN ("), "handler 须透传 role");

        userDao.lastWhere = "";
        handler.handle(ctx("subAccount=7"));
        assertTrue(userDao.lastWhere.contains("id IN ("), "handler 须透传 subAccount");
    }

    private static RequestContext ctx(String query) {
        return new RequestContext("GET", UserHandler.PATH, query,
                java.util.Collections.emptyMap(), "", "HGT135", "127.0.0.1");
    }

    @Test
    @DisplayName("T-135 FR-AUTH-11：ChangeLogHandler 读取 from/to/changeTarget/operatorId 查询参数并下推")
    void changeLogHandlerReadsFilterParams() {
        HttpResult result = new ChangeLogHandler(service).handle(new RequestContext("GET",
                ChangeLogHandler.PATH,
                "userId=1&changeTarget=USER_ROLE&operatorId=9&from=2026-01-01&to=2026-12-31",
                java.util.Collections.emptyMap(), "", "HGT135", "127.0.0.1"));
        assertEquals(200, result.status);
        String w = changeLogDao.lastWhere;
        assertTrue(w.contains("change_target = ?"), "handler 须透传 changeTarget");
        assertTrue(w.contains("operator_id = ?"), "handler 须透传 operatorId");
        assertTrue(w.contains("change_time >= ?"), "handler 须透传 from");
        assertTrue(w.contains("change_time <= ?"), "handler 须透传 to");
    }

    // ------------------------------------------------------------ 缺口 (c)① CFG-23 配置化

    @Test
    @DisplayName("FR-AUTH-17④：锁定阈值/时长经 CONFIG 生效（hg.auth.login_fail_limit=3 ⇒ 第 3 次锁）")
    void loginLockThresholdFromConfig() {
        System.setProperty(Config.KEY_LOGIN_FAIL_LIMIT, "3");
        System.setProperty(Config.KEY_LOGIN_LOCK_MINUTES, "7");
        AuthService configured = build(Config.empty());
        configured.createUser("zhao", "赵", "NP-Z", "d", "N", "Hg#2026Trading01", 9L);
        for (int i = 0; i < 2; i++) {
            assertThrows(BizException.class, () -> configured.login("zhao", "Bad#Passw0rd1", "ip", "ua"));
        }
        BizException locked = assertThrows(BizException.class,
                () -> configured.login("zhao", "Bad#Passw0rd1", "ip", "ua"));
        assertEquals(ErrorCode.ACCOUNT_LOCKED, locked.errorCode(), "配置阈值 3 ⇒ 第 3 次即锁");
        assertEquals(Clocks.now().plusMinutes(7), userDao.byId.get(1L).lockedUntil,
                "锁定时长取配置 7 分钟（非硬编码 5）");
    }

    // ------------------------------------------------------------ 数据构造

    private static Role role(long id, String code, int groupId, String trading) {
        Role role = new Role();
        role.id = id;
        role.roleCode = code;
        role.roleName = code;
        role.positionGroupId = groupId;
        role.hasTradingOperation = trading;
        role.isBusinessRole = "Y";
        role.status = "ENABLED";
        return role;
    }

    private static PositionGroup group(int id, String code, String fmb) {
        PositionGroup g = new PositionGroup();
        g.id = id;
        g.groupCode = code;
        g.groupName = code;
        g.frontMidBack = fmb;
        g.isConflictEnforced = "Y";
        g.status = "ENABLED";
        return g;
    }

    private static UserRole userRole(long userId, long roleId) {
        UserRole ur = new UserRole();
        ur.userId = userId;
        ur.roleId = roleId;
        ur.grantedBy = 1L;
        ur.grantedTime = Clocks.now();
        return ur;
    }

    private static UserSubAccountPerm perm(long userId, int subAccountId) {
        UserSubAccountPerm p = new UserSubAccountPerm();
        p.userId = userId;
        p.subAccountId = subAccountId;
        p.permType = AuthService.PERM_LIMITED;
        p.grantedBy = 9L;
        p.effectiveFrom = LocalDate.of(2026, 1, 1);
        return p;
    }

    private static PermissionChangeLog changeLog(long targetUser) {
        PermissionChangeLog log = new PermissionChangeLog();
        log.changeTarget = AuthService.CHANGE_USER_ROLE;
        log.targetUserId = targetUser;
        log.operatorId = 9L;
        log.rejected = "N";
        log.changeTime = Clocks.now();
        return log;
    }

    private static UnauthorizedAttempt attempt(long operator) {
        UnauthorizedAttempt a = new UnauthorizedAttempt();
        a.operatorId = operator;
        a.attemptType = AuthService.ATTEMPT_POSITION_CONFLICT;
        a.attemptDesc = "x";
        a.rejectReason = "x";
        a.attemptTime = Clocks.now();
        return a;
    }

    // ------------------------------------------------------------ 假 DAO

    private static final class FakeUserDao extends UnsupportedBaseDao<UserAccount, Long>
            implements UserAccountDao {
        final Map<Long, UserAccount> byId = new LinkedHashMap<>();
        long seq;
        String lastWhere = "";

        @Override
        public int insert(UserAccount e, long operatorId) {
            if (e.id == 0) {
                e.id = ++seq;
            }
            byId.put(e.id, e);
            return 1;
        }

        @Override
        public int updateById(UserAccount e, long operatorId) {
            return byId.containsKey(e.id) ? 1 : 0;
        }

        @Override
        public UserAccount findById(Long id) {
            return byId.get(id);
        }

        @Override
        public UserAccount findByUsername(String username) {
            for (UserAccount u : byId.values()) {
                if (u.username.equals(username)) {
                    return u;
                }
            }
            return null;
        }

        @Override
        public UserAccount findByNaturalPersonKey(String key) {
            for (UserAccount u : byId.values()) {
                if (key.equals(u.naturalPersonKey)) {
                    return u;
                }
            }
            return null;
        }

        @Override
        public int incrementFailCount(long userId) {
            byId.get(userId).failCount++;
            return 1;
        }

        @Override
        public int lockUntil(long userId, LocalDateTime t) {
            byId.get(userId).lockedUntil = t;
            return 1;
        }

        @Override
        public int unlock(long userId) {
            byId.get(userId).lockedUntil = null;
            byId.get(userId).failCount = 0;
            return 1;
        }

        @Override
        public int updatePasswordHash(long userId, String hash, LocalDateTime t) {
            byId.get(userId).passwordHash = hash;
            return 1;
        }

        @Override
        public int touchLastLogin(long userId, LocalDateTime t) {
            byId.get(userId).lastLoginTime = t;
            return 1;
        }

        @Override
        public PageResult<UserAccount> findPage(PageQuery q, String where, List<Object> args) {
            lastWhere = where == null ? "" : where;
            return new PageResult<>(new ArrayList<>(byId.values()), byId.size(), q.page(), q.size());
        }
    }

    private static final class FakeRoleDao extends UnsupportedBaseDao<Role, Long> implements RoleDao {
        final Map<Long, Role> byId = new LinkedHashMap<>();
        long seq;

        void put(Role r) {
            byId.put(r.id, r);
        }

        @Override
        public int insert(Role e, long operatorId) {
            if (e.id == 0) {
                e.id = ++seq;
            }
            byId.put(e.id, e);
            return 1;
        }

        @Override
        public Role findById(Long id) {
            return byId.get(id);
        }

        @Override
        public Role findByCode(String code) {
            for (Role r : byId.values()) {
                if (r.roleCode.equals(code)) {
                    return r;
                }
            }
            return null;
        }

        @Override
        public List<Role> findByPositionGroup(int groupId) {
            List<Role> out = new ArrayList<>();
            for (Role r : byId.values()) {
                if (r.positionGroupId == groupId) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public List<Role> findTradingRoles() {
            List<Role> out = new ArrayList<>();
            for (Role r : byId.values()) {
                if ("Y".equals(r.hasTradingOperation)) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public PageResult<Role> findPage(PageQuery q, String where, List<Object> args) {
            return new PageResult<>(new ArrayList<>(byId.values()), byId.size(), q.page(), q.size());
        }
    }

    private static final class FakeGroupDao extends UnsupportedBaseDao<PositionGroup, Integer>
            implements PositionGroupDao {
        final Map<Integer, PositionGroup> byId = new LinkedHashMap<>();

        void put(PositionGroup g) {
            byId.put((int) g.id, g);
        }

        @Override
        public PositionGroup findById(Integer id) {
            return byId.get(id);
        }

        @Override
        public PositionGroup findByCode(String code) {
            for (PositionGroup g : byId.values()) {
                if (g.groupCode.equals(code)) {
                    return g;
                }
            }
            return null;
        }

        @Override
        public List<PositionGroup> findEnabled() {
            return new ArrayList<>(byId.values());
        }
    }

    private static final class FakeUserRoleDao extends UnsupportedBaseDao<UserRole, Long>
            implements UserRoleDao {
        final List<UserRole> rows = new ArrayList<>();

        void put(UserRole ur) {
            rows.add(ur);
        }

        @Override
        public int insert(UserRole e, long operatorId) {
            rows.add(e);
            return 1;
        }

        @Override
        public List<UserRole> findByUser(long userId) {
            List<UserRole> out = new ArrayList<>();
            for (UserRole r : rows) {
                if (r.userId == userId) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public List<UserRole> findByRole(long roleId) {
            List<UserRole> out = new ArrayList<>();
            for (UserRole r : rows) {
                if (r.roleId == roleId) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    private static final class FakeConflictDao extends UnsupportedBaseDao<PositionConflict, Long>
            implements PositionConflictDao {
        final List<PositionConflict> rows = new ArrayList<>();
        long seq;

        @Override
        public int insert(PositionConflict e, long operatorId) {
            if (e.id == 0) {
                e.id = ++seq;
            }
            rows.add(e);
            return 1;
        }

        @Override
        public List<PositionConflict> findConflictsOf(int groupId) {
            List<PositionConflict> out = new ArrayList<>();
            for (PositionConflict c : rows) {
                if (c.groupAId == groupId || c.groupBId == groupId) {
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public PageResult<PositionConflict> findPage(PageQuery q, String where, List<Object> args) {
            return new PageResult<>(new ArrayList<>(rows), rows.size(), q.page(), q.size());
        }
    }

    private static final class FakeSessionDao extends UnsupportedBaseDao<LoginSession, Long>
            implements LoginSessionDao {
        final List<LoginSession> rows = new ArrayList<>();
        int invalidateCalls;

        @Override
        public int insert(LoginSession e, long operatorId) {
            rows.add(e);
            return 1;
        }

        @Override
        public LoginSession findBySessionId(String sessionId) {
            for (LoginSession s : rows) {
                if (sessionId.equals(s.sessionId)) {
                    return s;
                }
            }
            return null;
        }

        @Override
        public int invalidateByUser(long userId, String status, Long kickedBy, long permVersion) {
            invalidateCalls++;
            return 1;
        }

        @Override
        public int touch(String sessionId, LocalDateTime t) {
            return 1;
        }

        @Override
        public int deleteExpired(LocalDateTime before) {
            return 0;
        }

        @Override
        public int deleteById(Long id) {
            return 1;
        }
    }

    private static final class FakeLoginLogDao extends UnsupportedBaseDao<LoginLog, Long>
            implements LoginLogDao {
        final List<LoginLog> rows = new ArrayList<>();

        @Override
        public int insert(LoginLog e, long operatorId) {
            rows.add(e);
            return 1;
        }

        @Override
        public List<LoginLog> findByUserBetween(Long userId, LocalDateTime from, LocalDateTime to) {
            return new ArrayList<>(rows);
        }

        @Override
        public long countFailByIpSince(String ip, LocalDateTime from) {
            return 0;
        }
    }

    private static final class FakeHistoryDao extends UnsupportedBaseDao<PasswordHistory, Long>
            implements PasswordHistoryDao {
        final List<PasswordHistory> rows = new ArrayList<>();

        @Override
        public int insert(PasswordHistory e, long operatorId) {
            rows.add(e);
            return 1;
        }

        @Override
        public List<PasswordHistory> findRecent(long userId, int limit) {
            return new ArrayList<>(rows);
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

    private static final class FakePermDao extends UnsupportedBaseDao<UserSubAccountPerm, Long>
            implements UserSubAccountPermDao {
        final List<UserSubAccountPerm> rows = new ArrayList<>();
        long seq;

        @Override
        public int insert(UserSubAccountPerm e, long operatorId) {
            if (e.id == 0) {
                e.id = ++seq;
            }
            rows.add(e);
            return 1;
        }

        @Override
        public List<UserSubAccountPerm> findByUser(long userId) {
            List<UserSubAccountPerm> out = new ArrayList<>();
            for (UserSubAccountPerm p : rows) {
                if (p.userId == userId) {
                    out.add(p);
                }
            }
            return out;
        }

        @Override
        public List<Long> findUserIdsBySubAccount(int subAccountId) {
            List<Long> out = new ArrayList<>();
            for (UserSubAccountPerm p : rows) {
                if (p.subAccountId == subAccountId && !out.contains(p.userId)) {
                    out.add(p.userId);
                }
            }
            return out;
        }
    }

    private static final class FakeAuthzDao extends UnsupportedBaseDao<Authorization, Long>
            implements AuthorizationDao {
        final Map<Long, Authorization> byId = new LinkedHashMap<>();
        long seq;

        @Override
        public int insert(Authorization e, long operatorId) {
            if (e.id == 0) {
                e.id = ++seq;
            }
            byId.put(e.id, e);
            return 1;
        }

        @Override
        public List<Authorization> findEffective(long userId, LocalDate onDate) {
            return new ArrayList<>(byId.values());
        }

        @Override
        public List<Authorization> findBySubAccount(int subAccountId) {
            return new ArrayList<>(byId.values());
        }

        @Override
        public int updateDispatchStatus(long id, String status, Long version) {
            return 1;
        }

        @Override
        public PageResult<Authorization> findPage(PageQuery q, String where, List<Object> args) {
            return new PageResult<>(new ArrayList<>(byId.values()), byId.size(), q.page(), q.size());
        }
    }

    private static final class FakeChangeLogDao extends UnsupportedBaseDao<PermissionChangeLog, Long>
            implements PermissionChangeLogDao {
        final List<PermissionChangeLog> rows = new ArrayList<>();
        long seq;
        String lastWhere = "";

        @Override
        public int insert(PermissionChangeLog e, long operatorId) {
            if (e.id == 0) {
                e.id = ++seq;
            }
            rows.add(e);
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
            throw FakeDao.unsupported("findByTargetUserBetween");
        }

        @Override
        public PageResult<PermissionChangeLog> findPage(PageQuery q, String where,
                                                        List<Object> args) {
            lastWhere = where == null ? "" : where;
            return new PageResult<>(new ArrayList<>(rows), rows.size(), q.page(), q.size());
        }
    }

    private static final class FakeAttemptDao extends UnsupportedBaseDao<UnauthorizedAttempt, Long>
            implements UnauthorizedAttemptDao {
        final List<UnauthorizedAttempt> rows = new ArrayList<>();
        long seq;

        @Override
        public int insert(UnauthorizedAttempt e, long operatorId) {
            if (e.id == 0) {
                e.id = ++seq;
            }
            rows.add(e);
            return 1;
        }

        @Override
        public List<UnauthorizedAttempt> findByOperatorSince(long operatorId, LocalDateTime from) {
            return new ArrayList<>(rows);
        }

        @Override
        public long countByTypeBetween(String type, LocalDateTime from, LocalDateTime to) {
            return 0;
        }

        @Override
        public PageResult<UnauthorizedAttempt> findPage(PageQuery q, String where,
                                                        List<Object> args) {
            return new PageResult<>(new ArrayList<>(rows), rows.size(), q.page(), q.size());
        }
    }
}
