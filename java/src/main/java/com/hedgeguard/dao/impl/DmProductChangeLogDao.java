package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ProductChangeLogDao;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_product_change_log} 的达梦实现（{@code DATA_MODEL.md} §2.3 TB-PRODUCT-7）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmProductChangeLogDao extends AbstractDmDao<ProductChangeLog, Long> implements ProductChangeLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "product_id", "change_type", "changed_field", "before_value", "after_value",
            "sub_account_id", "operator_id", "change_time", "change_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmProductChangeLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_product_change_log";
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
    protected void bindBusiness(PreparedStatement ps, ProductChangeLog e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.productId);
        bind(ps, i++, e.changeType);
        bind(ps, i++, e.changedField);
        bind(ps, i++, e.beforeValue);
        bind(ps, i++, e.afterValue);
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.changeTime);
        bind(ps, i, e.changeReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, ProductChangeLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.productId = nullableLong(rs, "product_id");
        e.changeType = string(rs, "change_type");
        e.changedField = string(rs, "changed_field");
        e.beforeValue = string(rs, "before_value");
        e.afterValue = string(rs, "after_value");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.operatorId = nullableLong(rs, "operator_id");
        e.changeTime = localDateTime(rs, "change_time");
        e.changeReason = string(rs, "change_reason");
    }

    @Override
    protected Long idOf(ProductChangeLog entity) {
        return entity.id;
    }

    @Override
    protected ProductChangeLog newEntity() {
        return new ProductChangeLog();
    }

    @Override
    protected void applyGeneratedId(ProductChangeLog entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<ProductChangeLog> findByProduct(long productId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE product_id = ?" + " ORDER BY change_time DESC";
        return queryList(sql, Arrays.asList(productId), this::mapRow);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code t_product_change_log} 为<b>只增不改</b>表（{@code DATA_MODEL.md} §0.3）：本方法<b>显式拒绝</b>，
     * 使"留痕被改写"在编译期之后的第一次调用即暴露，而不是静默覆盖审计证据。
     */
    @Override
    public int updateById(ProductChangeLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_product_change_log 只增不改（DATA_MODEL.md §0.3），不允许更新；复现=改为新增一行留痕记录");
    }
}
