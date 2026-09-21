package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code t_hedge_plan_exposure} 的达梦实现（{@code DATA_MODEL.md} §2.5 TB-PLAN-2）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmHedgePlanExposureDao extends AbstractDmDao<HedgePlanExposure, Long> implements HedgePlanExposureDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "hedge_plan_id", "exposure_id", "covered_qty", "bind_time",
            "unbind_time", "status"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmHedgePlanExposureDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_hedge_plan_exposure";
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
    protected void bindBusiness(PreparedStatement ps, HedgePlanExposure e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.exposureId);
        bind(ps, i++, e.coveredQty);
        bind(ps, i++, e.bindTime);
        bind(ps, i++, e.unbindTime);
        bind(ps, i, e.status);
    }

    @Override
    protected void readBusiness(ResultSet rs, HedgePlanExposure e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.exposureId = nullableLong(rs, "exposure_id");
        e.coveredQty = decimal(rs, "covered_qty");
        e.bindTime = localDateTime(rs, "bind_time");
        e.unbindTime = localDateTime(rs, "unbind_time");
        e.status = string(rs, "status");
    }

    @Override
    protected Long idOf(HedgePlanExposure entity) {
        return entity.id;
    }

    @Override
    protected HedgePlanExposure newEntity() {
        return new HedgePlanExposure();
    }

    @Override
    protected void applyGeneratedId(HedgePlanExposure entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<HedgePlanExposure> findByPlan(long hedgePlanId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE hedge_plan_id = ? ORDER BY status ASC, id ASC";
        return queryList(sql, java.util.Arrays.asList(hedgePlanId), this::mapRow);
    }
}
