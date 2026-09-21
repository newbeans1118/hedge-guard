package com.hedgeguard.web.handler.plan;

import com.hedgeguard.dao.entity.HedgeRatioCalc;
import com.hedgeguard.service.PlanService;
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
 * 套保比率三档 {@code POST /api/v1/plan/ratio/{simple|minvar|custom}}（{@code T-015}）。
 *
 * <p>三档的"不得静默降级"口径：
 * <ul>
 *   <li>{@code simple}：结果须 ∈ (0,1]，越界记 {@code RATIO_OUT_OF_RANGE} 且**比率为 0**；</li>
 *   <li>{@code minvar}：样本 &lt; {@value PlanService#MIN_VARIANCE_MIN_SAMPLES} ⇒
 *       {@code INSUFFICIENT_SAMPLE} 且**不返回任何比率**（{@code CFG-06b}）；</li>
 *   <li>{@code custom}：无生效规则 ⇒ {@code RULE_MISSING}；拟用比率超上限 ⇒
 *       {@code EXCEEDS_CUSTOM_CAP}；多套规则叠加**取最严**（{@code Q-6}）。</li>
 * </ul>
 * 每次计算均落 {@code t_hedge_ratio_calc} 留痕（方法 / 输入 / 输出 / 样本数 / 失败原因），可复算
 * （{@code R-PLAN-4}）。
 */
public final class PlanRatioHandler implements RouteHandler {

    /** 比率方法（与路径一一对应）。 */
    public enum Method {
        /** 简单比率法。 */
        SIMPLE("simple", "SIMPLE"),
        /** 最小方差法。 */
        MIN_VARIANCE("minvar", "MIN_VARIANCE"),
        /** 自定义规则法。 */
        CUSTOM_RULE("custom", "CUSTOM_RULE");

        private final String segment;
        private final String methodName;

        Method(String segment, String methodName) {
            this.segment = segment;
            this.methodName = methodName;
        }

        /** 注册路径（逐字取自 {@code §4.1.1}）。 */
        public String path() {
            return ApiRoutes.full("/plan/ratio/" + segment);
        }

        /** 计算留痕的 {@code ratio_method} 取值。 */
        public String methodName() {
            return methodName;
        }
    }

    private final PlanService planService;
    private final Method method;

    /**
     * 构造。
     *
     * @param planService 计划服务
     * @param method      比率方法
     */
    public PlanRatioHandler(PlanService planService, Method method) {
        this.planService = planService;
        this.method = method;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 JSON 体：复现=按 " + method.methodName() + " 所需字段提交（§4.1.1）");
        }
        HedgeRatioCalc calc;
        switch (method) {
            case SIMPLE:
                calc = planService.calcSimple(request.subAccountId, request.exposureId,
                        PlanRoutes.decimal(request.planQty, "planQty"), ctx.currentUserId());
                break;
            case MIN_VARIANCE:
                if (request.spotSeries == null || request.futuresSeries == null) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "最小方差法须提供 spotSeries 与 futuresSeries（等长、按日对齐）："
                                    + "复现=导入 ≥ " + PlanService.MIN_VARIANCE_MIN_SAMPLES + " 个交易日序列");
                }
                calc = planService.calcMinVariance(request.subAccountId, request.exposureId,
                        decimals(request.spotSeries, "spotSeries"),
                        decimals(request.futuresSeries, "futuresSeries"), ctx.currentUserId());
                break;
            default:
                calc = planService.calcCustomRule(request.subAccountId,
                        PlanRoutes.parseId(request.productId, "productId"), request.direction,
                        request.requestedRatio == null ? null
                                : PlanRoutes.decimal(request.requestedRatio, "requestedRatio"),
                        PlanRoutes.date(request.bizDate, "bizDate"), ctx.currentUserId());
                break;
        }
        return HttpResult.json(200, ApiResponse.ok(RatioView.of(calc), ctx.traceId()));
    }

    private static List<BigDecimal> decimals(List<String> raw, String field) {
        List<BigDecimal> out = new ArrayList<>();
        for (String value : raw) {
            out.add(PlanRoutes.decimal(value, field));
        }
        return out;
    }

    /** 比率计算请求体（按方法取用）。 */
    public static final class Request {

        /** 子账户。 */
        public int subAccountId;

        /** 敞口（simple / minvar 必填）。 */
        public long exposureId;

        /** 拟套保数量（simple 必填，字符串化十进制）。 */
        public String planQty;

        /** 现货价序列（minvar 必填，字符串化十进制）。 */
        public List<String> spotSeries;

        /** 期货价序列（minvar 必填，与现货等长）。 */
        public List<String> futuresSeries;

        /** 品种（custom 必填；字符串化整数）。 */
        public String productId;

        /** 方向（custom 必填：{@code BUY} / {@code SELL}）。 */
        public String direction;

        /** 拟用比率（custom 可选；非空时校验是否超上限）。 */
        public String requestedRatio;

        /** 业务日期（custom 必填，{@code yyyy-MM-dd}）。 */
        public String bizDate;
    }

    /** 比率计算响应（含失败原因，失败时 {@code outputRatio = "0"}）。 */
    public static final class RatioView {

        /** 计算留痕 ID。 */
        public long id;

        /** 方法。 */
        public String ratioMethod;

        /** 结果码（{@code SUCCESS} / {@code INSUFFICIENT_SAMPLE} / …）。 */
        public String resultCode;

        /** 输出比率（失败时为 {@code "0"}）。 */
        public String outputRatio;

        /** 相关系数 ρ（最小方差法）。 */
        public String rho;

        /** 现货价标准差 σ_S。 */
        public String sigmaSpot;

        /** 期货价标准差 σ_F。 */
        public String sigmaFutures;

        /** 样本数。 */
        public Integer sampleCount;

        /** 本次实际采用的比例上限（自定义规则法）。 */
        public String appliedRatioCap;

        /** 命中的规则 ID。 */
        public Long appliedRuleId;

        /** 命中规则条数（&gt;1 表示发生叠加）。 */
        public Integer hitRuleCount;

        /** 失败详情（须列明缺失原因，不得静默跳过）。 */
        public String failDetail;

        static RatioView of(HedgeRatioCalc calc) {
            RatioView view = new RatioView();
            view.id = calc.id;
            view.ratioMethod = calc.ratioMethod;
            view.resultCode = calc.resultCode;
            view.outputRatio = calc.outputRatio == null ? "0" : calc.outputRatio.toPlainString();
            view.rho = calc.rho == null ? null : calc.rho.toPlainString();
            view.sigmaSpot = calc.sigmaSpot == null ? null : calc.sigmaSpot.toPlainString();
            view.sigmaFutures = calc.sigmaFutures == null ? null : calc.sigmaFutures.toPlainString();
            view.sampleCount = calc.sampleCount;
            view.appliedRatioCap = calc.appliedRatioCap == null ? null
                    : calc.appliedRatioCap.toPlainString();
            view.appliedRuleId = calc.appliedRuleId;
            view.hitRuleCount = calc.hitRuleCount;
            view.failDetail = calc.failDetail;
            return view;
        }
    }
}
