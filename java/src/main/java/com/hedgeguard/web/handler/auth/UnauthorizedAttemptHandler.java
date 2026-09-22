package com.hedgeguard.web.handler.auth;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 越权尝试记录 {@code GET /api/v1/auth/unauthorized-attempt}（{@code FR-AUTH-18}）。
 *
 * <p>返回被系统拒绝的越权操作（含操作人、时间、尝试的操作、拒绝原因）；<b>只增不改不删</b>、
 * 无删除入口。默认按尝试时间倒序。
 */
public final class UnauthorizedAttemptHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/unauthorized-attempt");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public UnauthorizedAttemptHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "attempt_time", "desc");
        String raw = ctx.queryParam("operatorId");
        Long operator = (raw == null || raw.isEmpty()) ? null : Long.valueOf(raw.trim());
        PageResult<UnauthorizedAttempt> page = authService.listUnauthorizedAttempts(query, operator);
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(page.items(), page.total(), page.page(), page.size()), ctx.traceId()));
    }
}
