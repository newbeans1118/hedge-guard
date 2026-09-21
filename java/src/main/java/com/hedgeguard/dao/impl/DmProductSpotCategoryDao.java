package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.entity.ProductSpotCategory;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_product_spot_category} 的达梦实现（{@code DATA_MODEL.md} §2.3 TB-PRODUCT-4）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmProductSpotCategoryDao extends AbstractDmDao<ProductSpotCategory, Long> implements ProductSpotCategoryDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "spot_category_code", "spot_category_name", "product_id", "mapping_direction",
            "is_primary", "status", "change_desc"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmProductSpotCategoryDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_product_spot_category";
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
    protected void bindBusiness(PreparedStatement ps, ProductSpotCategory e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.spotCategoryCode);
        bind(ps, i++, e.spotCategoryName);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.mappingDirection);
        bind(ps, i++, e.isPrimary);
        bind(ps, i++, e.status);
        bind(ps, i, e.changeDesc);
    }

    @Override
    protected void readBusiness(ResultSet rs, ProductSpotCategory e) throws SQLException {
        e.id = rs.getLong("id");
        e.spotCategoryCode = string(rs, "spot_category_code");
        e.spotCategoryName = string(rs, "spot_category_name");
        e.productId = nullableLong(rs, "product_id");
        e.mappingDirection = string(rs, "mapping_direction");
        e.isPrimary = string(rs, "is_primary");
        e.status = string(rs, "status");
        e.changeDesc = string(rs, "change_desc");
    }

    @Override
    protected Long idOf(ProductSpotCategory entity) {
        return entity.id;
    }

    @Override
    protected ProductSpotCategory newEntity() {
        return new ProductSpotCategory();
    }

    @Override
    protected void applyGeneratedId(ProductSpotCategory entity, long generated) {
        entity.id = generated;
    }

    @Override
    public ProductSpotCategory findPrimaryMapping(String spotCategoryCode, String mappingDirection) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE spot_category_code = ? AND (mapping_direction = ? OR mapping_direction = ?)"
                + " AND status = ? ORDER BY is_primary DESC, id ASC";
        List<ProductSpotCategory> rows = queryList(sql,
                Arrays.asList(spotCategoryCode, mappingDirection, "BOTH", "ENABLED"), this::mapRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<ProductSpotCategory> findByProduct(long productId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE product_id = ? ORDER BY status ASC, spot_category_code ASC";
        return queryList(sql, java.util.Arrays.asList(productId), this::mapRow);
    }
}
