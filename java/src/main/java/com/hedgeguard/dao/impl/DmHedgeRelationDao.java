package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.HedgeRelationDao;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_hedge_relation} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-1）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmHedgeRelationDao extends AbstractDmDao<HedgeRelation, Long> implements HedgeRelationDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "relation_no", "hedge_plan_id", "hedged_item_exposure_id",
            "hedging_instrument_type", "hedging_instrument_desc", "hedge_doc_id",
            "status", "designated_time", "terminated_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmHedgeRelationDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_hedge_relation";
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
    protected void bindBusiness(PreparedStatement ps, HedgeRelation e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.relationNo);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.hedgedItemExposureId);
        bind(ps, i++, e.hedgingInstrumentType);
        bind(ps, i++, e.hedgingInstrumentDesc);
        bind(ps, i++, e.hedgeDocId);
        bind(ps, i++, e.status);
        bind(ps, i++, e.designatedTime);
        bind(ps, i, e.terminatedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, HedgeRelation e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.relationNo = string(rs, "relation_no");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.hedgedItemExposureId = nullableLong(rs, "hedged_item_exposure_id");
        e.hedgingInstrumentType = string(rs, "hedging_instrument_type");
        e.hedgingInstrumentDesc = string(rs, "hedging_instrument_desc");
        e.hedgeDocId = nullableLong(rs, "hedge_doc_id");
        e.status = string(rs, "status");
        e.designatedTime = localDateTime(rs, "designated_time");
        e.terminatedTime = localDateTime(rs, "terminated_time");
    }

    @Override
    protected Long idOf(HedgeRelation entity) {
        return entity.id;
    }

    @Override
    protected HedgeRelation newEntity() {
        return new HedgeRelation();
    }

    @Override
    protected void applyGeneratedId(HedgeRelation entity, long generated) {
        entity.id = generated;
    }

    @Override
    public HedgeRelation findByRelationNo(String relationNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE relation_no = ?";
        return queryOne(sql, Arrays.asList(relationNo), this::mapRow);
    }

    @Override
    public List<HedgeRelation> findByHedgePlan(long hedgePlanId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE hedge_plan_id = ? ORDER BY designated_time ASC, id ASC";
        return queryList(sql, Arrays.asList(hedgePlanId), this::mapRow);
    }

    @Override
    public List<HedgeRelation> findByExposure(long exposureId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE hedged_item_exposure_id = ? ORDER BY designated_time ASC, id ASC";
        return queryList(sql, Arrays.asList(exposureId), this::mapRow);
    }
}
