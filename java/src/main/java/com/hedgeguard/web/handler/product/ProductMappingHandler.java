package com.hedgeguard.web.handler.product;

import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductSpotCategory;
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
 * 品种-现货品类映射 {@code GET|POST /api/v1/product/mapping}（{@code T-012}；{@code FR-PRODUCT-10}）。
 *
 * <p>这是**期现一体的唯一入口**：{@code GET ?productId=} 取该品种的映射清单；
 * {@code POST} 维护映射（同一品类+方向**至多一个** {@code is_primary='Y'}`，违反即 {@code 10010}）。
 *
 * <p><b>{@code T-151} 读取面隔离</b>：{@code GET} 先按<b>调用者可见子账户范围</b>过滤品种
 * （{@link ProductHandler#requireVisibleProduct}，{@code NFR-SEC-7}）；<b>不可见 ≡ 不存在</b>
 * （统一错误 {@code 10001}，不泄露存在性，不泄出映射明细）。{@code POST} 为写面：品种档案全局共享
 * （{@code R-PRODUCT-7}）⇒ <b>不加</b>可见性过滤。
 */
public final class ProductMappingHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/product/mapping");

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public ProductMappingHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            String raw = ctx.queryParam("productId");
            if (raw == null || raw.isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "查询参数必填：productId；复现=GET /product/mapping?productId=…");
            }
            // T-151：读取面按调用者可见子账户范围过滤品种；不可见 ≡ 不存在（统一错误 10001，不泄露存在性）
            Product product = ProductHandler.requireVisibleProduct(varietyService, raw,
                    ctx.currentUserId());
            List<MappingView> views = new ArrayList<>();
            for (ProductSpotCategory mapping : varietyService.listSpotMappings(product.id)) {
                views.add(MappingView.of(mapping));
            }
            return HttpResult.json(200, ApiResponse.ok(views, ctx.traceId()));
        }
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.spotCategoryCode == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {spotCategoryCode, productId, mappingDirection, isPrimary?}："
                            + "复现=按 FR-PRODUCT-10 提交");
        }
        ProductSpotCategory mapping = new ProductSpotCategory();
        mapping.spotCategoryCode = request.spotCategoryCode;
        mapping.spotCategoryName = request.spotCategoryName;
        mapping.productId = request.productId;
        mapping.mappingDirection = request.mappingDirection;
        mapping.isPrimary = request.isPrimary;
        mapping.changeDesc = request.changeDesc;
        long id = varietyService.putSpotMapping(mapping, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 写入请求体。 */
    public static final class Request {

        /** 现货品类编码。 */
        public String spotCategoryCode;

        /** 现货品类名称。 */
        public String spotCategoryName;

        /** 映射到的品种。 */
        public long productId;

        /** {@code PURCHASE} / {@code SALES} / {@code BOTH}。 */
        public String mappingDirection;

        /** 是否主映射（同一品类+方向至多一个 {@code 'Y'}）。 */
        public String isPrimary;

        /** 变更说明（变更不得影响已成立的套保计划）。 */
        public String changeDesc;
    }

    /** 映射视图。 */
    public static final class MappingView {

        /** 主键。 */
        public long id;

        /** 现货品类编码。 */
        public String spotCategoryCode;

        /** 现货品类名称。 */
        public String spotCategoryName;

        /** 品种。 */
        public long productId;

        /** 映射方向。 */
        public String mappingDirection;

        /** 是否主映射。 */
        public String isPrimary;

        /** 状态。 */
        public String status;

        static MappingView of(ProductSpotCategory mapping) {
            MappingView view = new MappingView();
            view.id = mapping.id;
            view.spotCategoryCode = mapping.spotCategoryCode;
            view.spotCategoryName = mapping.spotCategoryName;
            view.productId = mapping.productId == null ? 0L : mapping.productId;
            view.mappingDirection = mapping.mappingDirection;
            view.isPrimary = mapping.isPrimary;
            view.status = mapping.status;
            return view;
        }
    }
}
