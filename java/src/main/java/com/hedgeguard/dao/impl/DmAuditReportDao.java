package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AuditReportDao;
import com.hedgeguard.dao.entity.AuditReport;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_audit_report} 的达梦实现（{@code DATA_MODEL.md} §2.9 TB-AUDIT-6；{@code FR-AUDIT-5~7}/12）。
 *
 * <p>{@code content_json} / {@code break_list} 为 {@code CLOB}，经标准
 * {@code setCharacterStream} / {@code getCharacterStream}（{@code §6.1} D7）读写。
 * 本类是唯一出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmAuditReportDao extends AbstractDmDao<AuditReport, Long> implements AuditReportDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "report_no", "report_type", "period_key", "content_json",
            "dual_pnl_included", "chain_integrity", "break_list", "data_gap_desc",
            "generated_by", "generated_time", "submitted", "submitted_to", "submitted_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmAuditReportDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_audit_report";
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
    protected void bindBusiness(PreparedStatement ps, AuditReport e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.reportNo);
        bind(ps, i++, e.reportType);
        bind(ps, i++, e.periodKey);
        bindClob(ps, i++, e.contentJson);
        bind(ps, i++, e.dualPnlIncluded);
        bind(ps, i++, e.chainIntegrity);
        bindClob(ps, i++, e.breakList);
        bind(ps, i++, e.dataGapDesc);
        bind(ps, i++, e.generatedBy);
        bind(ps, i++, e.generatedTime);
        bind(ps, i++, e.submitted);
        bind(ps, i++, e.submittedTo);
        bind(ps, i, e.submittedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, AuditReport e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.reportNo = string(rs, "report_no");
        e.reportType = string(rs, "report_type");
        e.periodKey = string(rs, "period_key");
        e.contentJson = clob(rs, "content_json");
        e.dualPnlIncluded = string(rs, "dual_pnl_included");
        e.chainIntegrity = string(rs, "chain_integrity");
        e.breakList = clob(rs, "break_list");
        e.dataGapDesc = string(rs, "data_gap_desc");
        e.generatedBy = rs.getLong("generated_by");
        e.generatedTime = localDateTime(rs, "generated_time");
        e.submitted = string(rs, "submitted");
        e.submittedTo = string(rs, "submitted_to");
        e.submittedTime = localDateTime(rs, "submitted_time");
    }

    @Override
    protected Long idOf(AuditReport entity) {
        return entity.id;
    }

    @Override
    protected AuditReport newEntity() {
        return new AuditReport();
    }

    @Override
    protected void applyGeneratedId(AuditReport entity, long generated) {
        entity.id = generated;
    }

    @Override
    public AuditReport findByReportNo(String reportNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE report_no = ? ORDER BY id ASC";
        return queryOne(sql, Arrays.<Object>asList(reportNo), this::mapRow);
    }
}
