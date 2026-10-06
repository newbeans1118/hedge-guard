package com.hedgeguard.web.handler.risk;

import com.hedgeguard.service.RiskService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.List;

/**
 * 风控规则 / 阈值 {@code GET|POST /api/v1/risk/rule}（{@code T-063}；{@code FR-CONFIG-5} 验收 1）。
 *
 * <p>{@code GET} 列出 16 项事前风控阈值（配置键 / 当前值 / 默认值 / 合规红线 / 下发状态），
 * 即 {@code FR-CONFIG-5} 验收 1「风控阈值集中在一个配置界面可维护」与本单验收 4
 * 「风控规则清单/阈值可在界面查看」。
 *
 * <p>{@code POST} 变更一项阈值并下发（{@code FR-CONFIG-6}）；放宽合规红线须带 {@code reviewerId}，
 * 否则 {@code 10012}（{@code R-CONFIG-8}）。方法集 {@code GET/POST} 逐字取自
 * {@code ARCHITECTURE.md} §4.1.1 的 {@code RISK} 行。
 */
public final class RiskRuleHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/risk/rule");

    private final RiskService riskService;

    /**
     * 构造。
     *
     * @param riskService 风控阈值服务
     */
    public RiskRuleHandler(RiskService riskService) {
        this.riskService = riskService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            List<RiskService.ThresholdView> views = riskService.listThresholds();
            return HttpResult.json(200, ApiResponse.ok(views, ctx.traceId()));
        }
        ChangeRequest request = JsonCodec.fromJson(ctx.body(), ChangeRequest.class);
        if (request == null || request.thresholdKey == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "变更须提交 {thresholdKey, newValue, reviewerId?, reason}：复现=按 §4.1.1 提交");
        }
        RiskService.ThresholdView updated = riskService.updateThreshold(request.thresholdKey,
                request.newValue, ctx.currentUserId(), request.reviewerId, request.reason);
        return HttpResult.json(200, ApiResponse.ok(updated, ctx.traceId()));
    }

    /** 阈值变更请求体。 */
    public static final class ChangeRequest {

        /** 配置键（{@code ARCHITECTURE.md} §11.1）。 */
        public String thresholdKey;

        /** 新取值。 */
        public String newValue;

        /** 复核人（放宽合规红线时必填且不得等于操作人）。 */
        public Long reviewerId;

        /** 变更原因。 */
        public String reason;
    }
}
