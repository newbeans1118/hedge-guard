package com.hedgeguard.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST 路由器（{@code ARCHITECTURE.md} §3.7「内部结构 … Router」）。
 *
 * <p>路由规则（冻结）：
 * <ul>
 *   <li>基础路径 {@code /api/v1/{module}/{resource}}，段数不匹配即不命中；</li>
 *   <li>占位符以花括号表示，如 {@code /api/v1/product/{id}}；占位符匹配任意非空单段；</li>
 *   <li>同一 {@code (方法, 路径模式)} 重复注册 → 启动期显式失败（不静默覆盖）；</li>
 *   <li>路径命中但方法不命中 → {@code 405}（由调用方据 {@link #pathExists} 判定），
 *       便于前端区分"资源不存在"与"方法用错"。</li>
 * </ul>
 *
 * <p>线程安全：注册在启动期完成，之后只读；{@link #match} 无共享可变状态。
 */
public final class Router {

    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Route> registration = new LinkedHashMap<>();

    /**
     * 注册路由。
     *
     * @param method  HTTP 方法（查询 / 新增或动作 / 修改 / 草稿类物理删除，语义见
     *                {@code ARCHITECTURE.md} §4.1.1）
     * @param pattern 路径模式，须以 {@code /} 开头
     * @param handler 处理函数
     */
    public void register(String method, String pattern, RouteHandler handler) {
        String m = method.toUpperCase(Locale.ROOT);
        validate(m, pattern);
        String key = m + " " + pattern;
        if (registration.containsKey(key)) {
            throw new IllegalStateException(
                    "路由重复注册：" + key + "；原因=同一 (方法, 路径) 被注册两次；"
                            + "复现=检查 RouteRegistry 中该路径的注册次数（禁止静默覆盖）");
        }
        Route route = new Route(m, pattern, split(pattern), handler);
        registration.put(key, route);
        routes.add(route);
    }

    /**
     * 匹配路由。
     *
     * @param method HTTP 方法
     * @param path   请求路径
     * @return 命中结果；未命中返回 {@code null}
     */
    public RouteMatch match(String method, String path) {
        String m = method.toUpperCase(Locale.ROOT);
        String[] actual = split(normalize(path));
        for (Route r : routes) {
            if (!r.method.equals(m) || r.segments.length != actual.length) {
                continue;
            }
            Map<String, String> params = matchSegments(r.segments, actual);
            if (params != null) {
                return new RouteMatch(r, params);
            }
        }
        return null;
    }

    /**
     * 路径是否存在（忽略方法），用于 405 判定。
     *
     * @param path 请求路径
     * @return 存在任一方法的路由返回 {@code true}
     */
    public boolean pathExists(String path) {
        String[] actual = split(normalize(path));
        for (Route r : routes) {
            if (r.segments.length == actual.length && matchSegments(r.segments, actual) != null) {
                return true;
            }
        }
        return false;
    }

    /** 已注册路由的只读清单（形如 {@code GET /api/v1/ping}），用于自检与文档生成。 */
    public List<String> routes() {
        List<String> out = new ArrayList<>(routes.size());
        for (Route r : routes) {
            out.add(r.method + " " + r.pattern);
        }
        return Collections.unmodifiableList(out);
    }

    private void validate(String method, String pattern) {
        if (pattern == null || !pattern.startsWith("/")) {
            throw new IllegalArgumentException("路由模式须以 / 开头：" + pattern);
        }
        if (pattern.contains("//") || pattern.endsWith("/") && pattern.length() > 1) {
            throw new IllegalArgumentException("路由模式不得含 // 或以 / 结尾：" + pattern);
        }
        String[] segs = split(pattern);
        for (String s : segs) {
            if (s.isEmpty()) {
                throw new IllegalArgumentException("路由模式含空段：" + pattern);
            }
            if (s.indexOf('{') >= 0 && !(s.startsWith("{") && s.endsWith("}"))) {
                throw new IllegalArgumentException("占位符须独占整段：" + pattern + " 段=" + s);
            }
        }
    }

    private static Map<String, String> matchSegments(String[] pattern, String[] actual) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                if (actual[i].isEmpty()) {
                    return null;
                }
                params.put(p.substring(1, p.length() - 1), actual[i]);
            } else if (!p.equals(actual[i])) {
                return null;
            }
        }
        return params;
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        int q = path.indexOf('?');
        String p = q < 0 ? path : path.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String[] split(String path) {
        if ("/".equals(path)) {
            return new String[0];
        }
        String s = path.startsWith("/") ? path.substring(1) : path;
        return s.split("/", -1);
    }

    /** 一条路由。 */
    private static final class Route {
        private final String method;
        private final String pattern;
        private final String[] segments;
        private final RouteHandler handler;

        Route(String method, String pattern, String[] segments, RouteHandler handler) {
            this.method = method;
            this.pattern = pattern;
            this.segments = segments;
            this.handler = handler;
        }
    }

    /** 匹配结果：处理函数 + 占位符取值。 */
    public static final class RouteMatch {

        private final RouteHandler handler;
        private final Map<String, String> pathParams;
        private final String pattern;

        RouteMatch(Route route, Map<String, String> pathParams) {
            this.handler = route.handler;
            this.pathParams = Collections.unmodifiableMap(pathParams);
            this.pattern = route.pattern;
        }

        /** 命中的处理函数。 */
        public RouteHandler handler() {
            return handler;
        }

        /** 占位符取值。 */
        public Map<String, String> pathParams() {
            return pathParams;
        }

        /** 命中的路径模式（诊断用）。 */
        public String pattern() {
            return pattern;
        }
    }
}
