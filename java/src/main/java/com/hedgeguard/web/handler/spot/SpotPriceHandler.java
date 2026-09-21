package com.hedgeguard.web.handler.spot;

import com.hedgeguard.dao.entity.SpotPrice;
import com.hedgeguard.service.SpotService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.Locale;

/**
 * 现货价 {@code POST|GET /api/v1/spot/price}（{@code T-013}；{@code FR-SPOT-10~13/17}）。
 *
 * <p>{@code POST}（录入/覆盖）：已存在同口径价格时**必须** {@code overwriteConfirmed=true}，
 * 否则 {@code 10009}（{@code R-SPOT-3}：不得静默覆盖；原值记入 {@code overwritten_price}）。
 *
 * <p>{@code GET ?productId=&date=&purpose=RATIO|VALUATION}：按用途处置缺价 ——
 * {@code RATIO} 缺价**拒绝**（{@code 10015}）；{@code VALUATION} 可沿用最近一期价，
 * 响应**显式**返回 {@code priceDate}（即 {@code price_as_of}）与 {@code nonCurrent} 标记
 * （{@code CFG-12/12b} + {@code R-SPOT-4}）。
 */
public final class SpotPriceHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/spot/price");

    private final SpotService spotService;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     */
    public SpotPriceHandler(SpotService spotService) {
        this.spotService = spotService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            String rawProduct = ctx.queryParam("productId");
            String rawDate = ctx.queryParam("date");
            String rawPurpose = ctx.queryParam("purpose");
            long productId;
            try {
                productId = Long.parseLong(rawProduct == null ? "" : rawProduct);
            } catch (NumberFormatException e) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "productId 须为十进制整数：" + rawProduct + "；复现=补 ?productId=&date=", e);
            }
            java.time.LocalDate bizDate = SpotContractHandler.date(rawDate, "date");
            SpotService.PricePurpose purpose;
            try {
                purpose = SpotService.PricePurpose.valueOf(
                        (rawPurpose == null ? "VALUATION" : rawPurpose).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "purpose 只允许 RATIO / VALUATION：" + rawPurpose + "；复现=改正取值", e);
            }
            SpotPrice price = spotService.resolvePrice(productId, bizDate, purpose);
            PriceView view = new PriceView();
            view.productId = price.productId;
            view.priceDate = price.priceDate.toString();
            view.spotPrice = price.spotPrice.toPlainString();
            view.priceUnit = price.priceUnit;
            view.sourceType = price.sourceType;
            view.nonCurrent = price.priceDate.isBefore(bizDate);
            return HttpResult.json(200, ApiResponse.ok(view, ctx.traceId()));
        }
        PriceRequest request = JsonCodec.fromJson(ctx.body(), PriceRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 JSON 体（subAccountId/productId/priceDate/spotPrice/sourceType/priceUnit）："
                            + "复现=按 §4.1.1 提交");
        }
        SpotPrice price = new SpotPrice();
        price.subAccountId = request.subAccountId;
        price.productId = request.productId;
        price.priceDate = SpotContractHandler.date(request.priceDate, "priceDate");
        price.spotPrice = SpotContractHandler.decimal(request.spotPrice, "spotPrice");
        price.sourceType = request.sourceType;
        price.sourceDesc = request.sourceDesc;
        price.importFileName = request.importFileName;
        price.priceUnit = request.priceUnit;
        long id = spotService.putSpotPrice(price, request.overwriteConfirmed, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 录入/覆盖请求体。 */
    public static final class PriceRequest {

        /** 录入归属子账户。 */
        public int subAccountId;

        /** 品种。 */
        public long productId;

        /** 价格日期（yyyy-MM-dd；不得晚于当日）。 */
        public String priceDate;

        /** 现货价（字符串化十进制，&gt; 0）。 */
        public String spotPrice;

        /** 来源（{@code MANUAL} / {@code BATCH_PASTE} / {@code CSV_IMPORT}）。 */
        public String sourceType;

        /** 业务说明补充。 */
        public String sourceDesc;

        /** CSV 文件名（{@code CSV_IMPORT} 必填）。 */
        public String importFileName;

        /** 价格单位（如 {@code CNY/TON}）。 */
        public String priceUnit;

        /** 是否确认覆盖（已存在同口径价格时必须为 {@code true}）。 */
        public boolean overwriteConfirmed;
    }

    /** 取价视图（显式给出实际价时点与非当日标记）。 */
    public static final class PriceView {

        /** 品种。 */
        public long productId;

        /** 实际价格日期（= {@code price_as_of}）。 */
        public String priceDate;

        /** 价格（字符串化十进制）。 */
        public String spotPrice;

        /** 价格单位。 */
        public String priceUnit;

        /** 来源类型。 */
        public String sourceType;

        /** 是否为非当日价（{@code true} 时必须向用户标注）。 */
        public boolean nonCurrent;
    }
}
