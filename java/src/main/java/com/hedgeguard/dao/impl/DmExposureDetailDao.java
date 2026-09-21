package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Arrays;

/**
 * {@code t_exposure_detail} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-9）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmExposureDetailDao extends AbstractDmDao<ExposureDetail, Long> implements ExposureDetailDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "exposure_id", "component_type", "source_type", "source_id",
            "source_no", "contributed_qty", "unit", "direction", "calc_as_of_date"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmExposureDetailDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_exposure_detail";
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
    protected void bindBusiness(PreparedStatement ps, ExposureDetail e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.exposureId);
        bind(ps, i++, e.componentType);
        bind(ps, i++, e.sourceType);
        bind(ps, i++, e.sourceId);
        bind(ps, i++, e.sourceNo);
        bind(ps, i++, e.contributedQty);
        bind(ps, i++, e.unit);
        bind(ps, i++, e.direction);
        bind(ps, i, e.calcAsOfDate);
    }

    @Override
    protected void readBusiness(ResultSet rs, ExposureDetail e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.exposureId = nullableLong(rs, "exposure_id");
        e.componentType = string(rs, "component_type");
        e.sourceType = string(rs, "source_type");
        e.sourceId = nullableLong(rs, "source_id");
        e.sourceNo = string(rs, "source_no");
        e.contributedQty = decimal(rs, "contributed_qty");
        e.unit = string(rs, "unit");
        e.direction = string(rs, "direction");
        e.calcAsOfDate = localDate(rs, "calc_as_of_date");
    }

    @Override
    protected Long idOf(ExposureDetail entity) {
        return entity.id;
    }

    @Override
    protected ExposureDetail newEntity() {
        return new ExposureDetail();
    }

    @Override
    protected void applyGeneratedId(ExposureDetail entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<ExposureDetail> findByExposure(long exposureId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE exposure_id = ? ORDER BY component_type ASC, id ASC";
        return queryList(sql, Arrays.asList(exposureId), this::mapRow);
    }

    @Override
    public List<ExposureDetail> findBySource(String sourceType, long sourceId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE source_type = ? AND source_id = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(sourceType, sourceId), this::mapRow);
    }
}
