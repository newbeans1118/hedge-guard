package com.hedgeguard.web.handler.auth;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 角色管理 {@code GET|POST /api/v1/auth/role}（{@code FR-AUTH-6}/{@code FR-AUTH-8}）。
 *
 * <ul>
 *   <li>{@code GET} 角色列表（含岗位组）；</li>
 *   <li>{@code POST action=DEFINE} 定义角色（{@code FR-AUTH-6}：须选岗位组；系统管理员不得含交易能力）；</li>
 *   <li>{@code POST action=GRANT} 授予角色（{@code FR-AUTH-8}：岗位互斥自动校验并拒绝，记越权尝试）。</li>
 * </ul>
 */
public final class RoleHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/role");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public RoleHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                    ctx.queryParam("sort"), "role_code");
            PageResult<Role> page = authService.listRoles(query);
            return HttpResult.json(200, ApiResponse.ok(
                    new PageData<>(page.items(), page.total(), page.page(), page.size()),
                    ctx.traceId()));
        }
        RoleRequest request = JsonCodec.fromJson(ctx.body(), RoleRequest.class);
        if (request == null || request.action == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {action: DEFINE|GRANT, ...}；复现=按 §4.1.1 提交 application/json");
        }
        long operatorId = ctx.currentUserId();
        if ("DEFINE".equalsIgnoreCase(request.action)) {
            long id = authService.defineRole(request.roleCode, request.roleName,
                    request.positionGroupId, request.hasTradingOperation, operatorId,
                    request.reviewerId);
            return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
        }
        if ("GRANT".equalsIgnoreCase(request.action)) {
            authService.grantRole(request.userId, request.roleId, operatorId, request.reviewerId);
            return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
        }
        throw BizException.of(ErrorCode.PARAM_INVALID,
                "未知 action：" + request.action + "；复现=改用 DEFINE|GRANT");
    }

    /** 角色请求体。 */
    public static final class RoleRequest {

        /** 动作：{@code DEFINE} / {@code GRANT}。 */
        public String action;

        /** 角色编码（DEFINE）。 */
        public String roleCode;

        /** 角色名称（DEFINE）。 */
        public String roleName;

        /** 岗位组（DEFINE）。 */
        public int positionGroupId;

        /** 是否含交易下单能力（DEFINE）。 */
        public String hasTradingOperation;

        /** 目标用户（GRANT）。 */
        public long userId;

        /** 角色 ID（GRANT）。 */
        public long roleId;

        /** 复核人（必填且 != 操作人）。 */
        public Long reviewerId;
    }
}
