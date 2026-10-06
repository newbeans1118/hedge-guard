package com.hedgeguard.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST 资源清单（{@code ARCHITECTURE.md} §4.1.1 的代码镜像，<b>冻结</b>）。
 *
 * <p>本类提供<b>两级</b>声明：
 * <ol>
 *   <li><b>路径级</b>（{@link #byModule()} / {@link #all()}）：§4.1.1 资源表的完整镜像，
 *       含<b>已交付</b>与<b>后续里程碑（M3/M4）待交付</b>的模块/资源（如 {@code AUDIT}/{@code ACCT}
 *       及 {@code TRADE}/{@code RISK} 的未接入路径）。用于「不得臆造路径」的子集核对；</li>
 *   <li><b>(方法, 路径) 对级</b>（{@link #byModuleRoutes()} / {@link #allRoutes()}）：<b>当前已交付并注册</b>
 *       的冻结对，唯一事实源。判据 {@code ModuleRoutesConsistencyTest} 断言
 *       「运行时已注册的 (方法,路径) 集合 == 本声明集合」——<b>等值</b>（非 {@code ⊆}），
 *       同时防<b>退化</b>（少注册即红）与<b>漂移</b>（多注册即红 / 同步本类即自动跟随）。</li>
 * </ol>
 *
 * <p>用途：① 作为各模块注册路由的<b>唯一路径来源</b>（避免各模块自行拼字符串导致漂移）；
 * ② 供判据核对「实现已注册的 (方法,路径) 集合 == 本清单声明集合」。
 *
 * <p>方法语义以 {@code ARCHITECTURE.md} §4.1.1 表为准；§4.1.1 的方法列为<b>模块级粗粒度</b>
 * （如「GET/POST/PUT」），故对级声明由实现侧收束为<b>逐路径逐方法</b>的精确冻结对。
 * 基础路径 {@code /api/v1}。
 *
 * <p><b>变更纪律</b>：路径/方法的新增与改名须先改 {@code ARCHITECTURE.md} §4.1.1，再改本类
 * （{@code NFR-MAINT-5}）。
 */
public final class ApiRoutes {

    /** REST 基础路径。 */
    public static final String BASE = "/api/v1";

    /** 模块 → 资源路径（§4.1.1 完整镜像；含后续里程碑待交付模块）。 */
    private static final Map<String, List<String>> BY_MODULE = new LinkedHashMap<>();

    /** 模块 → 已交付的 {@code "METHOD /module-relative-path"} 冻结对（运行时注册的唯一事实源）。 */
    private static final Map<String, List<String>> BY_MODULE_ROUTES = new LinkedHashMap<>();

    static {
        BY_MODULE.put("PRODUCT", Arrays.asList(
                "/product", "/product/{id}", "/product/{id}/contracts", "/product/mapping",
                "/product/whitelist", "/product/quota", "/product/change-log", "/product/import",
                "/product/export"));
        BY_MODULE.put("SPOT", Arrays.asList(
                "/spot/purchase", "/spot/sales", "/spot/production", "/spot/wip", "/spot/inventory",
                "/spot/price", "/spot/price/import", "/spot/price/export", "/spot/exposure",
                "/spot/exposure/{id}", "/spot/exposure/recalc", "/spot/exposure/export",
                "/spot/doc/sub-account"));
        BY_MODULE.put("PLAN", Arrays.asList(
                "/plan", "/plan/{id}", "/plan/{id}/bind-exposure", "/plan/{id}/doc",
                "/plan/{id}/submit", "/plan/{id}/approve", "/plan/{id}/reject", "/plan/{id}/withdraw",
                "/plan/{id}/freeze", "/plan/{id}/unfreeze", "/plan/{id}/terminate", "/plan/{id}/extend",
                "/plan/{id}/trade-plans", "/plan/ratio/simple", "/plan/ratio/minvar",
                "/plan/ratio/custom"));
        BY_MODULE.put("TRADE", Arrays.asList(
                "/trade/order", "/trade/order/{id}/confirm", "/trade/order/{id}/cancel",
                "/trade/order-report", "/trade/fill", "/trade/position", "/trade/reason",
                "/trade/reject-record", "/trade/flow", "/trade/quote/{instrument}",
                "/trade/strategy", "/trade/export"));
        BY_MODULE.put("SETTLE", Arrays.asList(
                "/settle/ctp-data", "/settle/cycle", "/settle/cycle/{id}/reconcile",
                "/settle/cycle/{id}/aggregate", "/settle/cycle/{id}/close", "/settle/difference",
                "/settle/report", "/settle/margin", "/settle/stress-test", "/settle/monthly-check",
                "/settle/quarterly-report", "/settle/export"));
        BY_MODULE.put("RISK", Arrays.asList(
                "/risk/dashboard", "/risk/alert", "/risk/alert/{id}/dispose", "/risk/limit-open",
                "/risk/force-close-hint", "/risk/violation", "/risk/limit-usage", "/risk/mirror-status",
                "/risk/rule", "/risk/post-report"));
        BY_MODULE.put("AUDIT", Arrays.asList(
                "/audit/trace/forward", "/audit/trace/backward", "/audit/trace/integrity",
                "/audit/violation-screen", "/audit/report/quarterly", "/audit/report/annual",
                "/audit/report/major-event", "/audit/evidence/export", "/audit/log",
                "/audit/log/verify", "/audit/operation-log"));
        BY_MODULE.put("ACCT", Arrays.asList(
                "/acct/hedge-relation", "/acct/effectiveness", "/acct/rebalance", "/acct/voucher",
                "/acct/voucher/{id}/review", "/acct/voucher/{id}/reverse", "/acct/period",
                "/acct/period/{id}/close", "/acct/effect", "/acct/report/dual-pnl",
                "/acct/selfcheck", "/acct/export"));
        BY_MODULE.put("FUND", Arrays.asList(
                "/fund/ledger", "/fund/flow", "/fund/deposit", "/fund/withdraw", "/fund/{id}/review",
                "/fund/transfer", "/fund/transfer/{id}/approve", "/fund/receipt", "/fund/quota",
                "/fund/margin", "/fund/reconcile", "/fund/export"));
        BY_MODULE.put("AUTH", Arrays.asList(
                "/auth/login", "/auth/logout", "/auth/user", "/auth/role", "/auth/position-group",
                "/auth/authorization", "/auth/sub-account-perm", "/auth/change-log",
                "/auth/unauthorized-attempt", "/auth/password"));
        BY_MODULE.put("CONFIG", Arrays.asList(
                "/config/item", "/config/ctp", "/config/ctp/test", "/config/sub-account",
                "/config/dict", "/config/subject", "/config/report-period", "/config/dispatch/{id}",
                "/config/change-log", "/config/import", "/config/export"));

        BY_MODULE_ROUTES.put("PRODUCT", Arrays.asList(
                "GET /product", "POST /product", "POST /product/whitelist",
                "GET /product/quota", "POST /product/quota",
                "GET /product/{id}/contracts", "POST /product/{id}/contracts",
                "GET /product/mapping", "POST /product/mapping",
                "GET /product/change-log", "POST /product/import", "GET /product/export",
                "GET /product/{id}"));
        BY_MODULE_ROUTES.put("SPOT", Arrays.asList(
                "POST /spot/purchase", "POST /spot/sales", "POST /spot/inventory",
                "POST /spot/wip", "POST /spot/price", "GET /spot/price",
                "POST /spot/exposure", "GET /spot/exposure", "POST /spot/exposure/recalc",
                "POST /spot/production", "POST /spot/price/import", "GET /spot/price/export",
                "GET /spot/exposure/export", "POST /spot/doc/sub-account",
                "GET /spot/exposure/{id}"));
        BY_MODULE_ROUTES.put("PLAN", Arrays.asList(
                "GET /plan", "POST /plan", "GET /plan/{id}", "POST /plan/{id}/doc",
                "POST /plan/{id}/submit", "POST /plan/{id}/approve", "POST /plan/{id}/reject",
                "POST /plan/{id}/withdraw", "POST /plan/{id}/freeze", "POST /plan/{id}/unfreeze",
                "POST /plan/{id}/terminate", "POST /plan/{id}/extend",
                "GET /plan/{id}/trade-plans", "POST /plan/{id}/trade-plans",
                "POST /plan/ratio/simple", "POST /plan/ratio/minvar", "POST /plan/ratio/custom",
                "POST /plan/{id}/bind-exposure"));
        BY_MODULE_ROUTES.put("TRADE", Arrays.asList(
                "POST /trade/order", "POST /trade/order/{id}/confirm",
                "POST /trade/order/{id}/cancel"));
        BY_MODULE_ROUTES.put("SETTLE", Arrays.asList(
                "POST /settle/ctp-data", "GET /settle/cycle", "POST /settle/cycle",
                "POST /settle/cycle/{id}/reconcile", "POST /settle/cycle/{id}/aggregate",
                "POST /settle/cycle/{id}/close", "PUT /settle/difference", "GET /settle/report",
                "GET /settle/margin", "POST /settle/stress-test",
                "GET /settle/monthly-check", "POST /settle/monthly-check",
                "GET /settle/quarterly-report", "POST /settle/quarterly-report",
                "GET /settle/export"));
        BY_MODULE_ROUTES.put("RISK", Arrays.asList("GET /risk/mirror-status"));
        BY_MODULE_ROUTES.put("FUND", Arrays.asList(
                "GET /fund/ledger", "POST /fund/ledger", "GET /fund/flow", "POST /fund/deposit",
                "POST /fund/withdraw", "POST /fund/{id}/review", "POST /fund/transfer",
                "GET /fund/transfer", "POST /fund/transfer/{id}/approve", "POST /fund/receipt",
                "GET /fund/quota",
                "POST /fund/quota", "GET /fund/margin",
                "POST /fund/reconcile", "GET /fund/export"));
        BY_MODULE_ROUTES.put("AUTH", Arrays.asList(
                "POST /auth/login", "POST /auth/logout",
                "GET /auth/user", "POST /auth/user", "PUT /auth/user",
                "GET /auth/role", "POST /auth/role",
                "GET /auth/position-group", "POST /auth/position-group",
                "GET /auth/authorization", "POST /auth/authorization",
                "GET /auth/sub-account-perm", "POST /auth/sub-account-perm",
                "GET /auth/change-log", "GET /auth/unauthorized-attempt",
                "POST /auth/password", "PUT /auth/password"));
        BY_MODULE_ROUTES.put("CONFIG", Arrays.asList(
                "GET /config/item", "PUT /config/item",
                "GET /config/ctp", "POST /config/ctp", "POST /config/ctp/test",
                "GET /config/sub-account", "POST /config/sub-account", "PUT /config/sub-account",
                "GET /config/dict", "POST /config/dict",
                "GET /config/subject", "POST /config/subject",
                "GET /config/report-period", "POST /config/report-period",
                "GET /config/dispatch/{id}", "GET /config/change-log",
                "POST /config/import", "GET /config/export"));
    }

    /** 平台级公开资源（不属于 11 个业务模块）。 */
    public static final String PING = "/ping";

    /** 平台级冻结 {@code (方法,路径)} 对（{@code Bootstrap} 直接注册）。 */
    private static final List<String> PLATFORM_ROUTES =
            Arrays.asList("GET " + PING, "HEAD " + PING);

    private ApiRoutes() {
    }

    /**
     * 模块 → 资源路径（只读，§4.1.1 完整镜像）。
     *
     * @return 11 个模块的路径清单（含后续里程碑待交付模块）
     */
    public static Map<String, List<String>> byModule() {
        return Collections.unmodifiableMap(BY_MODULE);
    }

    /**
     * 模块 → 已交付的 {@code "METHOD /module-relative-path"} 冻结对（只读）。
     *
     * <p>仅含<b>当前已注册</b>的模块（{@code AUTH}/{@code CONFIG}/{@code PRODUCT}/{@code SPOT}/
     * {@code PLAN}/{@code TRADE}/{@code SETTLE}/{@code RISK}/{@code FUND}）；后续里程碑模块
     * （{@code AUDIT}/{@code ACCT}）及未接入路径仅在 {@link #byModule()} 的路径级声明中列出。
     *
     * @return 模块 → {@code "METHOD /module-relative-path"} 清单
     */
    public static Map<String, List<String>> byModuleRoutes() {
        return Collections.unmodifiableMap(BY_MODULE_ROUTES);
    }

    /**
     * 拼完整路径。
     *
     * @param modulePath 模块内路径（如 {@code /product/{id}}）
     * @return 完整路径（如 {@code /api/v1/product/{id}}）
     */
    public static String full(String modulePath) {
        return BASE + modulePath;
    }

    /** 全部路径（含平台级）的只读清单。 */
    public static List<String> all() {
        List<String> out = new ArrayList<>();
        out.add(BASE + PING);
        for (List<String> paths : BY_MODULE.values()) {
            for (String p : paths) {
                out.add(BASE + p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 全部冻结 {@code (方法,路径)} 对的只读清单（含平台级），形如
     * {@code "GET /api/v1/product"}——与 {@code Router.routes()} 同形。
     *
     * <p>这是「运行时已注册集合 == 本声明集合」等值判据（{@code ModuleRoutesConsistencyTest}）的右端。
     *
     * @return 全部 {@code "METHOD /api/v1/..."} 冻结对
     */
    public static List<String> allRoutes() {
        List<String> out = new ArrayList<>();
        for (String p : PLATFORM_ROUTES) {
            int sp = p.indexOf(' ');
            out.add(p.substring(0, sp) + " " + BASE + p.substring(sp + 1));
        }
        for (List<String> pairs : BY_MODULE_ROUTES.values()) {
            for (String pair : pairs) {
                int sp = pair.indexOf(' ');
                out.add(pair.substring(0, sp) + " " + BASE + pair.substring(sp + 1));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
