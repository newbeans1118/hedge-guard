package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.RiskAlertDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.RiskAlert;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_risk_alert} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-4；{@code FR-RISK-12}~{@code FR-RISK-14}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmRiskAlertDao extends AbstractDmDao<RiskAlert, Long>
        implements RiskAlertDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "alert_type", "alert_level", "metric_name", "metric_value",
            "threshold_value", "instrument_id", "alert_detail", "alert_ts_ms", "alert_time",
            "disposed", "disposal_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmRiskAlertDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_risk_alert";
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
    protected void bindBusiness(PreparedStatement ps, RiskAlert e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.alertType);
        bind(ps, i++, e.alertLevel);
        bind(ps, i++, e.metricName);
        bind(ps, i++, e.metricValue);
        bind(ps, i++, e.thresholdValue);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.alertDetail);
        ps.setLong(i++, e.alertTsMs);
        bind(ps, i++, e.alertTime);
        bind(ps, i++, e.disposed);
        bind(ps, i, e.disposalId);
    }

    @Override
    protected void readBusiness(ResultSet rs, RiskAlert e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.alertType = string(rs, "alert_type");
        e.alertLevel = string(rs, "alert_level");
        e.metricName = string(rs, "metric_name");
        e.metricValue = string(rs, "metric_value");
        e.thresholdValue = string(rs, "threshold_value");
        e.instrumentId = string(rs, "instrument_id");
        e.alertDetail = string(rs, "alert_detail");
        e.alertTsMs = rs.getLong("alert_ts_ms");
        e.alertTime = localDateTime(rs, "alert_time");
        e.disposed = string(rs, "disposed");
        e.disposalId = nullableLong(rs, "disposal_id");
    }

    @Override
    protected Long idOf(RiskAlert entity) {
        return entity.id;
    }

    @Override
    protected RiskAlert newEntity() {
        return new RiskAlert();
    }

    @Override
    protected void applyGeneratedId(RiskAlert entity, long generated) {
        entity.id = generated;
    }

    @Override
    public PageResult<RiskAlert> findPageScoped(PageQuery query, SubAccountScope scope,
                                                String extraWhere, List<Object> extraArgs) {
        // scope 谓词落在本表 sub_account_id 列（复用基类统一注入，空集恒假）
        return super.findPageScoped(query, scope, extraWhere, extraArgs);
    }

    @Override
    public List<RiskAlert> findBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? ORDER BY alert_time DESC, id DESC";
        return queryList(sql, Collections.singletonList((Object) subAccountId), this::mapRow);
    }
}
