package com.hedgeguard.web.handler.auth;

import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;
import java.util.List;

/**
 * 子账户数据权限 {@code GET|POST /api/v1/auth/sub-account-perm}
 * （{@code FR-AUTH-12} 软隔离 / {@code FR-AUTH-13} 跨子账户查看）。
 *
 * <ul>
 *   <li>{@code GET ?userId=} 查询某用户授权（缺省为当前用户）；<b>无授权 ⇒ 空集</b>（安全默认）；</li>
 *   <li>{@code POST} 授予（{@code LIMITED} 限定 / {@code CROSS_READONLY} 跨子账户只读，后者仅管理层/审计岗；须双人复核）。</li>
 * </ul>
 */
public final class SubAccountPermHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/sub-account-perm");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public SubAccountPermHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            String raw = ctx.queryParam("userId");
            long userId = (raw == null || raw.isEmpty()) ? ctx.currentUserId() : Long.parseLong(raw.trim());
            List<UserSubAccountPerm> perms = authService.listSubAccountPerms(userId);
            return HttpResult.json(200, ApiResponse.ok(perms, ctx.traceId()));
        }
        PermRequest request = JsonCodec.fromJson(ctx.body(), PermRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {userId, subAccountId, permType, effectiveFrom, reviewerId}；"
                            + "复现=按 §4.1.1 提交 application/json");
        }
        authService.assignSubAccountPerm(request.userId, request.subAccountId, request.permType,
                request.effectiveFrom == null ? null : LocalDate.parse(request.effectiveFrom),
                request.effectiveTo == null ? null : LocalDate.parse(request.effectiveTo),
                ctx.currentUserId(), request.reviewerId);
        return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
    }

    /** 子账户权限请求体。 */
    public static final class PermRequest {

        /** 目标用户。 */
        public long userId;

        /** 子账户（{@code CROSS_READONLY} 时可传哨兵 {@code 0}）。 */
        public int subAccountId;

        /** 权限类型：{@code LIMITED} / {@code CROSS_READONLY}。 */
        public String permType;

        /** 生效起始日。 */
        public String effectiveFrom;

        /** 生效截止日（可空）。 */
        public String effectiveTo;

        /** 复核人（必填且 != 操作人）。 */
        public Long reviewerId;
    }
}
