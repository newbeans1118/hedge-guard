package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.Router;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-047} SETTLE 路由**可达性**判据（{@code BUG-004} 教训：注册 ≠ 可达）。
 *
 * <p>验证：① 已注册路径全部落在 {@code ApiRoutes} 冻结清单内；② 占位符动作路由不会被同段数静态路由
 * 吞并（实例路径回查命中自身模式）；③ 未注册路径（{@code monthly-check}/{@code quarterly-report}，
 * 归 {@code T-048}）确为「未注册」；④ 处理函数非空。
 */
class SettleRouteDispatchTest {

    private Router assemble() {
        SettleService service = new SettleService(null, userId -> Arrays.asList(101, 102),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        Router router = new Router();
        SettleRoutes.register(router, service);
        return router;
    }

    @Test
    @DisplayName("SETTLE 已注册路径 ∈ ApiRoutes；占位符动作路由不被吞并（实例路径回查命中自身）")
    void registeredRoutesAreReachableAndContractual() {
        Router router = assemble();
        List<String> registered = router.routes();
        assertEquals(11, registered.size(), "SETTLE 最小注册面应为 11 个 (方法,路径) 对：" + registered);
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
        // 未注册（归 T-048）：路径已声明但未注册 ⇒ 匹配为空
        assertNull(router.match("POST", "/api/v1/settle/monthly-check"));
        assertNull(router.match("POST", "/api/v1/settle/quarterly-report"));
    }
}
