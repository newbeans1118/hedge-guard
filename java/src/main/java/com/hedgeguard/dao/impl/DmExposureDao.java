package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_exposure} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-8）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmExposureDao extends AbstractDmDao<Exposure, Long> implements ExposureDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "product_id", "spot_category_code", "direction", "calc_mode",
            "contract_unexecuted_qty", "inventory_qty", "production_plan_qty",
            "components_enabled", "snapshot_id", "snapshot_time", "combined_components",
            "as_of_date", "calc_time", "exposure_qty", "unit", "exposure_amount",
            "price_missing_flag", "price_as_of", "non_current_price_flag", "snapshot_as_of",
            "covered_qty", "status", "unmapped_flag"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmExposureDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_exposure";
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
    protected void bindBusiness(PreparedStatement ps, Exposure e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.spotCategoryCode);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.calcMode);
        bind(ps, i++, e.contractUnexecutedQty);
        bind(ps, i++, e.inventoryQty);
        bind(ps, i++, e.productionPlanQty);
        bind(ps, i++, e.componentsEnabled);
        bind(ps, i++, e.snapshotId);
        bind(ps, i++, e.snapshotTime);
        bind(ps, i++, e.combinedComponents);
        bind(ps, i++, e.asOfDate);
        bind(ps, i++, e.calcTime);
        bind(ps, i++, e.exposureQty);
        bind(ps, i++, e.unit);
        bind(ps, i++, e.exposureAmount);
        bind(ps, i++, e.priceMissingFlag);
        bind(ps, i++, e.priceAsOf);
        bind(ps, i++, e.nonCurrentPriceFlag);
        bind(ps, i++, e.snapshotAsOf);
        bind(ps, i++, e.coveredQty);
        bind(ps, i++, e.status);
        bind(ps, i, e.unmappedFlag);
    }

    @Override
    protected void readBusiness(ResultSet rs, Exposure e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productId = nullableLong(rs, "product_id");
        e.spotCategoryCode = string(rs, "spot_category_code");
        e.direction = string(rs, "direction");
        e.calcMode = string(rs, "calc_mode");
        e.contractUnexecutedQty = decimal(rs, "contract_unexecuted_qty");
        e.inventoryQty = decimal(rs, "inventory_qty");
        e.productionPlanQty = decimal(rs, "production_plan_qty");
        e.componentsEnabled = string(rs, "components_enabled");
        e.snapshotId = string(rs, "snapshot_id");
        e.snapshotTime = localDateTime(rs, "snapshot_time");
        e.combinedComponents = string(rs, "combined_components");
        e.asOfDate = localDate(rs, "as_of_date");
        e.calcTime = localDateTime(rs, "calc_time");
        e.exposureQty = decimal(rs, "exposure_qty");
        e.unit = string(rs, "unit");
        e.exposureAmount = decimal(rs, "exposure_amount");
        e.priceMissingFlag = string(rs, "price_missing_flag");
        e.priceAsOf = localDate(rs, "price_as_of");
        e.nonCurrentPriceFlag = string(rs, "non_current_price_flag");
        e.snapshotAsOf = localDateTime(rs, "snapshot_as_of");
        e.coveredQty = decimal(rs, "covered_qty");
        e.status = string(rs, "status");
        e.unmappedFlag = string(rs, "unmapped_flag");
    }

    @Override
    protected Long idOf(Exposure entity) {
        return entity.id;
    }

    @Override
    protected Exposure newEntity() {
        return new Exposure();
    }

    @Override
    protected void applyGeneratedId(Exposure entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<Exposure> findPendingHedge(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE sub_account_id = ? AND status = ?" + " ORDER BY product_id ASC, direction ASC";
        return queryList(sql, Arrays.asList(subAccountId, "PENDING_HEDGE"), this::mapRow);
    }
}
