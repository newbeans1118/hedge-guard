package com.hedgeguard.web.handler.spot;

import com.hedgeguard.dao.entity.ProductionPlan;
import com.hedgeguard.dao.entity.ProductionPlanLine;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生产计划 {@code POST /api/v1/spot/production}（{@code T-109}；{@code FR-SPOT-8}）。
 *
 * <p>录入生产计划头 + 行（产成品 / 所需原料）；计划须归属子账户并映射到品种；计划期不得为空；
 * 计划期与既有计划重叠时<b>提示重复但不阻断</b>（{@code overlapFlag='Y'}）。产成品行导出 {@code SELL}、
 * 原料行导出 {@code BUY}（敞口由 {@code /spot/exposure} 计算）。
 */
public final class ProductionPlanHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/spot/production");

    private final SpotService spotService;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     */
    public ProductionPlanHandler(SpotService spotService) {
        this.spotService = spotService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.lines == null || request.lines.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {subAccountId, planNo, planPeriodStart, planPeriodEnd, lines:[...]}："
                            + "复现=按 FR-SPOT-8 提交");
        }
        ProductionPlan plan = new ProductionPlan();
        plan.subAccountId = request.subAccountId;
        plan.planNo = request.planNo;
        plan.planPeriodStart = date(request.planPeriodStart, "planPeriodStart");
        plan.planPeriodEnd = date(request.planPeriodEnd, "planPeriodEnd");
        plan.status = request.status;
        List<ProductionPlanLine> lines = new ArrayList<>();
        for (Line line : request.lines) {
            ProductionPlanLine row = new ProductionPlanLine();
            row.lineType = line.lineType;
            row.productId = line.productId;
            row.quantity = decimal(line.quantity, "quantity");
            row.unit = line.unit;
            lines.add(row);
        }
        Map<String, Object> result = spotService.createProductionPlan(plan, lines, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(result, ctx.traceId()));
    }

    private static LocalDate date(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段必填：" + field + "（yyyy-MM-dd）；复现=补齐日期");
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段须为 yyyy-MM-dd：" + field + "=" + raw + "；复现=改正日期格式", e);
        }
    }

    private static BigDecimal decimal(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段必填：" + field + "（字符串化十进制）；复现=补齐后重试");
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段须为字符串化十进制：" + field + "=" + raw + "；复现=改正取值", e);
        }
    }

    /** 生产计划请求体。 */
    public static final class Request {

        /** 子账户（必填）。 */
        public Integer subAccountId;

        /** 生产计划编号（同一子账户内唯一）。 */
        public String planNo;

        /** 计划期起。 */
        public String planPeriodStart;

        /** 计划期止。 */
        public String planPeriodEnd;

        /** 状态（缺省 {@code SCHEDULED}）。 */
        public String status;

        /** 计划行。 */
        public List<Line> lines;
    }

    /** 计划行入参。 */
    public static final class Line {

        /** 行类型（{@code FINISHED} / {@code RAW_MATERIAL}）。 */
        public String lineType;

        /** 品种（须已映射，&gt; 0）。 */
        public Long productId;

        /** 数量（字符串化十进制，&gt; 0）。 */
        public String quantity;

        /** 单位。 */
        public String unit;
    }
}
