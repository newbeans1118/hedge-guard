package com.hedgeguard.web.handler.config;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.CtpFrontConfig;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * CTP 前置配置 {@code GET|POST /api/v1/config/ctp}（{@code T-109}；{@code FR-CONFIG-1}/{@code FR-CONFIG-10}）。
 *
 * <p>{@code GET ?envType=}：列出前置配置（{@code SIMULATION}/{@code PRODUCTION} 分离，防误连实盘）。
 * {@code POST}：新增 / 更新一条前置（地址须为 {@code tcp://host:port}；经纪商 / 用户代码必填）。
 *
 * <p><b>零落盘</b>：本资源结构上无凭据列（{@code NFR-SEC-1}），响应天然不含敏感字段。
 */
public final class CtpConfigHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/ctp");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public CtpConfigHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                    ctx.queryParam("sort"), "id");
            PageResult<CtpFrontConfig> page = configService.listCtpFronts(ctx.queryParam("envType"), query);
            List<FrontView> items = new ArrayList<>();
            for (CtpFrontConfig front : page.items()) {
                items.add(FrontView.of(front));
            }
            PageData<FrontView> data = new PageData<>(items, page.total(), page.page(), page.size());
            return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
        }
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {envType, frontType, frontAddr, brokerId, investorId, ...}："
                            + "复现=按 FR-CONFIG-1/10 提交");
        }
        CtpFrontConfig cfg = new CtpFrontConfig();
        cfg.id = request.id;
        cfg.envType = request.envType;
        cfg.frontType = request.frontType;
        cfg.frontAddr = request.frontAddr;
        cfg.priority = request.priority;
        cfg.brokerId = request.brokerId;
        cfg.investorId = request.investorId;
        cfg.connectTimeoutMs = request.connectTimeoutMs;
        cfg.reconnectIntervalMs = request.reconnectIntervalMs;
        cfg.flowPath = request.flowPath;
        cfg.isEnabled = request.isEnabled;
        long id = configService.saveCtpFront(cfg, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 写入请求体（{@code id > 0} 走更新）。 */
    public static final class Request {

        /** 主键（{@code > 0} 更新，否则新增）。 */
        public long id;

        /** 环境类型（{@code SIMULATION} / {@code PRODUCTION}，必填）。 */
        public String envType;

        /** 前置类型（{@code TRADE} / {@code MD}，必填）。 */
        public String frontType;

        /** 前置地址（{@code tcp://host:port}，必填）。 */
        public String frontAddr;

        /** 优先级（0 = 主，&gt;0 = 备份）。 */
        public int priority;

        /** 经纪商代码。 */
        public String brokerId;

        /** 用户代码（资金账号）。 */
        public String investorId;

        /** 连接超时（毫秒）。 */
        public int connectTimeoutMs;

        /** 重连间隔（毫秒）。 */
        public int reconnectIntervalMs;

        /** 流文件目录（非敏感）。 */
        public String flowPath;

        /** 是否启用（{@code Y} / {@code N}）。 */
        public String isEnabled;
    }

    /** 前置视图（仅非敏感列）。 */
    public static final class FrontView {

        /** 主键。 */
        public long id;

        /** 环境类型。 */
        public String envType;

        /** 前置类型。 */
        public String frontType;

        /** 前置地址。 */
        public String frontAddr;

        /** 优先级。 */
        public int priority;

        /** 经纪商代码。 */
        public String brokerId;

        /** 用户代码。 */
        public String investorId;

        /** 连接超时（毫秒）。 */
        public int connectTimeoutMs;

        /** 重连间隔（毫秒）。 */
        public int reconnectIntervalMs;

        /** 流文件目录。 */
        public String flowPath;

        /** 是否启用。 */
        public String isEnabled;

        static FrontView of(CtpFrontConfig front) {
            FrontView view = new FrontView();
            view.id = front.id;
            view.envType = front.envType;
            view.frontType = front.frontType;
            view.frontAddr = front.frontAddr;
            view.priority = front.priority;
            view.brokerId = front.brokerId;
            view.investorId = front.investorId;
            view.connectTimeoutMs = front.connectTimeoutMs;
            view.reconnectIntervalMs = front.reconnectIntervalMs;
            view.flowPath = front.flowPath;
            view.isEnabled = front.isEnabled;
            return view;
        }
    }
}
