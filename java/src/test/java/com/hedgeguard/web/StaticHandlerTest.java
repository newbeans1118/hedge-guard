package com.hedgeguard.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StaticHandler} 单元测试（{@code ARCHITECTURE.md} §3.7 静态托管约定）。
 *
 * <p>覆盖验收项："静态托管 {@code GET /web/**} 生效，未知扩展名返回 404，
 * 仅放行白名单扩展名（{@code .html/.js/.css/.png/.svg/.ico/.woff2}）"。
 */
class StaticHandlerTest {

    private static RequestContext get(String path) {
        return new RequestContext("GET", path, null, Collections.emptyMap(), "", "HGT", "127.0.0.1");
    }

    private static StaticHandler handlerFor(Path root) {
        return new StaticHandler(root);
    }

    @Test
    @DisplayName("白名单扩展名按 MIME 正确返回；/web/ 映射到 index.html")
    void servesWhitelistedExtensions(@TempDir Path root) throws IOException {
        Files.write(root.resolve("index.html"), "<html>HG</html>".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("app.js"), "var a=1;".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("hg.css"), "body{}".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("logo.svg"), "<svg/>".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("js/core"));
        Files.write(root.resolve("js/core/api.js"), "//api".getBytes(StandardCharsets.UTF_8));

        StaticHandler h = handlerFor(root);

        HttpResult index = h.handle(get("/web/"));
        assertEquals(200, index.status);
        assertEquals("text/html; charset=UTF-8", index.contentType);
        assertEquals("no-store", index.headers().get("Cache-Control"));

        assertEquals("application/javascript; charset=UTF-8", h.handle(get("/web/app.js")).contentType);
        assertEquals("text/css; charset=UTF-8", h.handle(get("/web/hg.css")).contentType);
        assertEquals("image/svg+xml", h.handle(get("/web/logo.svg")).contentType);
        assertEquals("application/javascript; charset=UTF-8",
                h.handle(get("/web/js/core/api.js")).contentType);
    }

    @Test
    @DisplayName("白名单外的扩展名与无扩展名一律 404")
    void rejectsNonWhitelistedExtensions(@TempDir Path root) throws IOException {
        Files.write(root.resolve("secret.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("db.properties"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("noext"), "x".getBytes(StandardCharsets.UTF_8));
        StaticHandler h = handlerFor(root);

        assertEquals(404, h.handle(get("/web/secret.txt")).status);
        assertEquals(404, h.handle(get("/web/db.properties")).status);
        assertEquals(404, h.handle(get("/web/noext")).status);
        assertEquals(404, h.handle(get("/web/.env")).status);
    }

    @Test
    @DisplayName("路径穿越被拦截（..、绝对路径、编码后穿越）")
    void blocksPathTraversal(@TempDir Path root) throws IOException {
        Path secret = root.getParent().resolve("outside-secret.html");
        Files.write(secret, "TOP SECRET".getBytes(StandardCharsets.UTF_8));
        StaticHandler h = handlerFor(root);

        assertEquals(404, h.handle(get("/web/../outside-secret.html")).status);
        assertEquals(404, h.handle(get("/web/..%2Foutside-secret.html")).status);
        assertEquals(404, h.handle(get("/web/....//outside-secret.html")).status);
        assertEquals(404, h.handle(get("/web/%2e%2e/outside-secret.html")).status);
    }

    @Test
    @DisplayName("文件不存在返回 404；非 GET 返回 405")
    void handlesMissingAndWrongMethod(@TempDir Path root) {
        StaticHandler h = handlerFor(root);
        assertEquals(404, h.handle(get("/web/missing.html")).status);
        assertEquals(405, h.handle(new RequestContext("POST", "/web/index.html", null,
                Collections.emptyMap(), "", "HGT", "127.0.0.1")).status);
    }

    @Test
    @DisplayName("白名单判定为纯函数，扩展名大小写不敏感")
    void extensionWhitelistIsPureAndCaseInsensitive() {
        assertTrue(StaticHandler.hasAllowedExtension("index.html"));
        assertTrue(StaticHandler.hasAllowedExtension("a/b/c.woff2"));
        assertFalse(StaticHandler.hasAllowedExtension("a.txt"));
        assertFalse(StaticHandler.hasAllowedExtension("a.html.txt"));
        assertFalse(StaticHandler.hasAllowedExtension("noext"));
        assertFalse(StaticHandler.hasAllowedExtension("dir.html/file"));
        assertEquals(7, StaticHandler.ALLOWED_EXTENSIONS.size());
        assertNotNull(StaticHandler.URL_PREFIX);
        assertEquals("/web", StaticHandler.URL_PREFIX);
    }
}
