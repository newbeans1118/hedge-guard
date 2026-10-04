package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.TradeReasonDao;
import com.hedgeguard.dao.entity.TradeReason;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_trade_reason} 的达梦实现（{@code DATA_MODEL.md} §2.6 TB-TRADE-4）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmTradeReasonDao extends AbstractDmDao<TradeReason, Long> implements TradeReasonDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "trade_order_id", "related_type", "related_plan_id",
            "related_exposure_id", "time_desc", "reason_text", "forced_by_freq_alert",
            "related_object_missing_flag", "operator_id", "fill_time", "is_modified",
            "before_code", "before_text", "modified_by", "modified_time",
            "reason_missing_flag", "missing_marked_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradeReasonDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_trade_reason";
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
    protected void bindBusiness(PreparedStatement ps, TradeReason e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.tradeOrderId);
        bind(ps, i++, e.relatedType);
        bind(ps, i++, e.relatedPlanId);
        bind(ps, i++, e.relatedExposureId);
        bind(ps, i++, e.timeDesc);
        bind(ps, i++, e.reasonText);
        bind(ps, i++, e.forcedByFreqAlert);
        bind(ps, i++, e.relatedObjectMissingFlag);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.fillTime);
        bind(ps, i++, e.isModified);
        bind(ps, i++, e.beforeCode);
        bind(ps, i++, e.beforeText);
        bind(ps, i++, e.modifiedBy);
        bind(ps, i++, e.modifiedTime);
        bind(ps, i++, e.reasonMissingFlag);
        bind(ps, i, e.missingMarkedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradeReason e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.tradeOrderId = nullableLong(rs, "trade_order_id");
        e.relatedType = string(rs, "related_type");
        e.relatedPlanId = nullableLong(rs, "related_plan_id");
        e.relatedExposureId = nullableLong(rs, "related_exposure_id");
        e.timeDesc = string(rs, "time_desc");
        e.reasonText = string(rs, "reason_text");
        e.forcedByFreqAlert = string(rs, "forced_by_freq_alert");
        e.relatedObjectMissingFlag = string(rs, "related_object_missing_flag");
        e.operatorId = nullableLong(rs, "operator_id");
        e.fillTime = localDateTime(rs, "fill_time");
        e.isModified = string(rs, "is_modified");
        e.beforeCode = string(rs, "before_code");
        e.beforeText = string(rs, "before_text");
        e.modifiedBy = nullableLong(rs, "modified_by");
        e.modifiedTime = localDateTime(rs, "modified_time");
        e.reasonMissingFlag = string(rs, "reason_missing_flag");
        e.missingMarkedTime = localDateTime(rs, "missing_marked_time");
    }

    @Override
    protected Long idOf(TradeReason entity) {
        return entity.id;
    }

    @Override
    protected TradeReason newEntity() {
        return new TradeReason();
    }

    @Override
    protected void applyGeneratedId(TradeReason entity, long generated) {
        entity.id = generated;
    }

    @Override
    public TradeReason findByTradeOrderId(long tradeOrderId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE trade_order_id = ?";
        return queryOne(sql, Arrays.asList(tradeOrderId), this::mapRow);
    }
}
