package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@code T-109}「已声明未注册」端点补齐的**契约一致性 + 可达性**证据。
 *
 * <p>两段判据：
 * <ol>
 *   <li><b>注册登记</b>：{@code CONFIG} 9 / {@code SPOT} 5 / {@code PLAN} 1 共 15 条路径对应的
 *       <b>19 个 (方法,路径) 对</b>必须被 {@code *Routes} 真实注册（{@code Router} 级，防漏注册）；</li>
 *   <li><b>调用可达</b>：经 {@link RequestDispatcher} <b>直连分发</b>（2 参 {@code SessionAuthFilter}＝不续期）
 *       进程内分发，逐条断言<b>不再 404/405</b>
 *       （{@code 404=未注册}、{@code 405=路径在而方法未注册}，二者正是本单要消灭的症状）。
 *       <br>⚠️ 段②<b>不再经</b> {@code Bootstrap} 装配分发器：T-010 起认证链含 {@code SessionRenewer}
 *       （写库），在假驱动下已认证请求一律 500 ⇒ 原断言恒真（空转，见 {@code TE-T010b-001}）。</li>
 * </ol>
 *
 * <p>本用例即「反退化」判据：若删除任一条 {@code router.register(...)}，段 1 必红；
 * 若删除路径下所有方法注册，段 2 的对应条目必红（404/405）。
 */
class T109EndpointRegistrationTest {

    /** 本单补齐的 (方法,路径) 对（19 条）。 */
    private static final List<String[]> NEW_ROUTES = List.of(
            new String[]{"GET", ApiRoutes.full("/config/ctp")},
            new String[]{"POST", ApiRoutes.full("/config/ctp")},
            new String[]{"POST", ApiRoutes.full("/config/ctp/test")},
            new String[]{"GET", ApiRoutes.full("/config/dict")},
            new String[]{"POST", ApiRoutes.full("/config/dict")},
            new String[]{"GET", ApiRoutes.full("/config/subject")},
            new String[]{"POST", ApiRoutes.full("/config/subject")},
            new String[]{"GET", ApiRoutes.full("/config/report-period")},
            new String[]{"POST", ApiRoutes.full("/config/report-period")},
            new String[]{"GET", ApiRoutes.full("/config/dispatch/{id}")},
            new String[]{"GET", ApiRoutes.full("/config/change-log")},
            new String[]{"POST", ApiRoutes.full("/config/import")},
            new String[]{"GET", ApiRoutes.full("/config/export")},
            new String[]{"POST", ApiRoutes.full("/spot/production")},
            new String[]{"POST", ApiRoutes.full("/spot/price/import")},
            new String[]{"GET", ApiRoutes.full("/spot/price/export")},
            new String[]{"GET", ApiRoutes.full("/spot/exposure/export")},
            new String[]{"POST", ApiRoutes.full("/spot/doc/sub-account")},
            new String[]{"POST", ApiRoutes.full("/plan/{id}/bind-exposure")});

    /** 段 2 分发用的具体路径（占位符替换为具体值）。 */
    private static final List<String[]> DISPATCH = List.of(
            new String[]{"GET", ApiRoutes.full("/config/ctp")},
            new String[]{"POST", ApiRoutes.full("/config/ctp")},
            new String[]{"POST", ApiRoutes.full("/config/ctp/test")},
            new String[]{"GET", ApiRoutes.full("/config/dict")},
            new String[]{"POST", ApiRoutes.full("/config/dict")},
            new String[]{"GET", ApiRoutes.full("/config/subject")},
            new String[]{"POST", ApiRoutes.full("/config/subject")},
            new String[]{"GET", ApiRoutes.full("/config/report-period")},
            new String[]{"POST", ApiRoutes.full("/config/report-period")},
            new String[]{"GET", ApiRoutes.full("/config/dispatch/1")},
            new String[]{"GET", ApiRoutes.full("/config/change-log")},
            new String[]{"POST", ApiRoutes.full("/config/import")},
            new String[]{"GET", ApiRoutes.full("/config/export")},
            new String[]{"POST", ApiRoutes.full("/spot/production")},
            new String[]{"POST", ApiRoutes.full("/spot/price/import")},
            new String[]{"GET", ApiRoutes.full("/spot/price/export")},
            new String[]{"GET", ApiRoutes.full("/spot/exposure/export")},
            new String[]{"POST", ApiRoutes.full("/spot/doc/sub-account")},
            new String[]{"POST", ApiRoutes.full("/plan/1/bind-exposure")});

    private static final String[] OVERRIDABLE = {
            Config.KEY_DB_URL, Config.KEY_DB_DRIVER, Config.KEY_DB_USER,
            Config.KEY_DB_POOL_INITIAL, Config.KEY_DB_POOL_MAX, Config.KEY_DB_POOL_BORROW_TIMEOUT_MS,
    };

    @BeforeEach
    @AfterEach
    void clearDbProperties() {
        for (String key : OVERRIDABLE) {
            System.clearProperty(key);
        }
    }

    @Test
    @DisplayName("段①：T-109 的 19 个 (方法,路径) 对全部被真实注册（Router 级）")
    void allT109RoutesRegistered() {
        Router router = new Router();
        ConfigRoutes.register(router, null);
        SpotRoutes.register(router, null);
        PlanRoutes.register(router, null);

        List<String> registered = new ArrayList<>(router.routes());
        List<String> missing = new ArrayList<>();
        for (String[] route : NEW_ROUTES) {
            String key = route[0] + " " + route[1];
            if (!registered.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "以下 T-109 端点未注册：" + missing + "；实际=" + registered);
        // 无重复注册（重复会在启动期失败，此处显式复核）
        Set<String> unique = new LinkedHashSet<>(registered);
        assertTrue(unique.size() == registered.size(), "存在重复注册：" + registered);
    }

    @Test
    @DisplayName("段②：经分发器调用 T-109 端点，逐条不再 404/405")
    void t109EndpointsNot404Not405() {
        System.setProperty(Config.KEY_DB_URL, "jdbc:dm://127.0.0.1:5236/HG");
        System.setProperty(Config.KEY_DB_DRIVER, FakeJdbcDriver.class.getName());
        System.setProperty(Config.KEY_DB_USER, "hg_app");
        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");

        Bootstrap.Assembly assembly = assertDoesNotThrow(
                () -> Bootstrap.assembleWithResources(Config.empty()));
        try {
            assembly.sessionStore().save(new Session("S-1", 7L, "lisi", 1L,
                    Clocks.now().plusMinutes(30)));
            Map<String, String> auth = Collections.singletonMap("X-HG-Session", "S-1");

            // 【T-010 修正 · `TE-T010b-001`（守卫空转）】
            //   原段② 经 `assembly.dispatcher()`（含 `SessionRenewer`）分发。T-010 起 `Bootstrap` 为
            //   认证过滤器注入 `authService::touchSession`（认证成功即写 `t_login_session` 并顺延会话）；
            //   在本环境的**假驱动**下该写抛 NPE ⇒ **已认证请求一律 500**（注册/未注册**皆然**，已实测）
            //   ⇒ 只收 404/405 的 `problems.isEmpty()` **恒真、不再守卫「端点注册」**（空转，等于没有断言）。
            //   现改为**直连分发**（2 参 `SessionAuthFilter` = 不续期，与产出方已改的反向对照同形）：
            //   认证通过后才进入路由匹配，恢复 404/500 的区分力 ——
            //   已注册 ⇒ 命中处理（handler 因无真实库而 5xx，但**非** 404/405）；未注册 ⇒ 404（路径在而方法缺 ⇒ 405）。
            //   判据 =「19 条逐条不被 404/405 拒绝」；**删掉任一端点注册 ⇒ 其条目必 404/405 ⇒ 本断言必红**（已双向实测）。
            //   与 `AUTHEndpointRegistrationTest` 段② 同形；装配面注册完整性另由 `BootstrapAssemblyTest`
            //   的 `routeCount == 83` 守卫，本用例不重复承担该职责（仅保留装配冒烟：假驱动下装配不抛）。
            Router plainRouter = new Router();
            plainRouter.register("GET", ApiRoutes.full(ApiRoutes.PING),
                    new com.hedgeguard.web.handler.PingHandler());
            ConfigRoutes.register(plainRouter, null);
            SpotRoutes.register(plainRouter, null);
            PlanRoutes.register(plainRouter, null);
            RequestDispatcher plain = new RequestDispatcher(plainRouter,
                    new StaticHandler(java.nio.file.Paths.get(".")),
                    new SessionAuthFilter(assembly.sessionStore(), "HGSESSION"));

            List<String> problems = new ArrayList<>();
            for (String[] route : DISPATCH) {
                HttpResult result = plain.dispatch(
                        new RequestContext(route[0], route[1], null, auth, "", "HGT109", "127.0.0.1"));
                if (result.status == 404 || result.status == 405) {
                    problems.add(route[0] + " " + route[1] + " → " + result.status);
                }
            }
            assertTrue(problems.isEmpty(),
                    "T-109 端点仍返回 404/405（=未注册 / 方法未注册）：" + problems);
            // 反向对照：仍不存在的路径应 404/405（证明"非 404"并非因为分发器盲放行）。
            HttpResult unknown = plain.dispatch(new RequestContext(
                    "GET", "/api/v1/config/definitely-not-a-route", null, auth, "", "HGT109",
                    "127.0.0.1"));
            assertNotEquals(200, unknown.status);
            assertTrue(unknown.status == 404 || unknown.status == 405,
                    "不存在的路径应 404/405，实际=" + unknown.status);
        } finally {
            Bootstrap.close(assembly);
            // 防泄漏：PoolConfig 校验超时常量与既有用例一致
            assertTrue(PoolConfig.DEFAULT_VALIDATION_TIMEOUT_SECONDS > 0);
        }
    }
}
