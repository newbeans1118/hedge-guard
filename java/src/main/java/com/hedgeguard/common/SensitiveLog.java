package com.hedgeguard.common;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 日志脱敏（{@code ARCHITECTURE.md} §8.2 {@code NFR-SEC-1} ③）。
 *
 * <p>零依赖落地：用 JDK 内置 {@code java.util.logging}（不引 SLF4J / Logback，{@code §7.2.2}），
 * 并强制安装脱敏过滤器。敏感字段清单来自 {@code CFG-19}：{@code password,auth_code,fund_password,auth_token}
 * ——<b>不得删减</b>。
 */
public final class SensitiveLog {

    /** 敏感字段清单（{@code CFG-19}，固定集合，不得删减）。 */
    public static final List<String> SENSITIVE_FIELDS =
            java.util.Collections.unmodifiableList(
                    Arrays.asList("password", "auth_code", "fund_password", "auth_token"));

    private SensitiveLog() {
    }

    /**
     * 脱敏过滤器：命中敏感关键字的值一律替换为 {@code ***}。
     *
     * <p>处理两种形态：{@code key=value} / {@code key: value} / {@code "key":"value"}。
     */
    public static final class MaskingFilter implements Filter {

        private static final String MASK = "***";

        @Override
        public boolean isLoggable(LogRecord record) {
            if (record == null) {
                return false;
            }
            String msg = record.getMessage();
            if (msg != null) {
                record.setMessage(mask(msg));
            }
            Object[] params = record.getParameters();
            if (params != null) {
                Object[] masked = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    masked[i] = (params[i] instanceof String) ? mask((String) params[i]) : params[i];
                }
                record.setParameters(masked);
            }
            return true;
        }

        /**
         * 脱敏文本。
         *
         * @param text 原始文本
         * @return 脱敏后文本
         */
        public static String mask(String text) {
            String out = text;
            for (String field : SENSITIVE_FIELDS) {
                String lower = field.toLowerCase(Locale.ROOT);
                out = maskKeyEquals(out, lower);
                out = maskJsonKey(out, lower);
            }
            return out;
        }

        /** {@code password=xxx} / {@code password: xxx} 形态。 */
        private static String maskKeyEquals(String text, String key) {
            StringBuilder sb = new StringBuilder(text.length());
            int from = 0;
            while (true) {
                int idx = indexOfIgnoreCase(text, key, from);
                if (idx < 0) {
                    sb.append(text, from, text.length());
                    return sb.toString();
                }
                int eq = idx + key.length();
                while (eq < text.length() && (text.charAt(eq) == ' ' || text.charAt(eq) == '"'
                        || text.charAt(eq) == '\'')) {
                    eq++;
                }
                if (eq >= text.length() || (text.charAt(eq) != '=' && text.charAt(eq) != ':')) {
                    sb.append(text, from, idx + key.length());
                    from = idx + key.length();
                    continue;
                }
                sb.append(text, from, eq + 1);
                int valueStart = eq + 1;
                while (valueStart < text.length() && text.charAt(valueStart) == ' ') {
                    valueStart++;
                }
                int valueEnd = valueStart;
                while (valueEnd < text.length() && isValueChar(text.charAt(valueEnd))) {
                    valueEnd++;
                }
                sb.append(' ').append(MASK);
                from = valueEnd;
            }
        }

        /** JSON 形态：{@code "password":"xxx"}（键已被上面的形态覆盖，此处处理单引号/无引号键）。 */
        private static String maskJsonKey(String text, String key) {
            return maskKeyEquals(text, key);
        }

        private static boolean isValueChar(char c) {
            return c != ' ' && c != ',' && c != '}' && c != ']' && c != ';' && c != '\n' && c != '\r'
                    && c != '&';
        }

        private static int indexOfIgnoreCase(String haystack, String needle, int from) {
            int limit = haystack.length() - needle.length();
            for (int i = Math.max(from, 0); i <= limit; i++) {
                if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * 为指定 Logger 安装脱敏过滤器（幂等）。
     *
     * @param logger 目标 Logger
     */
    public static void install(Logger logger) {
        Filter existing = logger.getFilter();
        if (existing instanceof MaskingFilter) {
            return;
        }
        logger.setFilter(new MaskingFilter());
        logger.setLevel(Level.INFO);
        if (logger.getParent() != null) {
            logger.getParent().setLevel(Level.INFO);
        }
        logger.setUseParentHandlers(true);
    }

    /** 根 Logger 安装（进程启动时调用一次）。 */
    public static void installRoot() {
        install(Logger.getLogger(""));
        for (String name : KNOWN_LOGGERS) {
            install(Logger.getLogger(name));
        }
    }

    private static final List<String> KNOWN_LOGGERS = java.util.Collections.unmodifiableList(
            Arrays.asList("com.hedgeguard", "com.hedgeguard.web", "com.hedgeguard.dao",
                    "com.hedgeguard.bridge", "com.hedgeguard.service"));

    /**
     * 带脱敏的结构化日志辅助（键值对形式，值经脱敏后再写）。
     */
    public static final class Kv {

        private final Map<String, String> values = new LinkedHashMap<>();

        /**
         * 追加一个键值对。
         *
         * @param key   键
         * @param value 值
         * @return 自身，便于链式调用
         */
        public Kv put(String key, Object value) {
            values.put(key, value == null ? "" : String.valueOf(value));
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return MaskingFilter.mask(sb.toString());
        }
    }
}
