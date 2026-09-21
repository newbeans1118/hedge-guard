package com.hedgeguard.web;

/**
 * 认证与授权过滤器（{@code ARCHITECTURE.md} §3.7「③ 认证与授权过滤器」）。
 *
 * <p>安全默认（{@code NFR-SEC-3}、附录 B 红线 9）：<b>除显式声明的公开路径外一律拒绝</b>，
 * 且<b>不提供</b>关闭鉴权的配置项。功能权限 / 数据权限（子账户可见性）由 {@code AUTH} 模块的服务层实现，
 * 本接口只负责"是否已登录"这一层。
 */
public interface AuthFilter {

    /**
     * 是否为公开路径（无需登录）。
     *
     * @param ctx 请求上下文
     * @return 公开返回 {@code true}
     */
    boolean isPublic(RequestContext ctx);

    /**
     * 鉴权；失败时抛 {@link BizException}（{@code 10006}/{@code 10007}/{@code 10008}）。
     *
     * @param ctx 请求上下文
     */
    void authenticate(RequestContext ctx);
}
