package com.hedgeguard.web.handler.auth;

import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 口令管理 {@code POST|PUT /api/v1/auth/password}（{@code FR-AUTH-16}）。
 *
 * <ul>
 *   <li>{@code POST} 用户改口令（{@code oldPassword} + {@code newPassword}；强度策略 + 近 5 次禁重用）；</li>
 *   <li>{@code PUT} 管理员重置（<b>不回传新口令</b>；新口令仅以加盐哈希落库，明文不展示、不传递、
 *       不入日志；{@code FR-AUTH-16} / {@code NFR-SEC-2}）。</li>
 * </ul>
 */
public final class PasswordHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/password");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public PasswordHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("PUT".equalsIgnoreCase(ctx.method())) {
            ResetRequest reset = JsonCodec.fromJson(ctx.body(), ResetRequest.class);
            if (reset == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "重置须提交 {userId}；复现=按 §4.1.1 提交 application/json");
            }
            // FR-AUTH-16：重置后不得以明文方式展示或传递新口令（服务层内部生成 + 加盐哈希落库，明文即弃）。
            authService.resetPassword(reset.userId, ctx.currentUserId());
            return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
        }
        ChangeRequest request = JsonCodec.fromJson(ctx.body(), ChangeRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "改口令须提交 {oldPassword, newPassword}；复现=按 §4.1.1 提交 application/json");
        }
        authService.changePassword(ctx.currentUserId(), request.oldPassword, request.newPassword);
        return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
    }

    /** 改口令请求体。 */
    public static final class ChangeRequest {

        /** 原口令（仅内存）。 */
        public String oldPassword;

        /** 新口令（仅内存）。 */
        public String newPassword;
    }

    /** 重置请求体。 */
    public static final class ResetRequest {

        /** 目标用户 ID。 */
        public long userId;
    }
}
