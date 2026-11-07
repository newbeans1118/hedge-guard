package com.hedgeguard.web.handler.product;

import com.hedgeguard.dao.entity.ProductUnit;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 品种状态与换算关系动作 {@code POST /api/v1/product/{id}/{disable|enable|unit}}（{@code T-131}；
 * {@code FR-PRODUCT-3} 停用 / {@code FR-PRODUCT-4} 启用 / {@code FR-PRODUCT-13} 计量单位）。
 *
 * <p><b>形态依据（{@code §4.1.1}，{@code v1.5.27}）</b>：三条路径已在 {@code §4.1.1} 的 PRODUCT 资源行
 * 声明为**动作式 POST**（方法列括注 {@code disable}/{@code enable}/{@code unit} 为 {@code POST}），
 * 承载 {@code FR-PRODUCT-3}/{@code FR-PRODUCT-4}/{@code FR-PRODUCT-2}（计量单位与换算关系）。
 * 为什么不是 {@code PUT}：
 * <ul>
 *   <li>停用 / 启用是<b>状态机迁移</b>（{@code R-PRODUCT-1} 仅 {@code ENABLED→DISABLED} 与
 *       {@code DISABLED→ENABLED} 两条边），不是属性幂等覆盖；</li>
 *   <li>计量单位是<b>追加一条生效换算关系</b>（{@code FR-PRODUCT-13}「变更不追溯重算历史」⇒
 *       {@code t_product_unit} 落新行 + {@code uk_product_unit_eff(product_id, effective_from)}）——
 *       若用 {@code PUT} 表达「幂等替换当前换算关系」会与「同一生效日重复提交即 {@code 10010}」相矛盾。</li>
 * </ul>
 * 对属性的修改仍走 {@code PUT /product/{id}}（见 {@link ProductHandler}）。
 *
 * <p><b>请求体</b>：
 * <pre>
 *   POST /product/{id}/disable  {"reason":"不再套保"}                        → {status, removedWhitelistRows}
 *   POST /product/{id}/enable   {}                                            → {status}
 *   POST /product/{id}/unit     {"spotUnit":"吨","futuresUnit":"手",
 *                                "convertFactor":"5.0000","effectiveFrom":"2026-11-01",
 *                                "convertDesc":"1 手 = 5 吨"}                 → {unitId, effectiveFrom}
 * </pre>
 *
 * <p><b>权限</b>：三动作的授权判定<b>全部</b>在服务层（{@code disableProduct}/{@code enableProduct}/
 * {@code putUnit} → {@code requireGlobalObjectWriteAuthority}，{@code T-118} 的同一强制点；
 * 拒绝 {@code 10003}，入口缺位 fail-closed）。本层不做第二次判定，避免两套口径（{@code CH-012}）。
 * 品种档案与换算关系属<b>全局共享</b>对象（{@code R-PRODUCT-7}）⇒ <b>不</b>套子账户写面断言（会误拒）。
 */
public final class ProductActionHandler implements RouteHandler {

    /** 可执行的写动作（路径段逐字取自 {@code §4.1.1} 的 PRODUCT 行声明）。 */
    public enum Action {

        /** 停用（{@code FR-PRODUCT-3}；原因必填，级联移出白名单）。 */
        DISABLE("disable"),

        /** 启用（{@code FR-PRODUCT-4}；前置：存在生效换算关系）。 */
        ENABLE("enable"),

        /** 新增计量单位换算关系（{@code FR-PRODUCT-13}）。 */
        PUT_UNIT("unit");

        private final String segment;

        Action(String segment) {
            this.segment = segment;
        }

        /** 注册路径（{@code /api/v1/product/{id}/<segment>}）。 */
        public String path() {
            return ApiRoutes.full("/product/{id}/" + segment);
        }
    }

    private final VarietyService varietyService;
    private final Action action;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     * @param action         动作
     */
    public ProductActionHandler(VarietyService varietyService, Action action) {
        this.varietyService = varietyService;
        this.action = action;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long productId = ProductHandler.parseId(ctx.pathParam("id"));
        long operatorId = ctx.currentUserId();
        Request request = JsonCodec.fromJson(safeBody(ctx), Request.class);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", productId);
        switch (action) {
            case DISABLE:
                requireReason(request == null ? null : request.reason);
                data.put("status", "DISABLED");
                data.put("removedWhitelistRows", varietyService.disableProduct(productId,
                        request.reason, operatorId));
                break;
            case ENABLE:
                varietyService.enableProduct(productId, operatorId);
                data.put("status", "ENABLED");
                break;
            case PUT_UNIT:
            default:
                ProductUnit unit = unitOf(productId, request);
                data.put("unitId", varietyService.putUnit(unit, operatorId));
                data.put("effectiveFrom", unit.effectiveFrom.toString());
                break;
        }
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    /** 空体宽容（启用动作无必填字段；{@code JsonCodec} 对空体抛 {@code 10001}，故先补 {@code {}}）。 */
    private static String safeBody(RequestContext ctx) {
        String body = ctx.body();
        return body == null || body.trim().isEmpty() ? "{}" : body;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "停用原因必填（FR-PRODUCT-3 输入列）：复现=在请求体给出 reason 后重试");
        }
    }

    /** 组装换算关系实体（类型解析只在本层；取值域与方向由服务层校验）。 */
    private static ProductUnit unitOf(long productId, Request request) {
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {spotUnit, futuresUnit, convertFactor, effectiveFrom}："
                            + "复现=按 FR-PRODUCT-13 输入列提交（系数须 > 0，生效日 yyyy-MM-dd）");
        }
        ProductUnit unit = new ProductUnit();
        unit.productId = productId;
        unit.spotUnit = request.spotUnit;
        unit.futuresUnit = request.futuresUnit;
        unit.convertDesc = request.convertDesc;
        unit.status = request.status;
        unit.convertFactor = parseFactor(request.convertFactor);
        unit.effectiveFrom = parseDate(request.effectiveFrom);
        return unit;
    }

    /**
     * 解析换算系数（字符串化十进制 ⇒ {@code BigDecimal}；空/非法 ⇒ {@code 10001}）。
     *
     * @param raw 原始字符串
     * @return 换算系数（{@code > 0} 由服务层校验，{@code FR-PRODUCT-13} 验收 2）
     */
    static BigDecimal parseFactor(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "换算系数必填（convertFactor，字符串化十进制，如 \"1.0000\"）：复现=补齐 convertFactor");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "换算系数须为十进制数字字符串：实际=" + raw
                            + "；复现=按 §4.1.1 金额/数量字符串化口径提交（如 \"1.0000\"）", e);
        }
    }

    /**
     * 解析生效起始日（{@code yyyy-MM-dd}；空/非法 ⇒ {@code 10001}）。
     *
     * @param raw 原始字符串
     * @return 生效起始日
     */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "生效起始日必填（effectiveFrom，yyyy-MM-dd；换算关系变更不追溯重算历史）："
                            + "复现=补齐 effectiveFrom");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "生效起始日格式须为 yyyy-MM-dd：实际=" + raw
                            + "；复现=按 §4.1.1 纯日期格式提交", e);
        }
    }

    /**
     * 请求体（各动作所需字段；{@code FR-PRODUCT-3/4/13} 输入列）。
     */
    public static final class Request {

        /** 停用原因（{@code disable} 必填）。 */
        public String reason;

        /** 现货侧计量单位（{@code unit} 必填，如「吨」）。 */
        public String spotUnit;

        /** 期货侧交易单位（{@code unit} 必填，如「手」）。 */
        public String futuresUnit;

        /** 换算系数（{@code unit} 必填；字符串化十进制，须 {@code > 0}）。 */
        public String convertFactor;

        /** 换算方向说明（如「1 手 = 10 吨」，可空）。 */
        public String convertDesc;

        /** 生效起始日（{@code unit} 必填，{@code yyyy-MM-dd}）。 */
        public String effectiveFrom;

        /** 换算关系状态（可空 ⇒ 服务层默认 {@code ENABLED}）。 */
        public String status;
    }
}
