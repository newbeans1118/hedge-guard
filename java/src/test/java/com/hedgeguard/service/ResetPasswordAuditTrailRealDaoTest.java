package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.entity.LoginSession;
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
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionStore;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-150}（{@code FR-AUTH-16} 处理规则「支持管理员重置（<b>重置须留痕</b>……）」）——
 * {@link AuthService#resetPassword} <b>独立审计留痕</b>的<b>真实 DAO 回归守卫</b>。
 *
 * <p><b>缺陷（修复前）</b>：{@code resetPassword} 只写 {@code t_user}（新哈希 + 强制改口令标记）与
 * {@code t_login_session.kicked_by}，<b>不写</b> {@code t_permission_change_log}（仅 {@code LOG.info}）。
 * 目标用户<b>无在线会话</b>时 {@code invalidateByUser} 命中 <b>0 行</b> ⇒ <b>不存在任何持久化的操作人记录</b>
 * （{@code t_password_history} 无操作人列，{@code LOG} 不属持久化审计面）。同类一致性：
 * {@code createUser} / {@code updateUser} / {@code disableUser} / {@code enableUser} / {@code forceLogout}
 * 均调 {@code recordChange}，唯 {@code resetPassword} 不调。
 *
 * <p><b>为何必须走真实 DAO</b>（本项目先例 {@code T-144} / {@code T-148}）：假 DAO 的
 * {@code PermissionChangeLogDao.insert} 只累加内存列表、不落表，且 {@code invalidateByUser} 不建模行数 ⇒
 * 「留痕被删掉」或「重复留痕」都全绿。本用例经 {@link InMemoryDmEngine} 求值真实
 * {@link DmPermissionChangeLogDao} / {@link DmLoginSessionDao} / {@link DmUserAccountDao} 的 SQL，
 * 断言读<b>库中真值</b>（引擎原始行），非内存对象推断。
 *
 * <p><b>承重（自证）</b>：删掉 {@code resetPassword} 中新增的 {@code recordChange} 调用 ⇒ 判据 ①/② 必红
 * （留痕表 0 行）。只改「留痕条数」不做，判据 ② 的红点即「期望 1 行、实为 2 行」。
 *
 * <p><b>一处诚实披露（引擎限制）</b>：{@link InMemoryDmEngine} 的 {@code WHERE} 只求值
 * {@code =} / {@code IN} / {@code IS NULL} / {@code LIKE}，<b>不解析 {@code >}</b>；而
 * {@code recordPasswordHistory} 会调 {@code DmPasswordHistoryDao.deleteBeyond}（SQL 含 {@code seq_no > ?}）
 * ⇒ 本用例对<b>口令历史</b> DAO 使用内存假件（与留痕无关，不影响守卫强度）；被守卫的写入
 * （{@code DmPermissionChangeLogDao.insert}）<b>是真实 DAO</b>。
 */
class ResetPasswordAuditTrailRealDaoTest {

    /** 固定业务时刻（可注入时钟，{@code NFR-TEST-4}）。 */
    private static final Instant T0 = Instant.parse("2026-11-08T02:00:00Z");

    /** 建表脚本（含 {@code t_user} / {@code t_login_session} / {@code t_permission_change_log}）。 */
    private static final String SCHEMA = "docs/DEPLOY/schema/m1_config_auth.sql";

    private static final long UID = 1L;
    private static final long OPERATOR = 9L;
    private static final String OLD_PWD = "Hg#2026Trading01";
    private static final String NEW_PWD = "Hg#2027Trading01";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmUserAccountDao userDao;
    private DmLoginSessionDao loginSessionDao;
    private SessionStore.InMemory store;
    private AuthService service;

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        engine = new InMemoryDmEngine();
        Path schema = repoRoot().resolve(SCHEMA);
        assertTrue(Files.isRegularFile(schema), "缺少建表脚本：" + schema);
        engine.executeScript(new String(Files.readAllBytes(schema), StandardCharsets.UTF_8));

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        userDao = new DmUserAccountDao(tx);
        loginSessionDao = new DmLoginSessionDao(tx);
        store = new SessionStore.InMemory();
        service = new AuthService(tx, userDao, new DmRoleDao(tx), new DmUserRoleDao(tx),
                new DmPositionConflictDao(tx), loginSessionDao, new DmLoginLogDao(tx),
                new FakeHistoryDao(), new DmUserSubAccountPermDao(tx),
                new DmUnauthorizedAttemptDao(tx), store,
                new DmAuthorizationDao(tx), new DmPermissionChangeLogDao(tx),
                new DmPositionGroupDao(tx), Config.empty());

        seedUser();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ ① 无在线会话

    @Test
    @DisplayName("① 判据1：目标用户【无在线会话】时重置 ⇒ t_permission_change_log 恰一行且"
            + " operator_id = 实际操作人（删掉 recordChange 必红）")
    void resetWithoutOnlineSessionLeavesOneAuditRowWithOperator() {
        // 前置：目标用户无任何在线会话 ⇒ invalidateByUser 命中 0 行（缺陷的「无留痕」场景）
        assertEquals(0, engine.table("t_login_session").rows.size(),
                "前置：本用例须处于「目标用户无在线会话」分支（否则判据1无判别力）");
        assertEquals(0, changeLogRows().size(), "前置：留痕表须为空");

        service.resetPassword(UID, NEW_PWD, OPERATOR);

        // ①-a 留痕表恰一行（真实 DAO 落表，读库中真值）
        assertEquals(1, changeLogRows().size(),
                "重置成功后须留痕：t_permission_change_log 应恰有 1 行，实际 " + changeLogRows().size()
                        + " 行（0 行 = resetPassword 未写留痕，FR-AUTH-16「重置须留痕」落空）");

        Map<String, Object> row = changeLogRows().get(0);
        assertEquals(AuthService.CHANGE_USER_ATTR, str(row, "change_target"),
                "留痕类别须与 enableUser 同形 = USER_ATTR");
        assertEquals(String.valueOf(UID), str(row, "target_id"), "变更对象 ID 须为目标用户");
        assertEquals(String.valueOf(UID), str(row, "target_user_id"), "受影响用户须为目标用户");
        assertEquals(String.valueOf(OPERATOR), str(row, "operator_id"),
                "operator_id 须为【实际操作人】（这是「无在线会话时唯一可追溯操作人」的承载点）");
        assertNull(row.get("reviewer_id"), "重置口令不涉双人复核 ⇒ reviewer_id 为 null（与 enableUser 同形）");
        assertEquals("N", str(row, "rejected"), "成功变更须为未拒绝");
        assertEquals("NOT_REQUIRED", str(row, "dispatch_status"), "非对接类变更不派发");

        // ①-b 摘要不得携带口令材料（FR-AUTH-16 / R-AUTH-6：不以明文展示或传递口令）
        String after = str(row, "after_value");
        assertNotNull(after, "after_value 须记录变更摘要");
        assertTrue(after.contains("重置口令"), "after_value 须可读地说明本次变更为「重置口令」，实际=" + after);
        assertFalse(after.contains(NEW_PWD), "after_value 不得写入口令明文，实际=" + after);
        assertFalse(after.contains(OLD_PWD), "after_value 不得写入口令旧明文，实际=" + after);
        assertFalse(after.toLowerCase().contains("pbkdf2"), "after_value 不得写入口令哈希，实际=" + after);

        // ①-c 反证：会话失效确实一次都没命中（无会话表行）⇒ 若无本行留痕则完全无操作人记录
        assertEquals(0, engine.table("t_login_session").rows.size(),
                "本场景会话表须仍为空（说明缺陷路径下不存在任何会话侧留痕）");
    }

    // ------------------------------------------------------------ ② 有在线会话

    @Test
    @DisplayName("② 判据2：目标用户【有在线会话】时重置 ⇒ 留痕表仍【仅一行】（不得因会话失效重复写）")
    void resetWithOnlineSessionStillLeavesExactlyOneAuditRow() {
        store.save(session("S-1", UID));
        seedActiveSessionRow("S-1", UID);
        assertEquals(1, engine.table("t_login_session").rows.size(), "前置：库中须有一行 ACTIVE 会话");

        service.resetPassword(UID, NEW_PWD, OPERATOR);

        // 会话失效确实发生——这正是「重复写」的风险来源（失效路径另行写 t_login_session）
        LoginSession kicked = loginSessionDao.findBySessionId("S-1");
        assertNotNull(kicked, "库中会话行应存在");
        assertEquals("KICKED", kicked.status, "前置：重置须使目标用户在线会话失效");
        assertNotNull(kicked.kickedBy, "kicked_by 须留痕操作人");
        assertEquals(OPERATOR, kicked.kickedBy.longValue(), "kicked_by 须为重置操作人");

        assertEquals(1, changeLogRows().size(),
                "有在线会话时亦【仅一行】留痕（重复写会使计数 = 2），实际 " + changeLogRows().size() + " 行");
        assertEquals(String.valueOf(OPERATOR), str(changeLogRows().get(0), "operator_id"),
                "operator_id 须为实际操作人");
        assertEquals(AuthService.CHANGE_USER_ATTR, str(changeLogRows().get(0), "change_target"),
                "留痕类别须为 USER_ATTR");
    }

    // ------------------------------------------------------------ ③ 反向：失败不留痕

    @Test
    @DisplayName("③ 反向：重置【失败】（新口令与当前相同被拒）⇒ 不得留痕（recordChange 须在成功后）")
    void failedResetLeavesNoAuditRow() {
        BizException rejected = assertThrows(BizException.class,
                () -> service.resetPassword(UID, OLD_PWD, OPERATOR),
                "新口令与当前口令相同须拒（FR-AUTH-16 近 N 次禁重用）");
        assertEquals(ErrorCode.PARAM_INVALID, rejected.errorCode(), "须为 10001 参数校验失败");
        assertEquals(0, changeLogRows().size(),
                "失败的重置不得留痕（否则 t_permission_change_log 会记录未发生的变更），实际 "
                        + changeLogRows().size() + " 行");
    }

    // ------------------------------------------------------------ 工具

    /** 读 {@code t_permission_change_log} 的引擎原始行（库中真值）。 */
    private List<Map<String, Object>> changeLogRows() {
        return engine.table("t_permission_change_log").rows;
    }

    private static String str(Map<String, Object> row, String column) {
        Object raw = row.get(column);
        return raw == null ? null : String.valueOf(raw);
    }

    private static Session session(String sid, long userId) {
        return new Session(sid, userId, "lisi", 1L, false, Clocks.now().plusMinutes(30));
    }

    /** 经真实 DAO 写入一个用户（{@code row_version=1}，{@code must_change_password='N'}）。 */
    private void seedUser() {
        UserAccount u = new UserAccount();
        u.username = "lisi";
        u.realName = "李四";
        u.naturalPersonKey = "NP-001";
        u.department = "财务部";
        u.isEnterpriseLeader = "N";
        u.status = "ENABLED";
        u.passwordHash = PasswordHasher.hash(OLD_PWD);
        u.mustChangePassword = "N";
        u.failCount = 0;
        assertEquals(1, userDao.insert(u, 0L), "种子用户须写入 1 行");
        assertEquals(UID, u.id, "自增主键须为 1");
    }

    /** 经真实 DAO 写入一个 ACTIVE 会话行（与内存会话同 sessionId）。 */
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

    /**
     * 口令历史 DAO 的内存假件：仅本用例使用。
     *
     * <p>存在原因：真实 {@code DmPasswordHistoryDao.deleteBeyond} 的 SQL 含 {@code seq_no > ?}，而
     * {@link InMemoryDmEngine} 不求值 {@code >}（见类注释「诚实披露」）。口令历史与留痕表无因果关系，
     * 故不影响守卫强度。
     */
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
