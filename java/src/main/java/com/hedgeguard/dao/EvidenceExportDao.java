package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.EvidenceExport;

/**
 * {@code TB-AUDIT-7 t_evidence_export} 数据访问（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-8}）。
 */
public interface EvidenceExportDao extends BaseDao<EvidenceExport, Long> {

    /**
     * 按导出批次号取唯一一行（{@code uk_evidence_export_no}）。
     *
     * @param exportNo 导出批次号
     * @return 行；无则 {@code null}
     */
    EvidenceExport findByExportNo(String exportNo);
}
