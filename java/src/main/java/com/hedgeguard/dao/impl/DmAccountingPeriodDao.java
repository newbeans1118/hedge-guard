package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AccountingPeriodDao;
import com.hedgeguard.dao.entity.AccountingPeriod;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * {@code t_accounting_period} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-6）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。全局期间定义（例外 E2），无子账户维度。
 */
public final class DmAccountingPeriodDao extends AbstractDmDao<AccountingPeriod, Long>
        implements AccountingPeriodDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "period_key", "period_type", "period_start", "period_end", "status",
            "closed_by", "closed_time", "close_reviewer_id", "reopened_by", "reopened_time",
            "reopen_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmAccountingPeriodDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_accounting_period";
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
    protected void bindBusiness(PreparedStatement ps, AccountingPeriod e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.periodKey);
        bind(ps, i++, e.periodType);
        bind(ps, i++, e.periodStart);
        bind(ps, i++, e.periodEnd);
        bind(ps, i++, e.status);
        bind(ps, i++, e.closedBy);
        bind(ps, i++, e.closedTime);
        bind(ps, i++, e.closeReviewerId);
        bind(ps, i++, e.reopenedBy);
        bind(ps, i++, e.reopenedTime);
        bind(ps, i, e.reopenReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, AccountingPeriod e) throws SQLException {
        e.id = rs.getLong("id");
        e.periodKey = string(rs, "period_key");
        e.periodType = string(rs, "period_type");
        e.periodStart = localDate(rs, "period_start");
        e.periodEnd = localDate(rs, "period_end");
        e.status = string(rs, "status");
        e.closedBy = nullableLong(rs, "closed_by");
        e.closedTime = localDateTime(rs, "closed_time");
        e.closeReviewerId = nullableLong(rs, "close_reviewer_id");
        e.reopenedBy = nullableLong(rs, "reopened_by");
        e.reopenedTime = localDateTime(rs, "reopened_time");
        e.reopenReason = string(rs, "reopen_reason");
    }

    @Override
    protected Long idOf(AccountingPeriod entity) {
        return entity.id;
    }

    @Override
    protected AccountingPeriod newEntity() {
        return new AccountingPeriod();
    }

    @Override
    protected void applyGeneratedId(AccountingPeriod entity, long generated) {
        entity.id = generated;
    }

    @Override
    public AccountingPeriod findByPeriodKey(String periodKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE period_key = ?";
        return queryOne(sql, Arrays.asList(periodKey), this::mapRow);
    }
}
