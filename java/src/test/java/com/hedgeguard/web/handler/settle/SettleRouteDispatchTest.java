package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleCheckService;
import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.Router;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SETTLE} 路由**可达性**判据（{@code BUG-004} 教训：注册 ≠ 可达）。
 *
 * <p>{@code T-047} 覆盖结算主链 11 对；{@code T-048} 增补核对任务与电子签署 4 对
 * （{@code monthly-check}/{@code quarterly-report} 各 {@code GET}/{@code POST}）⇒ 合计 15 对。
 * 验证：① 已注册路径全部落在 {@code ApiRoutes} 冻结清单内；② 占位符动作路由不被同段数静态路由吞并；
 * ③ 处理函数非空。
 */
class SettleRouteDispatchTest {

    private Router assemble() {
        SettleService service = new SettleService(null, userId -> Arrays.asList(101, 102),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        SettleCheckService checkService = new SettleCheckService(null,
                userId -> Arrays.asList(101, 102), userId -> Arrays.asList(),
                userId -> false, key -> null, null, null, null, null);
        Router router = new Router();
        SettleRoutes.register(router, service);
        SettleCheckRoutes.register(router, checkService);
        return router;
    }

    @Test
    @DisplayName("SETTLE 已注册路径 ∈ ApiRoutes；占位符动作路由不被吞并；核对签署面可达")
    void registeredRoutesAreReachableAndContractual() {
        Router router = assemble();
        List<String> registered = router.routes();
        assertEquals(15, registered.size(),
                "SETTLE 注册面 = 结算 11 + 核对签署 4：\n" + registered);
        List<String> all = ApiRoutes.all();
        for (String entry : registered) {
            String path = entry.substring(entry.indexOf(' ') + 1);
            assertTrue(all.contains(path), "注册路径不在 ApiRoutes 冻结清单：" + entry);
        }
        // 静态集合路由
        assertNotNull(router.match("GET", "/api/v1/settle/cycle").handler());
        assertNotNull(router.match("POST", "/api/v1/settle/cycle").handler());
        // 占位符动作路由：实例路径必须命中自身模式（否则=被同段数静态路由吞并，BUG-004）
        assertEquals("/api/v1/settle/cycle/{id}/reconcile",
                router.match("POST", "/api/v1/settle/cycle/5/reconcile").pattern());
        assertEquals("/api/v1/settle/cycle/{id}/aggregate",
                router.match("POST", "/api/v1/settle/cycle/5/aggregate").pattern());
        assertEquals("/api/v1/settle/cycle/{id}/close",
                router.match("POST", "/api/v1/settle/cycle/5/close").pattern());
        // T-048：核对任务与电子签署面（GET 只读查询 + POST 生成/签署）
        assertNotNull(router.match("GET", "/api/v1/settle/monthly-check").handler());
        assertNotNull(router.match("POST", "/api/v1/settle/monthly-check").handler());
        assertNotNull(router.match("GET", "/api/v1/settle/quarterly-report").handler());
        assertNotNull(router.match("POST", "/api/v1/settle/quarterly-report").handler());
    }
}
