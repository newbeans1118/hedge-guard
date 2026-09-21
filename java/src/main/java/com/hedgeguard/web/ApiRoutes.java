package com.hedgeguard.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST 资源清单（{@code ARCHITECTURE.md} §4.1.1 的代码镜像，<b>冻结</b>）。
 *
 * <p>用途：① 作为 {@code #java2} 各模块注册路由的<b>唯一路径来源</b>（避免各模块自行拼字符串导致漂移）；
 * ② 供自检脚本核对"实现已注册的路由 ⊆ 本清单"。
 *
 * <p>方法语义以 {@code ARCHITECTURE.md} §4.1.1 表为准（本类只固定路径）：
 * 查询 / 新增或动作 / 修改 / 草稿类物理删除。基础路径 {@code /api/v1}。
 *
 * <p><b>变更纪律</b>：路径新增/改名须先改 {@code ARCHITECTURE.md} §4.1.1，再改本类（{@code NFR-MAINT-5}）。
 */
public final class ApiRoutes {

    /** REST 基础路径。 */
    public static final String BASE = "/api/v1";

    private static final Map<String, List<String>> BY_MODULE = new LinkedHashMap<>();

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
                "/acct/period/{id}/close", "/acct/effect", "/acct/report/dual-pnl", "/acct/selfcheck",
                "/acct/export"));
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
    }

    /** 平台级公开资源（不属于 11 个业务模块）。 */
    public static final String PING = "/ping";

    private ApiRoutes() {
    }

    /**
     * 模块 → 资源路径（只读）。
     *
     * @return 11 个模块的路径清单
     */
    public static Map<String, List<String>> byModule() {
        return Collections.unmodifiableMap(BY_MODULE);
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
        List<String> out = new java.util.ArrayList<>();
        out.add(BASE + PING);
        for (List<String> paths : BY_MODULE.values()) {
            for (String p : paths) {
                out.add(BASE + p);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
