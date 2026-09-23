package com.hedgeguard.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-154}：**全量穷举**的路由可达性判据（消灭 {@code BUG-004} 类缺陷的复发可能）。
 *
 * <h2>待防缺陷（{@code BUG-004}，已由 {@code T-151} 修复）</h2>
 * {@link Router#match(String, String)} 按 {@code routes}（注册序）返回**首个命中**，且占位段
 * {@code {id}} 匹配**任意非空单段**。故若占位符路由注册在**同段数**静态路由之前，后者被
 * **静默吞并**（生产装配下不可达）。实测后果：{@code GET /product/{id}} 曾吞并
 * {@code /product/quota}、{@code /product/mapping}、{@code /product/change-log}、
 * {@code /product/export}（4 条），{@code GET /spot/exposure/{id}} 曾吞并
 * {@code /spot/exposure/export}（1 条）⇒ **共 5 端点生产不可达**。
 *
 * <h2>为什么既有判据无牙</h2>
 * <ul>
 *   <li>{@code ModuleRoutesConsistencyTest} 的四条不变量（不臆造 / 无重复 / 覆盖下限 / 路径归属）
 *       **无一条含「匹配 / 遮蔽」**，它把 {@code GET /product/{id}} 与 {@code GET /product/quota}
 *       **并列**列为「已注册」⇒ **注册 ≠ 可达**；</li>
 *   <li>{@code T109EndpointRegistrationTest} 只断言 {@code /spot/exposure/export} **已注册**；</li>
 *   <li>曾出现「**自建 {@code Router} 只注册被测 handler**」的复核手法 ⇒ 对遮蔽**结构性失明**。</li>
 * </ul>
 *
 * <h2>判据（实例路径回查）</h2>
 * 对**生产装配下已注册的每一条** {@code (方法, 模式)}：
 * <ol>
 *   <li>把模式中每个 {@code {...}} 占位段替换为一个确定非空段（{@code 1}）⇒ **实例路径**；</li>
 *   <li>断言 {@code match(方法, 实例路径).pattern()} **等于该模式自身**；</li>
 *   <li>断言命中的 {@code handler()} 非空。</li>
 * </ol>
 * 若某静态路由被更早注册的同段数占位符吞并，则其实例路径会命中**那个占位符的模式**（≠ 自身）
 * ⇒ 第 ② 条变红。该形态**天然覆盖** {@code GET}/{@code POST}/{@code PUT} 全部方法与
 * {@code PlanRoutes} 的两个动态循环（{@code PlanActionHandler.Action.values()} /
 * {@code PlanRatioHandler.Method.values()}），无需手工维护清单。
 *
 * <p><b>逐段对齐</b>：遮蔽只在「占位段与静态段**位于同一段位**」时发生。实例回查按段位取值，
 * 故形如 {@code /config/dispatch/{id}}（占位段 idx3）与 {@code GET /config/change-log}
 * （idx3 为静态 {@code change-log}，但两边**段数相同而段位不同**的那类）**不会**误报——
 * 这正是它优于「人工枚举路由对」之处：不依赖人判断「哪两条构成候选冲突」。
 *
 * <p><b>健全性前提（哨兵不得与字面段碰撞，{@code T-156} 显式化）</b>：实例路径把每个占位段替换为
 * 哨兵 {@link #INSTANCE_SEGMENT}（{@code 1}）。若某静态路由的某**字面段恰为哨兵**且注册在同段数占位符
 * **之前**，则该占位符路由的实例路径会命中**那个静态路由**（其 {@code pattern} ≠ 占位符模式）⇒
 * 判据**误报「被遮蔽」**（假阳性）。故判据的健全性依赖不变式「**字面段 ∌ 哨兵**」，该不变式由
 * {@link #sentinelDoesNotCollideWithLiteralSegments()} **机械断言**（违反即红，并提示更换哨兵）。
 *
 * <p><b>数字口径（{@code T-156} 据 {@code T-154} 复核观察 {@code O-1} 收口）</b>：本文件**不写**
 * 「朴素（naive）规则会产出 N 组 near-miss」这类**具体数字** —— 该数字随「naive 规则」的定义而变
 * （复核席对「同方法+同段数+早占位/晚字面」/「任一含占位」/「早含占位且晚全静态」三种口径分别得
 * 46 / 115 / 42，原自报的 47 无唯一可复现口径）。此处只保留**定性表述**：朴素「有占位符 + 同段数即
 * 报错」的规则确会产出**数十组假阳性**（而实例回查证实**真遮蔽 = 0**），这正是本判据采用
 * 「实例路径回查 + 逐段对齐」而非朴素规则的理由。
 *
 * <p>本文件**不重写** {@code RouteReachabilityBug004Test} 的 5 条自有断言（该文件保留为
 * {@code BUG-004} 的**回归留痕**）；本文件是其**全量穷举**升级，二者共用
 * {@link FullWebAssembly} 的同一份生产装配。
 */
class RouteReachabilityExhaustiveTest {

    /**
     * 实例回查中占位段的**取值**（哨兵段）。取 {@code 1}：确定性、可复现。
     *
     * <p><b>前提</b>：该哨兵**不得等于任何字面段**（否则会误报遮蔽，见类 javadoc）；由
     * {@link #sentinelDoesNotCollideWithLiteralSegments()} 断言。更换哨兵时须同步本常量。
     */
    static final String INSTANCE_SEGMENT = "1";

    @Test
    @DisplayName("装配规模：五模块生产装配已注册路由条数 == " + FullWebAssembly.ROUTE_COUNT
            + "（守卫：删路由会使穷举判据静默变宽）")
    void assemblySizeIsStable() {
        Router router = FullWebAssembly.assemble();
        List<String> routes = router.routes();
        assertFalse(routes.isEmpty(), "生产装配未注册任何路由，穷举判据将空转");
        assertEquals(FullWebAssembly.ROUTE_COUNT, routes.size(),
                "五模块生产装配已注册路由条数偏离期望值 ⇒ 若有增删请同步更新 FullWebAssembly.ROUTE_COUNT"
                        + "（勿盲目改大：先确认新路由的可达性判据仍成立）；实际清单=" + routes);
    }

    @Test
    @DisplayName("全量穷举：生产装配下每条已注册 (方法,模式) 经实例路径回查必须命中自身且 handler 非空")
    void everyRegisteredRouteMatchesItself() {
        Router router = FullWebAssembly.assemble();
        List<String> routes = router.routes();
        assertFalse(routes.isEmpty(), "生产装配未注册任何路由，穷举判据将空转");

        List<String> failures = new ArrayList<>();
        for (String entry : routes) {
            int sep = entry.indexOf(' ');
            String method = entry.substring(0, sep);
            String pattern = entry.substring(sep + 1);
            String instance = instantiate(pattern);

            Router.RouteMatch match = router.match(method, instance);
            if (match == null) {
                failures.add(entry + " → 实例路径 " + instance + " 未命中任何路由");
                continue;
            }
            if (!pattern.equals(match.pattern())) {
                failures.add(entry + " → 实例路径 " + instance + " 被**遮蔽**：实际命中 " + match.pattern());
                continue;
            }
            if (match.handler() == null) {
                failures.add(entry + " → 实例路径 " + instance + " 命中自身但 handler 为 null");
            }
        }
        assertTrue(failures.isEmpty(),
                "存在不可达 / 被遮蔽的路由（注册序须「先静态、后占位符」）：\n"
                        + String.join("\n", failures));
    }

    @Test
    @DisplayName("全覆盖自证：穷举面 == 装配清单（同一份 routes()），无遗漏")
    void exhaustiveSurfaceEqualsRegisteredSurface() {
        Router router = FullWebAssembly.assemble();
        Set<String> dumped = new LinkedHashSet<>(router.routes());
        // 逐条回查的输入就是 routes() 本身，此处固化「无遗漏」：条数与去重后条数一致，
        // 且每条都出现在清单中（Router 已保证不重复注册，此处守卫判据不会漏项）。
        assertEquals(router.routes().size(), dumped.size(),
                "已注册清单存在重复项 ⇒ 穷举面与装配面不一致");
        assertEquals(FullWebAssembly.ROUTE_COUNT, dumped.size(),
                "穷举面条数 ≠ 装配规模 ⇒ 判据可能漏项");
        for (String entry : dumped) {
            assertNotNull(entry, "装配清单含 null 项");
            assertTrue(entry.indexOf(' ') > 0, "装配清单项格式异常（应为 \"METHOD pattern\"）：" + entry);
        }
    }

    @Test
    @DisplayName("镜像方向：确实未注册的路径 match 必须返回 null（否则「恒返回非空」的实现也能全绿）")
    void unregisteredPathsAreNotMatched() {
        Router router = FullWebAssembly.assemble();

        // ① 段数相同但末段不匹配任何静态路由（5 段，与 /product/{id}/contracts 同段数）
        assertNull(router.match("GET", "/api/v1/product/1/no-such-segment"),
                "未注册路径却命中 ⇒ 判据过宽");
        // ② 未注册模块（TRADE 未纳入本装配）
        assertNull(router.match("GET", "/api/v1/trade/order"),
                "未注册模块 TRADE 却被命中 ⇒ 判据过宽");
        // ③ 未注册方法（DELETE 未在本装配注册于 /product）
        assertNull(router.match("DELETE", "/api/v1/product"),
                "未注册方法 DELETE 却被命中 ⇒ 判据过宽");
        // ④ 完全不存在的模块前缀
        assertNull(router.match("GET", "/api/v1/no-such-module/x"),
                "不存在的模块前缀却被命中 ⇒ 判据过宽");
    }

    @Test
    @DisplayName("判据非空转：人为「占位符在前、静态在后」⇒ 实例回查必检出遮蔽（红）")
    void detectorIsNotVacuous() {
        Router shadowed = new Router();
        shadowed.register("GET", "/api/v1/product/{id}", ctx -> null);   // 占位符在前
        shadowed.register("GET", "/api/v1/product/export", ctx -> null); // 静态在后 → 被吞并

        // 与主判据**完全同一机制**：对静态路由构造实例路径并回查。
        Router.RouteMatch hit = shadowed.match("GET", "/api/v1/product/export");
        assertNotNull(hit, "被遮蔽的静态路由仍应有命中（只是命中错的那个）");
        assertNotEquals("/api/v1/product/export", hit.pattern(),
                "本应检出遮蔽却回查为自身 ⇒ 主判据为空转");
        assertEquals("/api/v1/product/{id}", hit.pattern(),
                "遮蔽方应为先注册的占位符路由");
    }

    @Test
    @DisplayName("健全性：实例回查哨兵不得与任何字面段碰撞（否则误报「被遮蔽」⇒ 须更换哨兵）")
    void sentinelDoesNotCollideWithLiteralSegments() {
        List<String> routes = FullWebAssembly.assemble().routes();
        Set<String> literals = literalSegments(routes);
        assertFalse(literals.contains(INSTANCE_SEGMENT),
                "哨兵与字面段碰撞 ⇒ 请更换哨兵：哨兵=\"" + INSTANCE_SEGMENT + "\" 出现在字面段集合中；"
                        + "后果=某静态路由若注册在同段数占位符之前，该占位符的实例路径会命中它 ⇒ "
                        + "判据误报「被遮蔽」（假阳性，而非真缺陷）。全部字面段=" + literals);
    }

    /**
     * 由模式构造**实例路径**：每个 {@code {...}} 占位段替换为哨兵段 {@link #INSTANCE_SEGMENT}，
     * 其余段逐字保留。
     *
     * <p>取固定值而非随机值：确定性可复现。其健全性前提（哨兵 ∌ 字面段）由
     * {@link #sentinelDoesNotCollideWithLiteralSegments()} 机械断言。
     */
    private static String instantiate(String pattern) {
        String[] segments = pattern.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < segments.length; i++) {  // segments[0] == ""（前导 /）
            out.append('/');
            String seg = segments[i];
            out.append(isPlaceholder(seg) ? INSTANCE_SEGMENT : seg);
        }
        return out.toString();
    }

    /** 提取全部**字面段**（非 {@code {...}} 占位段；忽略前导/空段），用于哨兵碰撞断言。 */
    private static Set<String> literalSegments(List<String> entries) {
        Set<String> out = new LinkedHashSet<>();
        for (String entry : entries) {
            String pattern = entry.substring(entry.indexOf(' ') + 1);
            for (String seg : pattern.split("/", -1)) {
                if (!seg.isEmpty() && !isPlaceholder(seg)) {
                    out.add(seg);
                }
            }
        }
        return out;
    }

    /** 是否为占位段（形如 {@code {name}}，须独占整段）。与 {@code Router.matchSegments} 同判据。 */
    private static boolean isPlaceholder(String seg) {
        return seg.length() >= 2 && seg.startsWith("{") && seg.endsWith("}");
    }
}
