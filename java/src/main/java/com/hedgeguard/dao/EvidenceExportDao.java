package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.EvidenceExport;

import java.util.List;

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

    /**
     * 取全表行（固定 {@code exported_time DESC, id ASC} 全序；{@code T-069} 导出记录列表的
     * Java 侧行级可见性过滤承载——本表无 {@code sub_account_id} 列，无法以 SQL {@code IN} 谓词过滤，
     * {@code DATA_MODEL.md} §4.3「导出批次级」口径）。表容量上限 2 万行（§2.9）。
     *
     * @return 全部导出记录（全序，不空 {@code null}）
     */
    List<EvidenceExport> findAll();
}
