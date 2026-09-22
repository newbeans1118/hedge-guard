package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.web.handler.auth.AuthRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-010}「REST 8 端点未注册」缺口的**反退化判据**（{@code #java2}）。
 *
 * <p>两段判据：
 * <ol>
 *   <li><b>声明面 == 注册面</b>（路径级）：{@code ApiRoutes} 冻结的 AUTH <b>10</b> 条路径，
 *       必须<b>全部</b>被 {@code AuthRoutes} 真实注册（消灭「声明了却少注册」）；</li>
 *   <li><b>(方法,路径) 对</b>：本单补齐的 15 个对必须全部注册（消灭「路径在而方法缺」）；</li>
 *   <li><b>可达</b>：经 {@link RequestDispatcher} 进程内分发，逐条断言<b>不再 404/405</b>。</li>
 * </ol>
 *
 * <p><b>反退化</b>：删除任一条 {@code router.register(...)}，段 1/2 必红；若把某路径下所有方法注册删光，
 * 段 3 对应条目必红（404=路径未注册 / 405=方法未注册，二者正是本单要消灭的症状）。
 */
class AUTHEndpointRegistrationTest {

    /** 本单补齐的 15 个 (方法,路径) 对。 */
    private static final List<String[]> NEW_ROUTES = List.of(
            new String[]{"GET", ApiRoutes.full("/auth/user")},
            new String[]{"POST", ApiRoutes.full("/auth/user")},
            new String[]{"PUT", ApiRoutes.full("/auth/user")},
            new String[]{"GET", ApiRoutes.full("/auth/role")},
            new String[]{"POST", ApiRoutes.full("/auth/role")},
            new String[]{"GET", ApiRoutes.full("/auth/position-group")},
            new String[]{"POST", ApiRoutes.full("/auth/position-group")},
            new String[]{"GET", ApiRoutes.full("/auth/authorization")},
            new String[]{"POST", ApiRoutes.full("/auth/authorization")},
            new String[]{"GET", ApiRoutes.full("/auth/sub-account-perm")},
            new String[]{"POST", ApiRoutes.full("/auth/sub-account-perm")},
            new String[]{"GET", ApiRoutes.full("/auth/change-log")},
            new String[]{"GET", ApiRoutes.full("/auth/unauthorized-attempt")},
            new String[]{"POST", ApiRoutes.full("/auth/password")},
            new String[]{"PUT", ApiRoutes.full("/auth/password")});

    @Test
    @DisplayName("段①：AUTH 声明面 10 条路径全部注册（声明 == 注册，路径级）+ 本单 15 个 (方法,路径) 对在册")
    void allAuthPathsAndNewPairsRegistered() {
        Router router = new Router();
        AuthRoutes.register(router, null, null);
        List<String> registered = new ArrayList<>(router.routes());

        // 路径级：声明 10 条路径全部出现
        Set<String> registeredPaths = new HashSet<>();
        for (String entry : registered) {
            registeredPaths.add(entry.substring(entry.indexOf(' ') + 1));
        }
        List<String> missingPaths = new ArrayList<>();
        for (String path : ApiRoutes.byModule().get("AUTH")) {
            if (!registeredPaths.contains(ApiRoutes.full(path))) {
                missingPaths.add(path);
            }
        }
        assertTrue(missingPaths.isEmpty(),
                "AUTH 声明路径未注册：" + missingPaths + "；实际=" + registered);
        assertEquals(ApiRoutes.byModule().get("AUTH").size(), registeredPaths.size(),
                "AUTH 注册路径数应等于声明数（10）：实际=" + registeredPaths);

        // (方法,路径) 级：本单 15 对在册
        List<String> missing = new ArrayList<>();
        for (String[] route : NEW_ROUTES) {
            String key = route[0] + " " + route[1];
            if (!registered.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "以下 T-010 端点未注册：" + missing + "；实际=" + registered);
        assertEquals(registered.size(), new LinkedHashSet<>(registered).size(),
                "存在重复注册：" + registered);
    }

    @Test
    @DisplayName("段②：经分发器调用 AUTH 新端点，逐条不再 404/405")
    void authEndpointsNot404Not405() {
        SessionStore store = new SessionStore.InMemory();
        Router router = new Router();
        router.register("GET", ApiRoutes.full(ApiRoutes.PING), new com.hedgeguard.web.handler.PingHandler());
        AuthRoutes.register(router, null, null);
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(Paths.get(".")),
                new SessionAuthFilter(store, "HGSESSION"));
        store.save(new Session("S-1", 7L, "lisi", 1L, Clocks.now().plusMinutes(30)));
        Map<String, String> auth = Collections.singletonMap("X-HG-Session", "S-1");

        List<String> problems = new ArrayList<>();
        for (String[] route : NEW_ROUTES) {
            HttpResult result = dispatcher.dispatch(new RequestContext(route[0], route[1], null,
                    auth, "", "HGT010", "127.0.0.1"));
            if (result.status == 404 || result.status == 405) {
                problems.add(route[0] + " " + route[1] + " → " + result.status);
            }
        }
        assertTrue(problems.isEmpty(),
                "AUTH 端点仍返回 404/405（=未注册 / 方法未注册）：" + problems);
        // 反向对照：不存在的路径应 404/405（证明"非 404"并非分发器盲放行）
        HttpResult unknown = dispatcher.dispatch(new RequestContext("GET",
                "/api/v1/auth/definitely-not-a-route", null, auth, "", "HGT010", "127.0.0.1"));
        assertTrue(unknown.status == 404 || unknown.status == 405,
                "不存在的路径应 404/405，实际=" + unknown.status);
        assertNotEquals(200, unknown.status);
    }
}
