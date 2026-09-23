package com.hedgeguard.web.handler.product;

import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductContract;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 品种-合约关联 {@code GET|POST /api/v1/product/{id}/contracts}（{@code T-012}；{@code FR-PRODUCT-9}）。
 *
 * <p>{@code GET}：该品种的合约清单（下单合约下拉）；{@code POST}：新增关联。
 * 期权与期货**共用同一追溯链与同一套保额度**（{@code R-PLAN-11}），故 {@code instrumentType} 必显式。
 *
 * <p><b>{@code T-151} 读取面隔离</b>：{@code GET} 先按<b>调用者可见子账户范围</b>过滤品种
 * （{@link ProductHandler#requireVisibleProduct}，{@code NFR-SEC-7} / {@code FR-PRODUCT-6} 处理规则）；
 * <b>不可见 ≡ 不存在</b>（统一错误 {@code 10001}，不泄露存在性，不泄出合约明细）。
 * {@code POST} 为写面：品种档案全局共享（{@code R-PRODUCT-7}），写入不改变可见集 ⇒ <b>不加</b>可见性过滤，
 * 维持既有存在性校验。
 */
public final class ProductContractHandler implements RouteHandler {

    /** 注册路径模板（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH_BY_PRODUCT = ApiRoutes.full("/product/{id}/contracts");

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public ProductContractHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        String rawId = ctx.pathParam("id");
        if ("GET".equalsIgnoreCase(ctx.method())) {
            // T-151：读取面（GET）按调用者可见子账户范围过滤品种；不可见 ≡ 不存在（统一错误，不泄露存在性）
            Product product = ProductHandler.requireVisibleProduct(varietyService, rawId,
                    ctx.currentUserId());
            List<ContractView> views = new ArrayList<>();
            for (ProductContract contract : varietyService.listContracts(product.id)) {
                views.add(ContractView.of(contract));
            }
            return HttpResult.json(200, ApiResponse.ok(views, ctx.traceId()));
        }
        // 写面（POST）：品种档案全局共享（R-PRODUCT-7），写入不改变可见集 ⇒ 维持既有存在性校验（无需可见性过滤）
        long productId = ProductHandler.requireProduct(varietyService, rawId).id;
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.instrumentId == null || request.instrumentType == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {instrumentId, instrumentType, exchangeCode, contractMultiplier, expiryDate}："
                            + "复现=按 FR-PRODUCT-9 提交（期权另需标的/行权价/类型）");
        }
        ProductContract contract = new ProductContract();
        contract.productId = productId;
        contract.instrumentId = request.instrumentId;
        contract.instrumentType = request.instrumentType;
        contract.exchangeCode = request.exchangeCode;
        contract.contractMultiplier = request.contractMultiplier == null ? null
                : new BigDecimal(request.contractMultiplier);
        contract.expiryDate = request.expiryDate == null ? null
                : java.time.LocalDate.parse(request.expiryDate);
        contract.underlyingInstrumentId = request.underlyingInstrumentId;
        contract.strikePrice = request.strikePrice == null ? null : new BigDecimal(request.strikePrice);
        contract.optionType = request.optionType;
        long id = varietyService.putContract(contract, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 写入请求体（数量/价格以字符串承载，{@code §4.1.1}）。 */
    public static final class Request {

        /** 合约代码（全局唯一）。 */
        public String instrumentId;

        /** {@code FUTURES} / {@code OPTION}（必须显式标注）。 */
        public String instrumentType;

        /** 交易所代码。 */
        public String exchangeCode;

        /** 合约乘数（字符串化十进制）。 */
        public String contractMultiplier;

        /** 到期日（{@code yyyy-MM-dd}）。 */
        public String expiryDate;

        /** 期权标的合约（期权必填）。 */
        public String underlyingInstrumentId;

        /** 行权价（期权必填，字符串化十进制）。 */
        public String strikePrice;

        /** {@code CALL} / {@code PUT}（期权必填）。 */
        public String optionType;
    }

    /** 合约视图。 */
    public static final class ContractView {

        /** 主键。 */
        public long id;

        /** 合约代码。 */
        public String instrumentId;

        /** 合约类型。 */
        public String instrumentType;

        /** 交易所。 */
        public String exchangeCode;

        /** 合约乘数（字符串化十进制）。 */
        public String contractMultiplier;

        /** 到期日。 */
        public String expiryDate;

        /** 期权类型。 */
        public String optionType;

        /** 行权价（字符串化十进制）。 */
        public String strikePrice;

        /** 状态。 */
        public String status;

        static ContractView of(ProductContract contract) {
            ContractView view = new ContractView();
            view.id = contract.id;
            view.instrumentId = contract.instrumentId;
            view.instrumentType = contract.instrumentType;
            view.exchangeCode = contract.exchangeCode;
            view.contractMultiplier = contract.contractMultiplier == null ? null
                    : contract.contractMultiplier.toPlainString();
            view.expiryDate = contract.expiryDate == null ? null : contract.expiryDate.toString();
            view.optionType = contract.optionType;
            view.strikePrice = contract.strikePrice == null ? null
                    : contract.strikePrice.toPlainString();
            view.status = contract.status;
            return view;
        }
    }
}
