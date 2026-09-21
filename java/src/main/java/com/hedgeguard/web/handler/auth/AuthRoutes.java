package com.hedgeguard.web.handler.auth;

import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.SessionStore;

/**
 * {@code AUTH} 模块路由注册（{@code #java2} 产出；路径逐字取自 {@code ApiRoutes.byModule().get("AUTH")}）。
 *
 * <p>归属声明：本类与 {@code web/handler/auth/**} 由 {@code backend-engineer#java2} 新增，
 * 属「台账与合规链」交付；{@code web/} 的框架类（{@code Router}/{@code ApiRoutes}/{@code Bootstrap} 等）
 * 保持 {@code #java} 写入域不变。
 *
 * <p>装配方式（由 {@code Bootstrap} 调用；本类不改动框架文件）：
 * <pre>
 *   AuthRoutes.register(router, authService, sessionStore);
 * </pre>
 */
public final class AuthRoutes {

    private AuthRoutes() {
    }

    /**
     * 注册 {@code AUTH} 模块路由。
     *
     * @param router       路由
     * @param authService  认证服务
     * @param sessionStore 会话存储
     */
    public static void register(Router router, AuthService authService, SessionStore sessionStore) {
        router.register("POST", ApiRoutes.full("/auth/login"),
                new LoginHandler(authService, sessionStore));
        router.register("POST", ApiRoutes.full("/auth/logout"), new LogoutHandler(authService));
    }
}
