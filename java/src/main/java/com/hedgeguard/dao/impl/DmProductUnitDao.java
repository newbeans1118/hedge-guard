package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ProductUnitDao;
import com.hedgeguard.dao.entity.ProductUnit;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_product_unit} 的达梦实现（{@code DATA_MODEL.md} §2.3 TB-PRODUCT-2）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmProductUnitDao extends AbstractDmDao<ProductUnit, Long> implements ProductUnitDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "product_id", "spot_unit", "futures_unit", "convert_factor", "convert_desc", "status",
            "effective_from"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmProductUnitDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_product_unit";
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
    protected void bindBusiness(PreparedStatement ps, ProductUnit e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.productId);
        bind(ps, i++, e.spotUnit);
        bind(ps, i++, e.futuresUnit);
        bind(ps, i++, e.convertFactor);
        bind(ps, i++, e.convertDesc);
        bind(ps, i++, e.status);
        bind(ps, i, e.effectiveFrom);
    }

    @Override
    protected void readBusiness(ResultSet rs, ProductUnit e) throws SQLException {
        e.id = rs.getLong("id");
        e.productId = nullableLong(rs, "product_id");
        e.spotUnit = string(rs, "spot_unit");
        e.futuresUnit = string(rs, "futures_unit");
        e.convertFactor = decimal(rs, "convert_factor");
        e.convertDesc = string(rs, "convert_desc");
        e.status = string(rs, "status");
        e.effectiveFrom = localDate(rs, "effective_from");
    }

    @Override
    protected Long idOf(ProductUnit entity) {
        return entity.id;
    }

    @Override
    protected ProductUnit newEntity() {
        return new ProductUnit();
    }

    @Override
    protected void applyGeneratedId(ProductUnit entity, long generated) {
        entity.id = generated;
    }

    @Override
    public ProductUnit findEffective(long productId, LocalDate bizDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE product_id = ? AND status = ? AND effective_from <= ?"
                + " ORDER BY effective_from DESC";
        List<ProductUnit> rows = queryList(sql, Arrays.asList(productId, "ENABLED", bizDate),
                this::mapRow);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
