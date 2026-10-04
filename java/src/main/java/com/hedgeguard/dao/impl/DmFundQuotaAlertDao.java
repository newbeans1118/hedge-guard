package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.FundQuotaAlertDao;
import com.hedgeguard.dao.entity.FundQuotaAlert;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_fund_quota_alert} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-8）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmFundQuotaAlertDao extends AbstractDmDao<FundQuotaAlert, Long> implements FundQuotaAlertDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "quota_scope", "quota_value", "occupied_value", "alert_level",
            "alert_time", "blocked_operation", "disposed"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmFundQuotaAlertDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_fund_quota_alert";
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
    protected void bindBusiness(PreparedStatement ps, FundQuotaAlert e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.quotaScope);
        bind(ps, i++, e.quotaValue);
        bind(ps, i++, e.occupiedValue);
        bind(ps, i++, e.alertLevel);
        bind(ps, i++, e.alertTime);
        bind(ps, i++, e.blockedOperation);
        bind(ps, i, e.disposed);
    }

    @Override
    protected void readBusiness(ResultSet rs, FundQuotaAlert e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.quotaScope = string(rs, "quota_scope");
        e.quotaValue = decimal(rs, "quota_value");
        e.occupiedValue = decimal(rs, "occupied_value");
        e.alertLevel = string(rs, "alert_level");
        e.alertTime = localDateTime(rs, "alert_time");
        e.blockedOperation = string(rs, "blocked_operation");
        e.disposed = string(rs, "disposed");
    }

    @Override
    protected Long idOf(FundQuotaAlert entity) {
        return entity.id;
    }

    @Override
    protected FundQuotaAlert newEntity() {
        return new FundQuotaAlert();
    }

    @Override
    protected void applyGeneratedId(FundQuotaAlert entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<FundQuotaAlert> findBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? ORDER BY alert_time ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId), this::mapRow);
    }

    @Override
    public List<FundQuotaAlert> findByLevel(String alertLevel) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE alert_level = ? ORDER BY alert_time ASC, id ASC";
        return queryList(sql, Arrays.asList(alertLevel), this::mapRow);
    }
}
