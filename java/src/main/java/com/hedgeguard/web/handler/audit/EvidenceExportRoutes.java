package com.hedgeguard.web.handler.audit;

import com.hedgeguard.service.EvidenceExportService;
import com.hedgeguard.web.Router;

/**
 * {@code AUDIT} 模块证据链导出路由注册（{@code T-069}；{@code FR-AUDIT-8}）。
 *
 * <pre>
 *   GET|POST /api/v1/audit/evidence/export   证据链导出（FR-AUDIT-8）
 * </pre>
 *
 * <p>路径逐字取自 {@code ApiRoutes.byModule().get("AUDIT")}（未新增路径）。
 */
public final class EvidenceExportRoutes {

    private EvidenceExportRoutes() {
    }

    /**
     * 注册证据链导出路由（1 路径 × GET/POST 共 2 对）。
     *
     * @param router  路由
     * @param service 导出服务
     */
    public static void register(Router router, EvidenceExportService service) {
        EvidenceExportHandler handler = new EvidenceExportHandler(service);
        router.register("GET", EvidenceExportHandler.PATH, handler);
        router.register("POST", EvidenceExportHandler.PATH, handler);
    }
}
