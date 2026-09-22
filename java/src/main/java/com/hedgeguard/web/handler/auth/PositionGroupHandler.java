package com.hedgeguard.web.handler.auth;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.PositionConflict;
import com.hedgeguard.dao.entity.PositionGroup;
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

import java.util.List;

/**
 * 岗位组与岗位分离互斥 {@code GET|POST /api/v1/auth/position-group}（{@code FR-AUTH-7}）。
 *
 * <ul>
 *   <li>{@code GET} 返回 {@code {groups, conflicts}}（岗位组清单 + 互斥矩阵）；</li>
 *   <li>{@code POST} 配置互斥（矩阵单向存储 {@code a<b}；须双人复核；<b>不得</b>关闭互斥）。</li>
 * </ul>
 */
public final class PositionGroupHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/position-group");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public PositionGroupHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                    ctx.queryParam("sort"), "group_a_id");
            PageResult<PositionConflict> conflicts = authService.listPositionConflicts(query);
            List<PositionGroup> groups = authService.listPositionGroups();
            PositionGroupView view = new PositionGroupView();
            view.groups = groups;
            view.conflicts = new PageData<>(conflicts.items(), conflicts.total(), conflicts.page(),
                    conflicts.size());
            return HttpResult.json(200, ApiResponse.ok(view, ctx.traceId()));
        }
        ConflictRequest request = JsonCodec.fromJson(ctx.body(), ConflictRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {groupAId, groupBId, reason, reviewerId}；复现=按 §4.1.1 提交");
        }
        authService.definePositionConflict(request.groupAId, request.groupBId, request.reason,
                ctx.currentUserId(), request.reviewerId);
        return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
    }

    /** 岗位组视图（组清单 + 互斥矩阵）。 */
    public static final class PositionGroupView {

        /** 启用岗位组。 */
        public List<PositionGroup> groups;

        /** 互斥矩阵（分页）。 */
        public PageData<PositionConflict> conflicts;
    }

    /** 互斥配置请求体。 */
    public static final class ConflictRequest {

        /** 岗位组 A。 */
        public int groupAId;

        /** 岗位组 B。 */
        public int groupBId;

        /** 互斥依据。 */
        public String reason;

        /** 复核人（必填且 != 操作人）。 */
        public Long reviewerId;
    }
}
