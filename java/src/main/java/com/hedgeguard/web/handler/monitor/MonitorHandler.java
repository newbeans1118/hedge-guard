package com.hedgeguard.web.handler.monitor;

import com.hedgeguard.bridge.ComponentStatus;
import com.hedgeguard.monitor.ComponentMonitor;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code GET /api/v1/monitor/component} 处理函数（阶段 1 监控面）。
 *
 * <p><b>默认不查 C++</b>：无 {@code refresh} 参数时只返回 {@code ComponentMonitor} 的
 * 内存快照（{@code [事件]/[链路]} 观测 + 最近一次核验结果），语义与
 * {@code GET /risk/mirror-status} 同族（{@code R-RISK-10}）。
 *
 * <p>仅当显式 {@code ?refresh=1}（或 {@code =true}）时才发一帧
 * {@code CMD_QUERY_STATUS} 主动核验，且经 {@code ComponentMonitor} 单飞 + 有界等待；
 * 超时/中断返回带 {@code refreshPending=true} 的缓存快照，<b>不抛 5xx、不假装成功</b>。
 *
 * <p>本端点<b>不在</b>公开白名单，需会话 Cookie；响应字段均为 camelCase，
 * 与 {@code ComponentStatus} 逐字对应。
 */
public final class MonitorHandler implements RouteHandler {

    /** 路径（逐字取自 {@link ApiRoutes} MONITOR 清单）。 */
    public static final String PATH = ApiRoutes.full("/monitor/component");

    /** 等待 refresh 回执的有界上界（{@code sendCommand} 自身的超时更早兜底）。 */
    private static final long REFRESH_WAIT_MS = 10_000L;

    private final ComponentMonitor monitor;

    /**
     * @param monitor 监控服务（测试装配可传 {@code null}）
     */
    public MonitorHandler(ComponentMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if (monitor == null) {
            return HttpResult.json(200,
                    ApiResponse.ok(ComponentStatus.empty(), ctx.traceId()));
        }
        String refresh = ctx.queryParam("refresh");
        if (refresh == null || !("1".equals(refresh) || "true".equalsIgnoreCase(refresh))) {
            return HttpResult.json(200, ApiResponse.ok(monitor.snapshot(), ctx.traceId()));
        }
        try {
            ComponentStatus status = monitor.refresh()
                    .get(REFRESH_WAIT_MS, TimeUnit.MILLISECONDS);
            return HttpResult.json(200, ApiResponse.ok(status, ctx.traceId()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpResult.json(200,
                    ApiResponse.ok(monitor.snapshotPending(), ctx.traceId()));
        } catch (ExecutionException | TimeoutException e) {
            return HttpResult.json(200,
                    ApiResponse.ok(monitor.snapshotPending(), ctx.traceId()));
        }
    }
}
