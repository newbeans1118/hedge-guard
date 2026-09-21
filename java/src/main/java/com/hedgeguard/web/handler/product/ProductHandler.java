package com.hedgeguard.web.handler.product;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.service.VarietyService;
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
 * 品种 {@code GET|POST /api/v1/product} 与 {@code GET /api/v1/product/{id}}（{@code T-012} 提交面）。
 *
 * <p>只暴露品种档案的**读写**；启停、白名单、额度各有独立路径（{@code /product/{id}} 的动作、
 * {@code /product/whitelist}、{@code /product/quota}），避免一个端点混杂多种语义
 * （{@code §4.1.1} 方法语义：查询 / 新增或动作 / 修改）。
 *
 * <p>品种**不允许物理删除**（{@code status} 无 {@code DELETED}），故本类**无** {@code DELETE}。
 */
public final class ProductHandler implements RouteHandler {

    /** 列表/新增路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/product");

    /** 详情路径模板（占位符 {@code {id}} 独占整段，{@code §1.4}）。 */
    public static final String PATH_BY_ID = ApiRoutes.full("/product/{id}");

    /**
     * 列表**缺省**排序：{@code created_time} <b>倒序</b>（{@code FR-PRODUCT-5}「默认按创建时间倒序」）。
     *
     * <p><b>为何必须带方向</b>：{@code PageQuery.of(..., defaultSortField)} 在 {@code sort} 缺省时
     * 方向<b>恒为 ASC</b>，故只传裸字段名会得到<b>升序</b>（见 {@code BUG-001}）。
     */
    private static final String DEFAULT_SORT = VarietyService.DEFAULT_SORT_FIELD + ",desc";

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public ProductHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            String id = ctx.pathParam("id");
            if (id != null && !id.isEmpty()) {
                return HttpResult.json(200, ApiResponse.ok(
                        ProductView.of(requireProduct(varietyService, id)), ctx.traceId()));
            }
            return list(ctx);
        }
        CreateRequest request = JsonCodec.fromJson(ctx.body(), CreateRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "新增品种须提交 JSON 体（productCode/productName/category）：复现=按 §4.1.1 提交");
        }
        Product product = new Product();
        product.productCode = request.productCode;
        product.productName = request.productName;
        product.category = request.category;
        product.remark = request.remark;
        long id = varietyService.createProduct(product, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(
                ProductView.of(varietyService.getProduct(id)), ctx.traceId()));
    }

    /**
     * 品种列表（{@code GET /product}，{@code FR-PRODUCT-5}）：分页 + 排序 + 子账户隔离。
     *
     * <p>分页 / 排序按 {@code §4.1.1}（{@code page/size/sort}；{@code size ∈ {10,20,50,100}}，默认 20），
     * 缺省排序 {@code created_time} <b>倒序</b>（{@code PageQuery} 缺省方向恒 {@code ASC}，故缺省时须
     * 显式补 {@code ,desc}，见 {@code BUG-001}）。响应 {@code data} 为 {@code {items,total,page,size}}
     * 分页信封；{@code total} 按过滤后总数计（含子账户隔离）。隔离身份取自会话（{@code ctx.currentUserId()}），
     * 不可由查询参数伪造。
     */
    private HttpResult list(RequestContext ctx) {
        String sort = ctx.queryParam("sort");
        if (sort == null || sort.isEmpty()) {
            sort = DEFAULT_SORT;
        }
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"), sort,
                VarietyService.DEFAULT_SORT_FIELD);
        PageResult<Product> page = varietyService.listProducts(ctx.currentUserId(), query);
        List<ProductView> items = new ArrayList<>();
        for (Product product : page.items()) {
            items.add(ProductView.of(product));
        }
        PageData<ProductView> data = new PageData<>(items, page.total(), page.page(), page.size());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    static Product requireProduct(VarietyService varietyService, String rawId) {
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种 ID 须为十进制整数：实际=" + rawId + "；复现=核对路径参数 {id}", e);
        }
        Product product = varietyService.getProduct(id);
        if (product == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：id=" + id + "；复现=按 product_code 查询确认 ID");
        }
        return product;
    }

    /** 新增请求体。 */
    public static final class CreateRequest {

        /** 品种编码（全系统唯一，一经使用不得修改）。 */
        public String productCode;

        /** 品种名称。 */
        public String productName;

        /** 分类（{@code COMMODITY} / {@code TRADE}）。 */
        public String category;

        /** 备注。 */
        public String remark;
    }

    /** 品种视图（枚举与文本原样返回；无金额字段故无字符串化数值约定）。 */
    public static final class ProductView {

        /** 主键。 */
        public long id;

        /** 编码。 */
        public String productCode;

        /** 名称。 */
        public String productName;

        /** 分类（{@code COMMODITY} → 90% 红线 / {@code TRADE} → 80% 红线）。 */
        public String category;

        /** 是否纳入分类管理。 */
        public String categoryManaged;

        /** 状态（{@code ENABLED} / {@code DISABLED}；无 {@code DELETED}）。 */
        public String status;

        /** 是否境内场内（恒 {@code "Y"}）。 */
        public String isDomesticExchange;

        /** 计价币种（本项目恒 {@code "CNY"}）。 */
        public String currency;

        /** 备注。 */
        public String remark;

        /** 停用原因（停用时非空）。 */
        public String disabledReason;

        static ProductView of(Product product) {
            ProductView view = new ProductView();
            view.id = product.id;
            view.productCode = product.productCode;
            view.productName = product.productName;
            view.category = product.category;
            view.categoryManaged = product.categoryManaged;
            view.status = product.status;
            view.isDomesticExchange = product.isDomesticExchange;
            view.currency = product.currency;
            view.remark = product.remark;
            view.disabledReason = product.disabledReason;
            return view;
        }
    }
}
