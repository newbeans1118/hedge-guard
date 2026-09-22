package com.hedgeguard.web.handler.auth;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.PermissionChangeLog;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 权限变更留痕 {@code GET /api/v1/auth/change-log}（{@code FR-AUTH-11} / {@code FR-AUTH-18}）。
 *
 * <p>返回授权/角色/子账户权限/用户属性等的变更历史（含前后值与复核人）；<b>只增不改不删</b>，
 * <b>无</b>编辑或删除入口（{@code FR-AUTH-11} 验收 2）。默认按变更时间倒序。
 */
public final class ChangeLogHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/change-log");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public ChangeLogHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "change_time", "desc");
        String raw = ctx.queryParam("userId");
        Long target = (raw == null || raw.isEmpty()) ? null : Long.valueOf(raw.trim());
        PageResult<PermissionChangeLog> page = authService.listPermissionChangeLogs(query, target);
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(page.items(), page.total(), page.page(), page.size()), ctx.traceId()));
    }
}
