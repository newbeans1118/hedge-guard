package com.hedgeguard.risk_intraday;

import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * {@code GET /api/v1/risk/mirror-status} 处理函数（{@code ARCHITECTURE.md} §3.11 / §4.1.1）。
 *
 * <p>只读端点：返回最近一次内存评估的镜像状态（含 6 类指标、告警、强制平仓提示与受限子账户），
 * 不触发任何处置、不查库、不改 C++ 内存（{@code R-RISK-10}）。
 * 子账户隔离经会话身份（{@code ctx.currentUserId()}），不由查询参数伪造（{@code NFR-SEC-7}）。
 */
public final class MirrorStatusHandler implements RouteHandler {

    /** 路径（逐字取自 {@code ApiRoutes} RISK 清单）。 */
    public static final String PATH = ApiRoutes.full("/risk/mirror-status");

    private final MirrorStatusService service;

    /**
     * 构造。
     *
     * @param service 读取服务
     */
    public MirrorStatusHandler(MirrorStatusService service) {
        this.service = service;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        MirrorStatusView view = service.current(ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(view, ctx.traceId()));
    }
}
