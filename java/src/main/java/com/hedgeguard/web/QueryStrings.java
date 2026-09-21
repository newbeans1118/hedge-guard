package com.hedgeguard.web;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询串解析（{@code ?page=1&size=20&sort=field,asc}）。
 *
 * <p>重复参数取<b>最后一个</b>（前端约定不产生重复键；重复时的取舍在此显式固定，避免各处不一致）。
 */
public final class QueryStrings {

    private QueryStrings() {
    }

    /**
     * 解析查询串。
     *
     * @param rawQuery 原始查询串（可为 {@code null}）
     * @return 键值映射（保持出现顺序）
     */
    public static Map<String, String> parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(key), decode(value));
        }
        return out;
    }

    /**
     * URL 解码（{@code application/x-www-form-urlencoded} 语义）。
     *
     * @param text 编码文本
     * @return 解码文本；解码失败时原样返回
     */
    public static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return text;
        }
    }
}
