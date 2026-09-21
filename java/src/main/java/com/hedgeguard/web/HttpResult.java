package com.hedgeguard.web;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 处理结果（接口层内部载体，屏蔽 JDK {@code HttpExchange}，保证 L3 可离线单测，{@code NFR-TEST-2}）。
 */
public final class HttpResult {

    /** HTTP 状态码。 */
    public final int status;

    /** 响应 Content-Type；为 {@code null} 时不写该头。 */
    public final String contentType;

    /** 响应体字节。 */
    public final byte[] body;

    private final Map<String, String> headers;

    private HttpResult(int status, String contentType, byte[] body, Map<String, String> headers) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        this.headers = Collections.unmodifiableMap(headers);
    }

    /**
     * JSON 响应。
     *
     * @param status HTTP 状态码
     * @param value  待序列化对象
     * @return 结果
     */
    public static HttpResult json(int status, Object value) {
        return new HttpResult(status, "application/json; charset=UTF-8", JsonCodec.toJsonBytes(value),
                new LinkedHashMap<>());
    }

    /**
     * 纯文本响应。
     *
     * @param status HTTP 状态码
     * @param text   文本
     * @return 结果
     */
    public static HttpResult text(int status, String text) {
        return new HttpResult(status, "text/plain; charset=UTF-8",
                text.getBytes(StandardCharsets.UTF_8), new LinkedHashMap<>());
    }

    /**
     * 字节响应（静态资源）。
     *
     * @param status      HTTP 状态码
     * @param contentType MIME 类型
     * @param body        字节
     * @return 结果
     */
    public static HttpResult bytes(int status, String contentType, byte[] body) {
        return new HttpResult(status, contentType, body, new LinkedHashMap<>());
    }

    /**
     * 空响应（仅状态码）。
     *
     * @param status HTTP 状态码
     * @return 结果
     */
    public static HttpResult empty(int status) {
        return new HttpResult(status, null, new byte[0], new LinkedHashMap<>());
    }

    /**
     * 追加响应头。
     *
     * @param name  头名
     * @param value 头值
     * @return 新结果（本类不可变）
     */
    public HttpResult withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new HttpResult(status, contentType, body, copy);
    }

    /** 附加响应头（只读）。 */
    public Map<String, String> headers() {
        return headers;
    }
}
