package com.hedgeguard.web.handler.spot;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.service.SpotService;
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
import java.util.Map;

/**
 * 实货敞口 {@code /api/v1/spot/exposure**}（{@code T-014}）。
 *
 * <pre>
 *   POST /spot/exposure              计算并落库敞口快照（三分量与合计**同事务**校验，Σ≠合计拒写）
 *   GET  /spot/exposure              待套保敞口列表（计划编制入口；**PageData 分页信封** + 子账户隔离）
 *   GET  /spot/exposure/{id}         取单条敞口（**按调用者可见子账户范围过滤**）
 *   POST /spot/exposure/recalc       展示侧重算（只告警 + 留痕，**不回写**落库值，Q-17 ④）
 * </pre>
 *
 * <p>缺价处置：未映射行（{@code productId=0}）无品种即无价 → {@code priceMissingFlag='Y'}；
 * 已映射行沿用最近一期价时会**显式**返回 {@code priceAsOf} 与 {@code nonCurrentPriceFlag}
 * （{@code CFG-12/12b}）。
 */
public final class SpotExposureHandler implements RouteHandler {

    /** 敞口集合路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/spot/exposure");

    /** 敞口详情路径模板。 */
    public static final String PATH_BY_ID = ApiRoutes.full("/spot/exposure/{id}");

    /** 展示侧重算路径。 */
    public static final String PATH_RECALC = ApiRoutes.full("/spot/exposure/recalc");

    /** 列表缺省排序字段（{@code FR-SPOT-15}「默认按敞口金额或数量倒序」）。 */
    private static final String DEFAULT_SORT_FIELD = "exposure_qty";

    /**
     * 列表<b>缺省</b>排序：{@code exposure_qty} <b>倒序</b>（{@code FR-SPOT-15} 处理规则
     * 「默认按敞口金额或数量倒序」；取数量而非金额，因金额在缺价时为 {@code NULL}）。
     *
     * <p><b>为何必须带方向</b>：{@code PageQuery.of(..., defaultSortField)} 在 {@code sort} 缺省时
     * 方向<b>恒为 {@code ASC}</b>，故只传裸字段名会得到<b>升序</b>（见 {@code BUG-001}）。
     */
    private static final String DEFAULT_SORT = DEFAULT_SORT_FIELD + ",desc";

    private final SpotService spotService;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     */
    public SpotExposureHandler(SpotService spotService) {
        this.spotService = spotService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if (PATH_RECALC.equals(ctx.path())) {
            RecalcRequest request = JsonCodec.fromJson(ctx.body(), RecalcRequest.class);
            if (request == null || request.exposureId == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "重算须提交 {exposureId}：复现=按 §4.1.1 提交");
            }
            Map<String, Object> result = spotService.recalcForDisplay(request.exposureId,
                    ctx.currentUserId());
            return HttpResult.json(200, ApiResponse.ok(result, ctx.traceId()));
        }
        if ("GET".equalsIgnoreCase(ctx.method())) {
            String id = ctx.pathParam("id");
            if (id != null && !id.isEmpty()) {
                return HttpResult.json(200, ApiResponse.ok(ExposureView.of(
                        spotService.getExposure(parseId(id), ctx.currentUserId())), ctx.traceId()));
            }
            return list(ctx);
        }
        ComputeRequest request = JsonCodec.fromJson(ctx.body(), ComputeRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交敞口计算入参（subAccountId/spotCategoryCode/direction/calcMode/asOfDate/"
                            + "三分量/exposureQty/unit）：复现=按 §4.1.1 提交");
        }
        Exposure exposure = new Exposure();
        exposure.subAccountId = request.subAccountId;
        exposure.productId = request.productId == null ? SpotService.UNMAPPED_PRODUCT_SENTINEL
                : request.productId;
        exposure.spotCategoryCode = request.spotCategoryCode;
        exposure.direction = request.direction;
        exposure.calcMode = request.calcMode;
        exposure.contractUnexecutedQty = SpotContractHandler.decimal(
                request.contractUnexecutedQty == null ? "0" : request.contractUnexecutedQty,
                "contractUnexecutedQty");
        exposure.inventoryQty = SpotContractHandler.decimal(
                request.inventoryQty == null ? "0" : request.inventoryQty, "inventoryQty");
        exposure.productionPlanQty = SpotContractHandler.decimal(
                request.productionPlanQty == null ? "0" : request.productionPlanQty,
                "productionPlanQty");
        exposure.componentsEnabled = request.componentsEnabled;
        exposure.combinedComponents = request.combinedComponents;
        exposure.asOfDate = SpotContractHandler.date(request.asOfDate, "asOfDate");
        exposure.exposureQty = SpotContractHandler.decimal(request.exposureQty, "exposureQty");
        exposure.unit = request.unit;
        exposure.unmappedFlag = request.unmappedFlag == null ? "N" : request.unmappedFlag;
        exposure.coveredQty = java.math.BigDecimal.ZERO;

        List<ExposureDetail> details = new ArrayList<>();
        if (request.details != null) {
            for (DetailRequest item : request.details) {
                ExposureDetail detail = new ExposureDetail();
                detail.componentType = item.componentType;
                detail.sourceType = item.sourceType;
                detail.sourceId = item.sourceId == null ? 0L : item.sourceId;
                detail.sourceNo = item.sourceNo;
                detail.contributedQty = SpotContractHandler.decimal(item.contributedQty,
                        "contributedQty");
                detail.unit = item.unit == null ? exposure.unit : item.unit;
                detail.direction = exposure.direction;
                detail.calcAsOfDate = exposure.asOfDate;
                details.add(detail);
            }
        }
        Exposure stored = spotService.computeExposure(exposure, details, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(ExposureView.of(stored), ctx.traceId()));
    }

    /**
     * 敞口列表（{@code GET /spot/exposure}，{@code FR-SPOT-15}）：分页 + 排序 + 子账户隔离。
     *
     * <p>分页 / 排序按 {@code §4.1.1}（{@code page/size/sort}；{@code size ∈ {10,20,50,100}}，默认 20），
     * 缺省排序 {@code exposure_qty} <b>倒序</b>（{@code FR-SPOT-15}；{@code PageQuery} 缺省方向恒
     * {@code ASC}，故缺省时须显式补 {@code ,desc}，见 {@code BUG-001}）。响应 {@code data} 为
     * {@code {items,total,page,size}} 分页信封（{@code §4.1.1} 冻结）。隔离身份取自会话
     * （{@code ctx.currentUserId()}），<b>不可</b>由 {@code subAccountId} 查询参数伪造——该参数仅作
     * <b>收窄过滤</b>（与服务端可见集取交集），省略即返回全部可见子账户敞口。
     */
    private HttpResult list(RequestContext ctx) {
        String sort = ctx.queryParam("sort");
        if (sort == null || sort.isEmpty()) {
            sort = DEFAULT_SORT;
        }
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"), sort,
                DEFAULT_SORT_FIELD);
        Integer subAccountId = optionalSubAccountId(ctx.queryParam("subAccountId"));
        PageResult<Exposure> page =
                spotService.listPendingHedge(ctx.currentUserId(), query, subAccountId);
        List<ExposureView> items = new ArrayList<>();
        for (Exposure exposure : page.items()) {
            items.add(ExposureView.of(exposure));
        }
        PageData<ExposureView> data = new PageData<>(items, page.total(), page.page(), page.size());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    /** 解析可选 {@code subAccountId} 查询参数（空 ⇒ {@code null}；非整数 ⇒ 10001）。 */
    private static Integer optionalSubAccountId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "subAccountId 须为十进制整数：" + raw + "；复现=补 ?subAccountId=<子账户ID> 或省略该参数", e);
        }
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "敞口 ID 须为十进制整数：" + raw + "；复现=核对路径参数 {id}", e);
        }
    }

    /** 敞口计算请求体。 */
    public static final class ComputeRequest {

        /** 子账户。 */
        public int subAccountId;

        /** 品种（未映射行必须为 {@code 0}）。 */
        public Long productId;

        /** 现货品类编码（恒必填；已映射行由服务归一为占位常量）。 */
        public String spotCategoryCode;

        /** 方向（{@code BUY} / {@code SELL}）。 */
        public String direction;

        /** 口径（{@code COMBINED} / {@code CONTRACT_UNEXECUTED} / {@code INVENTORY_HELD} / {@code PRODUCTION_PLAN}）。 */
        public String calcMode;

        /** 分量①已签合同未执行量。 */
        public String contractUnexecutedQty;

        /** 分量②库存持有量。 */
        public String inventoryQty;

        /** 分量③生产计划需求量。 */
        public String productionPlanQty;

        /** 启用的分量集合。 */
        public String componentsEnabled;

        /** 组合口径构成（{@code COMBINED} 时必填）。 */
        public String combinedComponents;

        /** 计算基准日（yyyy-MM-dd）。 */
        public String asOfDate;

        /** 敞口数量（= Σ三分量，否则拒写）。 */
        public String exposureQty;

        /** 单位。 */
        public String unit;

        /** 是否未映射（{@code Y} 时 productId 必须为 0）。 */
        public String unmappedFlag;

        /** 构成明细（可选；提供时逐分量之和必须等于分量列）。 */
        public List<DetailRequest> details;
    }

    /** 构成明细请求体。 */
    public static final class DetailRequest {

        /** 分量（{@code CONTRACT_UNEXECUTED} / {@code INVENTORY} / {@code PRODUCTION_PLAN}）。 */
        public String componentType;

        /** 来源单据类型。 */
        public String sourceType;

        /** 来源单据 ID。 */
        public Long sourceId;

        /** 来源单据编号（冗余）。 */
        public String sourceNo;

        /** 贡献量（字符串化十进制）。 */
        public String contributedQty;

        /** 单位（可空则随敞口头）。 */
        public String unit;
    }

    /** 重算请求体。 */
    public static final class RecalcRequest {

        /** 敞口 ID。 */
        public Long exposureId;
    }

    /** 敞口视图。 */
    public static final class ExposureView {

        /** 主键。 */
        public long id;

        /** 快照标识（下发与审计复现的引用键）。 */
        public String snapshotId;

        /** 子账户。 */
        public int subAccountId;

        /** 品种（未映射为 0）。 */
        public long productId;

        /** 现货品类（已映射为占位常量 {@code __MAPPED__}）。 */
        public String spotCategoryCode;

        /** 方向。 */
        public String direction;

        /** 口径。 */
        public String calcMode;

        /** 分量①。 */
        public String contractUnexecutedQty;

        /** 分量②。 */
        public String inventoryQty;

        /** 分量③。 */
        public String productionPlanQty;

        /** 合计。 */
        public String exposureQty;

        /** 单位。 */
        public String unit;

        /** 敞口金额（缺价时为 {@code null}）。 */
        public String exposureAmount;

        /** 是否缺价。 */
        public String priceMissingFlag;

        /** 实际价时点（{@code price_as_of}）。 */
        public String priceAsOf;

        /** 是否非当日价。 */
        public String nonCurrentPriceFlag;

        /** 已被引用数量。 */
        public String coveredQty;

        /** 状态。 */
        public String status;

        static ExposureView of(Exposure exposure) {
            if (exposure == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "敞口不存在；复现=按 snapshot_id 查询确认 ID");
            }
            ExposureView view = new ExposureView();
            view.id = exposure.id;
            view.snapshotId = exposure.snapshotId;
            view.subAccountId = exposure.subAccountId == null ? 0 : exposure.subAccountId;
            view.productId = exposure.productId == null ? 0L : exposure.productId;
            view.spotCategoryCode = exposure.spotCategoryCode;
            view.direction = exposure.direction;
            view.calcMode = exposure.calcMode;
            view.contractUnexecutedQty = plain(exposure.contractUnexecutedQty);
            view.inventoryQty = plain(exposure.inventoryQty);
            view.productionPlanQty = plain(exposure.productionPlanQty);
            view.exposureQty = plain(exposure.exposureQty);
            view.unit = exposure.unit;
            view.exposureAmount = exposure.exposureAmount == null ? null
                    : exposure.exposureAmount.toPlainString();
            view.priceMissingFlag = exposure.priceMissingFlag;
            view.priceAsOf = exposure.priceAsOf == null ? null : exposure.priceAsOf.toString();
            view.nonCurrentPriceFlag = exposure.nonCurrentPriceFlag;
            view.coveredQty = plain(exposure.coveredQty);
            view.status = exposure.status;
            return view;
        }

        private static String plain(java.math.BigDecimal value) {
            return value == null ? "0" : value.toPlainString();
        }
    }
}
