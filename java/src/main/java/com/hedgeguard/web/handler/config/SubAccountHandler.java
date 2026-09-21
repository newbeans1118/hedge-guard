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
 * 子账户 {@code GET|POST /api/v1/config/sub-account}（{@code T-008}）。
 *
 * <p>{@code GET}：列启用中的子账户（加总对账取全集 / 下拉框）。
 * {@code POST}：创建子账户（分类 {@code COMMODITY}/{@code TRADE}）。
 * <b>无 {@code DELETE}</b>——{@code CFG-10} 要求 {@code DISABLE_ONLY}，停用走独立动作接口。
 */
public final class SubAccountHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/sub-account");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public SubAccountHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            List<SubAccountView> views = new ArrayList<>();
            for (SubAccount account : configService.listEnabled()) {
                views.add(SubAccountView.of(account));
            }
            return HttpResult.json(200, ApiResponse.ok(views, ctx.traceId()));
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
