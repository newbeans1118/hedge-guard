package com.hedgeguard.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求上下文（L4 的唯一输入载体）。
 *
 * <p>刻意<b>不</b>携带 JDK {@code HttpExchange} / {@code HttpServletRequest} 等 I/O 类型，
 * 使 L4 之后的逻辑可在无 HTTP 环境下单测（{@code ARCHITECTURE.md} §2.3、{@code NFR-TEST-2}）。
 */
public final class RequestContext {

    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, String> query;
    private final Map<String, String> headers;
    private final Map<String, String> pathParams;
    private final String body;
    private final String traceId;
    private final String clientIp;
    private Session session;

    /**
     * 构造上下文。
     *
     * @param method   HTTP 方法（大写）
     * @param path     路径（不含查询串，已解码）
     * @param rawQuery 原始查询串（可为 {@code null}）
     * @param headers  请求头（键统一小写）
     * @param body     请求体文本
     * @param traceId  追踪号
     * @param clientIp 客户端 IP（审计用）
     */
    public RequestContext(String method, String path, String rawQuery, Map<String, String> headers,
                          String body, String traceId, String clientIp) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        // 请求头键统一转小写：HTTP 头名大小写不敏感，调用方（HttpServerLauncher / 测试桩）
        // 传入大小写不一，在此归一，避免 header("Cookie") 与 "cookie" 取不到。
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null) {
                normalized.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue());
            }
        }
        this.headers = Collections.unmodifiableMap(normalized);
        this.body = body == null ? "" : body;
        this.traceId = traceId;
        this.clientIp = clientIp;
        this.query = Collections.unmodifiableMap(QueryStrings.parse(rawQuery));
        this.pathParams = new LinkedHashMap<>();
    }

    /**
     * 绑定路由占位符参数（由 {@link Router} 在匹配成功后调用）。
     *
     * @param params 参数
     */
    void bindPathParams(Map<String, String> params) {
        this.pathParams.clear();
        this.pathParams.putAll(params);
    }

    /** HTTP 方法。 */
    public String method() {
        return method;
    }

    /** 请求路径。 */
    public String path() {
        return path;
    }

    /** 原始查询串。 */
    public String rawQuery() {
        return rawQuery;
    }

    /**
     * 取路径占位符（如 {@code /api/v1/product/{id}} 中的 {@code id}）。
     *
     * @param name 占位符名
     * @return 值；不存在返回 {@code null}
     */
    public String pathParam(String name) {
        return pathParams.get(name);
    }

    /**
     * 取查询参数。
     *
     * @param name 参数名
     * @return 值；不存在返回 {@code null}
     */
    public String queryParam(String name) {
        return query.get(name);
    }

    /** 全部查询参数（只读）。 */
    public Map<String, String> queryParams() {
        return query;
    }

    /**
     * 取请求头（键小写）。
     *
     * @param name 头名（大小写不敏感）
     * @return 值；不存在返回 {@code null}
     */
    public String header(String name) {
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 请求体文本。 */
    public String body() {
        return body;
    }

    /** 追踪号。 */
    public String traceId() {
        return traceId;
    }

    /** 客户端 IP。 */
    public String clientIp() {
        return clientIp;
    }

    /** 当前会话（未登录为 {@code null}）。 */
    public Session session() {
        return session;
    }

    /**
     * 绑定会话（由认证过滤器注入）。
     *
     * @param session 会话
     */
    public void bindSession(Session session) {
        this.session = session;
    }

    /**
     * 幂等键（写类接口必需：出入金 / 划拨 / 下单 / 参数下发）。
     *
     * @return {@code Idempotency-Key} 头；缺失返回 {@code null}
     */
    public String idempotencyKey() {
        return header("Idempotency-Key");
    }

    /**
     * 当前用户 ID。
     *
     * @return 用户 ID；未登录返回 {@code 0}
     */
    public long currentUserId() {
        return session == null ? 0L : session.userId;
    }
}
