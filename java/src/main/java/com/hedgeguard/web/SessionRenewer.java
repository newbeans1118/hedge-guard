package com.hedgeguard.web;

/**
 * 会话续期入口（{@code FR-AUTH-15} 验收 2「空闲超时」的接线缝；{@code CFG-23b}）。
 *
 * <p><b>为何需要本接口</b>：会话空闲超时要求「每次有效请求都把过期时间顺延」（滑动过期）。
 * 续期动作与库内会话行写入属服务层（{@link com.hedgeguard.service.AuthService#touchSession(String)}），
 * 而认证发生在接口层的 {@link SessionAuthFilter}。为不把整个服务层耦合进过滤器，也便于单测注入，
 * 这里只暴露一个纯续期方法；生产装配以 {@code authService::touchSession} 注入，
 * 未注入（{@code null}）时过滤器回退为「只查不续期」的绝对过期语义（向后兼容既有 2 参构造的调用点）。
 *
 * @see SessionAuthFilter
 */
@FunctionalInterface
public interface SessionRenewer {

    /**
     * 续期并返回会话（滑动过期）。
     *
     * @param sessionId 会话 ID
     * @return 续期后的会话；会话不存在返回 {@code null}
     * @throws BizException 会话已空闲超时（{@code 10006}，须重新认证）
     */
    Session renew(String sessionId);
}
