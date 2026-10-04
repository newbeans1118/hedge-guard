package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.pool.PoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code D-19}：{@code Bootstrap} 的<b>受配置保护装配</b>契约（完全离线，不接触 DM8）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>无 {@code hg.db.url} 不启动失败</b>：{@link Bootstrap#assemble(Config)} 不抛异常，
 *       {@code GET|HEAD /api/v1/ping} 可用，业务路由<b>不注册</b>（不可达）；</li>
 *   <li><b>有 {@code hg.db.url} 才接入数据库层</b>：开关为真时装配路径确实执行
 *       （本环境无 DM 驱动，故以连接池预建失败的明确异常反证"该分支被走到"）；</li>
 *   <li><b>{@code CFG-31 hg.db.pool.*} 映射</b>与默认值、以及资源释放入口的<b>幂等 / 空安全</b>。</li>
 * </ol>
 */
class BootstrapAssemblyTest {

    private static final String[] OVERRIDABLE = {
            Config.KEY_DB_URL,
            Config.KEY_DB_DRIVER,
            Config.KEY_DB_USER,
            Config.KEY_DB_POOL_INITIAL,
            Config.KEY_DB_POOL_MAX,
            Config.KEY_DB_POOL_BORROW_TIMEOUT_MS,
    };

    @BeforeEach
    @AfterEach
    void clearDbProperties() {
        for (String key : OVERRIDABLE) {
            System.clearProperty(key);
        }
    }

    // ------------------------------------------------------------------ 1. 无 DB：进程可用

    @Test
    @DisplayName("无 hg.db.url：assemble 不抛异常，ping 可用，业务路由未注册（404）")
    void withoutDatabaseProcessStaysUsable() {
        Bootstrap.Assembly assembly = assertDoesNotThrow(
                () -> Bootstrap.assembleWithResources(Config.empty()));

        assertFalse(assembly.databaseAssembled(), "未配置 hg.db.url 时不得装配数据库层");
        assertNull(assembly.pool(), "不应持有连接池");
        assertNull(assembly.factory(), "不应持有连接工厂");
        assertEquals(2, assembly.routeCount(), "未配库时应只注册平台级 ping 的 GET/HEAD");

        // 平台级能力照常
        assertEquals(200, dispatch(assembly, "GET", "/api/v1/ping", null).status);
        assertEquals(200, dispatch(assembly, "HEAD", "/api/v1/ping", null).status);

        // 业务路由不可达：/auth/login 属公开白名单，未注册 ⇒ 直达 404（区别于未认证的 10006）
        HttpResult login = dispatch(assembly, "POST", "/api/v1/auth/login", null);
        assertEquals(404, login.status, "未装配数据库时业务路由必须不可达");
    }

    @Test
    @DisplayName("无 hg.db.url：已认证请求访问业务路由同样 404（路由确实未注册）")
    void authenticatedBusinessRouteIs404WithoutDatabase() {
        Bootstrap.Assembly assembly = Bootstrap.assembleWithResources(Config.empty());
        assembly.sessionStore().save(new Session("S-1", 7L, "lisi", 1L,
                Clocks.now().plusMinutes(30)));

        Map<String, String> auth = Collections.singletonMap("X-HG-Session", "S-1");
        HttpResult r = dispatch(assembly, "GET", "/api/v1/config/item", auth);
        assertEquals(404, r.status, "业务路由未注册，认证后仍应 404（非 10006、非 405）");
        String body = new String(r.body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("404"), "应返回 404 文案，实际=" + body);

        // 对照：ping 仍可用
        assertEquals(200, dispatch(assembly, "GET", "/api/v1/ping", auth).status);
    }

    @Test
    @DisplayName("assemble 保持既有签名：返回分发器且 ping 可用（RequestDispatcherTest 依赖）")
    void assembleKeepsDispatcherContract() {
        RequestDispatcher dispatcher = Bootstrap.assemble(Config.empty());
        assertNotNull(dispatcher);
        assertEquals(200, dispatcher.dispatch(req("GET", "/api/v1/ping", null)).status);
    }

    // ------------------------------------------------------------------ 2. 有 DB：开关生效

    @Test
    @DisplayName("配了 hg.db.url 走数据库装配分支（本环境无 DM 驱动 ⇒ 以明确异常反证分支被执行）")
    void configuredDatabaseEntersAssemblyBranch() {
        System.setProperty(Config.KEY_DB_URL, "jdbc:dm://127.0.0.1:5236/HG");
        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Bootstrap.assembleWithResources(Config.empty()));
        assertTrue(e.getMessage().contains("预建连接失败") || e.getMessage().contains("驱动")
                        || e.getMessage().contains("连接"),
                "应由数据库访问失败抛出（证明数据库装配分支被走到），实际=" + e.getMessage());
    }

    // ------------------------------------------------------------------ 2b. 正分支：直接断言业务路由注册

    @Test
    @DisplayName("🔴 正分支直接断言：配了 hg.db.url（假驱动）时 6 个注册器真的被调用、84 条路由真的注册")
    void configuredDatabaseRegistersAllBusinessRoutes() {
        System.setProperty(Config.KEY_DB_URL, "jdbc:dm://127.0.0.1:5236/HG");
        System.setProperty(Config.KEY_DB_DRIVER, FakeJdbcDriver.class.getName());
        System.setProperty(Config.KEY_DB_USER, "hg_app");
        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");

        Bootstrap.Assembly assembly = assertDoesNotThrow(
                () -> Bootstrap.assembleWithResources(Config.empty()),
                "配了 hg.db.url 且驱动可用时，装配不得抛异常");
        try {
            assertTrue(assembly.databaseAssembled(), "配了 hg.db.url 应装配数据库层");
            assertNotNull(assembly.pool(), "应持有连接池");
            assertNotNull(assembly.factory(), "应持有连接工厂");

            // 精确断言（**非下界**）：82 条业务路由（含 T-109 补齐的 19 条 + T-010 补齐的 AUTH 15 条
            // + T-135 补齐的 CONFIG 停用 1 条 + T-050 补齐的 RISK 1 条）+ ping 的 GET/HEAD 2 条 = 84。
            // 若 registerBusinessRoutes(...) 被删除/短路，此处必红 —— 这是本用例的唯一目的。
            // 【T-010 订正（加注）】断言值由 67 订正为 82：订正理由 = 需求面（`ApiRoutes` 声明 AUTH 10 条
            //   路径、「声明面 ⊇ 注册面」缺口被补齐）——`AuthRoutes` 由 2 个 (方法,路径) 对补齐为 17
            //   （+15），非为过测试而放宽断言。计数明细（业务）= AUTH17/CONFIG17/PRODUCT13/SPOT15/PLAN18。
            // 【T-135 订正（加注）】断言值由 82 订正为 83：订正理由 = 需求面（`FR-CONFIG-7` 验收 3「停用
            //   条件不满足时停用被拒绝」要求可调用的停用端点；`ConfigService.disableSubAccount` 已实现但
            //   生产 0 调用）——`ConfigRoutes` 于既有已声明路径 `/config/sub-account` 上补注册 `PUT`
            //   （该路径已在 `ApiRoutes` CONFIG 清单内，未新增路径），CONFIG 由 17 对增为 18 对（+1），
            //   非为过测试而放宽断言。
            // 【T-050 订正（加注）】断言值由 83 订正为 84：订正理由 = 需求面（ApiRoutes 声明 RISK 模块
            //   /risk/mirror-status、ARCHITECTURE.md §3.11 J5 事中风控 REST 面）——RiskRoutes 于既有已声明
            //   路径上新增 1 个 (方法,路径) 对（GET），RISK 由 0 增为 1，业务路由 82
            //   （AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK1）+ ping 2 = 84。非为过测试而放宽断言。
            assertEquals(84, assembly.routeCount(),
                    "业务路由必须全部注册：82（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK1）+ ping 2");

            // 抽查 2 条具体业务路径：命中处理（非 404）即证明该路由已注册。
            // （不真发 HTTP：经 RequestDispatcher 进程内分发；handler 因无真实库而 4xx/5xx 皆可，
            //   唯一被排除的正是"未注册"时才会出现的 404。）
            HttpResult login = dispatch(assembly, "POST", "/api/v1/auth/login", null);
            assertNotEquals(404, login.status,
                    "POST /api/v1/auth/login 应已注册（非 404），实际=" + login.status);

            assembly.sessionStore().save(new Session("S-1", 7L, "lisi", 1L,
                    Clocks.now().plusMinutes(30)));
            HttpResult product = dispatch(assembly, "GET", "/api/v1/product",
                    Collections.singletonMap("X-HG-Session", "S-1"));
            assertNotEquals(404, product.status,
                    "GET /api/v1/product 应已注册（非 404），实际=" + product.status);

            // FR-PLAN-4 / CR-001 回归：GET /plan（列表）必须已注册。
            // 405 = 路径存在但 GET 未注册（正是 CR-001 的原始症状）；404 = 未注册。
            // 若删掉 PlanRoutes 中 "GET"+PlanHandler.PATH 的注册行，本断言必红。
            HttpResult planList = dispatch(assembly, "GET", "/api/v1/plan",
                    Collections.singletonMap("X-HG-Session", "S-1"));
            assertNotEquals(404, planList.status,
                    "GET /api/v1/plan 应已注册（非 404），实际=" + planList.status);
            assertNotEquals(405, planList.status,
                    "GET /api/v1/plan 不应 405（405=路径在而方法未注册，即 CR-001），实际="
                            + planList.status);

            // T-050 回归：GET /risk/mirror-status（事中风控 REST 面）必须已注册且可达（非 404）。
            // 可达性验证：经完整生产装配的 RequestDispatcher 进程内分发（非自建 Router）。
            HttpResult mirror = dispatch(assembly, "GET", "/api/v1/risk/mirror-status",
                    Collections.singletonMap("X-HG-Session", "S-1"));
            assertNotEquals(404, mirror.status,
                    "GET /api/v1/risk/mirror-status 应已注册（非 404），实际=" + mirror.status);
            assertNotEquals(405, mirror.status,
                    "GET /api/v1/risk/mirror-status 不应 405（路径在而方法未注册），实际=" + mirror.status);
        } finally {
            Bootstrap.close(assembly);
        }
    }

    // ------------------------------------------------------------------ 3. 配置映射与资源释放

    @Test
    @DisplayName("poolConfig：CFG-31 hg.db.pool.* 缺省取默认值，显式配置时被采纳")
    void poolConfigMapsCfg31Keys() {
        PoolConfig defaults = Bootstrap.poolConfig(Config.empty());
        assertEquals(PoolConfig.DEFAULT_INITIAL_SIZE, defaults.initialSize());
        assertEquals(PoolConfig.DEFAULT_MAX_SIZE, defaults.maxSize());
        assertEquals(PoolConfig.DEFAULT_BORROW_TIMEOUT_MILLIS, defaults.borrowTimeoutMillis());
        assertEquals(PoolConfig.DEFAULT_VALIDATION_TIMEOUT_SECONDS, defaults.validationTimeoutSeconds());

        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");
        System.setProperty(Config.KEY_DB_POOL_MAX, "4");
        System.setProperty(Config.KEY_DB_POOL_BORROW_TIMEOUT_MS, "1500");
        PoolConfig overridden = Bootstrap.poolConfig(Config.empty());
        assertEquals(1, overridden.initialSize());
        assertEquals(4, overridden.maxSize());
        assertEquals(1500, overridden.borrowTimeoutMillis());
        assertEquals(PoolConfig.DEFAULT_VALIDATION_TIMEOUT_SECONDS, overridden.validationTimeoutSeconds(),
                "校验超时固定取 PoolConfig.DEFAULT_VALIDATION_TIMEOUT_SECONDS");
    }

    @Test
    @DisplayName("close：无 DB 装配下幂等且空安全（可重复调用、可传 null）")
    void closeIsIdempotentAndNullSafe() {
        Bootstrap.Assembly assembly = Bootstrap.assembleWithResources(Config.empty());
        assertDoesNotThrow(() -> Bootstrap.close(assembly));
        assertDoesNotThrow(() -> Bootstrap.close(assembly));
        assertDoesNotThrow(() -> Bootstrap.close(null));
    }

    // ------------------------------------------------------------------ 工具

    private static HttpResult dispatch(Bootstrap.Assembly assembly, String method, String path,
                                       Map<String, String> headers) {
        return assembly.dispatcher().dispatch(req(method, path, headers));
    }

    private static RequestContext req(String method, String path, Map<String, String> headers) {
        return new RequestContext(method, path, null,
                headers == null ? Collections.emptyMap() : headers, "", "HGTEST", "127.0.0.1");
    }
}
