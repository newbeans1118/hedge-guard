package com.hedgeguard.web.handler;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.Duration;

/**
 * 存活探针 {@code GET /api/v1/ping}（公开路径，见 {@code SessionAuthFilter}）。
 *
 * <p>返回 {@code {"code":0,"message":"OK","data":{...},"traceId":"..."}}，
 * <b>不</b>返回任何业务数据，故可安全地对未登录请求开放（运维与健康检查用）。
 *
 * <p>不返回构建信息以外的任何配置内容（避免泄露内部拓扑）。
 */
public final class PingHandler implements RouteHandler {

    private final long startMillis;

    /** 构造（记录进程启动时刻，用于计算运行时长）。 */
    public PingHandler() {
        this.startMillis = Clocks.epochMilli();
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        PingInfo info = new PingInfo();
        info.service = "hg-biz";
        info.status = "UP";
        info.time = Clocks.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        info.uptimeSeconds = Duration.ofMillis(Clocks.epochMilli() - startMillis).getSeconds();
        info.apiBase = ApiRoutes.BASE;
        return HttpResult.json(200, ApiResponse.ok(info, ctx.traceId()));
    }

    /** 探针响应体（{@code data} 的内容）。 */
    public static final class PingInfo {

        /** 服务名。 */
        public String service;

        /** 状态。 */
        public String status;

        /** 服务端时间（ISO-8601，{@code Asia/Shanghai}）。 */
        public String time;

        /** 运行秒数。 */
        public long uptimeSeconds;

        /** REST 基础路径。 */
        public String apiBase;
    }
}
