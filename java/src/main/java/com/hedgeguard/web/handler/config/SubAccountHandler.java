package com.hedgeguard.web.handler.config;

import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 子账户 {@code GET|POST|PUT /api/v1/config/sub-account}（{@code T-008}；{@code PUT} 由 {@code T-135} 接线）。
 *
 * <p>{@code GET}：列启用中的子账户（加总对账取全集 / 下拉框）。
 * {@code POST}：创建子账户（分类 {@code COMMODITY}/{@code TRADE}）。
 * {@code PUT}：停用子账户（{@code FR-CONFIG-7} 验收 3；服务层 {@code ConfigService.disableSubAccount}
 * 已实现四条件判定 {@code DISABLE_ONLY}，此前生产 0 调用——本端点即其接线）。
 * <b>无 {@code DELETE}</b>——{@code CFG-10} 要求 {@code DISABLE_ONLY}，停用走 {@code PUT}。
 */
public final class SubAccountHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/sub-account");

    private final ConfigService configService;

    /** 停用四条件判定端口（{@code null} 时停用端点明确失败，不静默放行）。 */
    private final ConfigService.SubAccountDisablePrecheck disablePrecheck;

    /**
     * 构造（不装配停用端口；停用调用将明确失败）。
     *
     * @param configService 配置服务
     */
    public SubAccountHandler(ConfigService configService) {
        this(configService, null);
    }

    /**
     * 构造。
     *
     * @param configService   配置服务
     * @param disablePrecheck 停用四条件判定端口（{@code FR-CONFIG-7} 验收 3）
     */
    public SubAccountHandler(ConfigService configService,
                             ConfigService.SubAccountDisablePrecheck disablePrecheck) {
        this.configService = configService;
        this.disablePrecheck = disablePrecheck;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        String method = ctx.method().toUpperCase(java.util.Locale.ROOT);
        if ("GET".equals(method)) {
            List<SubAccountView> views = new ArrayList<>();
            for (SubAccount account : configService.listEnabled()) {
                views.add(SubAccountView.of(account));
            }
            return HttpResult.json(200, ApiResponse.ok(views, ctx.traceId()));
        }
        if ("PUT".equals(method)) {
            return disable(ctx);
        }
        SubAccountRequest request = JsonCodec.fromJson(ctx.body(), SubAccountRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "创建子账户须提交 JSON 体（subAccountCode/subAccountName/category）：复现=按 §4.1.1 提交");
        }
        SubAccount account = new SubAccount();
        account.subAccountCode = request.subAccountCode;
        account.subAccountName = request.subAccountName;
        account.category = request.category;
        account.businessLineDesc = request.businessLineDesc;
        long id = configService.createSubAccount(account, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(SubAccountView.of(configService
                .findSubAccountByCode(request.subAccountCode)), ctx.traceId()))
                .withHeader("X-HG-Created-Id", String.valueOf(id));
    }

    /** 停用子账户（{@code PUT /config/sub-account {subAccountId, reason}}；{@code FR-CONFIG-7} 验收 3）。 */
    private HttpResult disable(RequestContext ctx) {
        if (disablePrecheck == null) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "子账户停用前置校验端口未装配，无法核算四条件；复现=经 Bootstrap 装配后重试"
                            + "（FR-CONFIG-7 验收 3）");
        }
        DisableRequest request = JsonCodec.fromJson(ctx.body(), DisableRequest.class);
        if (request == null || request.subAccountId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "停用子账户须提交 {subAccountId, reason}：复现=按 FR-CONFIG-7 提交 application/json");
        }
        configService.disableSubAccount(request.subAccountId, request.reason, disablePrecheck,
                ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(
                SubAccountView.of(configService.findSubAccountById(request.subAccountId)),
                ctx.traceId()));
    }

    /** 停用请求体。 */
    public static final class DisableRequest {

        /** 目标子账户 ID。 */
        public int subAccountId;

        /** 停用原因（必填，审计要求）。 */
        public String reason;
    }

    /** 创建请求体。 */
    public static final class SubAccountRequest {

        /** 子账户编码（全系统唯一）。 */
        public String subAccountCode;

        /** 子账户名称。 */
        public String subAccountName;

        /** 分类（{@code COMMODITY} / {@code TRADE}；{@code CFG-02c}）。 */
        public String category;

        /** 业务线说明。 */
        public String businessLineDesc;
    }

    /** 子账户视图（数量以字符串承载，{@code §4.1.1}）。 */
    public static final class SubAccountView {

        /** 主键。 */
        public long id;

        /** 编码。 */
        public String code;

        /** 名称。 */
        public String name;

        /** 分类。 */
        public String category;

        /** 状态。 */
        public String status;

        /** 期初资金（字符串化十进制；无值时常量 {@code "0"}）。 */
        public String initialFund;

        static SubAccountView of(SubAccount account) {
            SubAccountView view = new SubAccountView();
            view.id = account.id;
            view.code = account.subAccountCode;
            view.name = account.subAccountName;
            view.category = account.category;
            view.status = account.status;
            view.initialFund = account.initialFund == null ? "0" : account.initialFund.toPlainString();
            return view;
        }
    }
}
