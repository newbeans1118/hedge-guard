package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleAggregationCheckDao;
import com.hedgeguard.dao.entity.SettleAggregationCheck;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_aggregation_check} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-11）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleAggregationCheckDao extends AbstractDmDao<SettleAggregationCheck, Long> implements SettleAggregationCheckDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "trade_date", "check_type", "sum_sub_account", "real_account_value", "difference", "result", "possible_sub_accounts", "block_scope", "block_effective_time", "alert_time", "check_time", "closed_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleAggregationCheckDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_aggregation_check";
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
    protected void bindBusiness(PreparedStatement ps, SettleAggregationCheck e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.checkType);
        bind(ps, i++, e.sumSubAccount);
        bind(ps, i++, e.realAccountValue);
        bind(ps, i++, e.difference);
        bind(ps, i++, e.result);
        bind(ps, i++, e.possibleSubAccounts);
        bind(ps, i++, e.blockScope);
        bind(ps, i++, e.blockEffectiveTime);
        bind(ps, i++, e.alertTime);
        bind(ps, i++, e.checkTime);
        bind(ps, i++, e.closedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleAggregationCheck e) throws SQLException {
        e.id = rs.getLong("id");
        e.tradeDate = localDate(rs, "trade_date");
        e.checkType = string(rs, "check_type");
        e.sumSubAccount = decimal(rs, "sum_sub_account");
        e.realAccountValue = decimal(rs, "real_account_value");
        e.difference = decimal(rs, "difference");
        e.result = string(rs, "result");
        e.possibleSubAccounts = string(rs, "possible_sub_accounts");
        e.blockScope = string(rs, "block_scope");
        e.blockEffectiveTime = localDateTime(rs, "block_effective_time");
        e.alertTime = localDateTime(rs, "alert_time");
        e.checkTime = localDateTime(rs, "check_time");
        e.closedTime = localDateTime(rs, "closed_time");
    }

    @Override
    protected Long idOf(SettleAggregationCheck entity) {
        return entity.id;
    }

    @Override
    protected SettleAggregationCheck newEntity() {
        return new SettleAggregationCheck();
    }

    @Override
    protected void applyGeneratedId(SettleAggregationCheck entity, long generated) {
        entity.id = generated;
    }
}
