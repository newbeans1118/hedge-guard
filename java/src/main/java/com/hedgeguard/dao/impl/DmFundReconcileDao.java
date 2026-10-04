package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.FundReconcileDao;
import com.hedgeguard.dao.entity.FundReconcile;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_fund_reconcile} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-9）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmFundReconcileDao extends AbstractDmDao<FundReconcile, Long> implements FundReconcileDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "trade_date", "reconcile_level", "begin_balance", "occurrence_total",
            "end_balance", "balance_equation_ok", "agg_compare_value", "real_account_value",
            "agg_consistent", "result", "agg_check_id", "difference_list", "reconcile_time",
            "operator_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmFundReconcileDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_fund_reconcile";
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
    protected void bindBusiness(PreparedStatement ps, FundReconcile e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.reconcileLevel);
        bind(ps, i++, e.beginBalance);
        bind(ps, i++, e.occurrenceTotal);
        bind(ps, i++, e.endBalance);
        bind(ps, i++, e.balanceEquationOk);
        bind(ps, i++, e.aggCompareValue);
        bind(ps, i++, e.realAccountValue);
        bind(ps, i++, e.aggConsistent);
        bind(ps, i++, e.result);
        bind(ps, i++, e.aggCheckId);
        bindClob(ps, i++, e.differenceList);
        bind(ps, i++, e.reconcileTime);
        bind(ps, i, e.operatorId);
    }

    @Override
    protected void readBusiness(ResultSet rs, FundReconcile e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.tradeDate = localDate(rs, "trade_date");
        e.reconcileLevel = string(rs, "reconcile_level");
        e.beginBalance = decimal(rs, "begin_balance");
        e.occurrenceTotal = decimal(rs, "occurrence_total");
        e.endBalance = decimal(rs, "end_balance");
        e.balanceEquationOk = string(rs, "balance_equation_ok");
        e.aggCompareValue = decimal(rs, "agg_compare_value");
        e.realAccountValue = decimal(rs, "real_account_value");
        e.aggConsistent = string(rs, "agg_consistent");
        e.result = string(rs, "result");
        e.aggCheckId = nullableLong(rs, "agg_check_id");
        e.differenceList = clob(rs, "difference_list");
        e.reconcileTime = localDateTime(rs, "reconcile_time");
        e.operatorId = nullableLong(rs, "operator_id");
    }

    @Override
    protected Long idOf(FundReconcile entity) {
        return entity.id;
    }

    @Override
    protected FundReconcile newEntity() {
        return new FundReconcile();
    }

    @Override
    protected void applyGeneratedId(FundReconcile entity, long generated) {
        entity.id = generated;
    }

    @Override
    public FundReconcile findByKey(LocalDate tradeDate, String reconcileLevel, Integer subAccountId) {
        if (subAccountId == null) {
            String sql = "SELECT " + selectList() + " FROM " + tableName()
                    + " WHERE trade_date = ? AND reconcile_level = ? AND sub_account_id IS NULL";
            return queryOne(sql, Arrays.asList(tradeDate, reconcileLevel), this::mapRow);
        }
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE trade_date = ? AND reconcile_level = ? AND sub_account_id = ?";
        return queryOne(sql, Arrays.asList(tradeDate, reconcileLevel, subAccountId), this::mapRow);
    }

    @Override
    public List<FundReconcile> findByDateAndLevel(LocalDate tradeDate, String reconcileLevel) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE trade_date = ? AND reconcile_level = ? ORDER BY sub_account_id ASC, id ASC";
        return queryList(sql, Arrays.asList(tradeDate, reconcileLevel), this::mapRow);
    }
}
