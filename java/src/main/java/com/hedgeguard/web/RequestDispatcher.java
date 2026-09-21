package com.hedgeguard.web;

import com.hedgeguard.common.SensitiveLog;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 请求分发（纯逻辑，无任何 I/O 类型）——{@code HttpServerLauncher} 与单测共用同一入口。
 *
 * <p>分发顺序（冻结）：
 * <ol>
 *   <li>{@code GET|HEAD /web/**} → {@link StaticHandler}（公开）；</li>
 *   <li>{@code /api/v1/**} → <b>先认证</b>（公开白名单除外）→ {@link Router} 匹配
 *       → 未命中：路径存在则 {@code 405}，否则 {@code 404}；</li>
 *   <li>其余路径 → {@code 404}；</li>
 *   <li>任何异常 → {@link ErrorMapper} 统一映射（含 {@code traceId}）。</li>
 * </ol>
 *
 * <p><b>为何"先认证再路由"</b>（安全默认）：未登录者访问任何 {@code /api/v1/**} 路径
 * 一律得到 {@code 10006}，<b>不区分</b>路径是否存在 —— 避免向未认证方泄露接口清单
 * （端点枚举）。因此 {@code 404/405} 只对已认证请求可见。
 *
 * <p>请求行会以 INFO 级别记录（经 {@link SensitiveLog} 强制脱敏，{@code NFR-SEC-1}）。
 */
public final class RequestDispatcher {

    private static final Logger LOG = Logger.getLogger(RequestDispatcher.class.getName());

    private static final String API_PREFIX = "/api/v1";

    private final Router router;
    private final StaticHandler staticHandler;
    private final AuthFilter authFilter;

    /**
     * 构造。
     *
     * @param router        路由器
     * @param staticHandler 静态资源处理器
     * @param authFilter    认证过滤器
     */
    public RequestDispatcher(Router router, StaticHandler staticHandler, AuthFilter authFilter) {
        this.router = router;
        this.staticHandler = staticHandler;
        this.authFilter = authFilter;
    }

    /**
     * 分发请求。
     *
     * @param ctx 请求上下文
     * @return 处理结果（永不抛异常；异常一律转为错误响应）
     */
    public HttpResult dispatch(RequestContext ctx) {
        long startNanos = System.nanoTime();
        HttpResult result;
        try {
            result = route(ctx);
        } catch (BizException e) {
            result = errorResult(ctx, e);
        } catch (IllegalArgumentException e) {
            result = errorResult(ctx, e);
        } catch (RuntimeException e) {
            result = errorResult(ctx, e);
        }
        logAccess(ctx, result, startNanos);
        return result;
    }

    private HttpResult route(RequestContext ctx) {
        String path = ctx.path();
        if (path.equals(StaticHandler.URL_PREFIX) || path.startsWith(StaticHandler.URL_PREFIX + "/")) {
            return staticHandler.handle(ctx);
        }
        if (!path.equals(API_PREFIX) && !path.startsWith(API_PREFIX + "/")) {
            return HttpResult.text(404, "404 未知路径（仅提供 /api/v1/** 与 /web/**）");
        }
        if (!authFilter.isPublic(ctx)) {
            authFilter.authenticate(ctx);
        }
        Router.RouteMatch match = router.match(ctx.method(), path);
        if (match == null) {
            if (router.pathExists(path)) {
                return HttpResult.text(405, "405 方法不被允许：" + ctx.method().toUpperCase(Locale.ROOT) + " " + path);
            }
            return HttpResult.text(404, "404 接口不存在：" + path);
        }
        ctx.bindPathParams(match.pathParams());
        return match.handler().handle(ctx);
    }

    private static HttpResult errorResult(RequestContext ctx, Throwable t) {
        ErrorInfo info = ErrorMapper.map(t, ctx.traceId());
        return HttpResult.json(ErrorMapper.httpStatus(t), info);
    }

    private static void logAccess(RequestContext ctx, HttpResult result, long startNanos) {
        if (!LOG.isLoggable(Level.INFO)) {
            return;
        }
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        LOG.info(new SensitiveLog.Kv()
                .put("traceId", ctx.traceId())
                .put("method", ctx.method())
                .put("path", ctx.path())
                .put("status", result.status)
                .put("ms", ms)
                .put("user", ctx.currentUserId())
                .put("ip", ctx.clientIp())
                .toString());
    }
}
