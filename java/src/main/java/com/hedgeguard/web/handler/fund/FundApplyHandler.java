package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出入金申请 {@code POST /api/v1/fund/deposit} 与 {@code POST /api/v1/fund/withdraw}
 * （{@code FR-FUND-5} / {@code FR-FUND-6}）。
 */
public final class FundApplyHandler implements RouteHandler {

    /** 可发起的出入金动作。 */
    public enum Action {
        /** 入金。 */
        DEPOSIT("deposit", FundService.DIR_IN),
        /** 出金。 */
        WITHDRAW("withdraw", FundService.DIR_OUT);

        private final String segment;
        private final String direction;

        Action(String segment, String direction) {
            this.segment = segment;
            this.direction = direction;
        }

        /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
        public String path() {
            return "/api/v1/fund/" + segment;
        }
    }

    private final FundService fundService;
    private final Action action;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     * @param action      动作
     */
    public FundApplyHandler(FundService fundService, Action action) {
        this.fundService = fundService;
        this.action = action;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        FundService.DepositWithdrawRequest in = JsonCodec.fromJson(
                FundParams.body(ctx), FundService.DepositWithdrawRequest.class);
        if (in == null) {
            in = new FundService.DepositWithdrawRequest();
        }
        in.direction = action.direction;
        if (in.idempotencyKey == null) {
            in.idempotencyKey = ctx.idempotencyKey();
        }
        DepositWithdrawApply a = fundService.initiateDepositWithdraw(in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view(a), ctx.traceId()));
    }

    static Map<String, Object> view(DepositWithdrawApply a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("subAccountId", a.subAccountId);
        m.put("applyNo", a.applyNo);
        m.put("direction", a.direction);
        m.put("amount", a.amount == null ? null : a.amount.toPlainString());
        m.put("status", a.status);
        m.put("applicantId", a.applicantId);
        m.put("reviewerId", a.reviewerId);
        m.put("reviewTime", a.reviewTime == null ? null : a.reviewTime.toString());
        m.put("submitTime", a.submitTime == null ? null : a.submitTime.toString());
        m.put("availableBefore", a.availableBefore == null ? null : a.availableBefore.toPlainString());
        m.put("retryOfId", a.retryOfId);
        m.put("originalConfirmed", a.originalConfirmed);
        return m;
    }
}
