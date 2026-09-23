package com.hedgeguard.web.handler.auth;

import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionStore;

/**
 * 登录 {@code POST /api/v1/auth/login}（{@code FR-AUTH-1}；公开路径白名单之一，{@code §4.1.2}）。
 *
 * <p>响应成功后写入会话 Cookie（{@code HGSESSION}，{@code HttpOnly}；名取自 {@code CFG-23b}
 * 默认值），并在 {@code data} 中返回会话摘要（<b>不含</b>任何口令/AuthCode/资金密码字段）。
 *
 * <p>失败一律由 {@code BizException} 经 {@code RequestDispatcher} 映射为统一错误信封
 * （{@code 10007} 不区分"用户不存在"与"口令错误"，防账号枚举）。
 */
public final class LoginHandler implements RouteHandler {

    /** 会话 Cookie 名（{@code CFG-23b} 默认值；{@code §4.1.1} 冻结）。 */
    public static final String SESSION_COOKIE = "HGSESSION";

    private final AuthService authService;
    private final SessionStore sessionStore;
    private final String sessionCookieName;

    /**
     * 构造（Cookie 名取 {@code CFG-23b} 默认值 {@value #SESSION_COOKIE}）。
     *
     * @param authService  认证服务
     * @param sessionStore 会话存储
     */
    public LoginHandler(AuthService authService, SessionStore sessionStore) {
        this(authService, sessionStore, SESSION_COOKIE);
    }

    /**
     * 构造（显式指定 Cookie 名，须与 {@code Config.KEY_SESSION_COOKIE} 的取值一致）。
     *
     * @param authService       认证服务
     * @param sessionStore      会话存储
     * @param sessionCookieName 会话 Cookie 名
     */
    public LoginHandler(AuthService authService, SessionStore sessionStore, String sessionCookieName) {
        this.authService = authService;
        this.sessionStore = sessionStore;
        this.sessionCookieName = sessionCookieName;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        LoginRequest request = JsonCodec.fromJson(ctx.body(), LoginRequest.class);
        if (request == null || request.username == null || request.password == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "登录请求须包含 username 与 password：复现=按 §4.1.1 提交 application/json");
        }
        Session session = authService.login(request.username, request.password, ctx.clientIp(),
                ctx.header("User-Agent"));
        sessionStore.save(session);
        LoginResponse body = new LoginResponse();
        body.userId = session.userId;
        body.username = session.username;
        body.expireTime = session.expireTime.toString();
        // FR-AUTH-16：把「须强制修改口令」状态回传递给前端，供其引导用户进入改密界面；
        // 服务端门禁本身由 SessionAuthFilter 独立强制（不依赖此字段）。
        body.mustChangePassword = session.mustChangePassword;
        return HttpResult.json(200, ApiResponse.ok(body, ctx.traceId()))
                .withHeader("Set-Cookie", sessionCookieName + "=" + session.sessionId
                        + "; Path=/; HttpOnly; SameSite=Lax");
    }

    /** 登录请求体。 */
    public static final class LoginRequest {

        /** 登录名。 */
        public String username;

        /** 明文口令（仅内存；服务端不落盘、不日志）。 */
        public String password;
    }

    /** 登录响应体（仅会话摘要）。 */
    public static final class LoginResponse {

        /** 用户 ID。 */
        public long userId;

        /** 登录名。 */
        public String username;

        /** 会话过期时间（ISO-8601）。 */
        public String expireTime;

        /**
         * 是否须强制修改口令（{@code FR-AUTH-16}：首次登录 / 口令被重置后为 {@code true}）。
         *
         * <p>供前端引导至改密界面；<b>不</b>承担门禁——门禁由服务端 {@code SessionAuthFilter} 强制。
         */
        public boolean mustChangePassword;
    }

    /** 供注册器使用的路径常量（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/login");
}
