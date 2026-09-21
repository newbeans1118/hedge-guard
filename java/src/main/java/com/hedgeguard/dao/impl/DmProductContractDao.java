package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_product_contract} 的达梦实现（{@code DATA_MODEL.md} §2.3 TB-PRODUCT-3）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmProductContractDao extends AbstractDmDao<ProductContract, Long> implements ProductContractDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "product_id", "instrument_id", "instrument_type", "exchange_code",
            "contract_multiplier", "expiry_date", "underlying_instrument_id", "strike_price",
            "option_type", "status", "is_domestic_exchange"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmProductContractDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_product_contract";
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
    protected void bindBusiness(PreparedStatement ps, ProductContract e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.productId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.instrumentType);
        bind(ps, i++, e.exchangeCode);
        bind(ps, i++, e.contractMultiplier);
        bind(ps, i++, e.expiryDate);
        bind(ps, i++, e.underlyingInstrumentId);
        bind(ps, i++, e.strikePrice);
        bind(ps, i++, e.optionType);
        bind(ps, i++, e.status);
        bind(ps, i, e.isDomesticExchange);
    }

    @Override
    protected void readBusiness(ResultSet rs, ProductContract e) throws SQLException {
        e.id = rs.getLong("id");
        e.productId = nullableLong(rs, "product_id");
        e.instrumentId = string(rs, "instrument_id");
        e.instrumentType = string(rs, "instrument_type");
        e.exchangeCode = string(rs, "exchange_code");
        e.contractMultiplier = decimal(rs, "contract_multiplier");
        e.expiryDate = localDate(rs, "expiry_date");
        e.underlyingInstrumentId = string(rs, "underlying_instrument_id");
        e.strikePrice = decimal(rs, "strike_price");
        e.optionType = string(rs, "option_type");
        e.status = string(rs, "status");
        e.isDomesticExchange = string(rs, "is_domestic_exchange");
    }

    @Override
    protected Long idOf(ProductContract entity) {
        return entity.id;
    }

    @Override
    protected ProductContract newEntity() {
        return new ProductContract();
    }

    @Override
    protected void applyGeneratedId(ProductContract entity, long generated) {
        entity.id = generated;
    }

    @Override
    public ProductContract findByInstrumentId(String instrumentId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE instrument_id = ?";
        return queryOne(sql, Arrays.asList(instrumentId), this::mapRow);
    }

    @Override
    public List<ProductContract> findByProduct(long productId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE product_id = ? ORDER BY instrument_type ASC, instrument_id ASC";
        return queryList(sql, java.util.Arrays.asList(productId), this::mapRow);
    }
}
