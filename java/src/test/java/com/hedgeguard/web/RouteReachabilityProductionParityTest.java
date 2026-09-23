package com.hedgeguard.web;

import com.hedgeguard.common.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-157}：**生产装配面 ↔ 判据装配面**的**集合级一致性断言**
 * （{@code T-153} 独立复核缺陷 {@code TE-T153-001}（中）的机械收口）。
 *
 * <h2>待防缺陷（{@code TE-T153-001}）</h2>
 * {@link FullWebAssembly} 的 {@code ROUTE_COUNT = 81} 与生产装配（{@link Bootstrap#assembleWithResources}）
 * 的 {@code 83} 是**两个互相独立的数**（差 {@code 2} = 平台级 {@code ping} 的 {@code GET}/{@code HEAD}），
 * 二者之间**无任何断言对齐**。后果：给生产装配新增第 6 个模块（{@code ApiRoutes} 已声明、尚未注册的
 * {@code TRADE}/{@code SETTLE}/{@code RISK}/{@code ACCT}/{@code FUND}/{@code AUDIT}，将在 M3/M4 接入
 * {@code Bootstrap}）而**忘记同步** {@code FullWebAssembly} 时，{@link RouteReachabilityExhaustiveTest}
 * 的穷举面**不含**该模块 ⇒ 该模块内的路由遮蔽**静默不报**。复核席实测：注入一个**含遮蔽形态**
 * （占位符在前、静态在后）的第 6 模块、但不改 {@code FullWebAssembly} ⇒ 该判据 **0 红（静默全绿）**，
 * 而同一判据对**真实生产装配**报 1 红。
 *
 * <h2>判据（集合级、双向差集 ∅）</h2>
 * <pre>
 *   businessRoutesOf(生产装配.routes())  ==  FullWebAssembly.assemble().routes()
 *   其中 businessRoutesOf(xs) = xs 去掉平台级 {"GET /api/v1/ping", "HEAD /api/v1/ping"}
 * </pre>
 * 集合语义（顺序无关）：{@code Router.routes()} 返回 {@code "METHOD pattern"} 字符串清单，本判据把它
 * 装入 {@link LinkedHashSet} 后比较 ⇒ **不依赖注册序**，也不依赖两边的遍历顺序。
 *
 * <p><b>差 2 是预期、不是放宽</b>：生产装配 = 本装配面 + 2 条平台级 {@code ping}（{@code Bootstrap} 在
 * 五模块注册器**之前**直接注册；3 段、最先、全字面，不属任何业务模块）。故判据**先从生产装配里显式
 * 减去**这 2 条，并另以**精确等值**断言 {@code routeCount() == ROUTE_COUNT + 2}；**不**把断言放宽为
 * 下界或包含关系 —— 任何**额外**或**缺失**的业务路由都会使双向差集非空 ⇒ 必红。
 *
 * <p><b>生产装配如何离线取得</b>：与 {@code BootstrapAssemblyTest} 同法 —— 以 {@link FakeJdbcDriver}
 * 令 {@code DataSourcePool} 预建连接成功，从而把 {@link Bootstrap#assembleWithResources} 跑到底
 * （**完全离线、不接触 DM8**）。{@code Bootstrap.Assembly} **未暴露** {@code routes()}
 * （只有 {@code routeCount()}），且本任务**不得改** {@code java/src/main/**}，故经**反射**读取
 * {@code RequestDispatcher} 的私有字段 {@code router}。反射目标若被改名/移除，本判据**响亮失败**，
 * 不会静默跳过。
 *
 * <p><b>纪律</b>：本文件**不改** {@code java/src/main/**}，亦不改 {@link FullWebAssembly} 的装配内容。
 * 若发现「生产装配面 vs {@code ApiRoutes} 声明面」本身不一致 ⇒ **报告，不自行修**。
 */
class RouteReachabilityProductionParityTest {

    /** 平台级路由（{@code Bootstrap} 直接注册、非业务模块）——须从生产装配面显式减去。 */
    private static final Set<String> PLATFORM_ROUTES =
            Set.of("GET " + ApiRoutes.full(ApiRoutes.PING), "HEAD " + ApiRoutes.full(ApiRoutes.PING));

    /**
     * 【{@code T-160}】合成「第 6 模块」路由（供非空转自测 {@link #parityDetectorIsNotVacuous()} 使用）。
     *
     * <p><b>必须不在 {@link ApiRoutes} 内</b>（{@code T-160} 收口）：若取真实模块路径（原实现用
     * {@code /trade/order} = {@code ApiRoutes.byModule()["TRADE"]} 的成员），则 {@code TRADE} 等模块在
     * M3/M4 接入并加入 {@link FullWebAssembly} 后，本自测会**重复注册**同一路径而抛
     * {@code IllegalStateException} ⇒ **以 ERROR 而非通过呈现**，反而误导维护者削弱/删除该自测。
     *
     * <p>取 {@code /probe/**}（探测专用前缀）：{@code ApiRoutes} 的 11 个模块路径均无 {@code probe} 段，
     * 故该合成路由**不随任何真实模块接入**而变真路由。
     */
    private static final String PROBE_MODULE_ROUTE = "/api/v1/probe/x";

    /** 假库装配所需的属性键（与 {@code BootstrapAssemblyTest} 同集合子集）。 */
    private static final String[] FAKE_DB_PROPERTIES = {
            Config.KEY_DB_URL,
            Config.KEY_DB_DRIVER,
            Config.KEY_DB_USER,
            Config.KEY_DB_POOL_INITIAL,
    };

    @BeforeEach
    @AfterEach
    void clearDbProperties() {
        for (String key : FAKE_DB_PROPERTIES) {
            System.clearProperty(key);
        }
    }

    @Test
    @DisplayName("装配面一致：生产装配 routes() 去 ping 后 == FullWebAssembly.routes() 集合（双向差集 ∅）")
    void productionAssemblyEqualsCriteriaAssemblyMinusPing() {
        System.setProperty(Config.KEY_DB_URL, "jdbc:dm://127.0.0.1:5236/HG");
        System.setProperty(Config.KEY_DB_DRIVER, FakeJdbcDriver.class.getName());
        System.setProperty(Config.KEY_DB_USER, "hg_app");
        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");

        Bootstrap.Assembly assembly = assembleProductionOffline();
        try {
            assertTrue(assembly.databaseAssembled(), "配了 hg.db.url 应装配数据库层");

            Set<String> production = businessRoutes(productionRouter(assembly).routes());
            Set<String> criteria = new LinkedHashSet<>(FullWebAssembly.assemble().routes());
            List<String> drift = symmetricDifference(production, criteria);

            assertTrue(drift.isEmpty(),
                    "生产装配面与判据装配面漂移（新增/删除模块须同步 FullWebAssembly.assemble() 与 "
                            + "ROUTE_COUNT；若确属预期变更，须先确认穷举判据仍覆盖新模块）：\n"
                            + "  差集（对称差）=" + drift + "\n"
                            + "  仅生产装配有（判据缺）=" + onlyIn(production, criteria) + "\n"
                            + "  仅判据装配有（生产缺）=" + onlyIn(criteria, production));

            // 「差 2」显式化：集合等式已蕴含条数等式，此处把「+平台级 ping」写成**精确等值**，
            // 以防他日有人把上面的集合断言放宽为下界/包含（届时本条仍钉住 83）。
            assertEquals(FullWebAssembly.ROUTE_COUNT + PLATFORM_ROUTES.size(), assembly.routeCount(),
                    "生产装配条数须精确 == FullWebAssembly.ROUTE_COUNT(" + FullWebAssembly.ROUTE_COUNT
                            + ") + 平台级 ping(" + PLATFORM_ROUTES.size() + ")，差 2 属预期、非放宽");
        } finally {
            Bootstrap.close(assembly);
        }
    }

    @Test
    @DisplayName("判据非空转：生产侧多一个「第 6 模块」路由 ⇒ 双向差集必非空（红）")
    void parityDetectorIsNotVacuous() {
        // 模拟「生产装配多一个模块、FullWebAssembly 不动」：判据装配面 + ping + 一条合成第 6 模块路由。
        // 【T-160】合成路径**不得**取 `ApiRoutes` 内的真实路径 —— 见 `PROBE_MODULE_ROUTE` 注释。
        Router productionLike = FullWebAssembly.assemble();
        String sixthModuleRoute = "GET " + PROBE_MODULE_ROUTE;
        productionLike.register("GET", PROBE_MODULE_ROUTE, ctx -> null);
        for (String platform : PLATFORM_ROUTES) {
            int sep = platform.indexOf(' ');
            productionLike.register(platform.substring(0, sep), platform.substring(sep + 1), ctx -> null);
        }

        Set<String> production = businessRoutes(productionLike.routes());
        Set<String> criteria = new LinkedHashSet<>(FullWebAssembly.assemble().routes());
        List<String> drift = symmetricDifference(production, criteria);

        assertFalse(drift.isEmpty(),
                "本应检出「生产装配多一个模块」却双向差集为空 ⇒ 主判据为空转");
        assertTrue(drift.contains(sixthModuleRoute),
                "差集须点名该第 6 模块路由 " + sixthModuleRoute + "，实际=" + drift);
    }

    // ------------------------------------------------------------------ 工具

    /** 生产装配（假驱动，离线）；装配失败即为缺陷 ⇒ 用 JUnit 的失败语义而非吞异常。 */
    private static Bootstrap.Assembly assembleProductionOffline() {
        try {
            return Bootstrap.assembleWithResources(Config.empty());
        } catch (RuntimeException e) {
            throw new AssertionError("假驱动下生产装配不应抛异常（本判据的离线前提）", e);
        }
    }

    /**
     * 从生产装配取回其 {@code Router}。
     *
     * <p>{@code Bootstrap.Assembly} 未暴露 {@code routes()}（仅 {@code routeCount()}），而本任务**不得改**
     * {@code java/src/main/**}，故反射读取 {@code RequestDispatcher.router}。目标不存在或不可访问时
     * **抛出**（判据必红、绝不静默跳过）。
     */
    private static Router productionRouter(Bootstrap.Assembly assembly) {
        try {
            Field field = RequestDispatcher.class.getDeclaredField("router");
            field.setAccessible(true);
            Object router = field.get(assembly.dispatcher());
            assertNotNull(router, "反射取得的路由器为 null");
            return (Router) router;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "无法经反射读取 RequestDispatcher.router ⇒ 本判据失效（须同步反射目标，勿静默跳过）", e);
        }
    }

    /** 业务装配面 = 已注册清单去掉平台级 ping。 */
    static Set<String> businessRoutes(List<String> routes) {
        Set<String> out = new LinkedHashSet<>(routes);
        out.removeAll(PLATFORM_ROUTES);
        return out;
    }

    /** 顺序无关的对称差（集合语义）。 */
    static List<String> symmetricDifference(Set<String> a, Set<String> b) {
        List<String> out = new ArrayList<>();
        out.addAll(onlyIn(a, b));
        out.addAll(onlyIn(b, a));
        return out;
    }

    /** 在 {@code source} 中而不在 {@code other} 中的项（保持 {@code source} 顺序）。 */
    private static List<String> onlyIn(Set<String> source, Set<String> other) {
        List<String> out = new ArrayList<>();
        for (String r : source) {
            if (!other.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }
}
