package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ProductQuotaDao;
import com.hedgeguard.dao.entity.ProductQuota;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_product_quota} 的达梦实现（{@code DATA_MODEL.md} §2.3 TB-PRODUCT-6）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmProductQuotaDao extends AbstractDmDao<ProductQuota, Long> implements ProductQuotaDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "limit_layer", "product_id", "quota_limit", "quota_unit", "used_qty",
            "frozen_qty", "effective_from", "effective_to", "dispatch_status", "dispatch_version"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmProductQuotaDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_product_quota";
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
    protected void bindBusiness(PreparedStatement ps, ProductQuota e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.limitLayer);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.quotaLimit);
        bind(ps, i++, e.quotaUnit);
        bind(ps, i++, e.usedQty);
        bind(ps, i++, e.frozenQty);
        bind(ps, i++, e.effectiveFrom);
        bind(ps, i++, e.effectiveTo);
        bind(ps, i++, e.dispatchStatus);
        bind(ps, i, e.dispatchVersion);
    }

    @Override
    protected void readBusiness(ResultSet rs, ProductQuota e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.limitLayer = string(rs, "limit_layer");
        e.productId = nullableLong(rs, "product_id");
        e.quotaLimit = decimal(rs, "quota_limit");
        e.quotaUnit = string(rs, "quota_unit");
        e.usedQty = decimal(rs, "used_qty");
        e.frozenQty = decimal(rs, "frozen_qty");
        e.effectiveFrom = localDate(rs, "effective_from");
        e.effectiveTo = localDate(rs, "effective_to");
        e.dispatchStatus = string(rs, "dispatch_status");
        e.dispatchVersion = nullableLong(rs, "dispatch_version");
    }

    @Override
    protected Long idOf(ProductQuota entity) {
        return entity.id;
    }

    @Override
    protected ProductQuota newEntity() {
        return new ProductQuota();
    }

    @Override
    protected void applyGeneratedId(ProductQuota entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<ProductQuota> findByLayerAndScope(String limitLayer, int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE limit_layer = ? AND sub_account_id = ?" + " ORDER BY product_id ASC";
        return queryList(sql, Arrays.asList(limitLayer, subAccountId), this::mapRow);
    }
}
