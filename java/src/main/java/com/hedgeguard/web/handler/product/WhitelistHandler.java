package com.hedgeguard.web.handler.product;

import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.service.VarietyService;
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
 * 子账户品种白名单 {@code GET|POST /api/v1/product/whitelist}（{@code T-012} 写面 + {@code T-131} 读面）。
 *
 * <p><b>读面（{@code GET}，{@code T-131}）</b>：返回指定品种（或指定子账户）的<b>当前完整白名单</b>，
 * 供 {@code FR-PRODUCT-6}「详情含…所属子账户白名单」与前端「白名单」Tab 列出实际行。
 * 查询参数：{@code productId} / {@code subAccountId}（至少给一个）/ {@code status}
 * （{@code ENABLED}|{@code DISABLED}，空 = 全部，含已移出历史行）。可见性口径见
 * {@code VarietyService.listWhitelist}（读面过滤；哨兵 {@code 0} ⇒ 不加过滤；空集 ⇒ 恒空）。
 * <b>为何读面归 {@code FR-PRODUCT-6} 而非 {@code FR-PRODUCT-12}</b>：后者的输入列只有「子账户标识、
 * 品种集合（增/减）」，不含读取（{@code docs/REQUIREMENTS/PRODUCT.md}）。
 *
 * <p><b>写面（{@code POST}，{@code FR-PRODUCT-12}）</b>：单端点双动作，用 {@code action} 区分
 * （{@code §4.1.1}「新增或动作」语义）：
 * <pre>
 *   {"action":"ENABLE",  "subAccountId":3, "productId":7, "categoryOverride":"TRADE"}
 *   {"action":"DISABLE", "subAccountId":3, "productId":7, "reason":"指标调整"}
 * </pre>
 * 移出（{@code DISABLE}）是**置 {@code DISABLED} 并保留历史**，不是物理删除
 * （{@code R-PRODUCT-5}）；变更置 {@code dispatch_status='PENDING'}`待下发。
 */
public final class WhitelistHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/product/whitelist");

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public WhitelistHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return list(ctx);
        }
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.action == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {action, subAccountId, productId, categoryOverride?/reason?}："
                            + "复现=action ∈ {ENABLE, DISABLE}");
        }
        if ("DISABLE".equalsIgnoreCase(request.action)) {
            varietyService.removeWhitelist(request.subAccountId, request.productId, request.reason,
                    ctx.currentUserId());
        } else if ("ENABLE".equalsIgnoreCase(request.action)) {
            varietyService.putWhitelist(request.subAccountId, request.productId,
                    request.categoryOverride, ctx.currentUserId());
        } else {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "未知 action：" + request.action + "；复现=取 ENABLE 或 DISABLE");
        }
        return HttpResult.json(200, ApiResponse.ok(null, ctx.traceId()));
    }

    /**
     * 白名单读取（{@code GET /product/whitelist}，{@code T-131}；{@code FR-PRODUCT-6}）。
     *
     * <p>参数解析只做<b>类型</b>校验（非数字 ⇒ {@code 10001}，不静默忽略）；取值域
     * （{@code subAccountId > 0}、{@code status ∈ {ENABLED, DISABLED}}、两者至少给一个）与
     * 可见性口径在服务层（{@code VarietyService.listWhitelist}）。
     *
     * @param ctx 请求上下文
     * @return 白名单行列表（无命中 ⇒ 空数组，不报错）
     */
    private HttpResult list(RequestContext ctx) {
        Long productId = parseOptionalLong(ctx.queryParam("productId"));
        Integer subAccountId = parseOptionalInt(ctx.queryParam("subAccountId"));
        String status = ctx.queryParam("status");
        List<WhitelistView> views = new ArrayList<>();
        for (SubProductWhitelist row : varietyService.listWhitelist(productId, subAccountId, status,
                ctx.currentUserId())) {
            views.add(WhitelistView.of(row));
        }
        return HttpResult.json(200, ApiResponse.ok(views, ctx.traceId()));
    }

    /** 解析可选十进制定点整数（空 ⇒ {@code null}；非法 ⇒ {@code 10001}）。 */
    private static Long parseOptionalLong(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "productId 须为十进制整数：实际=" + raw + "；复现=核对查询参数 productId", e);
        }
    }

    /** 解析可选十进制整数（空 ⇒ {@code null}；非法 ⇒ {@code 10001}）。 */
    private static Integer parseOptionalInt(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "subAccountId 须为十进制整数：实际=" + raw + "；复现=核对查询参数 subAccountId", e);
        }
    }

    /** 白名单行视图（wire 字段 {@code camelCase}，逐字对齐 {@code t_sub_product_whitelist} 语义列）。 */
    public static final class WhitelistView {

        /** 行主键。 */
        public long id;

        /** 子账户（真实子账户，{@code > 0}）。 */
        public Integer subAccountId;

        /** 品种。 */
        public Long productId;

        /** 品种级分类覆盖（{@code null} = 继承子账户分类，{@code Q-2}）。 */
        public String categoryOverride;

        /** 行状态（{@code ENABLED} 在册 / {@code DISABLED} 已移出但保留历史）。 */
        public String status;

        /** 下发状态（{@code PENDING} / {@code SENT} …；{@code R-PRODUCT-9}）。 */
        public String dispatchStatus;

        /** 下发版本号。 */
        public Long dispatchVersion;

        /** 移出原因（{@code DISABLED} 行）。 */
        public String disabledReason;

        /**
         * 组装视图。
         *
         * @param row 白名单行
         * @return 视图
         */
        static WhitelistView of(SubProductWhitelist row) {
            WhitelistView view = new WhitelistView();
            view.id = row.id;
            view.subAccountId = row.subAccountId;
            view.productId = row.productId;
            view.categoryOverride = row.categoryOverride;
            view.status = row.status;
            view.dispatchStatus = row.dispatchStatus;
            view.dispatchVersion = row.dispatchVersion;
            view.disabledReason = row.disabledReason;
            return view;
        }
    }

    /** 请求体。 */
    public static final class Request {

        /** {@code ENABLE}（加入/重新加入）或 {@code DISABLE}（移出并保留历史）。 */
        public String action;

        /** 子账户（真实子账户，&gt; 0）。 */
        public int subAccountId;

        /** 品种。 */
        public long productId;

        /** 品种级分类覆盖（可空 = 继承子账户分类，{@code Q-2}）。 */
        public String categoryOverride;

        /** 移出原因（{@code DISABLE} 时必填）。 */
        public String reason;
    }
}
