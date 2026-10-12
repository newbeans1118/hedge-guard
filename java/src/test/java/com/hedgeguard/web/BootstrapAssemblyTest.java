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
    @DisplayName("🔴 正分支直接断言：配了 hg.db.url（假驱动）时业务注册器真的被调用、145 条路由真的注册")
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
            // 【T-046 订正（加注）】断言值由 84 订正为 87：订正理由 = 需求面（`ApiRoutes` 声明 TRADE 模块
            //   12 条路径；`ARCHITECTURE.md` §3.8 `TradeService` 交易执行主链）。本任务只注册交易执行
            //   最小写/动作面 3 条 POST（`/trade/order`、`/trade/order/{id}/confirm`、`/trade/order/{id}/cancel`），
            //   TRADE 由 0 增为 3，业务路由 85（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK1/TRADE3）+ ping 2 = 87。
            //   非为过测试而放宽断言。
            // 【T-047 订正（加注）】断言值由 87 订正为 98：订正理由 = 需求面（ApiRoutes 声明 SETTLE 模块
            //   12 条路径；ARCHITECTURE.md §3.8 SettleService 结算主链、FR-SETTLE-1~12/15/16）——本任务
            //   注册结算最小面 11 个 (方法,路径) 对（ctp-data / cycle[GET,POST] / cycle/{id}/reconcile /
            //   cycle/{id}/aggregate / cycle/{id}/close / difference / report / margin / stress-test / export），
            //   SETTLE 由 0 增为 11，业务路由 96（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK1/TRADE3/SETTLE11）
            //   + ping 2 = 98。monthly-check/quarterly-report（FR-SETTLE-13/14/17）归 T-048，本任务不注册。
            //   非为过测试而放宽断言。
            // 【T-049 订正（加注）】断言值由 98 订正为 113：订正理由 = 需求面（ApiRoutes 声明 FUND 模块
            //   12 条路径；ARCHITECTURE.md §3.8 FundService 资金管理主链、FR-FUND-1~17）——本任务
            //   注册资金管理最小面 15 个 (方法,路径) 对（ledger[GET,POST] / flow / deposit / withdraw /
            //   {id}/review / receipt / transfer[GET,POST] / transfer/{id}/approve / quota[GET,POST] /
            //   margin / reconcile / export），FUND 由 0 增为 15，业务路由 111
            //   （AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK1/TRADE3/SETTLE11/FUND15）+ ping 2 = 113。
            //   非为过测试而放宽断言。
            // 【T-048 订正（加注）】断言值由 113 订正为 117：订正理由 = 需求面（{@code ApiRoutes} 声明 SETTLE
            //   模块 /settle/monthly-check、/settle/quarterly-report；{@code FR-SETTLE-13/14/17}、
            //   ARCHITECTURE.md §6.14）——SettleCheckRoutes 于既有已声明路径上各注册 GET/POST（4 对），
            //   SETTLE 由 11 增为 15，业务路由 115（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK1/TRADE3/
            //   SETTLE15/FUND15）+ ping 2 = 117。非为过测试而放宽断言。
            // 【T-063 订正（加注）】断言值由 117 订正为 119：订正理由 = 需求面（{@code ApiRoutes} 声明 RISK
            //   模块 /risk/rule，{@code ARCHITECTURE.md §4.1.1} 行 639 方法 GET/POST；{@code FR-CONFIG-5}
            //   验收 1「风控阈值集中在一个配置界面可维护」）——RiskRuleRoutes 于既有已声明路径上注册 GET/POST
            //   （2 对），RISK 由 1 增为 3，业务路由 117（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK3/TRADE3/
            //   SETTLE15/FUND15）+ ping 2 = 119。非为过测试而放宽断言。
            // 【T-067 订正（加注）】断言值由 119 订正为 127：订正理由 = 需求面（{@code ApiRoutes} 声明 AUDIT
            //   模块 /audit/trace/{forward,backward,integrity}、/audit/operation-log，{@code FR-AUDIT-1/2/3/11}、
            //   {@code ARCHITECTURE.md §6.11}）——AuditTraceRoutes 于既有已声明路径上各注册 GET/POST（8 对），
            //   AUDIT 由 0 增为 8，业务路由 125（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK3/TRADE3/
            //   SETTLE15/FUND15/AUDIT8）+ ping 2 = 127。非为过测试而放宽断言。
            // 【T-065 订正（加注）】断言值由 127 订正为 129：订正理由 = 需求面（{@code ApiRoutes} 声明 RISK
            //   模块 /risk/alert、/risk/alert/{id}/dispose，{@code FR-RISK-19}/{@code FR-RISK-15/16}、
            //   {@code ARCHITECTURE.md §4.1.1} 行 639）——RiskAlertRoutes 于既有已声明路径上注册 GET
            //   与 POST（2 对），RISK 由 3 增为 5，业务路由 127（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/
            //   RISK5/TRADE3/SETTLE15/FUND15/AUDIT8）+ ping 2 = 129。非为过测试而放宽断言。
            // 【T-068 订正（加注）】断言值由 129 订正为 131：订正理由 = 需求面（{@code ApiRoutes} 声明 AUDIT
            //   模块 /audit/violation-screen，{@code FR-AUDIT-4}、{@code ARCHITECTURE.md §6.11}）——
            //   ViolationScreenRoutes 于该既有已声明路径上注册 GET 与 POST（2 对），AUDIT 由 8 增为 10，
            //   业务路由 129（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK5/TRADE3/SETTLE15/FUND15/AUDIT10
            //   ）+ ping 2 = 131。非为过测试而放宽断言。
            // 【T-066 订正（加注）】断言值由 131 订正为 133：订正理由 = 需求面（{@code ApiRoutes} 声明 RISK
            //   模块 /risk/limit-usage，{@code FR-RISK-4/5/6} 红线复算 + {@code FR-RISK-18} 限额使用统计、
            //   {@code ARCHITECTURE.md §4.1.1}）——RiskLimitUsageRoutes 于该既有已声明路径上注册 GET 与 POST
            //   （2 对），RISK 由 5 增为 7，业务路由 131（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK7/
            //   TRADE3/SETTLE15/FUND15/AUDIT10）+ ping 2 = 133。非为过测试而放宽断言。
            // 【T-069 订正（加注）】断言值由 133 订正为 145：订正理由 = 需求面（{@code ApiRoutes} 路径级
            //   声明 AUDIT 模块 /audit/report/{quarterly,annual,major-event}、/audit/evidence/export、
            //   /audit/log、/audit/log/verify，{@code FR-AUDIT-5/6/7/12}、{@code FR-AUDIT-8}、
            //   {@code FR-AUDIT-9/10}；独立复核 TE-T069-001「三组 Routes 零调用」缺口）——
            //   AuditReportRoutes（6 对）/ EvidenceExportRoutes（2 对）/ AuditLogRoutes（4 对）注册进
            //   生产装配，AUDIT 由 10 增为 22（+12 对、+6 路径），业务路由 143（AUTH17/CONFIG18/PRODUCT13/
            //   SPOT15/PLAN18/RISK7/TRADE3/SETTLE15/FUND15/AUDIT22）+ ping 2 = 145。非为过测试而放宽断言。
            // 【T-194 订正（加注）】断言值由 145 订正为 147：订正理由 = 需求面（{@code FR-AUDIT-13} 验收
            //   1/2/5，{@code AUDIT.md:282}「查询某月核对任务，可见三方签署记录」；独立复核
            //   {@code te-verify-t071} 观察 {@code O-1}：{@code CheckTaskQueryService.query/overdue}
            //   实存但 {@code web/} 零 handler 调用 ⇒ 月度查询无 REST 端点）——
            //   CheckTaskQueryRoutes 于新增声明路径 /audit/check-tasks、/audit/check-tasks/overdue
            //   注册 GET（2 对），AUDIT 由 22 增为 24（+2 对、+2 路径），业务路由 145（AUTH17/CONFIG18/
            //   PRODUCT13/SPOT15/PLAN18/RISK7/TRADE3/SETTLE15/FUND15/AUDIT24）+ ping 2 = 147。
            //   非为过测试而放宽断言。
            assertEquals(147, assembly.routeCount(),
                    "业务路由必须全部注册：145（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK7/TRADE3/"
                            + "SETTLE15/FUND15/AUDIT24）+ ping 2");

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

            // T-069（TE-T069-001 修复）回归：审计三组面必须已注册且可达（非 404/405）。
            // 经完整生产装配的 RequestDispatcher 进程内分发（真实装配链，非自建 Router）。
            for (String[] auditRoute : new String[][]{
                    {"GET", "/api/v1/audit/log"},
                    {"GET", "/api/v1/audit/log/verify"},
                    {"GET", "/api/v1/audit/report/quarterly"},
                    {"GET", "/api/v1/audit/report/annual"},
                    {"GET", "/api/v1/audit/report/major-event"},
                    {"GET", "/api/v1/audit/evidence/export"}}) {
                HttpResult audit = dispatch(assembly, auditRoute[0], auditRoute[1],
                        Collections.singletonMap("X-HG-Session", "S-1"));
                assertNotEquals(404, audit.status,
                        auditRoute[0] + " " + auditRoute[1] + " 应已注册（非 404），实际=" + audit.status);
                assertNotEquals(405, audit.status,
                        auditRoute[0] + " " + auditRoute[1] + " 不应 405（路径在而方法未注册），实际="
                                + audit.status);
            }
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
