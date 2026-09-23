package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.entity.PasswordHistory;
import com.hedgeguard.dao.entity.UserAccount;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FR-AUTH-16}「首次登录与重置后强制修改」的<b>真实 DAO 端到端</b>判据：
 * 「未改口令 ⇒ 业务端点被拒；改密成功 ⇒ 放行；库中标记归零」。
 *
 * <p>走 {@link RequestDispatcher} → {@link SessionAuthFilter}（门禁）→ 真实 {@link PasswordHandler}
 * → 真实 {@link AuthService#changePassword} → 真实 {@link DmUserAccountDao}（{@code must_change_password} 落库真值），
 * 断言读<b>库中原始列值</b>（非内存对象推断），与 {@code T-144/O-2} 同一取证口径。
 *
 * <p><b>一处诚实披露（引擎限制）</b>：本用例<b>不经</b> {@code AuthService.login} 造会话——{@code login}
 * 成功路径会调用 {@code DmUserAccountDao.unlock}（{@code SET locked_until = NULL}），而
 * {@link InMemoryDmEngine} 的 {@code UPDATE} 只建模 {@code col = ?} 与 {@code col = col + 1}，
 * 会把 {@code NULL} 误存为数值 ⇒ 后续 {@code findById} 读 {@code locked_until} 时 {@code getTimestamp}
 * 抛 {@code ClassCastException}。故本用例直接向 {@link SessionStore} 播入「登录后会产生的」置位会话
 * （{@code login→session.mustChangePassword} 的贯通由 {@code AuthServiceTest} 覆盖）。被守卫的写入
 * （{@code DmUserAccountDao.updatePasswordHash}）<b>是真实 DAO</b>。
 *
 * <p><b>第二处诚实披露（引擎限制）</b>：{@code updatePasswordHash} 经 {@code executeUpdateSql}
 * 传入 {@code java.sql.Timestamp}，而引擎 {@code setObject} 原样存该对象（{@code setTimestamp} 才转
 * {@code LocalDateTime}）⇒ 改密后经 DAO {@code findById} 读 {@code password_updated_time} 亦抛
 * {@code ClassCastException}。故 ④ 的断言读<b>引擎原始行</b>（库中真值）而非 DAO 读回。
 */
class MustChangePasswordRealDaoTest {

    /** 固定业务时刻（可注入时钟，{@code NFR-TEST-4}）。 */
    private static final Instant T0 = Instant.parse("2026-09-23T02:00:00Z");

    /** 建表脚本（含 {@code t_user} / {@code t_password_history} / {@code t_login_session}）。 */
    private static final String SCHEMA = "docs/DEPLOY/schema/m1_config_auth.sql";

    private static final long UID = 1L;
    private static final String SID = "S-CHG";
    private static final String OLD_PWD = "Hg#2026Trading01";
    private static final String NEW_PWD = "Hg#2027Trading01";

    private InMemoryDmEngine engine;
    private DmUserAccountDao userDao;
    private SessionStore.InMemory store;
    private RequestDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        engine = new InMemoryDmEngine();
        Path schema = repoRoot().resolve(SCHEMA);
        assertTrue(Files.isRegularFile(schema), "缺少建表脚本：" + schema);
        engine.executeScript(new String(Files.readAllBytes(schema), StandardCharsets.UTF_8));

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        userDao = new DmUserAccountDao(tx);
        store = new SessionStore.InMemory();
        AuthService authService = new AuthService(tx, userDao, new DmRoleDao(tx),
                new DmUserRoleDao(tx), new DmPositionConflictDao(tx), new DmLoginSessionDao(tx),
                new DmLoginLogDao(tx), new FakeHistoryDao(), new DmUserSubAccountPermDao(tx),
                new DmUnauthorizedAttemptDao(tx), store, new DmAuthorizationDao(tx),
                new DmPermissionChangeLogDao(tx), new DmPositionGroupDao(tx), Config.empty());

        seedUser();

        Router router = new Router();
        router.register("POST", ApiRoutes.full("/auth/password"), new PasswordHandler(authService));
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

    @Test
    @DisplayName("①→② 置位会话访问业务端点被拒（10003）；真实改密成功后放行且库中标记归零")
    void changePasswordLiftsGateAndClearsFlagInDatabase() {
        // 置位会话（等价于 login 对 must_change_password='Y' 用户产生的会话）
        store.save(new Session(SID, UID, "lisi", 1L, true, Clocks.now().plusMinutes(30)));
        assertEquals("Y", rawFlag(), "种子须为置位态 'Y'（否则无判别力）");

        // ① 未改口令：业务端点被拒（服务端强制）
        HttpResult blocked = call("GET", ApiRoutes.full("/auth/user"), null);
        assertEquals(403, blocked.status, "置位会话访问业务端点须被拒");
        assertTrue(body(blocked).contains("\"code\":10003"), "body=" + body(blocked));

        // ② 改密（真实 PasswordHandler → 真实 AuthService → 真实 DAO）
        HttpResult changed = call("POST", ApiRoutes.full("/auth/password"),
                "{\"oldPassword\":\"" + OLD_PWD + "\",\"newPassword\":\"" + NEW_PWD + "\"}");
        assertEquals(200, changed.status, "本人改口令须成功 body=" + body(changed));

        // ③ 改密后：业务端点放行
        HttpResult allowed = call("GET", ApiRoutes.full("/auth/user"), null);
        assertEquals(200, allowed.status, "改密成功后业务端点须放行");

        // ④ 库中真值归零 + 内存会话标记清除 + 新口令可用
        assertEquals("N", rawFlag(), "改密后 t_user.must_change_password 原始列值须为 'N'");
        Session inStore = store.find(SID);
        assertNotNull(inStore);
        assertFalse(inStore.mustChangePassword, "内存会话的强制改口令标记须同步清除");
        String rawHash = String.valueOf(engine.table("t_user").rows.get(0).get("password_hash"));
        assertTrue(PasswordHasher.verify(NEW_PWD, rawHash), "新口令须可校验（库中真值）");
        assertFalse(PasswordHasher.verify(OLD_PWD, rawHash), "旧口令须失效（库中真值）");
    }

    @Test
    @DisplayName("③ 未置位会话：业务端点不受影响（防误拒）")
    void unflaggedSessionUnaffected() {
        store.save(new Session(SID, UID, "lisi", 1L, false, Clocks.now().plusMinutes(30)));
        assertEquals(200, call("GET", ApiRoutes.full("/auth/user"), null).status);
    }

    private HttpResult call(String method, String path, String bodyJson) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + SID);
        return dispatcher.dispatch(new RequestContext(method, path, null, headers, bodyJson,
                "HG-CHG", "127.0.0.1"));
    }

    private String body(HttpResult r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    /** 读 {@code t_user.must_change_password} 的引擎原始列值（库中真值）。 */
    private String rawFlag() {
        Object raw = engine.table("t_user").rows.get(0).get("must_change_password");
        return raw == null ? null : String.valueOf(raw);
    }

    /** 经真实 DAO 写入一个置位用户（{@code must_change_password='Y'}，可凭旧口令登录）。 */
    private void seedUser() {
        UserAccount u = new UserAccount();
        u.username = "lisi";
        u.realName = "李四";
        u.naturalPersonKey = "NP-001";
        u.department = "财务部";
        u.isEnterpriseLeader = "N";
        u.status = "ENABLED";
        u.passwordHash = PasswordHasher.hash(OLD_PWD);
        u.mustChangePassword = "Y";
        u.failCount = 0;
        assertEquals(1, userDao.insert(u, 0L), "种子用户须写入 1 行");
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
