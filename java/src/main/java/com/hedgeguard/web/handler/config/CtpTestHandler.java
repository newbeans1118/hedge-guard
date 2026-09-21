package com.hedgeguard.web.handler.config;

import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.Map;

/**
 * CTP 连接测试 {@code POST /api/v1/config/ctp/test}（{@code T-109}；{@code FR-CONFIG-1} 验收 1）。
 *
 * <p><b>M1 口径（显式披露，非静默）</b>：连通性测试须经 C++ 交易组件（{@code ctp_adapter}）+
 * HGCP 桥接（{@code T-042}，M2）方能执行。本端点始终<b>已注册</b>（非 404/405）；M1 调用时先做
 * 可离线的前置校验（该环境下是否有启用中的交易前置），随即以业务码 {@code 10002}
 * （交易组件通信不可用）<b>明确失败</b>并给出下一步——<b>不</b>伪报成功。M2 起随桥接返回真实结果。
 */
public final class CtpTestHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/ctp/test");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public CtpTestHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.envType == null || request.envType.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {envType}（SIMULATION/PRODUCTION）：复现=按 FR-CONFIG-1 提交");
        }
        Map<String, Object> result = configService.testCtpConnection(request.envType);
        return HttpResult.json(200, ApiResponse.ok(result, ctx.traceId()));
    }

    /** 测试请求体。 */
    public static final class Request {

        /** 环境类型（{@code SIMULATION} / {@code PRODUCTION}）。 */
        public String envType;
    }
}
