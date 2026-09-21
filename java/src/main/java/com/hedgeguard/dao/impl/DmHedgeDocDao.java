package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * {@code t_hedge_doc} 的达梦实现（{@code DATA_MODEL.md} §2.5 TB-PLAN-4）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmHedgeDocDao extends AbstractDmDao<HedgeDoc, Long> implements HedgeDocDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "hedge_plan_id", "doc_no", "doc_version", "is_current",
            "elem_formal_designation", "elem_effectiveness_method", "elem_rebalance_mechanism",
            "hedged_item_exposure_id", "hedging_instrument", "custom_rule_id",
            "pending_external_review", "generated_by", "generated_time", "invalidated_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmHedgeDocDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_hedge_doc";
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
    protected void bindBusiness(PreparedStatement ps, HedgeDoc e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.docNo);
        bind(ps, i++, e.docVersion);
        bind(ps, i++, e.isCurrent);
        bindClob(ps, i++, e.elemFormalDesignation);
        bindClob(ps, i++, e.elemEffectivenessMethod);
        bindClob(ps, i++, e.elemRebalanceMechanism);
        bind(ps, i++, e.hedgedItemExposureId);
        bind(ps, i++, e.hedgingInstrument);
        bind(ps, i++, e.customRuleId);
        bind(ps, i++, e.pendingExternalReview);
        bind(ps, i++, e.generatedBy);
        bind(ps, i++, e.generatedTime);
        bind(ps, i, e.invalidatedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, HedgeDoc e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.docNo = string(rs, "doc_no");
        e.docVersion = nullableInt(rs, "doc_version");
        e.isCurrent = string(rs, "is_current");
        e.elemFormalDesignation = clob(rs, "elem_formal_designation");
        e.elemEffectivenessMethod = clob(rs, "elem_effectiveness_method");
        e.elemRebalanceMechanism = clob(rs, "elem_rebalance_mechanism");
        e.hedgedItemExposureId = nullableLong(rs, "hedged_item_exposure_id");
        e.hedgingInstrument = string(rs, "hedging_instrument");
        e.customRuleId = nullableLong(rs, "custom_rule_id");
        e.pendingExternalReview = string(rs, "pending_external_review");
        e.generatedBy = nullableLong(rs, "generated_by");
        e.generatedTime = localDateTime(rs, "generated_time");
        e.invalidatedTime = localDateTime(rs, "invalidated_time");
    }

    @Override
    protected Long idOf(HedgeDoc entity) {
        return entity.id;
    }

    @Override
    protected HedgeDoc newEntity() {
        return new HedgeDoc();
    }

    @Override
    protected void applyGeneratedId(HedgeDoc entity, long generated) {
        entity.id = generated;
    }

    @Override
    public HedgeDoc findCurrent(long hedgePlanId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE hedge_plan_id = ? AND is_current = ?";
        return queryOne(sql, Arrays.asList(hedgePlanId, "Y"), this::mapRow);
    }
}
