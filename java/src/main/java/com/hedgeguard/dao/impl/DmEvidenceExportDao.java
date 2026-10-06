package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.EvidenceExportDao;
import com.hedgeguard.dao.entity.EvidenceExport;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_evidence_export} 的达梦实现（{@code DATA_MODEL.md} §2.9 TB-AUDIT-7；{@code FR-AUDIT-8}）。
 *
 * <p>{@code break_list} 为 {@code CLOB}，经标准 {@code setCharacterStream} / {@code getCharacterStream}
 * （{@code §6.1} D7）读写。本类是唯一出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmEvidenceExportDao extends AbstractDmDao<EvidenceExport, Long>
        implements EvidenceExportDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "export_no", "start_point_type", "start_point_ids", "date_from", "date_to",
            "sub_account_scope", "include_source_detail", "chain_integrity", "break_list",
            "dual_pnl_included", "reason_included", "file_id", "file_path", "exported_by",
            "exported_time", "audit_log_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmEvidenceExportDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_evidence_export";
    }

    @Override
    protected String idColumn() {
        return "id";
    }

    @Override
    protected String[] businessColumns() {
        return COLUMNS.clone();
    }

    @Override
    protected void bindBusiness(PreparedStatement ps, EvidenceExport e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.exportNo);
        bind(ps, i++, e.startPointType);
        bind(ps, i++, e.startPointIds);
        bind(ps, i++, e.dateFrom);
        bind(ps, i++, e.dateTo);
        bind(ps, i++, e.subAccountScope);
        bind(ps, i++, e.includeSourceDetail);
        bind(ps, i++, e.chainIntegrity);
        bindClob(ps, i++, e.breakList);
        bind(ps, i++, e.dualPnlIncluded);
        bind(ps, i++, e.reasonIncluded);
        bind(ps, i++, e.fileId);
        bind(ps, i++, e.filePath);
        bind(ps, i++, e.exportedBy);
        bind(ps, i++, e.exportedTime);
        bind(ps, i, e.auditLogId);
    }

    @Override
    protected void readBusiness(ResultSet rs, EvidenceExport e) throws SQLException {
        e.id = rs.getLong("id");
        e.exportNo = string(rs, "export_no");
        e.startPointType = string(rs, "start_point_type");
        e.startPointIds = string(rs, "start_point_ids");
        e.dateFrom = localDate(rs, "date_from");
        e.dateTo = localDate(rs, "date_to");
        e.subAccountScope = string(rs, "sub_account_scope");
        e.includeSourceDetail = string(rs, "include_source_detail");
        e.chainIntegrity = string(rs, "chain_integrity");
        e.breakList = clob(rs, "break_list");
        e.dualPnlIncluded = string(rs, "dual_pnl_included");
        e.reasonIncluded = string(rs, "reason_included");
        e.fileId = string(rs, "file_id");
        e.filePath = string(rs, "file_path");
        e.exportedBy = rs.getLong("exported_by");
        e.exportedTime = localDateTime(rs, "exported_time");
        e.auditLogId = nullableLong(rs, "audit_log_id");
    }

    @Override
    protected Long idOf(EvidenceExport entity) {
        return entity.id;
    }

    @Override
    protected EvidenceExport newEntity() {
        return new EvidenceExport();
    }

    @Override
    protected void applyGeneratedId(EvidenceExport entity, long generated) {
        entity.id = generated;
    }

    @Override
    public EvidenceExport findByExportNo(String exportNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE export_no = ? ORDER BY id ASC";
        return queryOne(sql, Arrays.<Object>asList(exportNo), this::mapRow);
    }
}
