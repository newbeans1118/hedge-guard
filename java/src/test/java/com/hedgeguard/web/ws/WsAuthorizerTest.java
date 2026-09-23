package com.hedgeguard.web.ws;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `W-2` 子账户数据权限裁决单测（`T-028` 验收 3：**安全必做**；完全离线）。
 *
 * <p>三条不变量逐条验证：
 * <ol>
 *   <li><b>不得只依赖客户端自报</b>：申请集必须与授权集求交；</li>
 *   <li><b>安全默认 = 空集</b>：无授权 ⇒ 一条都不下发（且**不算**越权尝试）；</li>
 *   <li><b>越权即拒 + 留痕</b>：申请未授权子账户 ⇒ `10004`（关闭码 `1008`）+ 写入留痕。</li>
 * </ol>
 */
class WsAuthorizerTest {

    @AfterEach
    void resetClock() {
        com.hedgeguard.common.Clocks.reset();
    }

    private static Set<Integer> setOf(Integer... v) {
        return new LinkedHashSet<>(Arrays.asList(v));
    }

    @Test
    @DisplayName("申请集 ⊆ 授权集：返回交集（不越权）")
    void intersectsWithGrants() {
        Recorder sink = new Recorder();
        WsAuthorizer authz = new WsAuthorizer(userId -> setOf(1, 2, 3), sink);

        assertEquals(setOf(1), authz.authorize(7L, setOf(1), "订阅 alert"));
        assertEquals(setOf(1, 3), authz.authorize(7L, setOf(3, 1), "订阅 alert"));
        assertTrue(sink.records.isEmpty(), "合法申请不得留痕");
    }

    @Test
    @DisplayName("未指定 subAccounts ⇒ 取该用户**全部已授权**子账户（仍严格限于授权集）")
    void omittedMeansAllGranted() {
        Recorder sink = new Recorder();
        WsAuthorizer authz = new WsAuthorizer(userId -> setOf(4, 5), sink);

        assertEquals(setOf(4, 5), authz.authorize(7L, null, "建立连接"));
        assertEquals(setOf(4, 5), authz.authorize(7L, Collections.emptySet(), "建立连接"));
        assertTrue(sink.records.isEmpty());
    }

    @Test
    @DisplayName("🔴 安全默认：无任何授权 ⇒ 空集（一条都不下发），且不计越权")
    void noGrantMeansNothingDelivered() {
        Recorder sink = new Recorder();
        WsAuthorizer authz = new WsAuthorizer(userId -> Collections.emptySet(), sink);

        assertTrue(authz.authorize(7L, null, "建立连接").isEmpty());
        assertTrue(authz.authorize(7L, setOf(1, 2), "订阅").isEmpty(),
                "无授权时即使客户端自报子账户也必须为空集");
        assertTrue(sink.records.isEmpty(), "「没授权」不等于「越权尝试」，不得留痕");
    }

    @Test
    @DisplayName("授权读取器返回 null 时按空集处理（防 NPE 与「全可见」退化）")
    void nullGrantTreatedAsEmpty() {
        WsAuthorizer authz = new WsAuthorizer(userId -> null, new Recorder());
        assertTrue(authz.authorize(7L, setOf(1), "订阅").isEmpty());
    }

    @Test
    @DisplayName("🔴 越权：申请未授权子账户 ⇒ 抛 10004（关闭码 1008）**且**写入留痕")
    void unauthorizedSubAccountRejectedAndRecorded() {
        Recorder sink = new Recorder();
        WsAuthorizer authz = new WsAuthorizer(userId -> setOf(1), sink);

        WsProtocolException e = assertThrows(WsProtocolException.class,
                () -> authz.authorize(7L, setOf(1, 99), "订阅 alert（连接 3）"));
        assertEquals(WsCloseCode.POLICY_VIOLATION, e.closeCode());
        assertTrue(e.getMessage().contains("10004"), "关闭原因须带业务码 10004");

        assertEquals(1, sink.records.size(), "越权必须留痕（不得静默求交放行）");
        WsAuthorizer.WsViolation v = sink.records.get(0);
        assertEquals(7L, v.userId);
        assertEquals(Integer.valueOf(99), v.subAccountId);
        assertTrue(v.desc.contains("连接 3"));
    }

    @Test
    @DisplayName("越权判定是「整单拒绝」：混合申请中只要有一个未授权即拒，不部分放行")
    void mixedRequestIsRejectedAtomically() {
        Recorder sink = new Recorder();
        WsAuthorizer authz = new WsAuthorizer(userId -> setOf(1, 2), sink);
        assertThrows(WsProtocolException.class, () -> authz.authorize(7L, setOf(1, 2, 3), "订阅"));
        assertEquals(1, sink.records.size());
        assertEquals(Integer.valueOf(3), sink.records.get(0).subAccountId);
    }

    @Test
    @DisplayName("账户级哨兵 0 也必须显式授权才可见（不得默认放行全账户口径）")
    void accountLevelSentinelRequiresGrant() {
        WsAuthorizer authz = new WsAuthorizer(userId -> setOf(1), new Recorder());
        assertThrows(WsProtocolException.class, () -> authz.authorize(7L, setOf(0), "订阅"));
        WsAuthorizer withZero = new WsAuthorizer(userId -> setOf(0, 1), new Recorder());
        assertEquals(setOf(0), withZero.authorize(7L, setOf(0), "订阅"));
    }

    @Test
    @DisplayName("申请集中的 null 被忽略（忽略只会减少可见范围，不会越权）")
    void nullEntriesAreIgnored() {
        WsAuthorizer authz = new WsAuthorizer(userId -> setOf(1), new Recorder());
        Set<Integer> r = authz.authorize(7L, new LinkedHashSet<>(Arrays.asList(1, null)), "订阅");
        assertEquals(setOf(1), r);
    }

    // ---------------------------------------------------------------- 生产读取器：生效期过滤

    @Test
    @DisplayName("PermDaoGrantReader 按 effective_from/effective_to 过滤（以可注入时钟判定「今日」）")
    void permDaoReaderFiltersByEffectiveWindow() {
        com.hedgeguard.common.Clocks.set(Clock.fixed(
                Instant.parse("2026-09-20T02:00:00Z"), com.hedgeguard.common.Clocks.ZONE));
        LocalDate today = LocalDate.of(2026, 9, 20);

        List<UserSubAccountPerm> rows = new ArrayList<>();
        rows.add(perm(1, today.minusDays(1), null));                    // 生效中（无截止）
        rows.add(perm(2, today.minusDays(10), today.plusDays(1)));       // 生效中（区间内）
        rows.add(perm(3, today.plusDays(1), null));                      // 未生效（起始在未来）
        rows.add(perm(4, today.minusDays(10), today.minusDays(1)));      // 已过期
        rows.add(perm(5, null, null));                                   // 无条件（视为长期有效）
        UserSubAccountPermDao dao = new PermDaoStub(rows);

        Set<Integer> granted = new WsAuthorizer.PermDaoGrantReader(dao).grantedSubAccounts(7L);
        assertEquals(new TreeSet<>(Arrays.asList(1, 2, 5)), new TreeSet<>(granted));
    }

    @Test
    @DisplayName("PermDaoGrantReader：DAO 返回 null（无记录）⇒ 空集（安全默认）")
    void permDaoReaderNullMeansEmpty() {
        assertTrue(new WsAuthorizer.PermDaoGrantReader(new PermDaoStub(null))
                .grantedSubAccounts(7L).isEmpty());
    }

    @Test
    @DisplayName("便捷：连接级过滤必须走 authorize（保证「空集恒过滤」不退化）")
    void emptyGrantNeverDelivers() {
        WsAuthorizer authz = new WsAuthorizer(userId -> Collections.emptySet(), new Recorder());
        Set<Integer> effective = authz.authorize(7L, null, "建立连接");
        WsSubscription sub = new WsSubscription(
                Collections.singleton(WsSubscription.CHANNEL_ALERT), Collections.emptySet(), effective);
        assertFalse(sub.matchesAlert(0));
        assertFalse(sub.matchesAlert(1));
        assertFalse(sub.matchesAlert(null));
        assertTrue(sub.subAccounts().isEmpty());
    }

    private static UserSubAccountPerm perm(int subAccountId, LocalDate from, LocalDate to) {
        UserSubAccountPerm p = new UserSubAccountPerm();
        p.userId = 7L;
        p.subAccountId = subAccountId;
        p.permType = "LIMITED";
        p.effectiveFrom = from;
        p.effectiveTo = to;
        return p;
    }

    /** 留痕记录器（内存）。 */
    private static final class Recorder implements WsAuthorizer.WsViolationSink {

        private final List<WsAuthorizer.WsViolation> records = new ArrayList<>();

        @Override
        public void record(WsAuthorizer.WsViolation violation) {
            records.add(violation);
        }
    }

    /** 只实现 {@code findByUser} 的 DAO 桩（其余方法不会被本单测触达）。 */
    private static final class PermDaoStub implements UserSubAccountPermDao {

        private final List<UserSubAccountPerm> rows;

        PermDaoStub(List<UserSubAccountPerm> rows) {
            this.rows = rows;
        }

        @Override
        public List<UserSubAccountPerm> findByUser(long userId) {
            return rows;
        }

        @Override
        public List<Long> findUserIdsBySubAccount(int subAccountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int insert(UserSubAccountPerm entity, long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateById(UserSubAccountPerm entity, long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserSubAccountPerm findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageResult<UserSubAccountPerm> findPage(PageQuery query, String where,
                                                       List<Object> whereArgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            throw new UnsupportedOperationException();
        }
    }
}
