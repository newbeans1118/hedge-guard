package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.AuditReport;

/**
 * {@code TB-AUDIT-6 t_audit_report} 数据访问（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-5~7}/12）。
 */
public interface AuditReportDao extends BaseDao<AuditReport, Long> {

    /**
     * 按报表编号取唯一一行（{@code uk_audit_report_no}）。
     *
     * @param reportNo 报表编号
     * @return 行；无则 {@code null}
     */
    AuditReport findByReportNo(String reportNo);
}
