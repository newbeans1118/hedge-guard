package com.hedgeguard.web;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 统一错误映射器（{@code ARCHITECTURE.md} §3.7「内部结构 … ErrorMapper」）。
 *
 * <p>职责：把任意异常映射为 {@link ErrorInfo}，确保：
 * <ul>
 *   <li>HTTP 状态码来自 {@link ErrorCode#httpStatus()}（不自行编造）；</li>
 *   <li>阈值型阻断类错误四项齐备（缺项时<b>不得</b>静默降级为普通错误，须显式失败并留诊断日志）；</li>
 *   <li>非业务异常一律归入 {@code 10022}，且响应中<b>不回显</b>堆栈细节，仅记录 {@code traceId}。</li>
 * </ul>
 */
public final class ErrorMapper {

    private static final Logger LOG = Logger.getLogger(ErrorMapper.class.getName());

    private ErrorMapper() {
    }

    /**
     * 映射异常为错误响应体。
     *
     * @param throwable 异常
     * @param traceId   追踪号
     * @return 错误信息（可直接序列化为错误响应）
     */
    public static ErrorInfo map(Throwable throwable, String traceId) {
        ErrorInfo info = new ErrorInfo();
        info.traceId = traceId;

        if (throwable instanceof BizException) {
            BizException biz = (BizException) throwable;
            ErrorCode code = biz.errorCode();
            info.code = code.code();
            info.message = biz.getMessage();
            info.ruleId = biz.ruleId();
            info.current = biz.current();
            info.threshold = biz.threshold();
            info.nextStep = biz.nextStep();
            verifyThresholdFields(code, info, throwable);
            return info;
        }

        if (throwable instanceof IllegalArgumentException) {
            info.code = ErrorCode.PARAM_INVALID.code();
            info.message = throwable.getMessage() == null
                    ? ErrorCode.PARAM_INVALID.defaultMessage() : throwable.getMessage();
            return info;
        }

        info.code = ErrorCode.INTERNAL_ERROR.code();
        info.message = ErrorCode.INTERNAL_ERROR.defaultMessage();
        LOG.log(Level.SEVERE, "内部错误 traceId=" + traceId + " type="
                + throwable.getClass().getName(), throwable);
        return info;
    }

    /**
     * 映射异常为 HTTP 状态码。
     *
     * @param throwable 异常
     * @return HTTP 状态码
     */
    public static int httpStatus(Throwable throwable) {
        if (throwable instanceof BizException) {
            return ((BizException) throwable).errorCode().httpStatus();
        }
        if (throwable instanceof IllegalArgumentException) {
            return ErrorCode.PARAM_INVALID.httpStatus();
        }
        return ErrorCode.INTERNAL_ERROR.httpStatus();
    }

    /**
     * 阈值型阻断错误四项齐备性校验：缺项即契约违背，须显式失败（禁止静默降级）。
     */
    private static void verifyThresholdFields(ErrorCode code, ErrorInfo info, Throwable cause) {
        if (!code.thresholdBlocking()) {
            return;
        }
        if (isBlank(info.ruleId) || isBlank(info.current) || isBlank(info.threshold)
                || isBlank(info.nextStep)) {
            String detail = "阈值型阻断错误四项缺失：code=" + code.code()
                    + " ruleId=" + info.ruleId + " current=" + info.current
                    + " threshold=" + info.threshold + " nextStep=" + info.nextStep;
            LOG.log(Level.SEVERE, detail
                    + "；复现=调用 ErrorCode.thresholdBlocking() 为 true 的错误码但未走 BizException.blocking()");
            throw new IllegalStateException(detail, cause);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
