package com.hedgeguard.web;

/**
 * 业务异常（唯一允许跨层抛出的异常载体，{@code ARCHITECTURE.md} §3.7 ErrorMapper 的输入）。
 *
 * <p>阻断类错误（{@link ErrorCode#thresholdBlocking()}）<b>必须</b>经
 * {@link #blocking} 构造，从源头保证 {@code ruleId/current/threshold/nextStep} 四项齐备
 * （{@code NFR-USE-3}、{@code NFR-USE-4}）。
 */
public final class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String ruleId;
    private final String current;
    private final String threshold;
    private final String nextStep;

    private BizException(ErrorCode errorCode, String message, String ruleId, String current,
                         String threshold, String nextStep, Throwable cause) {
        super(message == null || message.isEmpty() ? errorCode.defaultMessage() : message, cause);
        this.errorCode = errorCode;
        this.ruleId = ruleId;
        this.current = current;
        this.threshold = threshold;
        this.nextStep = nextStep;
    }

    /**
     * 普通业务错误。
     *
     * @param errorCode 错误码（不得为阈值型阻断码）
     * @param message   提示语
     * @return 异常
     */
    public static BizException of(ErrorCode errorCode, String message) {
        if (errorCode.thresholdBlocking()) {
            throw new IllegalArgumentException(
                    "错误码 " + errorCode.code() + " 属阈值型阻断类，必须经 BizException.blocking() 构造并携带"
                            + " ruleId/current/threshold/nextStep 四项（NFR-USE-3/4）；"
                            + "复现=改用 BizException.blocking(...) 并补齐四项");
        }
        return new BizException(errorCode, message, null, null, null, null, null);
    }

    /**
     * 普通业务错误（带原因链，用于定位根因）。
     *
     * @param errorCode 错误码
     * @param message   提示语
     * @param cause     原因
     * @return 异常
     */
    public static BizException of(ErrorCode errorCode, String message, Throwable cause) {
        if (errorCode.thresholdBlocking()) {
            throw new IllegalArgumentException(
                    "错误码 " + errorCode.code() + " 属阈值型阻断类，必须经 BizException.blocking() 构造；"
                            + "复现=改用 BizException.blocking(...) 并补齐四项");
        }
        return new BizException(errorCode, message, null, null, null, null, cause);
    }

    /**
     * 阈值型阻断错误（四项必填）。
     *
     * @param errorCode 错误码（须为阈值型阻断码）
     * @param message   提示语
     * @param ruleId    命中规则编号，如 {@code R-RISK-4}
     * @param current   当前值（字符串化十进制）
     * @param threshold 阈值（字符串化十进制）
     * @param nextStep  下一步建议
     * @return 异常
     */
    public static BizException blocking(ErrorCode errorCode, String message, String ruleId,
                                        String current, String threshold, String nextStep) {
        requireText(ruleId, "ruleId");
        requireText(current, "current");
        requireText(threshold, "threshold");
        requireText(nextStep, "nextStep");
        return new BizException(errorCode, message, ruleId, current, threshold, nextStep, null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "阻断类错误的 " + field + " 不得为空；复现=检查调用点是否遗漏该项（NFR-USE-3/4）");
        }
    }

    /** 错误码。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 命中规则编号（非阻断类为 {@code null}）。 */
    public String ruleId() {
        return ruleId;
    }

    /** 当前值（非阻断类为 {@code null}）。 */
    public String current() {
        return current;
    }

    /** 阈值（非阻断类为 {@code null}）。 */
    public String threshold() {
        return threshold;
    }

    /** 下一步建议（非阻断类为 {@code null}）。 */
    public String nextStep() {
        return nextStep;
    }
}
