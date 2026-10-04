package com.hedgeguard.web.handler.fund;

import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.RequestContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** FUND 模块路由的参数解析工具（{@code T-049}）。 */
final class FundParams {

    private FundParams() {
    }

    static String body(RequestContext ctx) {
        return ctx.body() == null || ctx.body().trim().isEmpty() ? "{}" : ctx.body();
    }

    static LocalDate dateOrNull(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 格式非法（yyyy-MM-dd）：" + raw);
        }
    }

    static LocalDate dateOrDefault(String raw, String field) {
        LocalDate d = dateOrNull(raw, field);
        return d == null ? com.hedgeguard.common.Clocks.today() : d;
    }

    static Integer intOrNull(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 须为整数：" + raw);
        }
    }

    static String text(String raw) {
        return raw == null || raw.trim().isEmpty() ? null : raw.trim();
    }

    static long parseId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "id 不得为空");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "id 须为整数：" + raw);
        }
    }

    static BigDecimal decimalOrNull(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 须为数值：" + raw);
        }
    }

    static String dec(java.math.BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
