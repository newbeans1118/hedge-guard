package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;

import java.util.UUID;

/**
 * 追踪号生成（每个 HTTP 请求一个，写入响应信封与所有日志行）。
 *
 * <p>格式：{@code HG<yyyyMMddHHmmss><8 位随机十六进制>}，便于按时间定位与人工比对。
 */
public final class TraceIds {

    private TraceIds() {
    }

    /**
     * 生成追踪号。
     *
     * @return 追踪号
     */
    public static String next() {
        String ts = Clocks.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "HG" + ts + rand;
    }

    /**
     * 取请求头 {@code X-HG-TraceId}（若由前端/网关传入则复用，便于跨跳追踪）。
     *
     * @param headerValue 头值
     * @return 追踪号（缺失或非法时新生成）
     */
    public static String resolve(String headerValue) {
        if (headerValue == null || headerValue.isEmpty() || headerValue.length() > 64) {
            return next();
        }
        for (int i = 0; i < headerValue.length(); i++) {
            char c = headerValue.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '-' || c == '_';
            if (!ok) {
                return next();
            }
        }
        return headerValue;
    }
}
