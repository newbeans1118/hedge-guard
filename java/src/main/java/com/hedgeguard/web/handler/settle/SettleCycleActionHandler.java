package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算单动作 {@code POST /api/v1/settle/cycle/{id}/reconcile|aggregate|close}
 * （{@code T-047}；{@code FR-SETTLE-5/6/7}、{@code FR-SETTLE-8}、{@code R-SETTLE-5}）。
 */
public final class SettleCycleActionHandler implements RouteHandler {

    /** 可执行动作。 */
    public enum Action {
        /** 三类核对（成交/仓位/资金）。 */
        RECONCILE("reconcile"),
        /** 加总对账。 */
        AGGREGATE("aggregate"),
        /** 关账。 */
        CLOSE("close");

        private final String segment;

        Action(String segment) {
            this.segment = segment;
        }

        /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
        public String path() {
            return "/api/v1/settle/cycle/{id}/" + segment;
        }
    }

    private final SettleService settleService;
    private final Action action;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     * @param action        动作
     */
    public SettleCycleActionHandler(SettleService settleService, Action action) {
        this.settleService = settleService;
        this.action = action;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long cycleId = parseId(ctx.pathParam("id"));
        long operatorId = ctx.currentUserId();
        Map<String, Object> data = new LinkedHashMap<>();
        if (action == Action.RECONCILE) {
            SettleService.ReconcileRequest body = JsonCodec.fromJson(
                    ctx.body() == null || ctx.body().trim().isEmpty() ? "{}" : ctx.body(),
                    SettleService.ReconcileRequest.class);
            SettleService.ReconcileResult r = settleService.reconcile(cycleId,
                    body == null ? null : body.systemTrades, operatorId);
            data.put("trade", line(r.trade));
            data.put("position", line(r.position));
            data.put("fund", line(r.fund));
        } else if (action == Action.AGGREGATE) {
            SettleService.AggregateResult r = settleService.aggregate(cycleId, operatorId);
            data.put("consistent", r.consistent);
            data.put("blockedSubAccountIds", new ArrayList<>(r.blockedSubAccountIds));
            List<Map<String, Object>> checks = new ArrayList<>();
            for (com.hedgeguard.dao.entity.SettleAggregationCheck c : r.checks) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.id);
                m.put("checkType", c.checkType);
                m.put("sumSubAccount", c.sumSubAccount == null ? "0" : c.sumSubAccount.toPlainString());
                m.put("realAccountValue", c.realAccountValue == null ? "0" : c.realAccountValue.toPlainString());
                m.put("difference", c.difference == null ? "0" : c.difference.toPlainString());
                m.put("result", c.result);
                m.put("blockScope", c.blockScope);
                m.put("blockEffectiveTime", c.blockEffectiveTime == null ? null : c.blockEffectiveTime.toString());
                checks.add(m);
            }
            data.put("checks", checks);
        } else {
            SettleService.CloseResult r = settleService.closeCycle(cycleId, operatorId);
            data.put("settleNo", r.settleNo);
            data.put("confirmed", r.confirmed);
        }
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    private static Map<String, Object> line(SettleService.ReconcileLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (l == null) {
            return m;
        }
        m.put("type", l.type);
        m.put("result", l.result);
        m.put("differenceCount", l.differenceCount);
        m.put("reconcileRecordId", l.reconcileRecordId);
        return m;
    }

    private static long parseId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "id 不得为空");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "id 须为整数：" + raw);
        }
    }
}
