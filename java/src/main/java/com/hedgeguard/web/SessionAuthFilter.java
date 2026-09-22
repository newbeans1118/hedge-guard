package com.hedgeguard.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 基于会话的认证过滤器（{@code ARCHITECTURE.md} §4.1.1「鉴权」）。
 *
 * <p>会话载体二选一：Cookie {@code HGSESSION=<opaque-session-id>}（HttpOnly）
 * 或请求头 {@code X-HG-Session}（同源前端在 WS/显式调用时使用）。
 *
 * <p><b>公开路径白名单（冻结）</b>：
 * <ul>
 *   <li>{@code GET|HEAD /web/**} —— 前端静态资源（不含数据）；</li>
 *   <li>{@code POST /api/v1/auth/login} —— 登录入口；</li>
 *   <li>{@code GET /api/v1/ping} —— 进程存活探针（运维/健康检查，<b>不返回任何业务数据</b>）。</li>
 * </ul>
 * 白名单外的任何请求（含所有业务查询）缺失/失效会话 → {@code 10006}。
 *
 * <p><b>空闲超时（{@code FR-AUTH-15} 验收 2 / {@code CFG-23b}）</b>：认证成功时<b>续期</b>会话
 * （滑动过期）——经 {@link SessionRenewer} 委托服务层
 * {@link com.hedgeguard.service.AuthService#touchSession(String)}（同时顺延内存会话与库内
 * {@code t_login_session} 行）。<b>未注入续期器</b>（{@code null}）时回退为「只查不续期」，
 * 等价于绝对过期——这保留既有 2 参构造调用点（WS / 单测桩）的语义。
 */
public final class SessionAuthFilter implements AuthFilter {

    private static final Set<String> PUBLIC_EXACT = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("POST /api/v1/auth/login", "GET /api/v1/ping",
                    "HEAD /api/v1/ping")));

    private final SessionStore sessionStore;
    private final String cookieName;
    private final SessionRenewer renewer;

    /**
     * 构造（不续期：绝对过期语义；向后兼容既有调用点）。
     *
     * @param sessionStore 会话存储
     * @param cookieName   会话 Cookie 名（默认 {@code HGSESSION}，见 {@code ARCHITECTURE.md} §4.1.1）
     */
    public SessionAuthFilter(SessionStore sessionStore, String cookieName) {
        this(sessionStore, cookieName, null);
    }

    /**
     * 构造（带续期器：空闲滑动超时）。
     *
     * @param sessionStore 会话存储
     * @param cookieName   会话 Cookie 名
     * @param renewer      续期器（{@code null} 表示不续期）
     */
    public SessionAuthFilter(SessionStore sessionStore, String cookieName, SessionRenewer renewer) {
        this.sessionStore = sessionStore;
        this.cookieName = cookieName;
        this.renewer = renewer;
    }

    @Override
    public boolean isPublic(RequestContext ctx) {
        if (ctx.path().equals(StaticHandler.URL_PREFIX)
                || ctx.path().startsWith(StaticHandler.URL_PREFIX + "/")) {
            return true;
        }
        return PUBLIC_EXACT.contains(ctx.method().toUpperCase(Locale.ROOT) + " " + ctx.path());
    }

    @Override
    public void authenticate(RequestContext ctx) {
        String sessionId = extractSessionId(ctx);
        if (sessionId == null) {
            throw BizException.of(ErrorCode.NOT_AUTHENTICATED,
                    "未提供会话凭据：请先登录（Cookie " + cookieName + " 或请求头 X-HG-Session）");
        }
        Session session = renewer == null
                ? sessionStore.find(sessionId) : renewer.renew(sessionId);
        if (session == null) {
            throw BizException.of(ErrorCode.NOT_AUTHENTICATED,
                    "会话不存在或已超时：请重新登录（空闲超时后需重新认证）");
        }
        ctx.bindSession(session);
    }

    /**
     * 从 Cookie 或请求头提取会话 ID。
     *
     * @param ctx 请求上下文
     * @return 会话 ID；缺失返回 {@code null}
     */
    private String extractSessionId(RequestContext ctx) {
        String headerSession = ctx.header("X-HG-Session");
        if (headerSession != null && !headerSession.isEmpty()) {
            return headerSession;
        }
        String cookie = ctx.header("Cookie");
        if (cookie == null || cookie.isEmpty()) {
            return null;
        }
        for (String part : cookie.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (cookieName.equalsIgnoreCase(p.substring(0, eq).trim())) {
                String v = p.substring(eq + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }
}
