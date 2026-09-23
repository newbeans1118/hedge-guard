package com.hedgeguard.web.handler.auth;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.UserAccount;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 用户管理 {@code GET|POST|PUT /api/v1/auth/user}（{@code FR-AUTH-1~5}）。
 *
 * <ul>
 *   <li>{@code GET} 列表（默认创建时间倒序；筛选 {@code status}/{@code leader}/{@code role}/
 *       {@code keyword}/{@code subAccount}；<b>绝不回任何口令字段</b>，{@code FR-AUTH-5} 验收 3）；</li>
 *   <li>{@code POST} 新增用户（{@code FR-AUTH-1}；可选 {@code roleCode} 授初始角色）；</li>
 *   <li>{@code PUT} 变更：{@code action=UPDATE|DISABLE|ENABLE|FORCE_LOGOUT}
 *       （{@code FR-AUTH-2/3/4/15④}；{@code FORCE_LOGOUT} 由 {@code T-135} 接线；
 *       {@code ENABLE} 会重置口令但<b>不回传新口令</b>，{@code FR-AUTH-16}）。</li>
 * </ul>
 */
public final class UserHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/user");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public UserHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        String method = ctx.method().toUpperCase(java.util.Locale.ROOT);
        if ("GET".equals(method)) {
            return list(ctx);
        }
        if ("POST".equals(method)) {
            return create(ctx);
        }
        return mutate(ctx);
    }

    private HttpResult list(RequestContext ctx) {
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "created_time", "desc");
        // T-135：补齐 FR-AUTH-5 输入列的筛选 —— 关键字（keyword）/ 角色（role）/ 子账户（subAccount）。
        Integer subAccount = parseOptionalInt(ctx.queryParam("subAccount"), "subAccount");
        PageResult<UserAccount> page = authService.listUsers(query,
                ctx.queryParam("status"), ctx.queryParam("leader"),
                ctx.queryParam("role"), ctx.queryParam("keyword"), subAccount);
        List<UserView> items = new ArrayList<>();
        for (UserAccount user : page.items()) {
            items.add(UserView.of(user, authService.roleCodesOf(user.id),
                    authService.visibleSubAccountIds(user.id)));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    private HttpResult create(RequestContext ctx) {
        CreateRequest request = JsonCodec.fromJson(ctx.body(), CreateRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "新增用户须提交 {username, realName, naturalPersonKey, initialPassword, ...}；"
                            + "复现=按 §4.1.1 提交 application/json");
        }
        long id = authService.createUser(request.username, request.realName,
                request.naturalPersonKey, request.department, request.isEnterpriseLeader,
                request.initialPassword, ctx.currentUserId(), request.roleCode);
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 解析可选整数查询参数；非数字 ⇒ {@code 10001}（不静默忽略）。 */
    private static Integer parseOptionalInt(String raw, String name) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数 " + name + " 须为十进制整数：" + raw + "；复现=改正取值", e);
        }
    }

    private HttpResult mutate(RequestContext ctx) {
        MutateRequest request = JsonCodec.fromJson(ctx.body(), MutateRequest.class);
        if (request == null || request.action == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "变更须提交 {action: UPDATE|DISABLE|ENABLE, ...}；复现=按 §4.1.1 提交");
        }
        long operatorId = ctx.currentUserId();
        switch (request.action.toUpperCase(java.util.Locale.ROOT)) {
            case "UPDATE":
                authService.updateUser(request.userId, request.realName, request.department,
                        request.isEnterpriseLeader, operatorId, request.reviewerId);
                return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
            case "DISABLE":
                authService.disableUser(request.userId, request.reason, operatorId);
                return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
            case "ENABLE":
                // FR-AUTH-16：启用会重置口令，但不得以明文方式展示或传递新口令（服务层内部生成 + 加盐哈希落库，明文即弃）。
                authService.enableUser(request.userId, operatorId, request.reviewerId);
                return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
            case "FORCE_LOGOUT":
                // FR-AUTH-15 验收 4：管理员强制下线指定用户（须留痕）——T-135 接线（服务层已实现）
                int kicked = authService.forceLogout(request.userId, operatorId);
                return HttpResult.json(200, ApiResponse.ok(kicked, ctx.traceId()));
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "未知 action：" + request.action
                                + "；复现=改用 UPDATE|DISABLE|ENABLE|FORCE_LOGOUT");
        }
    }

    /** 新增请求体。 */
    public static final class CreateRequest {

        /** 登录名（唯一）。 */
        public String username;

        /** 姓名。 */
        public String realName;

        /** 自然人唯一键。 */
        public String naturalPersonKey;

        /** 部门。 */
        public String department;

        /** 是否企业负责人（{@code Y}/{@code N}）。 */
        public String isEnterpriseLeader;

        /** 初始口令（仅内存）。 */
        public String initialPassword;

        /** 初始角色编码（{@code FR-AUTH-1} 输入列「初始角色」；可选，缺省不授角色）。 */
        public String roleCode;
    }

    /** 变更请求体。 */
    public static final class MutateRequest {

        /** 动作：{@code UPDATE} / {@code DISABLE} / {@code ENABLE}。 */
        public String action;

        /** 目标用户 ID。 */
        public long userId;

        /** 姓名（UPDATE）。 */
        public String realName;

        /** 部门（UPDATE）。 */
        public String department;

        /** 企业负责人标记（UPDATE）。 */
        public String isEnterpriseLeader;

        /** 停用原因（DISABLE）。 */
        public String reason;

        /** 复核人（UPDATE 标记变更 / ENABLE 必填）。 */
        public Long reviewerId;
    }

    /** 用户视图（<b>无任何口令字段</b>）。 */
    public static final class UserView {

        /** 用户 ID。 */
        public long id;

        /** 登录名。 */
        public String username;

        /** 姓名。 */
        public String realName;

        /** 部门。 */
        public String department;

        /** 企业负责人标记。 */
        public String isEnterpriseLeader;

        /** 状态。 */
        public String status;

        /** 最后登录时间（ISO-8601）。 */
        public String lastLoginTime;

        /** 角色编码集合。 */
        public List<String> roleCodes;

        /** 可见子账户范围（含哨兵 0 = 跨账户只读）。 */
        public List<Integer> subAccountScope;

        static UserView of(UserAccount user, List<String> roleCodes, List<Integer> scope) {
            UserView view = new UserView();
            view.id = user.id;
            view.username = user.username;
            view.realName = user.realName;
            view.department = user.department;
            view.isEnterpriseLeader = user.isEnterpriseLeader;
            view.status = user.status;
            view.lastLoginTime = user.lastLoginTime == null ? null : user.lastLoginTime.toString();
            view.roleCodes = roleCodes;
            view.subAccountScope = scope;
            return view;
        }
    }
}
