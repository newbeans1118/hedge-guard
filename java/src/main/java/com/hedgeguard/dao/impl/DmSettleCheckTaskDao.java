package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleCheckTaskDao;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.dao.tx.TxTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_check_task} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-13）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleCheckTaskDao extends AbstractDmDao<SettleCheckTask, Long> implements SettleCheckTaskDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "check_type", "period_key", "sub_account_id", "required_sign_roles", "signed_roles", "status", "snapshot_hash", "snapshot_json", "check_items", "check_conclusion", "content_json", "submitted_to", "deadline_date", "overdue_alerted", "generated_time", "completed_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleCheckTaskDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_check_task";
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
    protected void bindBusiness(PreparedStatement ps, SettleCheckTask e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.checkType);
        bind(ps, i++, e.periodKey);
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.requiredSignRoles);
        bind(ps, i++, e.signedRoles);
        bind(ps, i++, e.status);
        bind(ps, i++, e.snapshotHash);
        bindClob(ps, i++, e.snapshotJson);
        bind(ps, i++, e.checkItems);
        bind(ps, i++, e.checkConclusion);
        bindClob(ps, i++, e.contentJson);
        bind(ps, i++, e.submittedTo);
        bind(ps, i++, e.deadlineDate);
        bind(ps, i++, e.overdueAlerted);
        bind(ps, i++, e.generatedTime);
        bind(ps, i++, e.completedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleCheckTask e) throws SQLException {
        e.id = rs.getLong("id");
        e.checkType = string(rs, "check_type");
        e.periodKey = string(rs, "period_key");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.requiredSignRoles = string(rs, "required_sign_roles");
        e.signedRoles = string(rs, "signed_roles");
        e.status = string(rs, "status");
        e.snapshotHash = string(rs, "snapshot_hash");
        e.snapshotJson = clob(rs, "snapshot_json");
        e.checkItems = string(rs, "check_items");
        e.checkConclusion = string(rs, "check_conclusion");
        e.contentJson = clob(rs, "content_json");
        e.submittedTo = string(rs, "submitted_to");
        e.deadlineDate = localDate(rs, "deadline_date");
        e.overdueAlerted = string(rs, "overdue_alerted");
        e.generatedTime = localDateTime(rs, "generated_time");
        e.completedTime = localDateTime(rs, "completed_time");
    }

    @Override
    protected Long idOf(SettleCheckTask entity) {
        return entity.id;
    }

    @Override
    protected SettleCheckTask newEntity() {
        return new SettleCheckTask();
    }

    @Override
    protected void applyGeneratedId(SettleCheckTask entity, long generated) {
        entity.id = generated;
    }

    @Override
    public SettleCheckTask findByPeriod(String checkType, String periodKey, int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE check_type = ? AND period_key = ? AND sub_account_id = ?";
        return queryOne(sql, Arrays.asList(checkType, periodKey, subAccountId), this::mapRow);
    }

    @Override
    public List<SettleCheckTask> findOverdue(LocalDate asOf) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE status <> 'COMPLETED' AND deadline_date < ?"
                + " ORDER BY deadline_date ASC, id ASC";
        return queryList(sql, Arrays.asList(asOf), this::mapRow);
    }
}
