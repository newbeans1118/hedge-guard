package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SalesContractDao;
import com.hedgeguard.dao.entity.SalesContract;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * {@code t_sales_contract} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-2）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSalesContractDao extends AbstractDmDao<SalesContract, Long> implements SalesContractDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "contract_no", "spot_category_code", "product_id", "customer_name",
            "total_qty", "delivered_qty", "pricing_mode", "contract_start_date",
            "contract_end_date", "exposure_direction", "has_dual_direction", "buy_direction_qty",
            "status", "void_reason", "closed_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSalesContractDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_sales_contract";
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
    protected void bindBusiness(PreparedStatement ps, SalesContract e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.contractNo);
        bind(ps, i++, e.spotCategoryCode);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.customerName);
        bind(ps, i++, e.totalQty);
        bind(ps, i++, e.deliveredQty);
        bind(ps, i++, e.pricingMode);
        bind(ps, i++, e.contractStartDate);
        bind(ps, i++, e.contractEndDate);
        bind(ps, i++, e.exposureDirection);
        bind(ps, i++, e.hasDualDirection);
        bind(ps, i++, e.buyDirectionQty);
        bind(ps, i++, e.status);
        bind(ps, i++, e.voidReason);
        bind(ps, i, e.closedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, SalesContract e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.contractNo = string(rs, "contract_no");
        e.spotCategoryCode = string(rs, "spot_category_code");
        e.productId = nullableLong(rs, "product_id");
        e.customerName = string(rs, "customer_name");
        e.totalQty = decimal(rs, "total_qty");
        e.deliveredQty = decimal(rs, "delivered_qty");
        e.pricingMode = string(rs, "pricing_mode");
        e.contractStartDate = localDate(rs, "contract_start_date");
        e.contractEndDate = localDate(rs, "contract_end_date");
        e.exposureDirection = string(rs, "exposure_direction");
        e.hasDualDirection = string(rs, "has_dual_direction");
        e.buyDirectionQty = decimal(rs, "buy_direction_qty");
        e.status = string(rs, "status");
        e.voidReason = string(rs, "void_reason");
        e.closedTime = localDateTime(rs, "closed_time");
    }

    @Override
    protected Long idOf(SalesContract entity) {
        return entity.id;
    }

    @Override
    protected SalesContract newEntity() {
        return new SalesContract();
    }

    @Override
    protected void applyGeneratedId(SalesContract entity, long generated) {
        entity.id = generated;
    }

    @Override
    public SalesContract findByContractNo(int subAccountId, String contractNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE sub_account_id = ? AND contract_no = ?";
        return queryOne(sql, Arrays.asList(subAccountId, contractNo), this::mapRow);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>物理删除仅限例外态</b>（{@code DATA_MODEL.md} §0.3）：{@code t_sales_contract} 仅 DRAFT 态销售合同 可删；
     * 由服务层在删除前校验状态，本层不做状态判断（保持 SQL 单一职责）。
     */
    @Override
    public int deleteById(Long id) {
        String sql = "DELETE FROM " + tableName() + " WHERE " + idColumn() + " = ?";
        return executeUpdateSql(sql, java.util.Collections.singletonList(id));
    }
}
