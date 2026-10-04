package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleReconcileDifferenceDao;
import com.hedgeguard.dao.entity.SettleReconcileDifference;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_reconcile_difference} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-10）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleReconcileDifferenceDao extends AbstractDmDao<SettleReconcileDifference, Long> implements SettleReconcileDifferenceDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "trade_date", "reconcile_record_id", "difference_type", "difference_kind", "biz_key", "field_name", "system_value", "ctp_value", "diff_qty", "diff_amount", "handle_status", "handle_method", "handle_desc", "owner_id", "handler_id", "reviewer_id", "found_time", "closed_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleReconcileDifferenceDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_reconcile_difference";
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
    protected void bindBusiness(PreparedStatement ps, SettleReconcileDifference e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.reconcileRecordId);
        bind(ps, i++, e.differenceType);
        bind(ps, i++, e.differenceKind);
        bind(ps, i++, e.bizKey);
        bind(ps, i++, e.fieldName);
        bind(ps, i++, e.systemValue);
        bind(ps, i++, e.ctpValue);
        bind(ps, i++, e.diffQty);
        bind(ps, i++, e.diffAmount);
        bind(ps, i++, e.handleStatus);
        bind(ps, i++, e.handleMethod);
        bind(ps, i++, e.handleDesc);
        bind(ps, i++, e.ownerId);
        bind(ps, i++, e.handlerId);
        bind(ps, i++, e.reviewerId);
        bind(ps, i++, e.foundTime);
        bind(ps, i++, e.closedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleReconcileDifference e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.tradeDate = localDate(rs, "trade_date");
        e.reconcileRecordId = nullableLong(rs, "reconcile_record_id");
        e.differenceType = string(rs, "difference_type");
        e.differenceKind = string(rs, "difference_kind");
        e.bizKey = string(rs, "biz_key");
        e.fieldName = string(rs, "field_name");
        e.systemValue = string(rs, "system_value");
        e.ctpValue = string(rs, "ctp_value");
        e.diffQty = decimal(rs, "diff_qty");
        e.diffAmount = decimal(rs, "diff_amount");
        e.handleStatus = string(rs, "handle_status");
        e.handleMethod = string(rs, "handle_method");
        e.handleDesc = string(rs, "handle_desc");
        e.ownerId = nullableLong(rs, "owner_id");
        e.handlerId = nullableLong(rs, "handler_id");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.foundTime = localDateTime(rs, "found_time");
        e.closedTime = localDateTime(rs, "closed_time");
    }

    @Override
    protected Long idOf(SettleReconcileDifference entity) {
        return entity.id;
    }

    @Override
    protected SettleReconcileDifference newEntity() {
        return new SettleReconcileDifference();
    }

    @Override
    protected void applyGeneratedId(SettleReconcileDifference entity, long generated) {
        entity.id = generated;
    }
}
