package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.InventoryLedgerDao;
import com.hedgeguard.dao.entity.InventoryLedger;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code t_inventory_ledger} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-6）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmInventoryLedgerDao extends AbstractDmDao<InventoryLedger, Long> implements InventoryLedgerDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "product_id", "inventory_type", "quantity", "negative_confirmed",
            "unit", "valuation", "record_date", "exposure_direction", "remark"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmInventoryLedgerDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_inventory_ledger";
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
    protected void bindBusiness(PreparedStatement ps, InventoryLedger e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.inventoryType);
        bind(ps, i++, e.quantity);
        bind(ps, i++, e.negativeConfirmed);
        bind(ps, i++, e.unit);
        bind(ps, i++, e.valuation);
        bind(ps, i++, e.recordDate);
        bind(ps, i++, e.exposureDirection);
        bind(ps, i, e.remark);
    }

    @Override
    protected void readBusiness(ResultSet rs, InventoryLedger e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productId = nullableLong(rs, "product_id");
        e.inventoryType = string(rs, "inventory_type");
        e.quantity = decimal(rs, "quantity");
        e.negativeConfirmed = string(rs, "negative_confirmed");
        e.unit = string(rs, "unit");
        e.valuation = decimal(rs, "valuation");
        e.recordDate = localDate(rs, "record_date");
        e.exposureDirection = string(rs, "exposure_direction");
        e.remark = string(rs, "remark");
    }

    @Override
    protected Long idOf(InventoryLedger entity) {
        return entity.id;
    }

    @Override
    protected InventoryLedger newEntity() {
        return new InventoryLedger();
    }

    @Override
    protected void applyGeneratedId(InventoryLedger entity, long generated) {
        entity.id = generated;
    }
}
