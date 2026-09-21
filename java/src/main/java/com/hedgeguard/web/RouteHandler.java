package com.hedgeguard.web;

/**
 * 业务处理函数（L4 → L3 的适配点；不暴露 {@code HttpExchange}，保证 L3 可离线单测）。
 */
@FunctionalInterface
public interface RouteHandler {

    /**
     * 处理请求。
     *
     * @param ctx 请求上下文
     * @return 处理结果
     */
    HttpResult handle(RequestContext ctx);
}
