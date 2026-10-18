package com.hedgeguard.web.handler.acct;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.Cas24Selfcheck;
import com.hedgeguard.service.Cas24SelfcheckService;
import com.hedgeguard.web.ApiResponse;
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
 * CAS 24 条款自检 {@code /api/v1/acct/selfcheck}（{@code FR-ACCT-14}；{@code T-197}）。
 *
 * <p><b>POST</b>（发起自检，落批次）：请求体 {@code {scopeType, hedgeRelationId?, periodKey}}——
 * scopeType=HEDGE_RELATION 单关系五条逐条判定（验收 1/2）；scopeType=PERIOD 按期间聚合
 * （可见范围内有评估记录的关系，全账户批次）。缺再平衡记录（已触发未生成）⇒ 该项「不通过」
 * （验收 2）；关键数据缺失 ⇒ 批次 {@code CANNOT_SELFCHECK} + 原因。批次恒带「待外部复核」
 * 标注 + 「自检不替代外部复核」显式说明（R-ACCT-10，验收 3）。幂等：同 scope 同业务日
 * 重复发起返回既有批次，不重复落批。
 *
 * <p><b>GET</b>（查询清单/报告）：{@code selfcheckNo} 命中 ⇒ 单批次详情（含逐条结论 + 证据
 * 位置，验收 1）；否则分页清单（PageData 信封；筛选 scopeType/subAccountId/conclusion；
 * 缺省 {@code selfcheck_time desc}）。子账户可见性由服务层 fail-closed 强制（NFR-SEC-7）。
 */
public final class SelfcheckHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/selfcheck";

    private final Cas24SelfcheckService selfcheckService;

    /**
     * 构造。
     *
     * @param selfcheckService CAS 24 自检服务
     */
    public SelfcheckHandler(Cas24SelfcheckService selfcheckService) {
        this.selfcheckService = selfcheckService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return get(ctx);
        }
        // 参数级负例（缺 scopeType / 非法期间）在本 handler 直返 400 —— 服务层 BizException
        // 只有经 RequestDispatcher.dispatch 才映射状态码；handler 级直调（本类既有测试形态）
        // 不经 dispatcher ⇒ 须在此前置校验（与 body 判空同层，ExportHandler 400 范式同构）。
        try {
            return post(ctx);
        } catch (BizException e) {
            if (e.errorCode() == ErrorCode.PARAM_INVALID) {
                return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                        e.getMessage(), ctx.traceId()));
            }
            throw e;
        }
    }

    private HttpResult post(RequestContext ctx) {
        Cas24SelfcheckService.SelfcheckRequest in = JsonCodec.fromJson(
                AcctParams.body(ctx), Cas24SelfcheckService.SelfcheckRequest.class);
        if (in == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "请求体不得为空（scopeType/periodKey 必填）；复现=补齐请求体后重试",
                    ctx.traceId()));
        }
        Cas24Selfcheck sc = selfcheckService.runSelfcheck(in, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(
                selfcheckService.selfcheckView(sc, null), ctx.traceId()));
    }

    private HttpResult get(RequestContext ctx) {
        String selfcheckNo = AcctParams.text(ctx.queryParam("selfcheckNo"));
        if (selfcheckNo != null) {
            Cas24Selfcheck sc = selfcheckService.findByNo(selfcheckNo, ctx.currentUserId());
            return HttpResult.json(200, ApiResponse.ok(
                    selfcheckService.selfcheckView(sc, null), ctx.traceId()));
        }
        Integer subAccountId = AcctParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId");
        PageQuery query = PageQuery.of(AcctParams.text(ctx.queryParam("page")),
                AcctParams.text(ctx.queryParam("size")), AcctParams.text(ctx.queryParam("sort")),
                "selfcheck_time", "desc");
        PageResult<Cas24Selfcheck> page = selfcheckService.listBatches(query,
                AcctParams.text(ctx.queryParam("scopeType")), subAccountId,
                AcctParams.text(ctx.queryParam("conclusion")), ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Cas24Selfcheck sc : page.items()) {
            items.add(selfcheckService.selfcheckView(sc, null));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }
}
