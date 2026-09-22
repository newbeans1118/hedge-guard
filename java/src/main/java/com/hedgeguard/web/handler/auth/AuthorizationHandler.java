package com.hedgeguard.web.handler.auth;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.Authorization;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 人员授权 {@code GET|POST /api/v1/auth/authorization}（{@code FR-AUTH-10}）。
 *
 * <p>授权<b>须明确人员名单 + 品种 + 额度</b>三者（{@code R-AUTH-4}）；额度 {@code > 0} 且
 * 变更须双人复核。金额按 {@code §4.1.1} 以<b>字符串</b>回传（避免 JS 精度丢失）。
 */
public final class AuthorizationHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/authorization");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public AuthorizationHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                    ctx.queryParam("sort"), "id", "desc");
            Long target = parseNullableLong(ctx.queryParam("userId"));
            PageResult<Authorization> page = authService.listAuthorizations(query, target);
            List<AuthorizationView> items = new ArrayList<>();
            for (Authorization auth : page.items()) {
                items.add(AuthorizationView.of(auth));
            }
            return HttpResult.json(200, ApiResponse.ok(
                    new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
        }
        GrantRequest request = JsonCodec.fromJson(ctx.body(), GrantRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {userId, subAccountId, productId, quota, effectiveFrom, reviewerId}；"
                            + "复现=按 §4.1.1 提交 application/json");
        }
        BigDecimal quota = request.quota == null ? null : new BigDecimal(request.quota.trim());
        long id = authService.grantAuthorization(request.userId, request.subAccountId,
                request.productId, quota,
                request.effectiveFrom == null ? null : LocalDate.parse(request.effectiveFrom),
                request.effectiveTo == null ? null : LocalDate.parse(request.effectiveTo),
                ctx.currentUserId(), request.reviewerId);
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    private static Long parseNullableLong(String raw) {
        return (raw == null || raw.isEmpty()) ? null : Long.valueOf(raw.trim());
    }

    /** 授权请求体。 */
    public static final class GrantRequest {

        /** 被授权人。 */
        public long userId;

        /** 子账户。 */
        public int subAccountId;

        /** 品种。 */
        public long productId;

        /** 额度（字符串化十进制）。 */
        public String quota;

        /** 生效起始日（{@code yyyy-MM-dd}）。 */
        public String effectiveFrom;

        /** 生效截止日（{@code yyyy-MM-dd}；可空）。 */
        public String effectiveTo;

        /** 复核人（必填且 != 操作人）。 */
        public Long reviewerId;
    }

    /** 授权视图（额度字符串化）。 */
    public static final class AuthorizationView {

        /** 主键。 */
        public long id;

        /** 被授权人。 */
        public long userId;

        /** 子账户。 */
        public int subAccountId;

        /** 品种。 */
        public long productId;

        /** 额度（字符串）。 */
        public String quotaLimit;

        /** 生效起始日。 */
        public String effectiveFrom;

        /** 生效截止日。 */
        public String effectiveTo;

        /** 状态。 */
        public String status;

        /** 复核人。 */
        public Long reviewerId;

        static AuthorizationView of(Authorization auth) {
            AuthorizationView view = new AuthorizationView();
            view.id = auth.id;
            view.userId = auth.userId;
            view.subAccountId = auth.subAccountId;
            view.productId = auth.productId;
            view.quotaLimit = auth.quotaLimit == null ? null : auth.quotaLimit.toPlainString();
            view.effectiveFrom = auth.effectiveFrom == null ? null : auth.effectiveFrom.toString();
            view.effectiveTo = auth.effectiveTo == null ? null : auth.effectiveTo.toString();
            view.status = auth.status;
            view.reviewerId = auth.reviewerId;
            return view;
        }
    }
}
