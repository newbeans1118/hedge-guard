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
 *
 * <p><b>{@code T-010} 补齐声明面</b>：{@code ApiRoutes} 声明 {@code AUTH} <b>10</b> 条路径，
 * 本注册器在 {@code T-010} 重做中由仅 2 条（{@code login}/{@code logout}）补齐为全量
 * <b>10/10</b>（共 17 个 {@code (方法,路径)} 对）——
 * <ul>
 *   <li>{@code POST} {@code /auth/login}、{@code /auth/logout}（{@code FR-AUTH-14/15}）；</li>
 *   <li>{@code GET|POST|PUT} {@code /auth/user}（{@code FR-AUTH-1~5}）；</li>
 *   <li>{@code GET|POST} {@code /auth/role}（{@code FR-AUTH-6/8}）；</li>
 *   <li>{@code GET|POST} {@code /auth/position-group}（{@code FR-AUTH-7}）；</li>
 *   <li>{@code GET|POST} {@code /auth/authorization}（{@code FR-AUTH-10}）；</li>
 *   <li>{@code GET|POST} {@code /auth/sub-account-perm}（{@code FR-AUTH-12/13}）；</li>
 *   <li>{@code GET} {@code /auth/change-log}（{@code FR-AUTH-11/18}）；</li>
 *   <li>{@code GET} {@code /auth/unauthorized-attempt}（{@code FR-AUTH-18}）；</li>
 *   <li>{@code POST|PUT} {@code /auth/password}（{@code FR-AUTH-16}）。</li>
 * </ul>
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

        // T-010：补齐 AUTH 声明面（其余 8 条路径）
        router.register("GET", UserHandler.PATH, new UserHandler(authService));
        router.register("POST", UserHandler.PATH, new UserHandler(authService));
        router.register("PUT", UserHandler.PATH, new UserHandler(authService));

        router.register("GET", RoleHandler.PATH, new RoleHandler(authService));
        router.register("POST", RoleHandler.PATH, new RoleHandler(authService));

        router.register("GET", PositionGroupHandler.PATH, new PositionGroupHandler(authService));
        router.register("POST", PositionGroupHandler.PATH, new PositionGroupHandler(authService));

        router.register("GET", AuthorizationHandler.PATH, new AuthorizationHandler(authService));
        router.register("POST", AuthorizationHandler.PATH, new AuthorizationHandler(authService));

        router.register("GET", SubAccountPermHandler.PATH, new SubAccountPermHandler(authService));
        router.register("POST", SubAccountPermHandler.PATH, new SubAccountPermHandler(authService));

        router.register("GET", ChangeLogHandler.PATH, new ChangeLogHandler(authService));
        router.register("GET", UnauthorizedAttemptHandler.PATH,
                new UnauthorizedAttemptHandler(authService));

        router.register("POST", PasswordHandler.PATH, new PasswordHandler(authService));
        router.register("PUT", PasswordHandler.PATH, new PasswordHandler(authService));
    }
}
