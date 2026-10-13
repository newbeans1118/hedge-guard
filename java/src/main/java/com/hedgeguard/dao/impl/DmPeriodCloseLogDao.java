package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PeriodCloseLogDao;
import com.hedgeguard.dao.entity.PeriodCloseLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_period_close_log} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-7）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 *
 * <p><b>只增不改不删</b>（TB-ACCT-7 用途注记）：{@link #updateById} 显式拒绝。
 */
public final class DmPeriodCloseLogDao extends AbstractDmDao<PeriodCloseLog, Long>
        implements PeriodCloseLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "period_key", "action", "precheck_result", "operator_id", "reviewer_id",
            "reason", "action_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmPeriodCloseLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_period_close_log";
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
    protected void bindBusiness(PreparedStatement ps, PeriodCloseLog e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.periodKey);
        bind(ps, i++, e.action);
        bindClob(ps, i++, e.precheckResult);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.reviewerId);
        bind(ps, i++, e.reason);
        bind(ps, i, e.actionTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, PeriodCloseLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.periodKey = string(rs, "period_key");
        e.action = string(rs, "action");
        e.precheckResult = clob(rs, "precheck_result");
        e.operatorId = nullableLong(rs, "operator_id");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.reason = string(rs, "reason");
        e.actionTime = localDateTime(rs, "action_time");
    }

    @Override
    protected Long idOf(PeriodCloseLog entity) {
        return entity.id;
    }

    @Override
    protected PeriodCloseLog newEntity() {
        return new PeriodCloseLog();
    }

    @Override
    protected void applyGeneratedId(PeriodCloseLog entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(PeriodCloseLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_period_close_log 只增不改不删（TB-ACCT-7 用途注记）；"
                        + "复现=对已落库关账留痕调用 updateById");
    }

    @Override
    public List<PeriodCloseLog> findByPeriod(String periodKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE period_key = ? ORDER BY action_time ASC, id ASC";
        return queryList(sql, Arrays.asList(periodKey), this::mapRow);
    }
}
