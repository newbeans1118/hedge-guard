package com.hedgeguard.web.handler.spot;

import com.hedgeguard.dao.entity.InventoryLedger;
import com.hedgeguard.dao.entity.WipRecord;
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

/**
 * 库存台账与在制品 {@code POST /api/v1/spot/inventory}、{@code POST /api/v1/spot/wip}（{@code T-013}）。
 *
 * <p>两条结构约束在服务层显式判定（数据库侧另有 {@code CHECK} 兜底）：
 * <ul>
 *   <li>库存为负值必须二次确认（{@code negativeConfirmed='Y'}），否则 {@code 10001}
 *       （{@code FR-SPOT-9} 验收 2）；</li>
 *   <li>在制品完工比例必须落在 {@code [0,1]}（{@code R-SPOT-4}）。</li>
 * </ul>
 */
public final class SpotStockHandler implements RouteHandler {

    /** 库存路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH_INVENTORY = ApiRoutes.full("/spot/inventory");

    /** 在制品路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH_WIP = ApiRoutes.full("/spot/wip");

    private final SpotService spotService;
    private final boolean inventory;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     * @param inventory   {@code true} = 库存台账，{@code false} = 在制品
     */
    public SpotStockHandler(SpotService spotService, boolean inventory) {
        this.spotService = spotService;
        this.inventory = inventory;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        StockRequest request = JsonCodec.fromJson(ctx.body(), StockRequest.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 JSON 体（subAccountId/productId/数量/单位/记录日期）：复现=按 §4.1.1 提交");
        }
        long operatorId = ctx.currentUserId();
        if (inventory) {
            InventoryLedger ledger = new InventoryLedger();
            ledger.subAccountId = request.subAccountId;
            ledger.productId = request.productId;
            ledger.inventoryType = request.inventoryType;
            ledger.quantity = SpotContractHandler.decimal(request.quantity, "quantity");
            ledger.negativeConfirmed = request.negativeConfirmed == null ? "N"
                    : request.negativeConfirmed;
            ledger.unit = request.unit;
            ledger.valuation = request.valuation == null ? null
                    : SpotContractHandler.decimal(request.valuation, "valuation");
            ledger.recordDate = SpotContractHandler.date(request.recordDate, "recordDate");
            ledger.exposureDirection = request.exposureDirection;
            ledger.remark = request.remark;
            return HttpResult.json(200, ApiResponse.ok(
                    spotService.createInventory(ledger, operatorId), ctx.traceId()));
        }
        WipRecord record = new WipRecord();
        record.subAccountId = request.subAccountId;
        record.productId = request.productId;
        record.productionPlanId = request.productionPlanId;
        record.wipQty = SpotContractHandler.decimal(request.quantity, "quantity");
        record.completionRatio = SpotContractHandler.decimal(request.completionRatio,
                "completionRatio");
        record.exposureDirection = request.exposureDirection;
        record.recordDate = SpotContractHandler.date(request.recordDate, "recordDate");
        record.unit = request.unit;
        return HttpResult.json(200, ApiResponse.ok(spotService.createWip(record, operatorId),
                ctx.traceId()));
    }

    /** 库存/在制品请求体。 */
    public static final class StockRequest {

        /** 子账户（真实子账户，&gt; 0）。 */
        public int subAccountId;

        /** 品种。 */
        public long productId;

        /** 库存类型（{@code RAW_MATERIAL} / {@code FINISHED} / {@code WIP}；仅库存用）。 */
        public String inventoryType;

        /** 数量（字符串化十进制；库存允许负值但须确认；在制品须 ≥ 0）。 */
        public String quantity;

        /** 负值是否已二次确认（仅库存用）。 */
        public String negativeConfirmed;

        /** 完工比例（0–1；仅在制品用）。 */
        public String completionRatio;

        /** 关联生产计划（可空；仅在制品用）。 */
        public Long productionPlanId;

        /** 单位。 */
        public String unit;

        /** 计价单价（可空；仅库存用）。 */
        public String valuation;

        /** 记录日期（yyyy-MM-dd）。 */
        public String recordDate;

        /** 推导方向（{@code BUY} / {@code SELL}）。 */
        public String exposureDirection;

        /** 备注。 */
        public String remark;
    }

    /** 便捷：把字符串化十进制转 {@link BigDecimal}（校验失败抛 {@code 10001}）。 */
    static BigDecimal requireDecimal(String raw, String field) {
        return SpotContractHandler.decimal(raw, field);
    }
}
