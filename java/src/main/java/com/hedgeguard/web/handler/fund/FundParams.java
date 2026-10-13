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

    /**
     * 解析「日期 → 时间戳区间端点」（{@code T-195}；{@code /fund/apply-list} 的 from/to 按
     * {@code created_time} 过滤）。
     *
     * <p>{@code startOfDay=true} ⇒ 当日 {@code 00:00}（起始端）；{@code false} ⇒ 当日
     * {@code 23:59:59.999999999}（截止端，含当日全部时刻）。日期语义与 {@code dateOrNull} 一致
     * （yyyy-MM-dd、非法明确报参错），仅<b>落点端</b>不同——使「from=to=同一天」恰好覆盖当日全天。
     *
     * @param raw        日期串（可空）
     * @param startOfDay {@code true} = 当日起点；{@code false} = 当日终点
     * @return 时间戳；未传返回 {@code null}
     */
    static java.time.LocalDateTime dateTimeEndOfDayOrNull(String raw, boolean startOfDay) {
        LocalDate d = dateOrNull(raw, startOfDay ? "from" : "to");
        if (d == null) {
            return null;
        }
        return startOfDay ? d.atStartOfDay() : d.atTime(23, 59, 59, 999999999);
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
