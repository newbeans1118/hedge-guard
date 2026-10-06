package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ViolationScreenRunDao;
import com.hedgeguard.dao.entity.ViolationScreenRun;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_violation_screen_run} 的达梦实现（{@code DATA_MODEL.md} §2.9 TB-AUDIT-4；{@code FR-AUDIT-4}）。
 *
 * <p>本类是唯一出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmViolationScreenRunDao extends AbstractDmDao<ViolationScreenRun, Long>
        implements ViolationScreenRunDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "screen_no", "date_from", "date_to", "sub_account_scope", "screen_dimensions",
            "rule_version", "hit_count", "operator_id", "screen_time", "conclusion"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmViolationScreenRunDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_violation_screen_run";
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
    protected void bindBusiness(PreparedStatement ps, ViolationScreenRun e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.screenNo);
        bind(ps, i++, e.dateFrom);
        bind(ps, i++, e.dateTo);
        bind(ps, i++, e.subAccountScope);
        bind(ps, i++, e.screenDimensions);
        bind(ps, i++, e.ruleVersion);
        bind(ps, i++, e.hitCount);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.screenTime);
        bind(ps, i, e.conclusion);
    }

    @Override
    protected void readBusiness(ResultSet rs, ViolationScreenRun e) throws SQLException {
        e.id = rs.getLong("id");
        e.screenNo = string(rs, "screen_no");
        e.dateFrom = localDate(rs, "date_from");
        e.dateTo = localDate(rs, "date_to");
        e.subAccountScope = string(rs, "sub_account_scope");
        e.screenDimensions = string(rs, "screen_dimensions");
        e.ruleVersion = string(rs, "rule_version");
        e.hitCount = rs.getInt("hit_count");
        e.operatorId = rs.getLong("operator_id");
        e.screenTime = localDateTime(rs, "screen_time");
        e.conclusion = string(rs, "conclusion");
    }

    @Override
    protected Long idOf(ViolationScreenRun entity) {
        return entity.id;
    }

    @Override
    protected ViolationScreenRun newEntity() {
        return new ViolationScreenRun();
    }

    @Override
    protected void applyGeneratedId(ViolationScreenRun entity, long generated) {
        entity.id = generated;
    }

    @Override
    public ViolationScreenRun findByScreenNo(String screenNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE screen_no = ? ORDER BY id ASC";
        return queryOne(sql, Arrays.<Object>asList(screenNo), this::mapRow);
    }
}
