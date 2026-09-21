package com.hedgeguard.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 静态资源托管（{@code ARCHITECTURE.md} §3.7「静态托管」）。
 *
 * <p>冻结规则：
 * <ol>
 *   <li>作用域 {@code GET /web/**}，从部署目录（{@code CFG-30 hg.web.root}，默认 {@code ./web}）读取，<b>不做打包</b>；</li>
 *   <li>扩展名<b>白名单</b>：{@code .html/.js/.css/.png/.svg/.ico/.woff2}；白名单外（含无扩展名）→ {@code 404}；</li>
 *   <li>{@code /web/} 映射到 {@code index.html}（等价于 {@code /web/index.html}）；</li>
 *   <li>路径穿越（{@code ..}、{@code %}2e%2e} 解码后、绝对路径、符号链接越界）→ {@code 404}，且<b>不</b>回显解析后路径；</li>
 *   <li>非 {@code GET} → {@code 405}；</li>
 *   <li>{@code Cache-Control: no-store}——保证"改动源文件后刷新即生效"（{@code NFR-MAINT-1}）。</li>
 * </ol>
 */
public final class StaticHandler {

    /** 前端静态资源 URL 前缀。 */
    public static final String URL_PREFIX = "/web";

    /** 扩展名白名单（冻结，不得扩展而不经变更流程）。 */
    public static final List<String> ALLOWED_EXTENSIONS = Collections.unmodifiableList(
            Arrays.asList(".html", ".js", ".css", ".png", ".svg", ".ico", ".woff2"));

    private static final Map<String, String> CONTENT_TYPES;

    static {
        Map<String, String> m = new HashMap<>();
        m.put(".html", "text/html; charset=UTF-8");
        m.put(".js", "application/javascript; charset=UTF-8");
        m.put(".css", "text/css; charset=UTF-8");
        m.put(".png", "image/png");
        m.put(".svg", "image/svg+xml");
        m.put(".ico", "image/x-icon");
        m.put(".woff2", "font/woff2");
        CONTENT_TYPES = Collections.unmodifiableMap(m);
    }

    private final Path root;

    /**
     * 构造。
     *
     * @param root 静态资源根目录（{@code hg.web.root}）
     */
    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 静态资源根目录（绝对规范化路径）。 */
    public Path root() {
        return root;
    }

    /**
     * 处理静态资源请求。
     *
     * @param ctx 请求上下文
     * @return 结果（200 / 404 / 405）
     */
    public HttpResult handle(RequestContext ctx) {
        if (!"GET".equals(ctx.method()) && !"HEAD".equals(ctx.method())) {
            return HttpResult.text(405, "405 静态资源仅支持 GET");
        }
        String rel = ctx.path().length() > URL_PREFIX.length()
                ? ctx.path().substring(URL_PREFIX.length()) : "/";
        if (rel.isEmpty() || rel.endsWith("/")) {
            rel = rel + "index.html";
        }
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }

        String lower = rel.toLowerCase(Locale.ROOT);
        if (!hasAllowedExtension(lower)) {
            return HttpResult.text(404, "404 未知或未放行的资源扩展名");
        }

        Path target = resolveInsideRoot(rel);
        if (target == null || !Files.isRegularFile(target)) {
            return HttpResult.text(404, "404 资源不存在");
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String ct = CONTENT_TYPES.get(extension(lower));
            return HttpResult.bytes(200, ct, bytes).withHeader("Cache-Control", "no-store");
        } catch (IOException e) {
            return HttpResult.text(500, "500 读取静态资源失败（详见服务端日志，traceId=" + ctx.traceId() + "）");
        }
    }

    /**
     * 解析为根目录内的真实文件路径（穿越拦截）。
     *
     * @param relativePath 相对于 {@code /web/} 的路径
     * @return 合法路径；越界返回 {@code null}
     */
    private Path resolveInsideRoot(String relativePath) {
        if (relativePath.indexOf('\0') >= 0) {
            return null;
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            return null;
        }
        if (Files.isSymbolicLink(candidate)) {
            try {
                Path real = candidate.toRealPath();
                if (!real.normalize().startsWith(root)) {
                    return null;
                }
                return real;
            } catch (IOException e) {
                return null;
            }
        }
        return candidate;
    }

    /**
     * 扩展名是否在白名单内。
     *
     * @param lowerPath 已转小写的相对路径
     * @return 在白名单内返回 {@code true}
     */
    public static boolean hasAllowedExtension(String lowerPath) {
        int dot = lowerPath.lastIndexOf('.');
        int slash = lowerPath.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return false;
        }
        return ALLOWED_EXTENSIONS.contains(lowerPath.substring(dot));
    }

    private static String extension(String lowerPath) {
        return lowerPath.substring(lowerPath.lastIndexOf('.'));
    }
}
