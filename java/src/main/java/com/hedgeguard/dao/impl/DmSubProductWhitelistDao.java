package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_sub_product_whitelist} 的达梦实现（{@code DATA_MODEL.md} §2.3 TB-PRODUCT-5）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSubProductWhitelistDao extends AbstractDmDao<SubProductWhitelist, Long> implements SubProductWhitelistDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "product_id", "category_override", "status", "dispatch_status",
            "dispatch_version", "disabled_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSubProductWhitelistDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_sub_product_whitelist";
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
    protected void bindBusiness(PreparedStatement ps, SubProductWhitelist e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.categoryOverride);
        bind(ps, i++, e.status);
        bind(ps, i++, e.dispatchStatus);
        bind(ps, i++, e.dispatchVersion);
        bind(ps, i, e.disabledReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, SubProductWhitelist e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productId = nullableLong(rs, "product_id");
        e.categoryOverride = string(rs, "category_override");
        e.status = string(rs, "status");
        e.dispatchStatus = string(rs, "dispatch_status");
        e.dispatchVersion = nullableLong(rs, "dispatch_version");
        e.disabledReason = string(rs, "disabled_reason");
    }

    @Override
    protected Long idOf(SubProductWhitelist entity) {
        return entity.id;
    }

    @Override
    protected SubProductWhitelist newEntity() {
        return new SubProductWhitelist();
    }

    @Override
    protected void applyGeneratedId(SubProductWhitelist entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<SubProductWhitelist> findBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE sub_account_id = ?" + " ORDER BY status ASC, product_id ASC";
        return queryList(sql, Arrays.asList(subAccountId), this::mapRow);
    }

    @Override
    public List<SubProductWhitelist> findByProduct(long productId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE product_id = ? ORDER BY status ASC, sub_account_id ASC";
        return queryList(sql, Arrays.asList(productId), this::mapRow);
    }
}
