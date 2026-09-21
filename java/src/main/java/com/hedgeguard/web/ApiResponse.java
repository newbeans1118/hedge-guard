package com.hedgeguard.web;

/**
 * 统一响应信封（{@code ARCHITECTURE.md} §4.1.1）。
 *
 * <p>形状：{@code {"code":0,"message":"OK","data":{...},"traceId":"..."}}。
 * 字段为 {@code public} 且由 Gson 直接序列化（{@code null} 字段自动省略）。
 *
 * @param <T> 业务数据类型
 */
public final class ApiResponse<T> {

    /** 业务错误码，{@code 0} = 成功（见 {@link ErrorCode}）。 */
    public int code;

    /** 提示语（成功固定 {@code OK}）。 */
    public String message;

    /** 业务数据；错误响应时为 {@code null}（序列化时省略）。 */
    public T data;

    /** 全链路追踪号（每个请求唯一，便于日志定位）。 */
    public String traceId;

    /** 无参构造（供 Gson 反序列化）。 */
    public ApiResponse() {
    }

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    /**
     * 成功响应。
     *
     * @param data    业务数据
     * @param traceId 追踪号
     * @param <T>     数据类型
     * @return 信封
     */
    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data, traceId);
    }

    /**
     * 失败响应。
     *
     * @param code    错误码
     * @param message 提示语（{@code null} 时取默认语）
     * @param traceId 追踪号
     * @param <T>     数据类型
     * @return 信封
     */
    public static <T> ApiResponse<T> fail(ErrorCode code, String message, String traceId) {
        String msg = (message == null || message.isEmpty()) ? code.defaultMessage() : message;
        return new ApiResponse<>(code.code(), msg, null, traceId);
    }
}
