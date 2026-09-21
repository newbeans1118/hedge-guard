package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ReportPeriodConfigDao;
import com.hedgeguard.dao.entity.ReportPeriodConfig;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_report_period_config} 的达梦实现（{@code DATA_MODEL.md} §2.1 {@code TB-CONFIG-6}；{@code T-109} 补位）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 *
 * <p>列清单与建表脚本 {@code docs/DEPLOY/schema/m1_config_auth.sql} 逐列一致
 * （由 {@code DaoColumnAlignmentTest} 离线校验）。
 */
public final class DmReportPeriodConfigDao extends AbstractDmDao<ReportPeriodConfig, Long>
        implements ReportPeriodConfigDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "report_type", "period_unit", "period_value", "remind_advance_days",
            "remind_role_codes", "is_mandatory", "is_enabled"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmReportPeriodConfigDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_report_period_config";
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
    protected void bindBusiness(PreparedStatement ps, ReportPeriodConfig e, int start)
            throws SQLException {
        int i = start;
        bind(ps, i++, e.reportType);
        bind(ps, i++, e.periodUnit);
        ps.setInt(i++, e.periodValue);
        ps.setInt(i++, e.remindAdvanceDays);
        bind(ps, i++, e.remindRoleCodes);
        bind(ps, i++, e.isMandatory);
        bind(ps, i, e.isEnabled);
    }

    @Override
    protected void readBusiness(ResultSet rs, ReportPeriodConfig e) throws SQLException {
        e.id = rs.getLong("id");
        e.reportType = string(rs, "report_type");
        e.periodUnit = string(rs, "period_unit");
        e.periodValue = rs.getInt("period_value");
        e.remindAdvanceDays = rs.getInt("remind_advance_days");
        e.remindRoleCodes = string(rs, "remind_role_codes");
        e.isMandatory = string(rs, "is_mandatory");
        e.isEnabled = string(rs, "is_enabled");
    }

    @Override
    protected Long idOf(ReportPeriodConfig entity) {
        return entity.id;
    }

    @Override
    protected ReportPeriodConfig newEntity() {
        return new ReportPeriodConfig();
    }

    @Override
    protected void applyGeneratedId(ReportPeriodConfig entity, long generated) {
        entity.id = generated;
    }

    @Override
    public ReportPeriodConfig findByType(String reportType) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE report_type = ?";
        return queryOne(sql, Collections.singletonList(reportType), this::mapRow);
    }

    @Override
    public List<ReportPeriodConfig> findEnabled() {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE is_enabled = ? ORDER BY report_type ASC, id ASC";
        return queryList(sql, Collections.singletonList("Y"), this::mapRow);
    }
}
