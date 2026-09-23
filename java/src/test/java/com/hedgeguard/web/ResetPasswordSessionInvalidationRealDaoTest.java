package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.entity.LoginSession;
import com.hedgeguard.dao.entity.PasswordHistory;
import com.hedgeguard.dao.entity.PositionGroup;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.impl.DmAuthorizationDao;
import com.hedgeguard.dao.impl.DmLoginLogDao;
import com.hedgeguard.dao.impl.DmLoginSessionDao;
import com.hedgeguard.dao.impl.DmPermissionChangeLogDao;
import com.hedgeguard.dao.impl.DmPositionConflictDao;
import com.hedgeguard.dao.impl.DmPositionGroupDao;
import com.hedgeguard.dao.impl.DmRoleDao;
import com.hedgeguard.dao.impl.DmUnauthorizedAttemptDao;
import com.hedgeguard.dao.impl.DmUserAccountDao;
import com.hedgeguard.dao.impl.DmUserRoleDao;
import com.hedgeguard.dao.impl.DmUserSubAccountPermDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.service.PasswordHasher;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.handler.auth.PasswordHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-148}（{@code FR-AUTH-16} 处理规则「重置后强制修改」）——<b>对已在线的旧会话立即生效</b>的
 * <b>真实 DAO 端到端回归守卫</b>。
 *
 * <p><b>缺陷（修复前）</b>：{@link AuthService#resetPassword} 在同一事务内写新口令哈希 +
 * {@code must_change_password='Y'}，但<b>从不失效目标用户既有会话</b>。门禁
 * （{@link SessionAuthFilter} / {@link Session#mustChangePassword}）读的是<b>登录时快照</b>、不读库真值
 * ⇒ 管理员重置后，<b>正在登录的那个旧会话</b>快照仍为 {@code false}，业务端点照常放行。门禁只对
 * 「重置后按新口令<b>重新登录</b>的会话」生效——恰好漏掉管理员因怀疑口令泄露而最想掐掉的在线会话。
 *
 * <p><b>修复</b>：{@code resetPassword} 在事务提交后调用 {@code invalidateSessionsImmediately}
 * 失效目标用户全部会话（内存 + 库），并留痕操作人（{@code t_login_session.kicked_by}）。语义结果 =
 * 旧会话消失 ⇒ 过滤器落 {@link ErrorCode#NOT_AUTHENTICATED}（{@code 10006}，而<b>非</b> {@code 10003}）；
 * 重新登录的新会话携带置位标记 ⇒ 被 {@code 10003} 门禁。
 *
 * <p><b>为何必须走真实 DAO</b>（本项目先例 {@code T-144}）：假 DAO 的 {@code invalidateByUser} 只累加计数、
 * {@code updateById} 不建模 {@code row_version} ⇒ 「修复被删掉」也全绿。本用例经 {@link InMemoryDmEngine}
 * 求值真实 {@link DmUserAccountDao} / {@link DmLoginSessionDao} 的 SQL，断言读<b>库中真值</b>
 * （引擎原始行 + 真实 DAO 读回），与 {@code T-144}/{@code T-146} 同一取证口径。
 *
 * <p><b>一处诚实披露（引擎限制）</b>：{@link InMemoryDmEngine} 的 {@code WHERE} 只求值
 * {@code =} / {@code IN} / {@code IS NULL} / {@code LIKE}，<b>不解析 {@code >}</b>；{@code recordPasswordHistory}
 * 会调用 {@code DmPasswordHistoryDao.deleteBeyond}（SQL 含 {@code seq_no > ?}）⇒ 本用例对该 DAO 使用
 * 内存假件（口令历史与「会话失效」无因果关系，不影响本守卫强度）。被守卫的写入
 * （{@code DmUserAccountDao.updateById} / {@code DmLoginSessionDao.invalidateByUser}）<b>均为真实 DAO</b>。
 *
 * <p><b>第二处诚实披露（引擎限制）</b>：{@code login} 成功路径调用 {@code DmUserAccountDao.unlock}
 * （{@code SET locked_until = NULL}），而引擎 {@code UPDATE} 把非 {@code ?} 形态的 {@code NULL} 当作
 * {@code col = col + 1} 自增 ⇒ 其后再经 DAO {@code findById} 读 {@code locked_until} 会抛
 * {@code ClassCastException}。故 ② 在 {@code login} 之后<b>不再</b>触发 {@code findById}（过滤器读内存存储，
 * 无需读库）。
 */
class ResetPasswordSessionInvalidationRealDaoTest {

    /** 固定业务时刻（可注入时钟，{@code NFR-TEST-4}）。 */
    private static final Instant T0 = Instant.parse("2026-09-24T02:00:00Z");

    /** 建表脚本（含 {@code t_user} / {@code t_login_session} / {@code t_role} 等）。 */
    private static final String SCHEMA = "docs/DEPLOY/schema/m1_config_auth.sql";

    /** 被重置 / 启用的目标用户。 */
    private static final long UID = 1L;

    /** 反向用例中的「未被重置」在线用户。 */
    private static final long OTHER_UID = 2L;

    private static final long OPERATOR = 9L;
    private static final long REVIEWER = 8L;
    private static final String OLD_PWD = "Hg#2026Trading01";
    private static final String NEW_PWD = "Hg#2027Trading01";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmUserAccountDao userDao;
    private DmLoginSessionDao loginSessionDao;
    private SessionStore.InMemory store;
    private AuthService service;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        engine = new InMemoryDmEngine();
        Path schema = repoRoot().resolve(SCHEMA);
        assertTrue(Files.isRegularFile(schema), "缺少建表脚本：" + schema);
        engine.executeScript(new String(Files.readAllBytes(schema), StandardCharsets.UTF_8));

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        this.tx = tx;
        userDao = new DmUserAccountDao(tx);
        loginSessionDao = new DmLoginSessionDao(tx);
        DmRoleDao roleDao = new DmRoleDao(tx);
        DmPositionGroupDao groupDao = new DmPositionGroupDao(tx);
        DmUserRoleDao userRoleDao = new DmUserRoleDao(tx);
        store = new SessionStore.InMemory();
        service = new AuthService(tx, userDao, roleDao, userRoleDao,
                new DmPositionConflictDao(tx), loginSessionDao, new DmLoginLogDao(tx),
                new FakeHistoryDao(), new DmUserSubAccountPermDao(tx),
                new DmUnauthorizedAttemptDao(tx), store, new DmAuthorizationDao(tx),
                new DmPermissionChangeLogDao(tx), groupDao, Config.empty());

        seedUser(UID, "lisi", "NP-001");
        seedUser(OTHER_UID, "zhang", "NP-002");
        // enableUser 路径要求目标用户已有角色（否则 10010「未配置角色即启用」）；经真实 DAO 落
        // t_position_group → t_role → t_user_role 三行（FK 链）。
        seedRoleFor(UID);

        Router router = new Router();
        router.register("PUT", ApiRoutes.full("/auth/password"), new PasswordHandler(service));
        router.register("POST", ApiRoutes.full("/auth/password"), new PasswordHandler(service));
        router.register("GET", ApiRoutes.full("/auth/user"),
                ctx -> HttpResult.json(200, ApiResponse.ok("business-ok", ctx.traceId())));
        router.register("POST", ApiRoutes.full("/auth/logout"),
                ctx -> HttpResult.json(200, ApiResponse.ok("bye", ctx.traceId())));
        dispatcher = new RequestDispatcher(router, new StaticHandler(Paths.get(".")),
                new SessionAuthFilter(store, "HGSESSION"));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ ① 主判据

    @Test
    @DisplayName("① 主判据：管理员重置 ⇒ 已登录（未置位）旧会话立即失效 ⇒ 业务端点 10006（非 10003）")
    void resetPasswordInvalidatesExistingSession() {
        String sid = "S-1";
        store.save(session(sid, UID, false));
        seedActiveSessionRow(sid, UID);
        // 前置：未置位会话本可访问业务端点（否则无判别力）
        assertEquals(200, call(sid, "GET", ApiRoutes.full("/auth/user"), null).status,
                "前置：未置位会话应放行");
        assertEquals("N", rawFlag(UID), "前置：种子 must_change_password 须为 'N'（否则无判别力）");

        service.resetPassword(UID, NEW_PWD, OPERATOR);

        // 内存会话被清 ⇒ 过滤器落 10006（会话不存在/已超时）——不是 10003
        assertNull(store.find(sid), "重置须立即失效目标用户既有内存会话");
        HttpResult blocked = call(sid, "GET", ApiRoutes.full("/auth/user"), null);
        assertEquals(401, blocked.status, "旧会话访问业务端点须被拒 body=" + body(blocked));
        assertTrue(body(blocked).contains("\"code\":10006"),
                "须为 10006（会话不存在/已超时），非 10003；body=" + body(blocked));

        // 库中真值：会话行被 KICKED 且留痕操作人（FR-AUTH-16「重置须留痕」）
        LoginSession row = loginSessionDao.findBySessionId(sid);
        assertNotNull(row, "库中会话行应存在");
        assertEquals("KICKED", row.status, "库中会话状态须为 KICKED");
        assertNotNull(row.kickedBy, "kicked_by 须被写入（留痕重置操作人）");
        assertEquals(OPERATOR, row.kickedBy.longValue(), "kicked_by 须为重置操作人");

        // t_user 真值：标记置 'Y'、新口令生效、旧口令失效
        assertEquals("Y", rawFlag(UID), "重置后 must_change_password 原始列须为 'Y'");
        String rawHash = String.valueOf(rawUserRow(UID).get("password_hash"));
        assertTrue(PasswordHasher.verify(NEW_PWD, rawHash), "新口令须可校验（库中真值）");
        assertFalse(PasswordHasher.verify(OLD_PWD, rawHash), "旧口令须失效（库中真值）");
    }

    // ------------------------------------------------------------ ② 重登后受门禁

    @Test
    @DisplayName("② 重置后用新口令重新登录 ⇒ 新会话携带置位标记 ⇒ 业务端点 10003")
    void reloginAfterResetIsGated() {
        service.resetPassword(UID, NEW_PWD, OPERATOR);

        Session relogin = service.login("lisi", NEW_PWD, "127.0.0.1", "JUnit");
        assertTrue(relogin.mustChangePassword, "重置后重新登录的会话须携带强制改口令标记");

        HttpResult gated = call(relogin.sessionId, "GET", ApiRoutes.full("/auth/user"), null);
        assertEquals(403, gated.status, "重新登录的会话访问业务端点须被门禁 body=" + body(gated));
        assertTrue(body(gated).contains("\"code\":10003"),
                "须为 10003（功能权限不足：须先改口令）；body=" + body(gated));
    }

    // ------------------------------------------------------------ ③ 反向：不误伤

    @Test
    @DisplayName("③ 反向：目标用户全部会话失效，但未被重置的其他在线用户不受影响")
    void resetKillsAllTargetSessionsButNotOthers() {
        store.save(session("S-1a", UID, false));
        store.save(session("S-1b", UID, false));
        store.save(session("S-2", OTHER_UID, false));
        assertEquals(200, call("S-2", "GET", ApiRoutes.full("/auth/user"), null).status,
                "前置：其他用户会话应放行");

        service.resetPassword(UID, NEW_PWD, OPERATOR);

        assertTrue(call("S-1a", "GET", ApiRoutes.full("/auth/user"), null)
                .status == 401, "目标用户会话 A 须失效");
        assertTrue(call("S-1b", "GET", ApiRoutes.full("/auth/user"), null)
                .status == 401, "目标用户会话 B 须失效（失效该用户全部会话）");
        assertEquals(200, call("S-2", "GET", ApiRoutes.full("/auth/user"), null).status,
                "未被重置的其他在线用户不得受影响（防误伤）");
    }

    // ------------------------------------------------------------ ④ 改口令不误加失效

    @Test
    @DisplayName("④ 本人改口令不得失效本会话（标记清除、同一会话继续可用）")
    void changePasswordKeepsSameSession() {
        String sid = "S-3";
        store.save(session(sid, UID, true)); // 置位会话（等价于首次登录/重置后登录产生的会话）
        assertEquals(403, call(sid, "GET", ApiRoutes.full("/auth/user"), null).status,
                "前置：置位会话应先被门禁");

        HttpResult changed = call(sid, "POST", ApiRoutes.full("/auth/password"),
                "{\"oldPassword\":\"" + OLD_PWD + "\",\"newPassword\":\"" + NEW_PWD + "\"}");
        assertEquals(200, changed.status, "本人改口令须成功 body=" + body(changed));

        Session kept = store.find(sid);
        assertNotNull(kept, "本人改口令不得失效本会话（changePassword 不得误加 invalidate）");
        assertFalse(kept.mustChangePassword, "改密成功后本会话门禁标记须清除");
        assertEquals(200, call(sid, "GET", ApiRoutes.full("/auth/user"), null).status,
                "改密后同一会话应放行业务端点");
    }

    // ------------------------------------------------------------ ⑤ 镜像方向：enableUser

    @Test
    @DisplayName("⑤ 镜像方向：对在线（ENABLED）用户 enableUser 同形置位 ⇒ 既有会话须立即失效（10006）")
    void enableUserInvalidatesOnlineSession() {
        String sid = "S-4";
        store.save(session(sid, UID, false));
        assertEquals(200, call(sid, "GET", ApiRoutes.full("/auth/user"), null).status,
                "前置：未置位会话应放行");

        // 目标用户当前 ENABLED（在线），enableUser 校验只要求「有角色 + 复核人 ≠ 操作人」⇒ 可达
        service.enableUser(UID, NEW_PWD, OPERATOR, REVIEWER);

        assertNull(store.find(sid), "enableUser（重置口令）须立即失效目标用户既有会话");
        HttpResult blocked = call(sid, "GET", ApiRoutes.full("/auth/user"), null);
        assertEquals(401, blocked.status, "旧会话访问业务端点须被拒 body=" + body(blocked));
        assertTrue(body(blocked).contains("\"code\":10006"),
                "须为 10006；body=" + body(blocked));
        assertEquals("Y", rawFlag(UID), "启用后 must_change_password 原始列须为 'Y'");
        assertEquals("ENABLED", String.valueOf(rawUserRow(UID).get("status")));
    }

    // ------------------------------------------------------------ 工具

    private HttpResult call(String sid, String method, String path, String bodyJson) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + sid);
        return dispatcher.dispatch(new RequestContext(method, path, null, headers, bodyJson,
                "HG-T148", "127.0.0.1"));
    }

    private String body(HttpResult r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    private static Session session(String sid, long userId, boolean mustChange) {
        return new Session(sid, userId, userId == UID ? "lisi" : "zhang", 1L, mustChange,
                Clocks.now().plusMinutes(30));
    }

    /** 读 {@code t_user} 指定用户的引擎原始行（库中真值）。 */
    private Map<String, Object> rawUserRow(long userId) {
        for (Map<String, Object> row : engine.table("t_user").rows) {
            if (String.valueOf(userId).equals(String.valueOf(row.get("id")))) {
                return row;
            }
        }
        throw new IllegalStateException("t_user 无 id=" + userId + " 的原始行");
    }

    private String rawFlag(long userId) {
        Object raw = rawUserRow(userId).get("must_change_password");
        return raw == null ? null : String.valueOf(raw);
    }

    /** 经真实 DAO 写入一个用户（{@code row_version=1}，{@code must_change_password='N'}）。 */
    private void seedUser(long id, String username, String npk) {
        UserAccount u = new UserAccount();
        u.username = username;
        u.realName = username;
        u.naturalPersonKey = npk;
        u.department = "财务部";
        u.isEnterpriseLeader = "N";
        u.status = "ENABLED";
        u.passwordHash = PasswordHasher.hash(OLD_PWD);
        u.mustChangePassword = "N";
        u.failCount = 0;
        assertEquals(1, userDao.insert(u, 0L), "种子用户须写入 1 行");
        assertEquals(id, u.id, "自增主键须与预期一致");
    }

    /** 经真实 DAO 写入一个 ACTIVE 会话行（与内存会话同 sessionId，供库侧断言）。 */
    private void seedActiveSessionRow(String sid, long userId) {
        LoginSession s = new LoginSession();
        s.sessionId = sid;
        s.userId = userId;
        s.loginTime = Clocks.now();
        s.lastActiveTime = Clocks.now();
        s.idleTimeoutMinutes = 30;
        s.expireTime = Clocks.now().plusMinutes(30);
        s.clientIp = "127.0.0.1";
        s.userAgent = "JUnit";
        s.status = "ACTIVE";
        s.permissionVersionSnapshot = 1L;
        assertEquals(1, loginSessionDao.insert(s, userId), "会话行须写入 1 行");
    }

    /** 经真实 DAO 建 FK 链：岗位组 → 角色 → 用户角色（使 {@code enableUser} 的「有角色」前置成立）。 */
    private void seedRoleFor(long userId) {
        PositionGroup g = new PositionGroup();
        g.groupCode = "TRADER-G";
        g.groupName = "交易岗组";
        g.frontMidBack = "FRONT";
        g.isConflictEnforced = "Y";
        g.status = "ENABLED";
        assertEquals(1, new DmPositionGroupDao(tx).insert(g, 0L), "岗位组须写入 1 行");

        Role r = new Role();
        r.roleCode = "TRADER";
        r.roleName = "交易岗";
        r.positionGroupId = (int) g.id;
        r.isPreset = "Y";
        r.isBusinessRole = "Y";
        r.hasTradingOperation = "Y";
        r.status = "ENABLED";
        assertEquals(1, new DmRoleDao(tx).insert(r, 0L), "角色须写入 1 行");

        UserRole ur = new UserRole();
        ur.userId = userId;
        ur.roleId = r.id;
        ur.grantedBy = OPERATOR;
        ur.grantedTime = Clocks.now();
        ur.reviewerId = REVIEWER;
        assertEquals(1, new DmUserRoleDao(tx).insert(ur, OPERATOR), "用户角色须写入 1 行");
    }

    /** 口令历史 DAO 的内存假件（引擎不求值 {@code seq_no > ?}，见类注释「诚实披露」）。 */
    private static final class FakeHistoryDao extends UnsupportedBaseDao<PasswordHistory, Long>
            implements PasswordHistoryDao {
        private final List<PasswordHistory> rows = new ArrayList<>();

        @Override
        public int insert(PasswordHistory e, long operatorId) {
            rows.add(e);
            return 1;
        }

        @Override
        public List<PasswordHistory> findRecent(long userId, int limit) {
            List<PasswordHistory> out = new ArrayList<>();
            for (PasswordHistory h : rows) {
                if (h.userId == userId) {
                    out.add(h);
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
            return 0;
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
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test，当前="
                + Paths.get("").toAbsolutePath());
    }
}
