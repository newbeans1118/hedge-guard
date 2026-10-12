package com.hedgeguard.web.handler.audit;

import com.hedgeguard.service.CheckTaskQueryService;
import com.hedgeguard.web.Router;

/**
 * {@code AUDIT} 模块核对任务查询路由注册（{@code T-194}；{@code FR-AUDIT-13} 验收 1/2/5、
 * {@code R-AUDIT-11}）。
 *
 * <pre>
 *   GET /api/v1/audit/check-tasks            核对任务 + 签署明细分页查询（FR-AUDIT-13 验收 1/2）
 *   GET /api/v1/audit/check-tasks/overdue    逾期未签清单（FR-AUDIT-13 验收 2）
 * </pre>
 *
 * <p>路径为 {@code T-194} 新增声明（{@code ApiRoutes} 模块表 + 方法注册表已同步）；
 * 只读（{@code R-AUDIT-6}），仅注册 {@code GET}。
 */
public final class CheckTaskQueryRoutes {

    private CheckTaskQueryRoutes() {
    }

    /**
     * 注册核对任务查询路由（2 个 {@code (方法,路径)} 对，均 {@code GET}）。
     *
     * @param router  路由
     * @param service 核对任务与签署记录查询服务
     */
    public static void register(Router router, CheckTaskQueryService service) {
        CheckTaskQueryHandler handler = new CheckTaskQueryHandler(service);
        router.register("GET", CheckTaskQueryHandler.PATH, handler);
        router.register("GET", CheckTaskQueryHandler.PATH_OVERDUE, handler);
    }
}
