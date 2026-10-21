package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.TransferApply;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 划拨集合 {@code GET /api/v1/fund/transfer}（{@code FR-FUND-16}，成对查询见 {@code /fund/flow}）与
 * {@code POST /api/v1/fund/transfer}（发起划拨，{@code FR-FUND-10}）。
 */
public final class FundTransferHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/transfer";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundTransferHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return list(ctx);
        }
        FundService.TransferRequest in = JsonCodec.fromJson(
                FundParams.body(ctx), FundService.TransferRequest.class);
        if (in == null) {
            in = new FundService.TransferRequest();
        }
        if (in.idempotencyKey == null) {
            in.idempotencyKey = ctx.idempotencyKey(); // Idempotency-Key 头兜底（§4.1.1；先例 FundApplyHandler）
        }
        TransferApply t = fundService.initiateTransfer(in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view(t), ctx.traceId()));
    }

    private HttpResult list(RequestContext ctx) {
        PageQuery q = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "id", "desc");
        PageResult<TransferApply> page = fundService.listTransfers(q,
                FundParams.text(ctx.queryParam("status")),
                FundParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (TransferApply t : page.items()) {
            items.add(view(t));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    static Map<String, Object> view(TransferApply t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("applyNo", t.applyNo);
        m.put("fromSubAccountId", t.fromSubAccountId);
        m.put("toSubAccountId", t.toSubAccountId);
        m.put("amount", t.amount == null ? null : t.amount.toPlainString());
        m.put("reason", t.reason);
        m.put("status", t.status);
        m.put("applicantId", t.applicantId);
        m.put("approverId", t.approverId);
        m.put("approveTime", t.approveTime == null ? null : t.approveTime.toString());
        m.put("aggCheckPassed", t.aggCheckPassed);
        m.put("rollbackTime", t.rollbackTime == null ? null : t.rollbackTime.toString());
        m.put("postingTime", t.postingTime == null ? null : t.postingTime.toString());
        return m;
    }
}
