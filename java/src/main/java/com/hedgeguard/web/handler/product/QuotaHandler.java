package com.hedgeguard.web.handler.product;

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
import java.util.Map;

/**
 * 品种额度（三层限额）{@code POST|GET /api/v1/product/quota}（{@code T-012}；{@code Q-16}）。
 *
 * <p>{@code POST}（写入）：层①/层③走 {@link VarietyService#putQuota}，
 * 层②（含 {@code instrumentId}）走 {@link VarietyService#putInstrumentQuota}。
 * {@code quotaLimit} 以**字符串化十进制**承载（{@code §4.1.1} 金额/数量约定，防 JS 精度丢失）。
 *
 * <p>{@code GET ?subAccountId=&productId=} 取**三层全景**（展示用；其中的
 * {@code used_qty}/{@code frozen_qty} 是库内投影，**不作判定依据**，{@code R-RISK-9}）。
 */
public final class QuotaHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/product/quota");

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public QuotaHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            int subAccountId = parseInt(ctx.queryParam("subAccountId"), "subAccountId");
            long productId = parseLong(ctx.queryParam("productId"), "productId");
            Map<String, Object> panorama = varietyService.quotaPanorama(subAccountId, productId);
            return HttpResult.json(200, ApiResponse.ok(panorama, ctx.traceId()));
        }
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.limitLayer == null || request.quotaLimit == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {limitLayer, subAccountId, productId, quotaLimit, quotaUnit, effectiveFrom}"
                            + "（层②另需 instrumentId）：复现=按 Q-16 三层口径提交");
        }
        BigDecimal limit = parseDecimal(request.quotaLimit);
        LocalDate from = parseDate(request.effectiveFrom);
        long id;
        if (request.instrumentId != null && !request.instrumentId.isEmpty()) {
            id = varietyService.putInstrumentQuota(request.subAccountId, request.instrumentId,
                    request.productId, limit, request.quotaUnit, from, ctx.currentUserId());
        } else {
            id = varietyService.putQuota(request.subAccountId, request.limitLayer, request.productId,
                    limit, request.quotaUnit, from, ctx.currentUserId());
        }
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    private static int parseInt(String raw, String field) {
        long value = parseLong(raw, field);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    field + " 超出 INT 范围：" + raw + "；复现=核对子账户 ID");
        }
        return (int) value;
    }

    private static long parseLong(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数必填：" + field + "；复现=补 ?subAccountId=&productId=");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数须为十进制整数：" + field + "=" + raw + "；复现=改正取值", e);
        }
    }

    private static BigDecimal parseDecimal(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "quotaLimit 须为字符串化十进制：" + raw
                            + "；复现=按 §4.1.1 以字符串承载数量（禁止 JSON number）", e);
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "effectiveFrom 必填（yyyy-MM-dd）：复现=补齐生效起始日");
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "effectiveFrom 须为 yyyy-MM-dd：" + raw + "；复现=改正日期格式", e);
        }
    }

    /** 写入请求体。 */
    public static final class Request {

        /** 层级（{@code SUB_PRODUCT} / {@code SUB_INSTRUMENT} / {@code ACCOUNT_PRODUCT}）。 */
        public String limitLayer;

        /** 子账户（层③用哨兵 0；层①/②用真实子账户）。 */
        public int subAccountId;

        /** 品种。 */
        public long productId;

        /** 合约代码（层②必填）。 */
        public String instrumentId;

        /** 额度上限（**字符串化十进制**）。 */
        public String quotaLimit;

        /** 单位（{@code LOT} / {@code TON}）。 */
        public String quotaUnit;

        /** 生效起始日（{@code yyyy-MM-dd}）。 */
        public String effectiveFrom;
    }
}
