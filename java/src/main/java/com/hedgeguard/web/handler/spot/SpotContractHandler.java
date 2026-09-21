package com.hedgeguard.web.handler.spot;

import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.SalesContract;
import com.hedgeguard.service.SpotService;
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
import java.util.Locale;

/**
 * 现货合同 {@code POST /api/v1/spot/purchase}（采购）与 {@code /spot/sales}（销售）（{@code T-013}）。
 *
 * <p>同一处理器按构造参数 {@code purchase} 分派两个端点（= {@link SpotRoutes} 的装配契约）。
 * 采购侧动作集：{@code CREATE}（默认）/ {@code ACTIVATE} / {@code VOID} / {@code DELETE_DRAFT}：
 * <ul>
 *   <li>{@code ACTIVATE}：提交为有效——**现货品类必须先完成映射**（{@code FR-PRODUCT-10}）；</li>
 *   <li>{@code VOID}：{@code ACTIVE} 及之后**只能作废**（{@code R-SPOT-9}），须给 {@code voidReason}；
 *       若已被敞口明细引用则 {@code 10011}（保护追溯链，{@code FR-SPOT-3}）；</li>
 *   <li>{@code DELETE_DRAFT}：**仅草稿**可物理删除（{@code DATA_MODEL.md} §0.3 例外）。</li>
 * </ul>
 *
 * <p>{@link #decimal} / {@link #date} 为本包内各 handler 共用的**请求解析**工具
 * （字符串化十进制与 {@code yyyy-MM-dd}，{@code §4.1.1} 约定）。
 */
public final class SpotContractHandler implements RouteHandler {

    /** 采购合同路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH_PURCHASE = ApiRoutes.full("/spot/purchase");

    /** 销售合同路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH_SALES = ApiRoutes.full("/spot/sales");

    private final SpotService spotService;
    private final boolean purchase;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     * @param purchase    {@code true} = 采购端点；{@code false} = 销售端点
     */
    public SpotContractHandler(SpotService spotService, boolean purchase) {
        this.spotService = spotService;
        this.purchase = purchase;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        return purchase ? handlePurchase(ctx) : handleSales(ctx);
    }

    private HttpResult handlePurchase(RequestContext ctx) {
        PurchaseRequest request = JsonCodec.fromJson(ctx.body(), PurchaseRequest.class);
        if (request == null || request.contractNo == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {subAccountId, contractNo, spotCategoryCode, totalQty, "
                            + "contractStartDate, contractEndDate, action?, voidReason?}"
                            + "；复现=action ∈ {CREATE, ACTIVATE, VOID, DELETE_DRAFT}");
        }
        String action = request.action == null ? "CREATE" : request.action.toUpperCase(Locale.ROOT);
        if ("VOID".equals(action) || "DELETE_DRAFT".equals(action)) {
            if (request.contractId == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        action + " 须给出既存合同的 contractId：复现=先 CREATE 并以返回 id 调用"
                                + "（作废另需 voidReason）");
            }
            if ("VOID".equals(action)) {
                spotService.voidPurchaseContract(request.contractId, request.voidReason,
                        ctx.currentUserId());
            } else {
                spotService.deleteDraftPurchaseContract(request.contractId, ctx.currentUserId());
            }
            return HttpResult.json(200, ApiResponse.ok(request.contractId, ctx.traceId()));
        }

        PurchaseContract contract = new PurchaseContract();
        contract.subAccountId = request.subAccountId;
        contract.contractNo = request.contractNo;
        contract.spotCategoryCode = request.spotCategoryCode;
        contract.totalQty = decimal(request.totalQty, "totalQty");
        contract.contractStartDate = date(request.contractStartDate, "contractStartDate");
        contract.contractEndDate = date(request.contractEndDate, "contractEndDate");
        contract.supplierName = request.supplierName;
        long id = spotService.createPurchaseContract(contract, ctx.currentUserId());
        if ("ACTIVATE".equals(action)) {
            spotService.activatePurchaseContract(id, ctx.currentUserId());
        } else if (!"CREATE".equals(action)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "未知 action：" + action + "；复现=取 CREATE / ACTIVATE / VOID / DELETE_DRAFT");
        }
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    private HttpResult handleSales(RequestContext ctx) {
        SalesRequest request = JsonCodec.fromJson(ctx.body(), SalesRequest.class);
        if (request == null || request.contractNo == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {subAccountId, contractNo, spotCategoryCode, totalQty, "
                            + "contractStartDate, contractEndDate, hasDualDirection?, buyDirectionQty?}");
        }
        SalesContract contract = new SalesContract();
        contract.subAccountId = request.subAccountId;
        contract.contractNo = request.contractNo;
        contract.spotCategoryCode = request.spotCategoryCode;
        contract.totalQty = decimal(request.totalQty, "totalQty");
        contract.contractStartDate = date(request.contractStartDate, "contractStartDate");
        contract.contractEndDate = date(request.contractEndDate, "contractEndDate");
        contract.customerName = request.customerName;
        contract.hasDualDirection = request.hasDualDirection;
        contract.buyDirectionQty = request.buyDirectionQty == null ? null
                : decimal(request.buyDirectionQty, "buyDirectionQty");
        return HttpResult.json(200, ApiResponse.ok(
                spotService.createSalesContract(contract, ctx.currentUserId()), ctx.traceId()));
    }

    /**
     * 解析字符串化十进制（{@code §4.1.1}：数量/金额/价格一律字符串承载，禁止 JSON number）。
     *
     * @param raw   原始文本
     * @param field 字段名（用于错误定位）
     * @return 十进制值
     */
    public static BigDecimal decimal(String raw, String field) {
        if (raw == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段必填（字符串化十进制）：" + field + "；复现=按 §4.1.1 以字符串承载数量/金额");
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    field + " 须为字符串化十进制：" + raw + "；复现=发 \"1234.5600\" 而非 JSON number", e);
        }
    }

    /**
     * 解析业务日期（{@code yyyy-MM-dd}）。
     *
     * @param raw   原始文本
     * @param field 字段名（用于错误定位）
     * @return 日期
     */
    public static LocalDate date(String raw, String field) {
        if (raw == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "字段必填（yyyy-MM-dd）：" + field);
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    field + " 须为 yyyy-MM-dd：" + raw + "；复现=改正日期格式", e);
        }
    }

    /** 采购合同请求体。 */
    public static final class PurchaseRequest {

        /** 动作（{@code CREATE} 默认 / {@code ACTIVATE} / {@code VOID} / {@code DELETE_DRAFT}）。 */
        public String action;

        /** 既存合同 ID（{@code VOID} / {@code DELETE_DRAFT} 必填）。 */
        public Long contractId;

        /** 子账户（必填，不可静默变更）。 */
        public int subAccountId;

        /** 合同编号（同一子账户内唯一）。 */
        public String contractNo;

        /** 现货品类（经映射到品种）。 */
        public String spotCategoryCode;

        /** 合同总量（&gt; 0）。 */
        public String totalQty;

        /** 合同起始日（{@code yyyy-MM-dd}）。 */
        public String contractStartDate;

        /** 合同截止日（{@code yyyy-MM-dd}）。 */
        public String contractEndDate;

        /** 供应商名称（仅文本说明）。 */
        public String supplierName;

        /** 作废原因（{@code VOID} 必填）。 */
        public String voidReason;
    }

    /** 销售合同请求体。 */
    public static final class SalesRequest {

        /** 子账户。 */
        public int subAccountId;

        /** 合同编号。 */
        public String contractNo;

        /** 现货品类。 */
        public String spotCategoryCode;

        /** 合同总量（&gt; 0）。 */
        public String totalQty;

        /** 起始日。 */
        public String contractStartDate;

        /** 截止日。 */
        public String contractEndDate;

        /** 客户名称（仅文本说明）。 */
        public String customerName;

        /** 是否存在双向敞口（{@code Y}/{@code N}）。 */
        public String hasDualDirection;

        /** 双向敞口下的买入方向数量（{@code hasDualDirection='Y'} 时必填）。 */
        public String buyDirectionQty;
    }
}
