package com.hedgeguard.web.handler;

import com.hedgeguard.risk_intraday.RiskRoutes;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.handler.audit.AuditLogRoutes;
import com.hedgeguard.web.handler.audit.AuditReportRoutes;
import com.hedgeguard.web.handler.audit.AuditTraceRoutes;
import com.hedgeguard.web.handler.audit.EvidenceExportRoutes;
import com.hedgeguard.web.handler.audit.ViolationScreenRoutes;
import com.hedgeguard.web.handler.auth.AuthRoutes;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.fund.FundRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.product.ProductRoutes;
import com.hedgeguard.web.handler.risk.RiskAlertRoutes;
import com.hedgeguard.web.handler.risk.RiskLimitUsageRoutes;
import com.hedgeguard.web.handler.risk.RiskRuleRoutes;
import com.hedgeguard.web.handler.settle.SettleCheckRoutes;
import com.hedgeguard.web.handler.settle.SettleRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;
import com.hedgeguard.web.handler.trade.TradeRoutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各模块路由的**契约一致性**校验（REST 面的结构性证据）。
 *
 * <p>本用例只构建 {@link Router} 并注册（不触发任何业务调用，故服务依赖可传 {@code null}），
 * 以<b>生产装配同构</b>的方式重放全部已交付模块的注册器，固化两条不变量：
 * <ol>
 *   <li><b>(方法,路径) 等值</b>（{@code T-097}）：运行时已注册的 {@code "METHOD /api/v1/..."} 集合
 *       <b>等于</b> {@link ApiRoutes#allRoutes()} 声明集合——<b>等值</b>（非 {@code ⊆}）：
 *       <ul>
 *         <li><b>防退化</b>：声明了却少注册一条即红（保 {@code GAP-1} 的保护，且覆盖<b>方法级</b>
 *             缺口——如历史 {@code FR-PLAN-4} 的 {@code GET /plan} 缺失）；</li>
 *         <li><b>防漂移</b>：注册了却未声明（臆造 / 忘同步声明）即红；同步 {@code ApiRoutes}
 *             即自动跟随（本判据<b>不含</b>任何硬编码路由常量）；</li>
 *       </ul></li>
 *   <li><b>无重复注册</b>：同一 {@code (方法, 路径)} 重复注册会在启动期失败（{@code §1.4}）。</li>
 * </ol>
 *
 * <p><b>装配范围</b>：与生产装配（{@code Bootstrap#registerBusinessRoutes} + 平台级 {@code ping}）
 * 同构——{@code AUTH}/{@code CONFIG}/{@code PRODUCT}/{@code SPOT}/{@code PLAN}/{@code RISK}/
 * {@code TRADE}/{@code SETTLE}（含 {@code SettleCheckRoutes}）/{@code FUND}/
 * {@code AUDIT}（T-067 追溯留痕、T-068 违规筛查、T-069 报表/证据链导出/审计日志）十模块注册器 +
 * {@code ping} 的 {@code GET}/{@code HEAD}。未交付模块（{@code ACCT}）不在
 * {@link ApiRoutes#allRoutes()} 内，故亦不参与等值比较（其路径级声明见 {@link ApiRoutes#byModule()}）。
 */
class ModuleRoutesConsistencyTest {

    /** 以生产装配同构方式重放全部已交付模块注册器（服务依赖传 {@code null}）。 */
    private static Router assemble() {
        Router router = new Router();
        router.register("GET", ApiRoutes.full(ApiRoutes.PING),
                new com.hedgeguard.web.handler.PingHandler());
        router.register("HEAD", ApiRoutes.full(ApiRoutes.PING),
                new com.hedgeguard.web.handler.PingHandler());
        AuthRoutes.register(router, null, null);
        ConfigRoutes.register(router, null);
        ProductRoutes.register(router, null);
        SpotRoutes.register(router, null);
        PlanRoutes.register(router, null);
        RiskRoutes.register(router, null, null);
        // T-063：风控阈值配置面（GET|POST /api/v1/risk/rule）与生产装配同构重放。
        RiskRuleRoutes.register(router, null);
        // T-065：风控告警查询与处置面（GET /risk/alert、POST /risk/alert/{id}/dispose）
        RiskAlertRoutes.register(router, null);
        // T-066：红线复算与限额使用统计面（GET|POST /risk/limit-usage）
        RiskLimitUsageRoutes.register(router, null);
        TradeRoutes.register(router, null);
        SettleRoutes.register(router, null);
        SettleCheckRoutes.register(router, null);
        FundRoutes.register(router, null);
        // T-067：审计追溯与留痕面（GET|POST /api/v1/audit/trace/* 与 /audit/operation-log）与生产装配同构重放。
        AuditTraceRoutes.register(router, null);
        // T-068：违规事项筛查面（GET|POST /api/v1/audit/violation-screen）与生产装配同构重放。
        ViolationScreenRoutes.register(router, null);
        // T-069：审计报表/证据链导出/审计日志三组面（TE-T069-001 修复后已注册生产装配）同构重放。
        AuditReportRoutes.register(router, null);
        EvidenceExportRoutes.register(router, null);
        AuditLogRoutes.register(router, null);
        return router;
    }

    @Test
    @DisplayName("等值断言：运行时已注册 (方法,路径) 集合 == ApiRoutes 声明集合（含平台级 ping）")
    void registeredRoutesEqualDeclaration() {
        List<String> registered = new ArrayList<>(assemble().routes());

        // ① 无重复注册（Router 自身在重注册时抛；此处再固化一次）
        assertEquals(registered.size(), new HashSet<>(registered).size(),
                "存在重复注册：" + registered);

        Set<String> runtime = new HashSet<>(registered);
        Set<String> declared = new HashSet<>(ApiRoutes.allRoutes());

        // ② 双向差集须为空 —— 否则给出可定位的「缺 / 多」清单
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(runtime);
        Set<String> extra = new TreeSet<>(runtime);
        extra.removeAll(declared);
        assertEquals(Collections.emptySet(), missing,
                "声明但未注册（退化；含方法级缺口）：" + missing);
        assertEquals(Collections.emptySet(), extra,
                "已注册但未声明（漂移 / 臆造路径）：" + extra);

        // ③ 集合等值（在双向差集 ∅ 之上二次固化，语义自明）
        assertEquals(declared, runtime,
                "运行时已注册 (方法,路径) 集合必须与 ApiRoutes 声明集合等值");
    }

    @Test
    @DisplayName("声明自洽：对级声明的路径 ∈ 路径级声明；对级只声明已交付注册模块")
    void pairDeclarationsAreConsistent() {
        for (java.util.Map.Entry<String, List<String>> e : ApiRoutes.byModuleRoutes().entrySet()) {
            String module = e.getKey();
            List<String> declaredPaths = ApiRoutes.byModule().get(module);
            assertTrue(declaredPaths != null && !declaredPaths.isEmpty(),
                    "对级声明了模块却无路径级声明：" + module);
            for (String pair : e.getValue()) {
                int sp = pair.indexOf(' ');
                assertTrue(sp > 0, "对级声明格式非法（应为 \"METHOD /path\"）：" + pair);
                String path = pair.substring(sp + 1);
                assertTrue(declaredPaths.contains(path),
                        "对级声明的路径不在路径级声明中（臆造）：" + pair
                                + "；模块 " + module + " 路径级声明=" + declaredPaths);
            }
        }
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
