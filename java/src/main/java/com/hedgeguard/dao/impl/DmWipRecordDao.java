package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.WipRecordDao;
import com.hedgeguard.dao.entity.WipRecord;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code t_wip_record} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-5）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmWipRecordDao extends AbstractDmDao<WipRecord, Long> implements WipRecordDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "product_id", "production_plan_id", "wip_qty", "completion_ratio",
            "exposure_direction", "record_date", "unit"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmWipRecordDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_wip_record";
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
    protected void bindBusiness(PreparedStatement ps, WipRecord e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.productionPlanId);
        bind(ps, i++, e.wipQty);
        bind(ps, i++, e.completionRatio);
        bind(ps, i++, e.exposureDirection);
        bind(ps, i++, e.recordDate);
        bind(ps, i, e.unit);
    }

    @Override
    protected void readBusiness(ResultSet rs, WipRecord e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productId = nullableLong(rs, "product_id");
        e.productionPlanId = nullableLong(rs, "production_plan_id");
        e.wipQty = decimal(rs, "wip_qty");
        e.completionRatio = decimal(rs, "completion_ratio");
        e.exposureDirection = string(rs, "exposure_direction");
        e.recordDate = localDate(rs, "record_date");
        e.unit = string(rs, "unit");
    }

    @Override
    protected Long idOf(WipRecord entity) {
        return entity.id;
    }

    @Override
    protected WipRecord newEntity() {
        return new WipRecord();
    }

    @Override
    protected void applyGeneratedId(WipRecord entity, long generated) {
        entity.id = generated;
    }
}
