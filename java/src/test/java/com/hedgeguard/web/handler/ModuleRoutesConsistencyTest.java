package com.hedgeguard.web.handler;

import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.handler.auth.AuthRoutes;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.product.ProductRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

/**
 * {@code #java2} 各模块路由的**契约一致性**校验（REST 面的结构性证据，{@code T-008}~{@code T-016}）。
 *
 * <p>本用例只构建 {@link Router} 并注册（不触发任何业务调用，故服务依赖可传 {@code null}），
 * 固化四条不变量：
 * <ol>
 *   <li><b>不臆造路径</b>：已注册的每个路径都必须存在于 {@code ApiRoutes} 冻结清单
 *       （路径是冻结项：须先改 {@code ARCHITECTURE §4.1.1} 才能改代码，{@code NFR-MAINT-5}）；</li>
 *   <li><b>无重复注册</b>：同一 {@code (方法, 路径)} 重复注册会在启动期失败（{@code §1.4}）；</li>
 *   <li><b>覆盖下限</b>：本批显式登记的路由（AUTH 2 + CONFIG 4 + PRODUCT 6 + SPOT 10 + PLAN 5）必须真实注册；</li>
 *   <li><b>路径归属</b>：只允许本批五模块（{@code auth}/{@code config}/{@code product}/{@code plan}/{@code spot}）。</li>
 * </ol>
 */
class ModuleRoutesConsistencyTest {

    /** 本批交付的模块路径前缀（须为 {@code ApiRoutes} 清单的子集）。 */
    private static final List<String> MODULE_PREFIXES =
            Arrays.asList("/auth/", "/config/", "/product", "/plan", "/spot");

    /** 覆盖下限：AUTH 2 + CONFIG 4 + PRODUCT 6 + SPOT 10 + PLAN 5（含 {@code FR-PLAN-4} 的 {@code GET /plan}）。 */
    private static final List<String[]> REGISTERED = List.of(
            new String[]{"POST", ApiRoutes.full("/auth/login")},
            new String[]{"POST", ApiRoutes.full("/auth/logout")},
            new String[]{"GET", ApiRoutes.full("/config/item")},
            new String[]{"PUT", ApiRoutes.full("/config/item")},
            new String[]{"GET", ApiRoutes.full("/config/sub-account")},
            new String[]{"POST", ApiRoutes.full("/config/sub-account")},
            new String[]{"GET", ApiRoutes.full("/product")},
            new String[]{"POST", ApiRoutes.full("/product")},
            new String[]{"GET", ApiRoutes.full("/product/{id}")},
            new String[]{"POST", ApiRoutes.full("/product/whitelist")},
            new String[]{"GET", ApiRoutes.full("/product/quota")},
            new String[]{"POST", ApiRoutes.full("/product/quota")},
            new String[]{"POST", ApiRoutes.full("/spot/purchase")},
            new String[]{"POST", ApiRoutes.full("/spot/sales")},
            new String[]{"POST", ApiRoutes.full("/spot/inventory")},
            new String[]{"POST", ApiRoutes.full("/spot/wip")},
            new String[]{"POST", ApiRoutes.full("/spot/price")},
            new String[]{"GET", ApiRoutes.full("/spot/price")},
            new String[]{"POST", ApiRoutes.full("/spot/exposure")},
            new String[]{"GET", ApiRoutes.full("/spot/exposure")},
            new String[]{"GET", ApiRoutes.full("/spot/exposure/{id}")},
            new String[]{"POST", ApiRoutes.full("/spot/exposure/recalc")},
            new String[]{"GET", ApiRoutes.full("/plan")},
            new String[]{"POST", ApiRoutes.full("/plan")},
            new String[]{"GET", ApiRoutes.full("/plan/{id}")},
            new String[]{"POST", ApiRoutes.full("/plan/{id}/doc")},
            new String[]{"POST", ApiRoutes.full("/plan/{id}/trade-plans")});

    @Test
    @DisplayName("五模块注册路径全部 ∈ ApiRoutes；无重复注册；覆盖下限（AUTH/CONFIG/PRODUCT/SPOT/PLAN）满足")
    void registeredPathsAreContractual() {
        Router router = new Router();
        AuthRoutes.register(router, null, null);
        ConfigRoutes.register(router, null);
        ProductRoutes.register(router, null);
        SpotRoutes.register(router, null);
        PlanRoutes.register(router, null);

        List<String> registered = new ArrayList<>(router.routes());
        List<String> allPaths = ApiRoutes.all();

        // ① 不臆造路径
        for (String entry : registered) {
            String path = pathOf(entry);
            assertTrue(allPaths.contains(path),
                    "注册路径不在 ApiRoutes 冻结清单中（不得臆造路径）：" + entry);
        }
        // ② 无重复注册
        assertEquals(registered.size(), new HashSet<>(registered).size(),
                "存在重复注册：" + registered);
        // ③ 覆盖下限：本批显式登记的路由必须被真实注册
        for (String[] route : REGISTERED) {
            String key = route[0] + " " + route[1];
            assertTrue(registered.contains(key), "登记的路由未真正注册：" + key + "；实际=" + registered);
        }
        // ④ 归属：只允许本批五模块
        for (String entry : registered) {
            String path = pathOf(entry);
            boolean mine = MODULE_PREFIXES.stream()
                    .anyMatch(prefix -> path.startsWith(ApiRoutes.full(prefix)));
            assertTrue(mine, "路径归属应为本批五模块之一（" + MODULE_PREFIXES + "）：" + entry);
        }
        // ⑤ PLAN / SPOT 亦已铺开（REST 面结构证据）
        assertTrue(registered.stream()
                        .anyMatch(entry -> pathOf(entry).startsWith(ApiRoutes.full("/plan"))),
                "PLAN 模块尚未注册任何路由：" + registered);
        assertTrue(registered.stream()
                        .anyMatch(entry -> pathOf(entry).startsWith(ApiRoutes.full("/spot/"))),
                "SPOT 模块尚未注册任何路由：" + registered);
    }

    @Test
    @DisplayName("无孤儿 handler：声明了 PATH 常量的 handler 类必须被某个 *Routes 注册器引用")
    void noOrphanHandlers() throws IOException {
        Path handlerRoot = repoRoot().resolve("java/src/main/java/com/hedgeguard/web/handler");
        assertTrue(Files.isDirectory(handlerRoot), "缺少 handler 目录：" + handlerRoot);

        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(handlerRoot)) {
            javaFiles = walk.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        StringBuilder routesText = new StringBuilder();
        List<String> handlers = new ArrayList<>();
        for (Path file : javaFiles) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String simpleName = file.getFileName().toString().replace(".java", "");
            if (simpleName.endsWith("Routes")) {
                routesText.append(text).append('\n');
            } else if (text.contains("public static final String PATH")) {
                handlers.add(simpleName);
            }
        }
        assertTrue(!handlers.isEmpty(), "未发现任何声明 PATH 的 handler，疑似解析失效");

        List<String> orphans = new ArrayList<>();
        for (String handler : handlers) {
            if (routesText.indexOf(handler) < 0) {
                orphans.add(handler);
            }
        }
        assertTrue(orphans.isEmpty(),
                "存在**孤儿 handler**（声明了 PATH 但未被任何 *Routes 注册 ⇒ 死代码，且是"
                        + "「重复路由」的陷阱源；§1.4 禁止静默覆盖）：" + orphans);
    }

    /** 从 {@code "<METHOD> <pattern>"} 取路径部分。 */
    private static String pathOf(String entry) {
        return entry.substring(entry.indexOf(' ') + 1);
    }

    /** 从测试工作目录向上寻找仓库根（含 {@code docs/DATA_MODEL.md}）。 */
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
