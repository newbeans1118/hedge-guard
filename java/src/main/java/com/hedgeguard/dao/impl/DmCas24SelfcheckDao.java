package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.Cas24SelfcheckDao;
import com.hedgeguard.dao.entity.Cas24Selfcheck;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_cas24_selfcheck} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-11）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。全账户自检 {@code sub_account_id} 为 NULL。
 */
public final class DmCas24SelfcheckDao extends AbstractDmDao<Cas24Selfcheck, Long>
        implements Cas24SelfcheckDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "selfcheck_no", "scope_type", "scope_key", "pass_count",
            "fail_count", "conclusion", "cannot_selfcheck_reason", "report_file_id",
            "pending_external_review", "not_substitute_note", "executor_id", "selfcheck_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmCas24SelfcheckDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_cas24_selfcheck";
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
    protected void bindBusiness(PreparedStatement ps, Cas24Selfcheck e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.selfcheckNo);
        bind(ps, i++, e.scopeType);
        bind(ps, i++, e.scopeKey);
        bind(ps, i++, e.passCount);
        bind(ps, i++, e.failCount);
        bind(ps, i++, e.conclusion);
        bind(ps, i++, e.cannotSelfcheckReason);
        bind(ps, i++, e.reportFileId);
        bind(ps, i++, e.pendingExternalReview);
        bind(ps, i++, e.notSubstituteNote);
        bind(ps, i++, e.executorId);
        bind(ps, i, e.selfcheckTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, Cas24Selfcheck e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.selfcheckNo = string(rs, "selfcheck_no");
        e.scopeType = string(rs, "scope_type");
        e.scopeKey = string(rs, "scope_key");
        e.passCount = nullableInt(rs, "pass_count");
        e.failCount = nullableInt(rs, "fail_count");
        e.conclusion = string(rs, "conclusion");
        e.cannotSelfcheckReason = string(rs, "cannot_selfcheck_reason");
        e.reportFileId = string(rs, "report_file_id");
        e.pendingExternalReview = string(rs, "pending_external_review");
        e.notSubstituteNote = string(rs, "not_substitute_note");
        e.executorId = nullableLong(rs, "executor_id");
        e.selfcheckTime = localDateTime(rs, "selfcheck_time");
    }

    @Override
    protected Long idOf(Cas24Selfcheck entity) {
        return entity.id;
    }

    @Override
    protected Cas24Selfcheck newEntity() {
        return new Cas24Selfcheck();
    }

    @Override
    protected void applyGeneratedId(Cas24Selfcheck entity, long generated) {
        entity.id = generated;
    }

    @Override
    public Cas24Selfcheck findBySelfcheckNo(String selfcheckNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE selfcheck_no = ?";
        return queryOne(sql, Arrays.asList(selfcheckNo), this::mapRow);
    }

    @Override
    public List<Cas24Selfcheck> findByScope(String scopeType, String scopeKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE scope_type = ? AND scope_key = ? ORDER BY selfcheck_time ASC, id ASC";
        return queryList(sql, Arrays.asList(scopeType, scopeKey), this::mapRow);
    }
}
