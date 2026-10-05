package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AnnualScaleDao;
import com.hedgeguard.dao.entity.AnnualScale;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_annual_scale} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-11；{@code Q-1}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@code DmDialect}。
 */
public final class DmAnnualScaleDao extends AbstractDmDao<AnnualScale, Long>
        implements AnnualScaleDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "scope_type", "product_id", "scale_year",
            "expected_spot_business_qty", "accumulated_hedge_qty", "category",
            "scale_ratio_limit", "usage_ratio", "dispatch_status", "dispatch_version"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmAnnualScaleDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_annual_scale";
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
    protected void bindBusiness(PreparedStatement ps, AnnualScale e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.scopeType);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.scaleYear);
        bind(ps, i++, e.expectedSpotBusinessQty);
        bind(ps, i++, e.accumulatedHedgeQty);
        bind(ps, i++, e.category);
        bind(ps, i++, e.scaleRatioLimit);
        bind(ps, i++, e.usageRatio);
        bind(ps, i++, e.dispatchStatus);
        bind(ps, i++, e.dispatchVersion);
    }

    @Override
    protected void readBusiness(ResultSet rs, AnnualScale e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.scopeType = string(rs, "scope_type");
        e.productId = nullableLong(rs, "product_id");
        e.scaleYear = nullableInt(rs, "scale_year");
        e.expectedSpotBusinessQty = decimal(rs, "expected_spot_business_qty");
        e.accumulatedHedgeQty = decimal(rs, "accumulated_hedge_qty");
        e.category = string(rs, "category");
        e.scaleRatioLimit = decimal(rs, "scale_ratio_limit");
        e.usageRatio = decimal(rs, "usage_ratio");
        e.dispatchStatus = string(rs, "dispatch_status");
        e.dispatchVersion = nullableLong(rs, "dispatch_version");
    }

    @Override
    protected Long idOf(AnnualScale entity) {
        return entity.id;
    }

    @Override
    protected AnnualScale newEntity() {
        return new AnnualScale();
    }

    @Override
    protected void applyGeneratedId(AnnualScale entity, long generated) {
        entity.id = generated;
    }

    @Override
    public AnnualScale findByScopeAndProduct(String scopeType, int subAccountId, long productId,
                                             int scaleYear) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE scope_type = ? AND sub_account_id = ? AND product_id = ? AND scale_year = ?"
                + " ORDER BY id ASC";
        return queryOne(sql,
                Arrays.<Object>asList(scopeType, subAccountId, productId, scaleYear), this::mapRow);
    }
}
