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
 *   <li>{@code PUT} 管理员重置（<b>请求体含管理员设定的新口令 {@code newPassword}（必填）</b>；
 *       响应<b>不回传</b>该口令；新口令仅以加盐哈希落库，明文不展示、不传递、不入日志；
 *       {@code FR-AUTH-16} 形态 B「管理员设定新口令」/ {@code NFR-SEC-2}）。</li>
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
                        "重置须提交 {userId, newPassword}；复现=按 §4.1.1 提交 application/json");
            }
            if (reset.newPassword == null || reset.newPassword.isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "管理员重置须提交由管理员设定的新口令 {userId, newPassword}"
                                + "（FR-AUTH-16：新口令不得以明文展示或回传，但须由管理员作为输入设定）；"
                                + "复现=按 §4.1.1 提交 application/json");
            }
            // FR-AUTH-16 形态 B：管理员设定新口令（作为输入）；服务层加盐哈希落库，明文不回传。
            authService.resetPassword(reset.userId, reset.newPassword, ctx.currentUserId());
            return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
        }
        ChangeRequest request = JsonCodec.fromJson(ctx.body(), ChangeRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "改口令须提交 {oldPassword, newPassword}；复现=按 §4.1.1 提交 application/json");
        }
        authService.changePassword(ctx.currentUserId(), request.oldPassword, request.newPassword);
        // FR-AUTH-16：改密成功 ⇒ 解除本会话的「强制修改口令」门禁（服务端已在库中归零标记，此处同步内存快照）。
        if (ctx.session() != null) {
            authService.markPasswordChanged(ctx.session().sessionId);
        }
        return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
    }

    /** 改口令请求体。 */
    public static final class ChangeRequest {

        /** 原口令（仅内存）。 */
        public String oldPassword;

        /** 新口令（仅内存）。 */
        public String newPassword;
    }

    /** 重置请求体（{@code FR-AUTH-16} 形态 B：管理员设定新口令，作为输入）。 */
    public static final class ResetRequest {

        /** 目标用户 ID。 */
        public long userId;

        /** 管理员设定的新口令（必填；仅内存，服务层加盐哈希后即弃，响应不回传）。 */
        public String newPassword;
    }
}
