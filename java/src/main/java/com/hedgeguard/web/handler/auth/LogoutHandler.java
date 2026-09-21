package com.hedgeguard.web.handler.auth;

import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 登出 {@code POST /api/v1/auth/logout}（{@code FR-AUTH-2}）。
 *
 * <p>幂等：会话不存在或已过期时仍返回成功（避免前端因重复登出而报错），
 * 但<b>不</b>区分"会话不存在"与"已登出"（不泄露会话是否存在）。
 */
public final class LogoutHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/logout");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public LogoutHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        String sessionId = ctx.session() == null ? null : ctx.session().sessionId;
        if (sessionId != null) {
            authService.logout(sessionId);
        }
        return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()))
                .withHeader("Set-Cookie", LoginHandler.SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly");
    }
}
