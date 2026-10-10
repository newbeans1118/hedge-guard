package com.hedgeguard.web.handler.audit;

import com.hedgeguard.service.AuditReportService;
import com.hedgeguard.web.Router;

/**
 * {@code AUDIT} 模块审计报表路由注册（{@code T-069}；{@code FR-AUDIT-5/6/7/12}）。
 *
 * <pre>
 *   GET|POST /api/v1/audit/report/quarterly    季度报表（FR-AUDIT-5/12）
 *   GET|POST /api/v1/audit/report/annual       年度专项报告（FR-AUDIT-6）
 *   GET|POST /api/v1/audit/report/major-event  重大事项专项报告（FR-AUDIT-7）
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("AUDIT")}（未新增路径）。
 */
public final class AuditReportRoutes {

    private AuditReportRoutes() {
    }

    /**
     * 注册审计报表路由（3 路径 × GET/POST 共 6 对）。
     *
     * @param router  路由
     * @param service 报表服务
     */
    public static void register(Router router, AuditReportService service) {
        register(router, AuditReportHandler.PATH_QUARTERLY, AuditReportHandler.MODE_QUARTERLY, service);
        register(router, AuditReportHandler.PATH_ANNUAL, AuditReportHandler.MODE_ANNUAL, service);
        register(router, AuditReportHandler.PATH_MAJOR_EVENT, AuditReportHandler.MODE_MAJOR_EVENT, service);
    }

    private static void register(Router router, String path, String mode, AuditReportService service) {
        AuditReportHandler handler = new AuditReportHandler(mode, service);
        router.register("GET", path, handler);
        router.register("POST", path, handler);
    }
}
