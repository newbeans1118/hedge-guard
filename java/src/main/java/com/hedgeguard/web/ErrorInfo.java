package com.hedgeguard.web;

/**
 * 阻断类错误的扩展信息（{@code ARCHITECTURE.md} §4.1.1「阻断类错误」/ §4.3.1）。
 *
 * <p>形状：{@code {"code":..,"message":"..","ruleId":"R-RISK-4","current":"0.93",
 * "threshold":"0.90","nextStep":"..","traceId":".."}}。
 *
 * <p>字段为 {@code public} 以便 Gson 直接序列化；非阻断错误时四项置 {@code null}（序列化省略）。
 */
public final class ErrorInfo {

    /** 业务错误码。 */
    public int code;

    /** 提示语。 */
    public String message;

    /** 命中的规则编号（如 {@code R-RISK-4}）。 */
    public String ruleId;

    /** 当前值（字符串化十进制，避免 JS 精度丢失）。 */
    public String current;

    /** 阈值（字符串化十进制）。 */
    public String threshold;

    /** 下一步建议（可操作指引）。 */
    public String nextStep;

    /** 追踪号。 */
    public String traceId;

    /** 无参构造（供 Gson 反序列化）。 */
    public ErrorInfo() {
    }
}
