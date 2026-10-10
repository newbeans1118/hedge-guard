package com.hedgeguard.web.handler.audit;

import com.hedgeguard.service.AuditLogService;
import com.hedgeguard.web.Router;

/**
 * {@code AUDIT} 模块审计日志路由注册（{@code T-069}；{@code FR-AUDIT-9/10}）。
 *
 * <pre>
 *   GET|POST /api/v1/audit/log          审计日志查询（FR-AUDIT-9）
 *   GET|POST /api/v1/audit/log/verify   防篡改校验（FR-AUDIT-10）
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("AUDIT")}（未新增路径）。
 */
public final class AuditLogRoutes {

    private AuditLogRoutes() {
    }

    /**
     * 注册审计日志路由（2 路径 × GET/POST 共 4 对）。
     *
     * @param router  路由
     * @param service 审计日志服务
     */
    public static void register(Router router, AuditLogService service) {
        AuditLogHandler log = new AuditLogHandler(AuditLogHandler.MODE_LOG, service);
        router.register("GET", AuditLogHandler.PATH_LOG, log);
        router.register("POST", AuditLogHandler.PATH_LOG, log);
        AuditLogHandler verify = new AuditLogHandler(AuditLogHandler.MODE_VERIFY, service);
        router.register("GET", AuditLogHandler.PATH_VERIFY, verify);
        router.register("POST", AuditLogHandler.PATH_VERIFY, verify);
    }
}
