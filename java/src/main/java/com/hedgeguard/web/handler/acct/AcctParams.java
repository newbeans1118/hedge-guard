package com.hedgeguard.web.handler.acct;

import com.hedgeguard.web.RequestContext;

/**
 * ACCT 域请求参数解析（{@code FundParams} 同族；包内共享，避免各 handler 自拼）。
 */
final class AcctParams {

    private AcctParams() {
    }

    /** 请求体（{@code RequestContext.body()} 直传）。 */
    static String body(RequestContext ctx) {
        return ctx.body();
    }

    /** 可空整数参数（非法值明确报参数错误）。 */
    static Integer intOrNull(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw com.hedgeguard.web.BizException.of(
                    com.hedgeguard.web.ErrorCode.PARAM_INVALID, field + " 须为整数：" + raw);
        }
    }

    /** 可空长整数参数（非法值明确报参数错误）。 */
    static Long longOrNull(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw com.hedgeguard.web.BizException.of(
                    com.hedgeguard.web.ErrorCode.PARAM_INVALID, field + " 须为整数：" + raw);
        }
    }

    /** 可空文本参数（trim；空白归 null）。 */
    static String text(String raw) {
        return raw == null || raw.trim().isEmpty() ? null : raw.trim();
    }
}
