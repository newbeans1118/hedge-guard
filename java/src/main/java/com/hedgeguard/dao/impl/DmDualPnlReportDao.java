package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.DualPnlReportDao;
import com.hedgeguard.dao.entity.DualPnlReport;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_dual_pnl_report} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-9）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmDualPnlReportDao extends AbstractDmDao<DualPnlReport, Long>
        implements DualPnlReportDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "report_no", "report_period", "report_type",
            "spot_pnl_column_present", "spot_pnl_value", "spot_pnl_missing_reason",
            "derivative_pnl_value", "derivative_pnl_realized", "derivative_pnl_unrealized",
            "net_effect", "data_completeness_note", "submitted_by", "submitted_time",
            "pending_external_review"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmDualPnlReportDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_dual_pnl_report";
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
    protected void bindBusiness(PreparedStatement ps, DualPnlReport e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.reportNo);
        bind(ps, i++, e.reportPeriod);
        bind(ps, i++, e.reportType);
        bind(ps, i++, e.spotPnlColumnPresent);
        bind(ps, i++, e.spotPnlValue);
        bind(ps, i++, e.spotPnlMissingReason);
        bind(ps, i++, e.derivativePnlValue);
        bind(ps, i++, e.derivativePnlRealized);
        bind(ps, i++, e.derivativePnlUnrealized);
        bind(ps, i++, e.netEffect);
        bind(ps, i++, e.dataCompletenessNote);
        bind(ps, i++, e.submittedBy);
        bind(ps, i++, e.submittedTime);
        bind(ps, i, e.pendingExternalReview);
    }

    @Override
    protected void readBusiness(ResultSet rs, DualPnlReport e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.reportNo = string(rs, "report_no");
        e.reportPeriod = string(rs, "report_period");
        e.reportType = string(rs, "report_type");
        e.spotPnlColumnPresent = string(rs, "spot_pnl_column_present");
        e.spotPnlValue = decimal(rs, "spot_pnl_value");
        e.spotPnlMissingReason = string(rs, "spot_pnl_missing_reason");
        e.derivativePnlValue = decimal(rs, "derivative_pnl_value");
        e.derivativePnlRealized = decimal(rs, "derivative_pnl_realized");
        e.derivativePnlUnrealized = decimal(rs, "derivative_pnl_unrealized");
        e.netEffect = decimal(rs, "net_effect");
        e.dataCompletenessNote = string(rs, "data_completeness_note");
        e.submittedBy = nullableLong(rs, "submitted_by");
        e.submittedTime = localDateTime(rs, "submitted_time");
        e.pendingExternalReview = string(rs, "pending_external_review");
    }

    @Override
    protected Long idOf(DualPnlReport entity) {
        return entity.id;
    }

    @Override
    protected DualPnlReport newEntity() {
        return new DualPnlReport();
    }

    @Override
    protected void applyGeneratedId(DualPnlReport entity, long generated) {
        entity.id = generated;
    }

    @Override
    public DualPnlReport findByReportNo(String reportNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE report_no = ?";
        return queryOne(sql, Arrays.asList(reportNo), this::mapRow);
    }

    @Override
    public List<DualPnlReport> findByPeriodAndType(String reportPeriod, String reportType) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE report_period = ? AND report_type = ? ORDER BY submitted_time ASC, id ASC";
        return queryList(sql, Arrays.asList(reportPeriod, reportType), this::mapRow);
    }
}
