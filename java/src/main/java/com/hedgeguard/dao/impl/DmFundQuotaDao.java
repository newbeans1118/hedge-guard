package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.FundQuotaDao;
import com.hedgeguard.dao.entity.FundQuota;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_fund_quota} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-7）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmFundQuotaDao extends AbstractDmDao<FundQuota, Long> implements FundQuotaDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "quota_scope", "quota_value", "effective_from", "effective_to",
            "operator_id", "change_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmFundQuotaDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_fund_quota";
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
    protected void bindBusiness(PreparedStatement ps, FundQuota e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.quotaScope);
        bind(ps, i++, e.quotaValue);
        bind(ps, i++, e.effectiveFrom);
        bind(ps, i++, e.effectiveTo);
        bind(ps, i++, e.operatorId);
        bind(ps, i, e.changeReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, FundQuota e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.quotaScope = string(rs, "quota_scope");
        e.quotaValue = decimal(rs, "quota_value");
        e.effectiveFrom = localDate(rs, "effective_from");
        e.effectiveTo = localDate(rs, "effective_to");
        e.operatorId = nullableLong(rs, "operator_id");
        e.changeReason = string(rs, "change_reason");
    }

    @Override
    protected Long idOf(FundQuota entity) {
        return entity.id;
    }

    @Override
    protected FundQuota newEntity() {
        return new FundQuota();
    }

    @Override
    protected void applyGeneratedId(FundQuota entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<FundQuota> findBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? ORDER BY effective_from ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId), this::mapRow);
    }

    @Override
    public List<FundQuota> findByScope(String quotaScope) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE quota_scope = ? ORDER BY effective_from ASC, id ASC";
        return queryList(sql, Arrays.asList(quotaScope), this::mapRow);
    }
}
