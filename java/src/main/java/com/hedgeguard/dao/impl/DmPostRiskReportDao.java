package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PostRiskReportDao;
import com.hedgeguard.dao.entity.PostRiskReport;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;

/**
 * {@code t_post_risk_report} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-7；{@code FR-RISK-17}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmPostRiskReportDao extends AbstractDmDao<PostRiskReport, Long>
        implements PostRiskReportDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "report_date", "report_type", "content_json", "settle_reconcile_key",
            "generated_by", "generated_time", "settle_cycle_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmPostRiskReportDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_post_risk_report";
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
    protected void bindBusiness(PreparedStatement ps, PostRiskReport e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.reportDate);
        bind(ps, i++, e.reportType);
        bindClob(ps, i++, e.contentJson);
        bind(ps, i++, e.settleReconcileKey);
        bind(ps, i++, e.generatedBy);
        bind(ps, i++, e.generatedTime);
        bind(ps, i++, e.settleCycleId);
    }

    @Override
    protected void readBusiness(ResultSet rs, PostRiskReport e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.reportDate = localDate(rs, "report_date");
        e.reportType = string(rs, "report_type");
        e.contentJson = clob(rs, "content_json");
        e.settleReconcileKey = string(rs, "settle_reconcile_key");
        e.generatedBy = nullableLong(rs, "generated_by");
        e.generatedTime = localDateTime(rs, "generated_time");
        e.settleCycleId = nullableLong(rs, "settle_cycle_id");
    }

    @Override
    protected Long idOf(PostRiskReport entity) {
        return entity.id;
    }

    @Override
    protected PostRiskReport newEntity() {
        return new PostRiskReport();
    }

    @Override
    protected void applyGeneratedId(PostRiskReport entity, long generated) {
        entity.id = generated;
    }

    @Override
    public PostRiskReport findByKey(LocalDate reportDate, String reportType, Integer subAccountId) {
        String where = "report_date = ? AND report_type = ? AND "
                + (subAccountId == null ? "sub_account_id IS NULL" : "sub_account_id = ?");
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE " + where
                + " ORDER BY id ASC";
        return subAccountId == null
                ? queryOne(sql, Arrays.asList(reportDate, reportType), this::mapRow)
                : queryOne(sql, Arrays.asList(reportDate, reportType, subAccountId), this::mapRow);
    }
}
