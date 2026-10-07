package com.hedgeguard.web.handler.audit;

import com.hedgeguard.service.ViolationScreenService;
import com.hedgeguard.web.Router;

/**
 * {@code AUDIT} 模块违规事项筛查路由注册（{@code T-068}；{@code FR-AUDIT-4}）。
 *
 * <pre>
 *   GET|POST /api/v1/audit/violation-screen   违规事项筛查 / 命中清单查询
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("AUDIT")}（未新增路径）。
 * <b>接线状态</b>：本类仅提供注册入口；{@code Bootstrap} 的调用点归 {@code T-068} 接线步骤
 * （错峰协议：本波 {@code ApiRoutes}/{@code Bootstrap} 首轮归 {@code be-t065}）。
 */
public final class ViolationScreenRoutes {

    private ViolationScreenRoutes() {
    }

    /**
     * 注册违规事项筛查路由（1 路径 × GET/POST 共 2 对）。
     *
     * @param router  路由
     * @param service 筛查服务
     */
    public static void register(Router router, ViolationScreenService service) {
        ViolationScreenHandler handler = new ViolationScreenHandler(service);
        router.register("GET", ViolationScreenHandler.PATH, handler);
        router.register("POST", ViolationScreenHandler.PATH, handler);
    }
}
