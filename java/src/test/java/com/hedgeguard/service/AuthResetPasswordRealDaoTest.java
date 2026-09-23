package com.hedgeguard.service;

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
import com.hedgeguard.testsupport.UnsupportedBaseDao;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-144}（{@code O-2}）——{@link AuthService#resetPassword}「<b>单条 UPDATE 写全</b>」修复的
 * <b>真实 DAO 回归守卫</b>。
 *
 * <p><b>为何必须走真实 DAO</b>：{@code T-136} 顺带把 {@code resetPassword} 由「两步写」改为
 * 「单条 {@code updateById} 同时写新哈希 + {@code must_change_password='Y'}」。原有用例走
 * <b>假 DAO</b>，其 {@code updateById} <b>不建模 {@code row_version}</b> ⇒ 把实现改回两步写后
 * 仍全绿（{@code te-verify-t136} 实测 558 全绿）⇒ 该修复此前<b>无回归守卫</b>。
 *
 * <p>本用例以 {@link DmUserAccountDao}（真实 DAO）经 {@link InMemoryDmEngine} 求值
 * {@code UPDATE t_user SET … WHERE id = ? AND row_version = ?} 的<b>乐观锁语义</b>：两步写中
 * 第一步 {@code updatePasswordHash} 会把 {@code row_version} 推进 1，使第二步 {@code updateById}
 * 以陈旧版本影响 <b>0 行</b>而被静默吞掉 ⇒ {@code must_change_password} 停在第一步写入的
 * {@code 'N'}。断言读的是<b>库中真值</b>（真实 DAO {@code findById} 读回 + 引擎原始行），
 * 非内存对象推断。
 *
 * <p><b>承重（自证）</b>：把 {@code AuthService.resetPassword} 改回两步写 ⇒ 本用例必红，
 * 红点 = {@code mustChangePassword} 断言（期望 {@code 'Y'}、实为 {@code 'N'}）。
 *
 * <p><b>一处诚实披露（引擎限制）</b>：{@link InMemoryDmEngine} 的 {@code WHERE} 只求值
 * {@code =} / {@code IN} / {@code IS NULL} / {@code LIKE}，<b>不解析 {@code >}</b>；而
 * {@code recordPasswordHistory} 会调用 {@code DmPasswordHistoryDao.deleteBeyond}（其 SQL 含
 * {@code seq_no > ?}）⇒ 本用例对该 DAO 使用内存假件（口令历史与 {@code t_user} 的乐观锁无关，
 * 不影响本守卫强度）；被守卫的写入（{@code DmUserAccountDao.updateById}）<b>是真实 DAO</b>。
 */
class AuthResetPasswordRealDaoTest {

    /** 固定业务时刻（可注入时钟，{@code NFR-TEST-4}）。 */
    private static final Instant T0 = Instant.parse("2026-09-21T02:00:00Z");

    /** 建表脚本（含 {@code t_user} / {@code t_password_history}）。 */
    private static final String SCHEMA = "docs/DEPLOY/schema/m1_config_auth.sql";

    private InMemoryDmEngine engine;
    private DmUserAccountDao userDao;
    private AuthService service;

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        engine = new InMemoryDmEngine();
        Path schema = repoRoot().resolve(SCHEMA);
        assertTrue(Files.isRegularFile(schema), "缺少建表脚本：" + schema);
        engine.executeScript(new String(Files.readAllBytes(schema), StandardCharsets.UTF_8));

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        userDao = new DmUserAccountDao(tx);
        service = new AuthService(tx, userDao, new DmRoleDao(tx), new DmUserRoleDao(tx),
                new DmPositionConflictDao(tx), new DmLoginSessionDao(tx), new DmLoginLogDao(tx),
                new FakeHistoryDao(), new DmUserSubAccountPermDao(tx),
                new DmUnauthorizedAttemptDao(tx), new SessionStore.InMemory(),
                new DmAuthorizationDao(tx), new DmPermissionChangeLogDao(tx),
                new DmPositionGroupDao(tx), Config.empty());

        seedUser();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("T-144/O-2 真实 DAO 守卫：resetPassword 单条 UPDATE 后库中 must_change_password='Y'"
            + "（改回两步写必红）")
    void resetPasswordWritesMustChangeFlagInSingleUpdate() {
        // 种子：真实 DmUserAccountDao.insert ⇒ row_version=1、must_change_password='N'（判别值）
        UserAccount before = userDao.findById(1L);
        assertNotNull(before, "种子用户应落库");
        assertEquals("N", before.mustChangePassword, "种子初值须为 'N'（否则无判别力）");
        assertEquals(1, before.rowVersion, "种子 row_version 须为 1");
        String beforeFlag = rawFlag();

        String oldPwd = "Hg#2026Trading01";
        String newPwd = "Hg#2027Trading01";
        assertTrue(PasswordHasher.verify(oldPwd, before.passwordHash), "种子口令须可用旧口令校验");

        service.resetPassword(1L, newPwd, 9L);

        // ① 库中真值（引擎原始行、列级）——本守卫的判据承载点；先断此条，使回归红点落在断言而非读回异常。
        String afterFlag = rawFlag();
        assertEquals("Y", afterFlag,
                "重置后 t_user.must_change_password 原始列值必须为 'Y'（两步写因乐观锁 0 行会停在 'N'）："
                        + "before=" + beforeFlag + " after=" + afterFlag);

        // ② 真实 DAO 读回（库中真值经 DAO 映射复验，非内存对象推断）
        UserAccount after = userDao.findById(1L);
        assertNotNull(after);
        assertEquals("Y", after.mustChangePassword,
                "重置后库中 must_change_password 必须为 'Y'（经真实 DAO 读回）："
                        + "before=" + before.mustChangePassword + " after=" + after.mustChangePassword);
        assertTrue(PasswordHasher.verify(newPwd, after.passwordHash),
                "须可凭管理员设定的新口令通过哈希校验");
        assertFalse(PasswordHasher.verify(oldPwd, after.passwordHash), "重置后旧口令须失效");
        assertEquals(2, after.rowVersion, "单条 UPDATE 命中并推进版本 1 → 2");
    }

    /** 读 {@code t_user.must_change_password} 的引擎原始列值（库中真值）。 */
    private String rawFlag() {
        Object raw = engine.table("t_user").rows.get(0).get("must_change_password");
        return raw == null ? null : String.valueOf(raw);
    }

    /** 经真实 DAO 写入一个用户（{@code row_version=1}）。 */
    private void seedUser() {
        UserAccount u = new UserAccount();
        u.username = "lisi";
        u.realName = "李四";
        u.naturalPersonKey = "NP-001";
        u.department = "财务部";
        u.isEnterpriseLeader = "N";
        u.status = "ENABLED";
        u.passwordHash = PasswordHasher.hash("Hg#2026Trading01");
        u.mustChangePassword = "N";
        u.failCount = 0;
        assertEquals(1, userDao.insert(u, 0L), "种子用户须写入 1 行");
    }

    /**
     * 口令历史 DAO 的内存假件：仅本用例使用。
     *
     * <p>存在原因：真实 {@code DmPasswordHistoryDao.deleteBeyond} 的 SQL 含 {@code seq_no > ?}，
     * 而 {@link InMemoryDmEngine} 不求值 {@code >}（见类注释「诚实披露」）。口令历史与
     * {@code t_user} 乐观锁无因果关系，故不影响本守卫强度。
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
