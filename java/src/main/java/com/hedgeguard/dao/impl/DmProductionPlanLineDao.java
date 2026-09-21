package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ProductionPlanLineDao;
import com.hedgeguard.dao.entity.ProductionPlanLine;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;

/**
 * {@code t_production_plan_line} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-4）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmProductionPlanLineDao extends AbstractDmDao<ProductionPlanLine, Long> implements ProductionPlanLineDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "production_plan_id", "line_type", "product_id", "quantity", "unit",
            "exposure_direction"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmProductionPlanLineDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_production_plan_line";
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
    protected void bindBusiness(PreparedStatement ps, ProductionPlanLine e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productionPlanId);
        bind(ps, i++, e.lineType);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.quantity);
        bind(ps, i++, e.unit);
        bind(ps, i, e.exposureDirection);
    }

    @Override
    protected void readBusiness(ResultSet rs, ProductionPlanLine e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productionPlanId = nullableLong(rs, "production_plan_id");
        e.lineType = string(rs, "line_type");
        e.productId = nullableLong(rs, "product_id");
        e.quantity = decimal(rs, "quantity");
        e.unit = string(rs, "unit");
        e.exposureDirection = string(rs, "exposure_direction");
    }

    @Override
    protected Long idOf(ProductionPlanLine entity) {
        return entity.id;
    }

    @Override
    protected ProductionPlanLine newEntity() {
        return new ProductionPlanLine();
    }

    @Override
    protected void applyGeneratedId(ProductionPlanLine entity, long generated) {
        entity.id = generated;
    }
}
