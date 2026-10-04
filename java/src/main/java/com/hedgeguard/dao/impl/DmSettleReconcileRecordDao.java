package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleReconcileRecordDao;
import com.hedgeguard.dao.entity.SettleReconcileRecord;
import com.hedgeguard.dao.tx.TxTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_reconcile_record} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-9）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleReconcileRecordDao extends AbstractDmDao<SettleReconcileRecord, Long> implements SettleReconcileRecordDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "trade_date", "reconcile_type", "compare_granularity", "system_count", "ctp_count", "result", "difference_count", "operator_id", "reconcile_time", "remark"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleReconcileRecordDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_reconcile_record";
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
    protected void bindBusiness(PreparedStatement ps, SettleReconcileRecord e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.reconcileType);
        bind(ps, i++, e.compareGranularity);
        bind(ps, i++, e.systemCount);
        bind(ps, i++, e.ctpCount);
        bind(ps, i++, e.result);
        bind(ps, i++, e.differenceCount);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.reconcileTime);
        bind(ps, i++, e.remark);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleReconcileRecord e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.tradeDate = localDate(rs, "trade_date");
        e.reconcileType = string(rs, "reconcile_type");
        e.compareGranularity = string(rs, "compare_granularity");
        e.systemCount = nullableInt(rs, "system_count");
        e.ctpCount = nullableInt(rs, "ctp_count");
        e.result = string(rs, "result");
        e.differenceCount = nullableInt(rs, "difference_count");
        e.operatorId = nullableLong(rs, "operator_id");
        e.reconcileTime = localDateTime(rs, "reconcile_time");
        e.remark = string(rs, "remark");
    }

    @Override
    protected Long idOf(SettleReconcileRecord entity) {
        return entity.id;
    }

    @Override
    protected SettleReconcileRecord newEntity() {
        return new SettleReconcileRecord();
    }

    @Override
    protected void applyGeneratedId(SettleReconcileRecord entity, long generated) {
        entity.id = generated;
    }
}
