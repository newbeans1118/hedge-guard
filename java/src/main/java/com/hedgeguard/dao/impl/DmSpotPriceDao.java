package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SpotPriceDao;
import com.hedgeguard.dao.entity.SpotPrice;
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
 * {@code t_spot_price} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-7）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSpotPriceDao extends AbstractDmDao<SpotPrice, Long> implements SpotPriceDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "product_id", "price_date", "spot_price", "source_type",
            "source_desc", "import_file_name", "operator_id", "entry_time", "is_shared",
            "overwritten_price", "overwrite_confirmed", "price_unit"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSpotPriceDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_spot_price";
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
    protected void bindBusiness(PreparedStatement ps, SpotPrice e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.priceDate);
        bind(ps, i++, e.spotPrice);
        bind(ps, i++, e.sourceType);
        bind(ps, i++, e.sourceDesc);
        bind(ps, i++, e.importFileName);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.entryTime);
        bind(ps, i++, e.isShared);
        bind(ps, i++, e.overwrittenPrice);
        bind(ps, i++, e.overwriteConfirmed);
        bind(ps, i, e.priceUnit);
    }

    @Override
    protected void readBusiness(ResultSet rs, SpotPrice e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productId = nullableLong(rs, "product_id");
        e.priceDate = localDate(rs, "price_date");
        e.spotPrice = decimal(rs, "spot_price");
        e.sourceType = string(rs, "source_type");
        e.sourceDesc = string(rs, "source_desc");
        e.importFileName = string(rs, "import_file_name");
        e.operatorId = nullableLong(rs, "operator_id");
        e.entryTime = localDateTime(rs, "entry_time");
        e.isShared = string(rs, "is_shared");
        e.overwrittenPrice = decimal(rs, "overwritten_price");
        e.overwriteConfirmed = string(rs, "overwrite_confirmed");
        e.priceUnit = string(rs, "price_unit");
    }

    @Override
    protected Long idOf(SpotPrice entity) {
        return entity.id;
    }

    @Override
    protected SpotPrice newEntity() {
        return new SpotPrice();
    }

    @Override
    protected void applyGeneratedId(SpotPrice entity, long generated) {
        entity.id = generated;
    }

    @Override
    public SpotPrice findGlobalPrice(long productId, LocalDate priceDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE product_id = ? AND price_date = ? AND is_shared = ?";
        return queryOne(sql, Arrays.asList(productId, priceDate, "Y"), this::mapRow);
    }

    @Override
    public SpotPrice findLatestOnOrBefore(long productId, LocalDate priceDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE product_id = ? AND is_shared = ? AND price_date <= ?"
                + " ORDER BY price_date DESC";
        List<SpotPrice> rows = queryList(sql, Arrays.asList(productId, "Y", priceDate), this::mapRow);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
