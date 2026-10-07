package com.hedgeguard.web.handler.audit;

import com.hedgeguard.service.AuditService;
import com.hedgeguard.web.Router;

/**
 * {@code AUDIT} 模块追溯与留痕路由注册（{@code T-067}；{@code FR-AUDIT-1/2/3/11}）。
 *
 * <pre>
 *   GET|POST /api/v1/audit/trace/forward    正向追溯（敞口 - 凭证；FR-AUDIT-1）
 *   GET|POST /api/v1/audit/trace/backward   反向追溯（凭证/交易 - 敞口；FR-AUDIT-2）
 *   GET|POST /api/v1/audit/trace/integrity  完整性校验 + 断点清单（FR-AUDIT-3 / R-AUDIT-2）
 *   GET|POST /api/v1/audit/operation-log     操作留痕查询（FR-AUDIT-11）
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("AUDIT")}（未新增路径）。
 */
public final class AuditTraceRoutes {

    private AuditTraceRoutes() {
    }

    /**
     * 注册审计追溯路由（8 个 (方法,路径) 对）。
     *
     * @param router  路由
     * @param service 审计服务
     */
    public static void register(Router router, AuditService service) {
        router.register("GET", AuditTraceHandler.PATH_FORWARD,
                new AuditTraceHandler(AuditTraceHandler.MODE_FORWARD, service));
        router.register("POST", AuditTraceHandler.PATH_FORWARD,
                new AuditTraceHandler(AuditTraceHandler.MODE_FORWARD, service));
        router.register("GET", AuditTraceHandler.PATH_BACKWARD,
                new AuditTraceHandler(AuditTraceHandler.MODE_BACKWARD, service));
        router.register("POST", AuditTraceHandler.PATH_BACKWARD,
                new AuditTraceHandler(AuditTraceHandler.MODE_BACKWARD, service));
        router.register("GET", AuditTraceHandler.PATH_INTEGRITY,
                new AuditTraceHandler(AuditTraceHandler.MODE_INTEGRITY, service));
        router.register("POST", AuditTraceHandler.PATH_INTEGRITY,
                new AuditTraceHandler(AuditTraceHandler.MODE_INTEGRITY, service));
        router.register("GET", AuditTraceHandler.PATH_OPLOG,
                new AuditTraceHandler(AuditTraceHandler.MODE_OPLOG, service));
        router.register("POST", AuditTraceHandler.PATH_OPLOG,
                new AuditTraceHandler(AuditTraceHandler.MODE_OPLOG, service));
    }
}
