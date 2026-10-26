package com.hedgeguard.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SessionAuthFilter} 会话白名单<b>防脱钩</b>判据（{@code T-203} / cr-t025 {@code R1-002}）。
 *
 * <p>白名单 {@code PUBLIC_EXACT} 与 {@code FORCE_CHANGE_ALLOWED} 须以
 * {@code METHOD + ApiRoutes.full(路径)} 拼装，不得写字面量——否则 {@code ApiRoutes.BASE}
 * 漂移时白名单<b>静默脱钩</b>（脱钩方向 = 未认证即 {@code 10006} fail-closed，无越权面，
 * 属可维护性收口）。本判据三重锁：
 * <ol>
 *   <li><b>派生等值</b>：{@code PUBLIC_EXACT} 每项 = 派生值（基路径漂移且白名单未跟随 ⇒ 红）；</li>
 *   <li><b>负例</b>：不在白名单的 {@code METHOD+路径} 断言非成员（证判据非恒真）；</li>
 *   <li><b>源残留守卫</b>：主源代码行（非注释）不得再出现 {@code /api/v1/}（防「改回字面量」回归；
 *   工单字面 grep 形态 {@code "/api/v1/} 与合法取值形状重合（{@code "POST /api/v1/...} 引号后跟方法名）恒 0 命中，
 *   系盲守卫——本判据按「非注释行」收紧，javadoc 文字豁免）。</li>
 * </ol>
 *
 * <p><b>反退化</b>：把 {@code PUBLIC_EXACT} 改回字面量后（未来）若 {@code ApiRoutes.BASE}
 * 漂移 ⇒ {@link #publicExactEqualsApiRoutesDerivedValues()} 红；不改基路径直接改回字面量 ⇒
 * {@link #sourceContainsNoApiV1StringLiterals()} 红。
 */
class SessionAuthFilterWhitelistTest {

    /** 白名单派生值（与 SessionAuthFilter 生产拼装同形，逐字引用表达式，不含任何字面基路径）。 */
    private static Set<String> derivedPublicExact() {
        return new HashSet<>(Arrays.asList(
                "POST " + ApiRoutes.full("/auth/login"),
                "GET " + ApiRoutes.full("/ping"),
                "HEAD " + ApiRoutes.full("/ping")));
    }

    @Test
    @DisplayName("冻结值钉：派生值与改前三对字面值逐字恒等（ApiRoutes 基路径漂移 ⇒ 红）")
    void derivedValuesPinFrozenLiterals() {
        // 改前字面值（HEAD 8a67d34 时点）与改后拼装求值的逐字对照——亦是「运行值恒等」的机器断言。
        assertEquals("POST /api/v1/auth/login", "POST " + ApiRoutes.full("/auth/login"));
        assertEquals("GET /api/v1/ping", "GET " + ApiRoutes.full("/ping"));
        assertEquals("HEAD /api/v1/ping", "HEAD " + ApiRoutes.full("/ping"));
    }

    @Test
    @DisplayName("PUBLIC_EXACT 每项 = METHOD + ApiRoutes.full(路径) 派生值（基路径漂移时必红）")
    void publicExactEqualsApiRoutesDerivedValues() throws Exception {
        assertEquals(derivedPublicExact(), publicExact(),
                "PUBLIC_EXACT 与 ApiRoutes 派生值脱钩：白名单须以 METHOD + ApiRoutes.full(路径) 拼装");
    }

    @Test
    @DisplayName("负例：不在白名单的 METHOD+路径 断言非成员（判据非恒真）")
    void negativeExamplesAreNotMembers() throws Exception {
        Set<String> exact = publicExact();
        // 同路径异方法
        assertFalse(exact.contains("GET " + ApiRoutes.full("/auth/login")));
        assertFalse(exact.contains("PUT " + ApiRoutes.full("/auth/login")));
        assertFalse(exact.contains("POST " + ApiRoutes.full("/ping")));
        // 白名单外的业务路径
        assertFalse(exact.contains("GET " + ApiRoutes.full("/product")));
        assertFalse(exact.contains("DELETE " + ApiRoutes.full("/ping")));
        // 派生集与负例方向互斥：若上列断言失效（误判成员），本断言反向坐实判据两侧均有判别力
        assertEquals(3, exact.size(), "白名单恰 3 项（冻结：login + ping GET/HEAD）");
    }

    @Test
    @DisplayName("行为级：isPublic 对白名单派生值放行、同路径异方法与业务端点不放行")
    void isPublicBehaviorFollowsDerivedWhitelist() {
        SessionAuthFilter filter = new SessionAuthFilter(null, "HGSESSION");
        assertTrue(filter.isPublic(ctx("POST", ApiRoutes.full("/auth/login"))));
        assertTrue(filter.isPublic(ctx("GET", ApiRoutes.full("/ping"))));
        assertTrue(filter.isPublic(ctx("HEAD", ApiRoutes.full("/ping"))));
        // 负例：同路径异方法不放行（fail-closed 10006 方向）
        assertFalse(filter.isPublic(ctx("GET", ApiRoutes.full("/auth/login"))));
        assertFalse(filter.isPublic(ctx("POST", ApiRoutes.full("/ping"))));
        // 负例：业务端点不放行
        assertFalse(filter.isPublic(ctx("GET", ApiRoutes.full("/product"))));
    }

    @Test
    @DisplayName("源残留守卫：主源代码行（非注释）0 处 /api/v1/（javadoc 除外）")
    void sourceContainsNoApiV1StringLiterals() throws Exception {
        Path file = repoRoot().resolve("java/src/main/java/com/hedgeguard/web/SessionAuthFilter.java");
        String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        // 判据形态说明：字面量形态为 "POST /api/v1/..."（引号后跟方法名），若只查 "/api/v1/
        // 则与合法取值形状重合、恒 0 处（盲守卫）。故查「含 /api/v1/ 的非注释行」——
        // javadoc 文字（{@code POST /api/v1/...}）为合法豁免，代码行出现即残留。
        long hits = src.lines()
                .filter(l -> l.contains("/api/v1/"))
                .filter(l -> {
                    String t = l.trim();
                    return !(t.startsWith("*") || t.startsWith("/*") || t.startsWith("//"));
                })
                .count();
        assertEquals(0L, hits,
                "SessionAuthFilter 主源代码行出现 /api/v1/ 残留：白名单须以 ApiRoutes.full 拼装，防基路径脱钩");
    }

    private static RequestContext ctx(String method, String path) {
        return new RequestContext(method, path, null, Collections.emptyMap(), "", "t-203", "127.0.0.1");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> publicExact() throws Exception {
        Field f = SessionAuthFilter.class.getDeclaredField("PUBLIC_EXACT");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test，当前="
                + Paths.get("").toAbsolutePath());
    }
}
