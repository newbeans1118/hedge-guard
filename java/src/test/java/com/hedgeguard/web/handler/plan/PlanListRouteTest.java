package com.hedgeguard.web.handler.plan;

import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.Router;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FR-PLAN-4} 端点注册守卫：{@code GET /api/v1/plan}（计划列表）必须真实注册。
 *
 * <p><b>为何要这条</b>：该缺口曾以"路径已注册 ⇒ 一致性检查通过"的形式躲过审查 ——
 * {@code ApiRoutes} 只冻结<b>路径</b>不冻结<b>方法</b>，而 {@code /plan} 因 {@code POST} 已注册
 * 而让路径级检查"看起来绿"；缺的正是 {@code GET}，前端实测拿到 {@code 405}。
 *
 * <p>本用例直接断言 {@link Router} 上 {@code GET /plan} 可命中（即不会再 404/405）；
 * 一旦 {@code PlanRoutes} 里那行注册被删除/短路，本用例<b>必然变红</b>。
 */
class PlanListRouteTest {

    @Test
    @DisplayName("GET /api/v1/plan 已注册（FR-PLAN-4 计划列表），且既有方法不受影响")
    void getPlanListIsRegistered() {
        Router router = new Router();
        PlanRoutes.register(router, null);

        Router.RouteMatch list = router.match("GET", ApiRoutes.full("/plan"));
        assertNotNull(list, "GET /api/v1/plan 未注册 —— FR-PLAN-4 计划列表将 404/405");
        assertTrue(list.handler() instanceof PlanHandler, "列表应由 PlanHandler 处理");

        // 同路径既有方法不得受影响
        assertNotNull(router.match("POST", ApiRoutes.full("/plan")), "POST /api/v1/plan 应仍注册");
        assertNotNull(router.match("GET", ApiRoutes.full("/plan/123")), "GET /api/v1/plan/{id} 应仍注册");
    }
}
