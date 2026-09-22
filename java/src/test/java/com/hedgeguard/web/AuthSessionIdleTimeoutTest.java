package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.LoginSessionDao;
import com.hedgeguard.dao.entity.LoginSession;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.service.FakeTx;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-010} 缺口 (c)②「会话空闲超时」的反退化判据（{@code FR-AUTH-15} 验收 2 / {@code CFG-23b}）。
 *
 * <p>接线点 = {@link SessionAuthFilter#authenticate}：注入 {@link SessionRenewer}
 * （生产装配 = {@code authService::touchSession}）后，<b>每次有效请求都顺延过期时间</b>（滑动过期）。
 *
 * <p><b>承重</b>：把 renewer 换回 {@code null}（即旧实现「只查不续期」），
 * {@link #activeRequestsKeepSessionAliveSlicing()} 必红（第 2 次请求在绝对过期点之后）。
 */
class AuthSessionIdleTimeoutTest {

    private static final Instant T0 = Instant.parse("2026-09-21T02:00:00Z");
    private static final int IDLE = AuthService.SESSION_IDLE_MINUTES;

    private SessionStore store;
    private AuthSessionIdleTimeoutTest.FakeSessionDao sessionDao;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        store = new SessionStore.InMemory();
        sessionDao = new FakeSessionDao();
        authService = new AuthService(FakeTx.newTemplate(), null, null, null, null, sessionDao,
                null, null, null, null, store, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    private static RequestContext ctx() {
        Map<String, String> headers = Collections.singletonMap("X-HG-Session", "S-1");
        return new RequestContext("GET", "/api/v1/plan", null, headers, "", "HGT010", "127.0.0.1");
    }

    @Test
    @DisplayName("空闲滑动超时：活跃请求持续顺延（+20min 请求后 +40min 仍有效——绝对过期下必失效）")
    void activeRequestsKeepSessionAliveSlicing() {
        store.save(new Session("S-1", 7L, "lisi", 1L, Clocks.now().plusMinutes(IDLE)));
        SessionAuthFilter filter = new SessionAuthFilter(store, "HGSESSION",
                authService::touchSession);

        // T0+20min：有效请求 ⇒ 续期至 T0+20+30 = T0+50
        Clocks.set(Clock.fixed(T0.plusSeconds(20 * 60), Clocks.ZONE));
        RequestContext first = ctx();
        filter.authenticate(first);
        assertNotNull(first.session(), "应绑定会话");
        assertEquals(Clocks.now().plusMinutes(IDLE), first.session().expireTime, "应滑动续期");
        assertTrue(sessionDao.touchCalls >= 1, "库内会话须记录活跃时间（t_login_session.touch）");

        // T0+40min：已过原绝对过期点（T0+30），但滑动续期后（T0+50）仍有效
        Clocks.set(Clock.fixed(T0.plusSeconds(40 * 60), Clocks.ZONE));
        RequestContext second = ctx();
        filter.authenticate(second);
        assertEquals(Clocks.now().plusMinutes(IDLE), second.session().expireTime,
                "绝对过期实现在此处必失效（缺口 (c)②）");
    }

    @Test
    @DisplayName("空闲超时：超过配置时长后失效并 10006（须重新认证）")
    void idleTimeoutRejectsExpiredSession() {
        store.save(new Session("S-1", 7L, "lisi", 1L, Clocks.now().plusMinutes(IDLE)));
        SessionAuthFilter filter = new SessionAuthFilter(store, "HGSESSION",
                authService::touchSession);

        Clocks.set(Clock.fixed(T0.plusSeconds(IDLE * 60L + 1), Clocks.ZONE));
        BizException e = assertThrows(BizException.class, () -> filter.authenticate(ctx()));
        assertEquals(ErrorCode.NOT_AUTHENTICATED, e.errorCode(), "空闲超时应 10006");
        assertNull(store.find("S-1"), "超时会话应从存储中作废（重新认证）");
    }

    @Test
    @DisplayName("向后兼容：未注入续期器（2 参构造）时保持绝对过期语义")
    void withoutRenewerKeepsAbsoluteSemantics() {
        store.save(new Session("S-1", 7L, "lisi", 1L, Clocks.now().plusMinutes(IDLE)));
        SessionAuthFilter filter = new SessionAuthFilter(store, "HGSESSION");

        Clocks.set(Clock.fixed(T0.plusSeconds(IDLE * 60L + 1), Clocks.ZONE));
        assertEquals(ErrorCode.NOT_AUTHENTICATED,
                assertThrows(BizException.class, () -> filter.authenticate(ctx())).errorCode());
    }

    /** 内存假登录会话 DAO（只实现续期路径用到的方法）。 */
    private static final class FakeSessionDao extends UnsupportedBaseDao<LoginSession, Long>
            implements LoginSessionDao {

        int touchCalls;
        int invalidateCalls;

        @Override
        public int touch(String sessionId, LocalDateTime lastActiveTime) {
            touchCalls++;
            return 1;
        }

        @Override
        public int invalidateByUser(long userId, String status, Long kickedBy, long permVersion) {
            invalidateCalls++;
            return 1;
        }

        @Override
        public int insert(LoginSession entity, long operatorId) {
            return 1;
        }

        @Override
        public LoginSession findBySessionId(String sessionId) {
            return null;
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
}
