package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.RiskThresholdDao;
import com.hedgeguard.dao.entity.RiskThreshold;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_risk_threshold} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-1；{@code FR-CONFIG-5}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmRiskThresholdDao extends AbstractDmDao<RiskThreshold, Long>
        implements RiskThresholdDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "threshold_key", "threshold_value", "value_type", "min_value", "max_value",
            "default_value", "is_compliance_redline", "scope_type", "sub_account_id",
            "related_question_no", "dispatch_status", "dispatch_version", "description"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmRiskThresholdDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_risk_threshold";
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
    protected void bindBusiness(PreparedStatement ps, RiskThreshold e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.thresholdKey);
        bind(ps, i++, e.thresholdValue);
        bind(ps, i++, e.valueType);
        bind(ps, i++, e.minValue);
        bind(ps, i++, e.maxValue);
        bind(ps, i++, e.defaultValue);
        bind(ps, i++, e.isComplianceRedline);
        bind(ps, i++, e.scopeType);
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.relatedQuestionNo);
        bind(ps, i++, e.dispatchStatus);
        bind(ps, i++, e.dispatchVersion);
        bind(ps, i, e.description);
    }

    @Override
    protected void readBusiness(ResultSet rs, RiskThreshold e) throws SQLException {
        e.id = rs.getLong("id");
        e.thresholdKey = string(rs, "threshold_key");
        e.thresholdValue = string(rs, "threshold_value");
        e.valueType = string(rs, "value_type");
        e.minValue = decimal(rs, "min_value");
        e.maxValue = decimal(rs, "max_value");
        e.defaultValue = string(rs, "default_value");
        e.isComplianceRedline = string(rs, "is_compliance_redline");
        e.scopeType = string(rs, "scope_type");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.relatedQuestionNo = string(rs, "related_question_no");
        e.dispatchStatus = string(rs, "dispatch_status");
        e.dispatchVersion = nullableLong(rs, "dispatch_version");
        e.description = string(rs, "description");
    }

    @Override
    protected Long idOf(RiskThreshold entity) {
        return entity.id;
    }

    @Override
    protected RiskThreshold newEntity() {
        return new RiskThreshold();
    }

    @Override
    protected void applyGeneratedId(RiskThreshold entity, long generated) {
        entity.id = generated;
    }

    @Override
    public RiskThreshold findByKey(String thresholdKey, String scopeType, int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE threshold_key = ? AND scope_type = ? AND sub_account_id = ?"
                + " ORDER BY id ASC";
        return queryOne(sql, Arrays.<Object>asList(thresholdKey, scopeType, subAccountId),
                this::mapRow);
    }
}
